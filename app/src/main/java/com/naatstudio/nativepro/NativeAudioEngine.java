package com.naatstudio.nativepro;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AudioRecord -> Java PCM DSP -> AudioTrack / MediaStore WAV.
 * This bypasses Android WebView's getUserMedia entirely.  The browser renders UI only.
 * Bluetooth microphone routing is best effort because Android controls HFP/BLE routes.
 */
public final class NativeAudioEngine {
    public interface Listener { void onEvent(String kind, String value); }
    private final Context context;
    private final Listener events;
    private final AudioManager audioManager;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private AudioRecord input;
    private AudioTrack output;
    private AutomaticGainControl agc;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private Thread worker;
    private int sampleRate = 48000;
    private volatile boolean monitor = false;
    private volatile boolean warp = false;
    private volatile double level = .6, feedback = .25, master = .15;
    private volatile int delayMs = 380, gateDb = -50;
    private volatile boolean bypass = false;
    private volatile String mode = "WARM";
    private volatile int loopState = 0; // 0 idle, 1 capture, 2 play, 3 overdub
    private float[] loop = new float[0];
    private int loopPos = 0, loopLength = 0;
    private float[] delayLine = new float[0], reverse = new float[0];
    private int writeIndex = 0, reverseIndex = 0;
    private boolean reverseFilled = false;
    private float lowpass = 0, inputPrev = 0, highpass = 0, envelope = 0;
    private double phase = 0, glitchMs = 55, glitchClock = 0;
    private float[] hallA = new float[0], hallB = new float[0], hallC = new float[0];
    private int hallPosA = 0, hallPosB = 0, hallPosC = 0;
    private final Object recordLock = new Object();
    private ParcelFileDescriptor fileDescriptor;
    private FileOutputStream wavOut;
    private Uri recordingUri, lastRecording;
    private boolean recordRaw;
    private long pcmBytes = 0;

    NativeAudioEngine(Context ctx, Listener listener) {
        context = ctx.getApplicationContext();
        events = listener;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }
    private void emit(String kind, String message) { events.onEvent(kind, message); }
    void setParameters(double lev, double feed, int time, String presetMode, int gate, double vol, boolean isBypass) {
        level = clamp(lev, 0, 1.0);
        feedback = clamp(feed, 0, .94);
        delayMs = (int) clamp(time, 20, 2000);
        if (presetMode != null) mode = presetMode.toUpperCase(Locale.US);
        gateDb = (int) clamp(gate, -80, -20);
        master = clamp(vol, 0, 1.0);
        bypass = isBypass;
    }
    void setWarp(boolean b) { warp = b; }
    synchronized void loopCommand(String action) {
        if (!running.get()) { emit("hint", "Start native mic before using LOOP."); return; }
        if (!"LOOP".equals(mode)) { emit("hint", "Select LOOP mode first."); return; }
        switch (action) {
            case "rec":
                if (loopState == 0) { loopState = 1; loopPos = 0; loopLength = 0; }
                else { loopState = 3; loopPos = 0; }
                break;
            case "stop":
                if (loopState == 1) { loopLength = Math.max(1, loopPos); loopPos = 0; loopState = 2; }
                else if (loopState == 3) loopState = 2;
                else loopState = 0;
                break;
            case "clear":
                loopState = 0; loopPos = 0; loopLength = 0;
                java.util.Arrays.fill(loop, 0);
                break;
        }
        emit("loop", loopState == 1 ? "RECORDING" : loopState == 2 ? "PLAYING" : loopState == 3 ? "OVERDUB" : "STOPPED");
    }
    private static double clamp(double n, double lo, double hi) { return Math.max(lo, Math.min(hi, n)); }

    @SuppressLint("MissingPermission")
    JSONArray microphones() {
        JSONArray found = new JSONArray();
        try {
            JSONObject def = new JSONObject();
            def.put("id", "default"); def.put("name", "Android default microphone"); found.put(def);
            for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                JSONObject o = new JSONObject();
                o.put("id", String.valueOf(d.getId()));
                o.put("name", d.getProductName().toString() + " (" + deviceKind(d.getType()) + ")");
                found.put(o);
            }
        } catch (Exception e) { emit("hint", "Some input devices require Nearby devices permission."); }
        return found;
    }
    private String deviceKind(int type) {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return "Bluetooth call mic";
        if (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET) return "Bluetooth LE mic";
        if (type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET) return "USB mic";
        if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) return "Phone mic";
        return "input " + type;
    }
    @SuppressLint("MissingPermission")
    synchronized void start(String requestedDevice, boolean doMonitor) {
        stop();
        boolean chosenBluetooth = false;
        AudioDeviceInfo chosen = null;
        if (requestedDevice != null && !"default".equals(requestedDevice)) {
            try {
                for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    if (String.valueOf(d.getId()).equals(requestedDevice)) { chosen = d; break; }
                }
            } catch (SecurityException err) { emit("hint", "Nearby devices permission is required for Bluetooth input."); }
            if (chosen == null) { emit("error", "Selected mic disappeared. Choose Android default and retry."); return; }
            chosenBluetooth = chosen.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && chosen.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET);
        }
        try {
            if (chosenBluetooth && Build.VERSION.SDK_INT >= 31) {
                try { audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION); audioManager.setCommunicationDevice(chosen); }
                catch (Exception ignored) { emit("hint", "Bluetooth HFP route could not be forced; trying preferred mic."); }
            }
            // Prefer 48 kHz; fall back to common 44.1 kHz. This is a genuine native AudioRecord.
            AudioRecord candidate = null;
            int workingRate = 48000;
            for (int rate : new int[]{48000, 44100}) {
                for (int src : new int[]{MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION}) {
                    try {
                        int minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                        if (minimum <= 0) continue;
                        AudioFormat f = new AudioFormat.Builder().setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
                        AudioRecord r = new AudioRecord.Builder().setAudioSource(src).setAudioFormat(f)
                            .setBufferSizeInBytes(Math.max(minimum * 2, 8192)).build();
                        if (r.getState() == AudioRecord.STATE_INITIALIZED) { candidate = r; workingRate = rate; break; }
                        r.release();
                    } catch (Exception ignored) { /* Try next supported combination. */ }
                }
                if (candidate != null) break;
            }
            if (candidate == null) throw new IllegalStateException("Android could not initialize an input recording session.");
            input = candidate;
            sampleRate = workingRate;
            // Best effort: disable built-in AGC / echo cancellation / noise suppression.
            int sessionId = input.getAudioSessionId();
            try { if (AutomaticGainControl.isAvailable()) { agc = AutomaticGainControl.create(sessionId); if (agc != null) agc.setEnabled(false); } } catch (Exception ignored) {}
            try { if (AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(sessionId); if (aec != null) aec.setEnabled(false); } } catch (Exception ignored) {}
            try { if (NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(sessionId); if (ns != null) ns.setEnabled(false); } } catch (Exception ignored) {}
            if (chosen != null) {
                if (!input.setPreferredDevice(chosen)) emit("hint", "Android did not accept preferred mic routing. Recording source may differ.");
            }
            int trackMin = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (trackMin <= 0) trackMin = 8192;
            output = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(trackMin * 2, 8192)).build();
            if (output.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("Cannot open audio output.");
            initDSP();
            monitor = doMonitor;
            input.startRecording();
            if (input.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("Microphone was rejected by Android audio service.");
            if (monitor) output.play();
            running.set(true);
            worker = new Thread(this::processStream, "NaatStudioAudioEngine");
            worker.setPriority(Thread.MAX_PRIORITY);
            worker.start();
            emit("status", "MIC CONNECTED • Native AudioRecord • " + sampleRate + " Hz" + (chosenBluetooth ? " • Bluetooth requested" : ""));
        } catch (Exception e) {
            emit("error", "Mic could not start: " + e.getMessage() + ". Close other mic apps and try phone mic first.");
            stop();
        }
    }
    private void initDSP() {
        delayLine = new float[sampleRate * 3]; writeIndex = 0;
        reverse = new float[sampleRate]; reverseIndex = 0; reverseFilled = false;
        loop = new float[sampleRate * 40]; loopLength = 0; loopPos = 0; loopState = 0;
        hallA = new float[(int)(sampleRate * .087)];
        hallB = new float[(int)(sampleRate * .131)];
        hallC = new float[(int)(sampleRate * .191)];
        hallPosA = hallPosB = hallPosC = 0;
        lowpass = inputPrev = highpass = envelope = 0; phase = 0; glitchClock = 0;
    }
    private float readDelay(double timeMs) {
        int offset = (int) clamp(timeMs * sampleRate / 1000, 1, delayLine.length - 2);
        int idx = writeIndex - offset;
        if (idx < 0) idx += delayLine.length;
        return delayLine[idx];
    }
    private float process(float sample) {
        // 75 Hz DC / handling rumble filter and -80..-20 dB adaptive gate.
        highpass = (float) (sample - inputPrev + .9902 * highpass); inputPrev = sample;
        envelope = envelope * .997f + Math.abs(highpass) * .003f;
        float threshold = (float) Math.pow(10, gateDb / 20.0);
        float clean = highpass * (envelope < threshold ? .03f : 1f);
        float send = clean * (float)level;
        String m = mode;
        float delayed = readDelay(delayMs);
        float processed = delayed;
        float looped = 0;
        if ("LOOP".equals(m)) {
            if (loopState == 1) {
                loop[loopPos++] = send;
                if (loopPos >= loop.length) { loopLength = loop.length; loopPos = 0; loopState = 2; emit("loop", "PLAYING 40s"); }
            } else if (loopState == 2 || loopState == 3) {
                if (loopLength > 0) {
                    looped = loop[loopPos];
                    if (loopState == 3) loop[loopPos] = (float) clamp(loop[loopPos] * .75 + send * .55, -1, 1);
                    loopPos = (loopPos + 1) % loopLength;
                }
            }
            processed = looped;
        } else if ("REVERSE".equals(m)) {
            reverse[reverseIndex] = send;
            reverseIndex++;
            if (reverseIndex >= reverse.length) { reverseIndex = 0; reverseFilled = true; }
            if (reverseFilled) processed = reverse[reverse.length - 1 - reverseIndex];
        } else if ("TAPE".equals(m)) {
            processed = readDelay(delayMs + 5 * Math.sin(phase * 2 * Math.PI));
        } else if ("MOD".equals(m)) {
            processed = readDelay(delayMs + 8 * Math.sin(phase * 2 * Math.PI * 4));
        } else if ("GLT".equals(m)) {
            glitchClock += 1.0 / sampleRate;
            if (glitchClock > .12) { glitchClock = 0; glitchMs = 10 + Math.random() * 70; }
            processed = readDelay(glitchMs);
        } else if ("SHIM".equals(m)) {
            // Grain-crossfaded double-speed read heads; approximation of an octave-up shimmer.
            double p1 = (phase * 8) % 1;
            double p2 = (p1 + .5) % 1;
            double w1 = .5 - .5 * Math.cos(2 * Math.PI * p1);
            processed = (float) (readDelay(delayMs + 80 * (1 - p1)) * w1
                + readDelay(delayMs + 80 * (1 - p2)) * (1 - w1));
        }
        if ("ANALOG".equals(m) || "TAPE".equals(m) || "WARM".equals(m)) {
            double freq = "WARM".equals(m) ? 1500 : "ANALOG".equals(m) ? 2500 : 3000;
            double a = Math.exp(-2 * Math.PI * freq / sampleRate);
            lowpass = (float) (lowpass * a + processed * (1 - a));
            processed = (float) Math.tanh(lowpass * ("WARM".equals(m) ? 1.7 : 1.16));
        }
        if ("+RV".equals(m)) {
            float a = hallA[hallPosA], b = hallB[hallPosB], c = hallC[hallPosC];
            hallA[hallPosA] = .52f * a + processed * .23f;
            hallB[hallPosB] = .53f * b + processed * .22f;
            hallC[hallPosC] = .57f * c + processed * .19f;
            hallPosA = (hallPosA + 1) % hallA.length;
            hallPosB = (hallPosB + 1) % hallB.length;
            hallPosC = (hallPosC + 1) % hallC.length;
            processed = processed * .7f + (a + b + c) * .3f;
        }
        double fb = warp && "WARP".equals(m) ? .94 : feedback;
        delayLine[writeIndex++] = (float) Math.tanh(send + processed * fb);
        if (writeIndex == delayLine.length) writeIndex = 0;
        phase += 1.0 / sampleRate * ("TAPE".equals(m) ? .5 : 1);
        if (phase >= 100000) phase -= 100000;
        double wetGain = warp && "WARP".equals(m) ? 1.0 : .65;
        // Master soft limiter. The recording still receives processed audio when speaker monitoring is off.
        double combined = bypass ? clean : (clean * .78 + processed * wetGain);
        return (float) Math.tanh(combined * 1.3);
    }
    private void processStream() {
        short[] pcmIn = new short[512], pcmOut = new short[512];
        ByteBuffer chunk = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        int tick = 0;
        try {
            while (running.get()) {
                int count = input.read(pcmIn, 0, pcmIn.length, AudioRecord.READ_BLOCKING);
                if (count <= 0) { if (count < 0) emit("error", "Android AudioRecord read error: " + count); break; }
                chunk.clear(); double peak = 0;
                for (int i = 0; i < count; i++) {
                    float original = pcmIn[i] / 32768f;
                    float out = process(original);
                    peak = Math.max(peak, Math.abs(original));
                    short value = (short)(clamp(out, -1, 1) * 32767);
                    pcmOut[i] = (short)(clamp(out * master, -1, 1) * 32767);
                    boolean raw;
                    synchronized (recordLock) { raw = recordRaw; }
                    chunk.putShort(raw ? pcmIn[i] : value);
                }
                if (monitor) output.write(pcmOut, 0, count, AudioTrack.WRITE_BLOCKING);
                synchronized (recordLock) {
                    if (wavOut != null) {
                        try { wavOut.write(chunk.array(), 0, count * 2); pcmBytes += count * 2; }
                        catch (Exception e) { emit("error", "Recording write failed: " + e.getMessage()); finishWav(); }
                    }
                }
                tick += count;
                if (tick > sampleRate / 8) {
                    tick = 0;
                    emit("meter", String.valueOf((int) Math.max(-80, 20 * Math.log10(Math.max(peak, .00001)))));
                }
            }
        } catch (Exception ex) { emit("error", "Native mic interrupted: " + ex.getMessage()); }
        finally { running.set(false); emit("status", "MIC OFF"); }
    }
    synchronized void stop() {
        running.set(false);
        if (input != null) { try { input.stop(); } catch (Exception ignored) {} }
        if (worker != null && worker != Thread.currentThread()) {
            try { worker.join(600); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        worker = null;
        if (input != null) { input.release(); input = null; }
        if (agc != null) { agc.release(); agc = null; }
        if (aec != null) { aec.release(); aec = null; }
        if (ns != null) { ns.release(); ns = null; }
        if (output != null) { try { output.pause(); output.flush(); } catch (Exception ignored) {} output.release(); output = null; }
        if (audioManager != null && Build.VERSION.SDK_INT >= 31) {
            try { audioManager.clearCommunicationDevice(); audioManager.setMode(AudioManager.MODE_NORMAL); } catch (Exception ignored) {}
        }
        synchronized (recordLock) { finishWav(); }
        emit("status", "MIC OFF");
    }
    void close() { stop(); }
    void beginRecording(String label, boolean raw) {
        if (!running.get()) { emit("error", "Start mic before recording."); return; }
        synchronized (recordLock) {
            if (wavOut != null) { emit("hint", "Recording already running."); return; }
            try {
                String title = (label == null ? "Naat" : label).replaceAll("[^a-zA-Z0-9 _-]", "").trim();
                if (title.isEmpty()) title = "Naat";
                ContentValues values = new ContentValues();
                values.put(MediaStore.Audio.Media.DISPLAY_NAME, title + "-" + System.currentTimeMillis() + ".wav");
                values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
                values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Naat Studio PRO");
                values.put(MediaStore.Audio.Media.IS_PENDING, 1);
                recordingUri = context.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
                if (recordingUri == null) throw new IllegalStateException("Could not create Music recording.");
                fileDescriptor = context.getContentResolver().openFileDescriptor(recordingUri, "rw");
                if (fileDescriptor == null) throw new IllegalStateException("MediaStore refused file access.");
                wavOut = new FileOutputStream(fileDescriptor.getFileDescriptor());
                wavOut.write(new byte[44]); // Placeholder WAVE header; finalized on STOP.
                pcmBytes = 0; recordRaw = raw;
                emit("record", "RECORDING " + (raw ? "RAW MIC" : "EFFECTED AUDIO"));
            } catch (Exception ex) { emit("error", "Recording cannot start: " + ex.getMessage()); finishWav(); }
        }
    }
    void endRecording() { synchronized (recordLock) { finishWav(); } }
    private static void word(ByteBuffer b, long value) { b.putInt((int) value); }
    private void finishWav() {
        if (wavOut == null) return;
        try {
            int total = (int)Math.min(pcmBytes, 0xffffffffL - 36);
            ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            h.put(new byte[]{'R','I','F','F'}); word(h, 36L + total);
            h.put(new byte[]{'W','A','V','E','f','m','t',' '}); word(h,16);
            h.putShort((short)1); h.putShort((short)1); // PCM mono
            word(h,sampleRate); word(h,sampleRate * 2L);
            h.putShort((short)2); h.putShort((short)16);
            h.put(new byte[]{'d','a','t','a'}); word(h,total);
            wavOut.getChannel().position(0);
            wavOut.write(h.array()); wavOut.flush(); wavOut.close(); wavOut = null;
            if (fileDescriptor != null) { fileDescriptor.close(); fileDescriptor = null; }
            ContentValues finish = new ContentValues(); finish.put(MediaStore.Audio.Media.IS_PENDING, 0);
            context.getContentResolver().update(recordingUri, finish, null, null);
            lastRecording = recordingUri; recordingUri = null;
            emit("record", "SAVED • Music/Naat Studio PRO • " + (total / 1024) + " KB");
        } catch (Exception ex) {
            emit("error", "Could not finalize WAV file: " + ex.getMessage());
            try { wavOut.close(); } catch (Exception ignored) {}
            try { if (fileDescriptor != null) fileDescriptor.close(); } catch (Exception ignored) {}
            wavOut = null; fileDescriptor = null; recordingUri = null;
        }
    }
    void playLastRecording() {
        if (lastRecording == null) { emit("hint", "Save a recording first."); return; }
        try {
            Intent play = new Intent(Intent.ACTION_VIEW);
            play.setDataAndType(lastRecording, "audio/wav");
            play.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(play);
        } catch (Exception e) { emit("error", "No installed audio player could open the WAV. Find it in Music/Naat Studio PRO."); }
    }
}