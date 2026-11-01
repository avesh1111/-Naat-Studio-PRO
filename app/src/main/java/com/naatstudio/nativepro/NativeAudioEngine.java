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
import android.media.AudioRouting;
import android.os.Handler;
import android.os.Looper;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.Process;
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
    private AudioRouting.OnRoutingChangedListener outputRoutingListener;
    private volatile String outputRouteName = "not confirmed";
    private int requestedLowLatencyFrames = 0;
    private AudioDeviceInfo requestedPlaybackDevice;
    private volatile boolean bluetoothPlayback = false;
    private volatile boolean fastLiveMode = false;
    // Default Bluetooth live to a clean direct mic feed: the 380 ms DD-8 echo
    // should never be confused with actual mic-to-speaker transport latency.
    // Users can disable this in Bluetooth Studio to use their FX presets.
    private volatile boolean bluetoothDryTest = true;
    private long monitorFramesWritten = 0;
    private long outputDrops = 0;
    private AutomaticGainControl agc;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private Thread worker;
    private int sampleRate = 48000;
    private volatile boolean monitor = false;
    private volatile boolean warp = false;
    private volatile double level = .6, feedback = .25, master = .15;
    private volatile double vocalVolume = .65, instrumentVolume = .55;
    private volatile boolean mehfil = false;
    private volatile boolean stageVocalOnly = false;
    private AudioDeviceInfo selectedInputDevice;

    private volatile int noiseStrength = 2; // 0 OFF, 1 LIGHT, 2 BALANCED, 3 STRONG
    private float expanderGain = 1f;
    private float noiseTone = 0f;
    private boolean platformNoiseActive = false;
    private int wavChannels = 1;
    private volatile int delayMs = 380, gateDb = -50;
    private volatile boolean bypass = false;
    private volatile boolean hallEnabled = false;
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
    void setMixLevels(double mic, double inst) {
        vocalVolume = clamp(mic, 0, 1); instrumentVolume = clamp(inst, 0, 1);
    }
    void setWarp(boolean b) { warp = b; }
    void setHallEnabled(boolean enabled) { hallEnabled = enabled; }
    void setFastLiveMode(boolean enabled) { fastLiveMode = enabled; }
    // Direct PCM soundcheck bypasses app DSP; Android/Bluetooth transport delay still exists.
    void setBluetoothDryTest(boolean enabled) { bluetoothDryTest = enabled; }
    boolean isRunning() { return running.get(); }

    synchronized void setNoiseReduction(int strength) {
        noiseStrength = (int)clamp(strength, 0, 3);
        boolean enablePlatform = noiseStrength > 0 && !mehfil && !(bluetoothPlayback && bluetoothDryTest);
        try {
            if (ns != null) {
                ns.setEnabled(enablePlatform);
                platformNoiseActive = ns.getEnabled();
            } else {
                platformNoiseActive = false;
            }
        } catch (Exception ex) { platformNoiseActive = false; }
        String label = noiseStrength == 0 ? "OFF" : noiseStrength == 1 ? "LIGHT" :
            noiseStrength == 2 ? "BALANCED" : "STRONG";
        emit("noise", "Noise Reduction: " + label + " • " +
            (!running.get() ? "restart mic to apply Android suppression" :
                platformNoiseActive ? "Android NoiseSuppressor ON + vocal expander" :
                "software vocal expander only (Android NS unavailable/disabled)") +
            (mehfil ? " • instrument input unaffected" : ""));
    }

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
                int t = d.getType();
                o.put("kind", t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET ? "usb"
                    : t == AudioDeviceInfo.TYPE_BUILTIN_MIC ? "phone"
                    : t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET) ? "bt" : "other");
                o.put("channelCounts", java.util.Arrays.toString(d.getChannelCounts()));
                found.put(o);
            }
        } catch (Exception e) { emit("hint", "Some input devices require Nearby devices permission."); }
        return found;
    }
    private boolean isBluetoothPlaybackType(int t) {
        return t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (Build.VERSION.SDK_INT >= 31 &&
                (t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_SPEAKER));
    }
    @SuppressLint("MissingPermission")
    JSONArray audioOutputs() {
        JSONArray found = new JSONArray();
        try {
            JSONObject def = new JSONObject();
            def.put("id", "default"); def.put("name", "Android default audio output");
            def.put("kind", "default"); found.put(def);
            for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                JSONObject o = new JSONObject();
                o.put("id", String.valueOf(d.getId()));
                o.put("name", d.getProductName().toString() +
                    (isBluetoothPlaybackType(d.getType()) ? " (Bluetooth media output)" : " (wired / speaker)"));
                o.put("kind", isBluetoothPlaybackType(d.getType()) ? "bt" :
                    (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ? "phone" :
                    (d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) ? "usb" :
                    (d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET) ? "wired" : "other"));
                o.put("type", d.getType());
                found.put(o);
            }
        } catch (Exception ex) {
            emit("hint", "Bluetooth output list unavailable. Allow Nearby devices permission, then refresh.");
        }
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
        start(requestedDevice, doMonitor, false, "default");
    }
    synchronized void start(String requestedDevice, boolean doMonitor, boolean mehfilMode) {
        start(requestedDevice, doMonitor, mehfilMode, "default");
    }
    synchronized void start(String requestedDevice, boolean doMonitor, boolean mehfilMode, String requestedOutput) {
        start(requestedDevice, doMonitor, mehfilMode, requestedOutput, false);
    }
    synchronized void start(String requestedDevice, boolean doMonitor, boolean mehfilMode, String requestedOutput, boolean vocalOnlySoundcheck) {
        stop();
        mehfil = mehfilMode;
        stageVocalOnly = vocalOnlySoundcheck;
        selectedInputDevice = null;
        requestedPlaybackDevice = null;
        bluetoothPlayback = false;
        outputRouteName = "not confirmed";
        requestedLowLatencyFrames = 0;
        // Live stage monitoring must never silently fall back to phone speaker.
        // The user needs a physical LINE/wired route to the mixer/PA.
        if (mehfilMode || stageVocalOnly) {
            if (requestedOutput == null || "default".equals(requestedOutput)) {
                emit("error", "LIVE MEHFIL OUTPUT MISSING: connect/select USB audio interface LINE OUT or wired mixer output and tap REFRESH.");
                return;
            }
        }
        if (requestedOutput != null && !requestedOutput.equals("default")) {
            try {
                for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (String.valueOf(d.getId()).equals(requestedOutput)) {
                        requestedPlaybackDevice = d;
                        bluetoothPlayback = isBluetoothPlaybackType(d.getType());
                        break;
                    }
                }
            } catch (Exception ex) {
                emit("error", "Bluetooth output list not accessible. Allow Nearby devices permission.");
                return;
            }
            if (requestedPlaybackDevice == null) {
                emit("error", "Selected audio output disconnected. Reconnect USB interface or wired mixer and REFRESH.");
                return;
            }
            if (mehfilMode || stageVocalOnly) {
                int stageType = requestedPlaybackDevice.getType();
                boolean stageWired = stageType == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    stageType == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    stageType == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    stageType == AudioDeviceInfo.TYPE_WIRED_HEADSET;
                if (!stageWired) {
                    emit("error", "LIVE MEHFIL PA OUTPUT UNSAFE: select USB LINE OUT or wired mixer, not phone or Bluetooth speaker.");
                    return;
                }
            }
        }
        // In Bluetooth Live ALWAYS choose the fastest app-owned path. The user must
        // not accidentally reintroduce 1024-sample chunks by disabling a checkbox.
        // This is an app-side optimization, NOT a 100 ms end-to-end guarantee.
        if (bluetoothPlayback) fastLiveMode = true;
        final boolean lowLatencyPath = doMonitor;
        final boolean btDry = bluetoothPlayback && bluetoothDryTest;
        if (bluetoothPlayback && !doMonitor) {
            emit("error", "Enable LIVE monitoring to hear your processed voice over Bluetooth.");
            return;
        }
        if ((mehfilMode || stageVocalOnly) && (requestedDevice == null || "default".equals(requestedDevice))) {
            emit("error", "Live Mehfil requires USB audio input. Connect interface via OTG and tap REFRESH."); return;
        }
        boolean chosenBluetooth = false;
        AudioDeviceInfo chosen = null;
        if (bluetoothPlayback && (requestedDevice == null || "default".equals(requestedDevice))) {
            for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) { chosen = d; break; }
            }
            if (chosen == null) {
                emit("error", "Built-in phone microphone not found; check microphone permission.");
                return;
            }
        }
        if (requestedDevice != null && !"default".equals(requestedDevice)) {
            try {
                for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    if (String.valueOf(d.getId()).equals(requestedDevice)) { chosen = d; break; }
                }
            } catch (SecurityException err) { emit("hint", "Nearby devices permission is required for Bluetooth input."); }
            if (chosen == null) { emit("error", "Selected mic disappeared. Choose Android default and retry."); return; }
            if ((mehfilMode || stageVocalOnly) &&
                 chosen.getType() != AudioDeviceInfo.TYPE_USB_DEVICE &&
                 chosen.getType() != AudioDeviceInfo.TYPE_USB_HEADSET) {
                emit("error", "Live Mehfil input must be USB audio interface. Select USB mic from list."); return;
            }
            if (mehfilMode) {
                int[] availableCounts = chosen.getChannelCounts();
                if (availableCounts.length > 0) {
                    boolean stereoSupported = false;
                    for (int n : availableCounts) if (n >= 2) stereoSupported = true;
                    if (!stereoSupported) {
                        emit("error", "This USB device reports MONO input. Select MIC ONLY SOUNDCHECK or use 2-channel USB interface.");
                        return;
                    }
                }
            }
            selectedInputDevice = chosen;
            chosenBluetooth = chosen.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && chosen.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET);
        }
        try {
            if (chosenBluetooth && Build.VERSION.SDK_INT >= 31) {
                try { audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION); audioManager.setCommunicationDevice(chosen); }
                catch (Exception ignored) { emit("hint", "Bluetooth HFP route could not be forced; trying preferred mic."); }
            }
            // Phone capture should use an unprocessed source when supported. 48 kHz is preferred.
            AudioRecord candidate = null;
            int workingRate = 48000;
            for (int rate : new int[]{48000, 44100}) {
                for (int src : btDry
                    ? new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC}
                    : noiseStrength > 0 && !mehfil
                        ? new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.UNPROCESSED}
                        : new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION}) {
                    try {
                        int minimum = AudioRecord.getMinBufferSize(rate, mehfilMode ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                        if (minimum <= 0) continue;
                        AudioFormat f = new AudioFormat.Builder().setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mehfilMode ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO).build();
                        AudioRecord r = new AudioRecord.Builder().setAudioSource(src).setAudioFormat(f)
                            // Smaller native input buffer for Fast Live (no inflated 8192-byte default).
                            .setBufferSizeInBytes(lowLatencyPath
                                ? Math.max(minimum, mehfilMode ? 2048 : 1024)
                                : Math.max(minimum * 2, 8192)).build();
                        if (r.getState() == AudioRecord.STATE_INITIALIZED) { candidate = r; workingRate = rate; break; }
                        r.release();
                    } catch (Exception ignored) { /* Try next supported combination. */ }
                }
                if (candidate != null) break;
            }
            if (candidate == null) throw new IllegalStateException("Android could not initialize an input recording session.");
            input = candidate;
            sampleRate = workingRate;
            // Keep the physical PHONE microphone as capture source while Bluetooth plays music.
            // Do not switch to communication/HFP mode for Bluetooth media output.
            if (bluetoothPlayback) {
                audioManager.setMode(AudioManager.MODE_NORMAL);
                if (chosen == null || chosen.getType() != AudioDeviceInfo.TYPE_BUILTIN_MIC)
                    throw new IllegalStateException("Bluetooth Live needs the phone's built-in microphone. Select Phone mic.");
            }
            // The direct Bluetooth path must not even attach AGC, AEC or NS.
            // UNPROCESSED is best-effort; Android hardware may still impose preprocessing.
            platformNoiseActive = false;
            if (!btDry) {
                int sessionId = input.getAudioSessionId();
                try { if (AutomaticGainControl.isAvailable()) { agc = AutomaticGainControl.create(sessionId); if (agc != null) agc.setEnabled(false); } } catch (Exception ignored) {}
                try { if (AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(sessionId); if (aec != null) aec.setEnabled(false); } } catch (Exception ignored) {}
                try {
                    if (!mehfil && NoiseSuppressor.isAvailable()) {
                        ns = NoiseSuppressor.create(sessionId);
                        if (ns != null) {
                            ns.setEnabled(noiseStrength > 0);
                            platformNoiseActive = ns.getEnabled();
                        }
                    }
                } catch (Exception ignored) { platformNoiseActive = false; }
            }
            emit("noise", "Vocal Noise Reduction " +
                (noiseStrength == 0 ? "OFF" : noiseStrength == 1 ? "LIGHT" :
                noiseStrength == 2 ? "BALANCED" : "STRONG") + " • " +
                (platformNoiseActive ? "Android NS ACTIVE + voice expander" :
                    "vocal-only expander; Android NS not active") +
                (mehfil ? " • instruments remain clean" : ""));
            if (chosen != null) {
                if (!input.setPreferredDevice(chosen)) {
                    if (mehfilMode || stageVocalOnly)
                        emit("hint", "USB preferred input not accepted yet; checking active routing after recording starts.");
                    emit("hint", "Android did not accept preferred mic routing. Recording source may differ.");
                }
            }
            int trackMin = AudioTrack.getMinBufferSize(sampleRate, mehfilMode ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (trackMin <= 0) trackMin = 8192;
            // BT A2DP may internally buffer hundreds of milliseconds; this minimizes app-side buffering.
            // Two minimum buffers for Bluetooth to avoid non-blocking starvation.
            // The accepted size is read back after the preferred route is selected.
            int trackBytes = bluetoothPlayback ? Math.max(trackMin * 2, 4096)
                : lowLatencyPath ? Math.max(trackMin, 2048)
                : Math.max(trackMin * 2, 8192);
            for (int attempt = 0; attempt < (lowLatencyPath ? 2 : 1); attempt++) {
                AudioTrack.Builder trackBuilder = new AudioTrack.Builder()
                    // GAME is Android's time-sensitive playback usage. On some phones it
                    // offers better Bluetooth scheduling than MEDIA, but A2DP transport
                    // latency remains outside the app's control.
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(lowLatencyPath ? AudioAttributes.USAGE_GAME : AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(mehfilMode ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(trackBytes);
                if (lowLatencyPath && attempt == 0)
                    trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
                try {
                    output = trackBuilder.build();
                    if (output.getState() == AudioTrack.STATE_INITIALIZED) break;
                    output.release();
                    output = null;
                } catch (Exception ex) {
                    if (output != null) { try { output.release(); } catch (Exception ignored) {} }
                    output = null;
                }
            }
            if (output == null || output.getState() != AudioTrack.STATE_INITIALIZED)
                throw new IllegalStateException("Cannot open audio output.");
            monitorFramesWritten = 0;
            outputDrops = 0;
            if (requestedPlaybackDevice != null && !output.setPreferredDevice(requestedPlaybackDevice))
                throw new IllegalStateException("Android rejected selected output. Reconnect Bluetooth media audio.");
            // Route first, then request frame capacity. Android may clamp or reconfigure.
            final int channels = mehfilMode ? 2 : 1;
            final int minFrames = Math.max(1, trackMin / (2 * channels));
            requestedLowLatencyFrames = lowLatencyPath
                ? Math.max(256, bluetoothPlayback ? minFrames * 2 : minFrames) : 0;
            if (requestedLowLatencyFrames > 0) {
                try { output.setBufferSizeInFrames(requestedLowLatencyFrames); }
                catch (Exception ignored) {}
            }
            if (doMonitor) {
                outputRoutingListener = router -> {
                    AudioTrack track = (AudioTrack) router;
                    if (track != output || !running.get()) return;
                    AudioDeviceInfo device = track.getRoutedDevice();
                    if (device == null) return;
                    outputRouteName = device.getProductName().toString();
                    if (requestedLowLatencyFrames > 0) {
                        try { track.setBufferSizeInFrames(requestedLowLatencyFrames); }
                        catch (Exception ignored) {}
                    }
                    emit("latency", "Output route: " + outputRouteName +
                        " • accepted app buffer " + track.getBufferSizeInFrames() +
                        " frames (" + (track.getBufferSizeInFrames() * 1000L / sampleRate) +
                        " ms) • Bluetooth transport delay not included");
                    if (bluetoothPlayback && !isBluetoothPlaybackType(device.getType())) {
                        monitor = false;
                        try { track.pause(); } catch (Exception ignored) {}
                        emit("error", "Output changed away from Bluetooth: " + outputRouteName +
                            ". Monitoring stopped for feedback safety.");
                    } else {
                        emit("route", "OUTPUT ACTIVE • " + outputRouteName);
                    }
                };
                output.addOnRoutingChangedListener(outputRoutingListener, new Handler(Looper.getMainLooper()));
            }
            initDSP();
            monitor = doMonitor;
            input.startRecording();
            if (input.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("Microphone was rejected by Android audio service.");
            // Android may report getRoutedDevice() as null until AFTER the first capture buffers.
            // Defer route validation into the worker instead of aborting on startup.
            if (monitor) output.play();
            running.set(true);
            worker = new Thread(this::processStream, "NaatStudioAudioEngine");
            worker.setPriority(Thread.MAX_PRIORITY);
            worker.start();
            emit("status", "MIC CONNECTED • " +
                (mehfilMode ? "MEHFIL USB STEREO" : stageVocalOnly ? "MEHFIL USB MIC SOUNDCHECK" : bluetoothPlayback ? "PHONE MIC → BLUETOOTH OUTPUT" : "Native AudioRecord") +
                " • " + sampleRate + " Hz" + (chosenBluetooth ? " • Bluetooth microphone requested" : ""));
            if (lowLatencyPath && !bluetoothPlayback) {
                emit("latency", "WIRED LOW LATENCY requested • " + sampleRate + " Hz • " +
                    (1000 * output.getBufferSizeInFrames() / sampleRate) +
                    " ms app playback buffer • USB/interface delay not measured");
            }
            if (bluetoothPlayback) {
                emit("hint", bluetoothDryTest
                    ? "FAST DIRECT VOICE: app echo, reverb and delay bypassed. Bluetooth speaker buffering remains."
                    : "NAAT FX ON: selected echo/delay preset adds intentional repeats. Bluetooth speaker buffering remains.");
                emit("hint", "Bluetooth media output requested. Fast Live " + (fastLiveMode ? "ON" : "OFF") +
                    ". Bluetooth codec latency is outside app control; keep volume low.");
                emit("latency", "FAST LIVE " + (fastLiveMode ? "ON" : "OFF") +
                    " • sample rate " + sampleRate + " Hz • track buffer " +
                    (1000 * output.getBufferSizeInFrames() / sampleRate) + " ms • transport latency not measured");
            }
        } catch (Exception e) {
            String detail = "Mic could not start: " + e.getMessage() +
                ". Check OTG, mic power, USB permissions and close other audio apps.";
            stop();
            emit("error", detail);
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
        lowpass = inputPrev = highpass = envelope = noiseTone = 0;
        expanderGain = 1f;
        phase = 0; glitchClock = 0;
    }
    private float readDelay(double timeMs) {
        int offset = (int) clamp(timeMs * sampleRate / 1000, 1, delayLine.length - 2);
        int idx = writeIndex - offset;
        if (idx < 0) idx += delayLine.length;
        return delayLine[idx];
    }
    private float process(float sample) {
        // Vocal-only noise processing. Channel 2 instruments are never passed to process().
        // First remove mic handling rumble (~75 Hz), then use a gentle, attack/release
        // downward expander so consonants and sustained Naat notes aren't hard-chopped.
        highpass = (float)(sample - inputPrev + .9902 * highpass);
        inputPrev = sample;
        float magnitude = Math.abs(highpass);
        envelope += (magnitude > envelope ? .023f : .00045f) * (magnitude - envelope);
        int strength = noiseStrength;
        float threshold = (float)Math.pow(10, gateDb / 20.0);
        if (strength == 1) threshold = Math.max(threshold, .0018f);   // -55 dB
        if (strength == 2) threshold = Math.max(threshold, .0032f);   // -50 dB
        if (strength == 3) threshold = Math.max(threshold, .0050f);   // -46 dB
        float minGain = strength == 3 ? .06f : strength == 2 ? .12f : strength == 1 ? .26f : .03f;
        float activity = (float)clamp((envelope / Math.max(threshold, 0.0001f) - .65) / 2.0, 0, 1);
        float smoothActivity = activity * activity * (3 - 2 * activity);
        float wantedGain = minGain + (1 - minGain) * smoothActivity;
        expanderGain += (wantedGain > expanderGain ? .0035f : .00045f)
            * (wantedGain - expanderGain);
        // Upper-band hiss taming; conservative to preserve Naat vocal detail.
        float vocal = highpass;
        if (strength != 0) {
            int cutoff = strength == 1 ? 13500 : strength == 2 ? 10500 : 7800;
            float pole = (float)Math.exp(-2.0 * Math.PI * cutoff / sampleRate);
            noiseTone = pole * noiseTone + (1 - pole) * highpass;
            vocal = noiseTone;
        } else {
            noiseTone = highpass;
        }
        float clean = vocal * expanderGain;
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
        // Optional Hall is independent of the DD-8 delay selection. It processes vocal
        // only (channel 2 instruments bypass this method), and never touches dry routing.
        // +RV already has its own hall algorithm, so don't apply it twice.
        if (hallEnabled && !bypass && !"+RV".equals(m)) {
            float a = hallA[hallPosA], b = hallB[hallPosB], c = hallC[hallPosC];
            hallA[hallPosA] = .55f * a + (float)combined * .16f;
            hallB[hallPosB] = .56f * b + (float)combined * .14f;
            hallC[hallPosC] = .58f * c + (float)combined * .12f;
            hallPosA = (hallPosA + 1) % hallA.length;
            hallPosB = (hallPosB + 1) % hallB.length;
            hallPosC = (hallPosC + 1) % hallC.length;
            combined = combined * .82 + (a + b + c) * .18;
        }
        return (float) Math.tanh(combined * 1.3);
    }
    private void processStream() {
        // Apply actual Android audio scheduling priority to the processing thread.
        // Thread.MAX_PRIORITY alone is not equivalent to this Linux scheduling hint.
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO); }
        catch (Exception ignored) { }
        // 256 mono samples = ~5.3 ms at 48 kHz. Use small blocks for monitored USB/wired
        // live audio as well as Fast Live Bluetooth; 1024 for non-live/legacy paths.
        final int chunkSamples = monitor ? 256 : 1024;
        short[] pcmIn = new short[chunkSamples], pcmOut = new short[chunkSamples];
        ByteBuffer chunk = ByteBuffer.allocate(chunkSamples * 2).order(ByteOrder.LITTLE_ENDIAN);
        int tick = 0;
        long liveDiagFrames = 0;
        int stageRouteFrames = 0;
        boolean stageInputVerified = false;
        boolean stageOutputVerified = false;
        try {
            while (running.get()) {
                int count = input.read(pcmIn, 0, pcmIn.length, AudioRecord.READ_BLOCKING);
                if (count <= 0) { if (count < 0) emit("error", "Android AudioRecord read error: " + count); break; }
                if (mehfil || stageVocalOnly) {
                    stageRouteFrames += count / (mehfil ? 2 : 1);
                    if (stageRouteFrames >= sampleRate / 4) {
                        stageRouteFrames = 0;
                        AudioDeviceInfo liveIn = input.getRoutedDevice();
                        if (liveIn != null && selectedInputDevice != null &&
                            liveIn.getId() != selectedInputDevice.getId()) {
                            emit("error", "USB MIC ROUTE ERROR: Android is using " + liveIn.getProductName() +
                                " instead of selected " + selectedInputDevice.getProductName() +
                                ". Check OTG and choose USB input again.");
                            break;
                        }
                        if (!stageInputVerified && liveIn != null) {
                            stageInputVerified = true;
                            emit("stage", "USB MIC ACTIVE: " + liveIn.getProductName() + " • " +
                                (mehfil ? "Stereo Mic+Instrument" : "Mono vocal soundcheck"));
                        }
                        if (!stageOutputVerified && monitor) {
                            AudioDeviceInfo liveOut = output.getRoutedDevice();
                            if (liveOut != null) {
                                stageOutputVerified = true;
                                if (requestedPlaybackDevice != null &&
                                    liveOut.getId() != requestedPlaybackDevice.getId()) {
                                    emit("error", "STAGE OUTPUT ROUTE ERROR: audio is playing on " +
                                        liveOut.getProductName() + ". Reconnect/select USB output; check mixer LINE IN.");
                                    break;
                                }
                                emit("stage", "SPEAKER OUTPUT ACTIVE: " + liveOut.getProductName());
                            }
                        }
                    }
                }
                chunk.clear();
                double vocalPeak = 0, instPeak = 0;
                int valid = mehfil ? count - count % 2 : count;
                if (mehfil) {
                    // Input channel 1 (LEFT) = vocal mic; input channel 2 (RIGHT) = dry instruments.
                    // Never process channel 2 through delay, pitch, feedback or noise gate.
                    for (int i = 0; i < valid; i += 2) {
                        float vocal = pcmIn[i] / 32768f;
                        float instrument = pcmIn[i + 1] / 32768f;
                        float affectedVocal = process(vocal) * (float)vocalVolume;
                        float dryInstrument = instrument * (float)instrumentVolume;
                        float combined = (float)Math.tanh((affectedVocal + dryInstrument) * .85);
                        short wave = (short)(clamp(combined, -1, 1) * 32767);
                        short speakers = (short)(clamp(combined * master, -1, 1) * 32767);
                        pcmOut[i] = speakers; pcmOut[i + 1] = speakers;
                        chunk.putShort(wave); chunk.putShort(wave);
                        vocalPeak = Math.max(vocalPeak, Math.abs(vocal));
                        instPeak = Math.max(instPeak, Math.abs(instrument));
                    }
                } else {
                    for (int i = 0; i < valid; i++) {
                        float original = pcmIn[i] / 32768f;
                        // An honest dry test: skip ALL app effects to isolate transport latency.
                        // Do not claim this makes Bluetooth A2DP instantaneous.
                        float affected = (bluetoothPlayback && bluetoothDryTest)
                            ? original : process(original) * (stageVocalOnly ? (float)vocalVolume : 1f);
                        short processed = (short)(clamp(affected, -1, 1) * 32767);
                        pcmOut[i] = (short)(clamp(affected * master, -1, 1) * 32767);
                        chunk.putShort(recordRaw ? pcmIn[i] : processed);
                        vocalPeak = Math.max(vocalPeak, Math.abs(original));
                    }
                }
                if (monitor) {
                    // Non-blocking BT prioritizes fresh live audio over ever-growing
                    // software backlogs. Short writes are counted (not silently hidden)
                    // and measured against the device's playback queue.
                    int written = bluetoothPlayback
                        ? output.write(pcmOut, 0, valid, AudioTrack.WRITE_NON_BLOCKING)
                        : output.write(pcmOut, 0, valid, AudioTrack.WRITE_BLOCKING);
                    if (written < 0) {
                        emit("error", "Audio output write failed (" + written + "). Reconnect the speaker and restart Live.");
                        break;
                    }
                    if (written > 0) monitorFramesWritten += written / (mehfil ? 2 : 1);
                    if (bluetoothPlayback && written < valid) outputDrops++;
                    // Output routing changes are handled via AudioRouting listener,
                    // never by Binder calls on this audio processing thread.
                }
                synchronized (recordLock) {
                    if (wavOut != null) {
                        try { wavOut.write(chunk.array(), 0, valid * 2); pcmBytes += valid * 2; }
                        catch (Exception ex) { emit("error", "Recording write failed: " + ex.getMessage()); finishWav(); }
                    }
                }
                tick += valid;
                if (bluetoothPlayback && monitor) {
                    liveDiagFrames += valid / (mehfil ? 2 : 1);
                    if (liveDiagFrames >= sampleRate) {
                        liveDiagFrames = 0;
                        // Playback-head queue is only an app-side estimate, NOT mic-to-ear latency.
                        long playHead = Integer.toUnsignedLong(output.getPlaybackHeadPosition());
                        long queuedFrames = Math.max(0, monitorFramesWritten - playHead);
                        long appQueueMs = Math.min(60000, queuedFrames * 1000L / sampleRate);
                        String routedName = outputRouteName;
                        emit("latency", "APP queue estimate " + appQueueMs + " ms (target ≤100 ms) • " +
                            (appQueueMs > 100 ? "APP QUEUE ABOVE TARGET • " : "") +
                            "underruns " + output.getUnderrunCount() + " • dropped chunks " +
                            outputDrops + " • " + routedName +
                            " • speaker/Bluetooth transmission NOT measured (total delay unknown)");
                    }
                }
                if (tick > sampleRate / 8 * (mehfil ? 2 : 1)) {
                    tick = 0;
                    int dbV = (int)Math.max(-80, 20 * Math.log10(Math.max(vocalPeak, .00001)));
                    int dbI = (int)Math.max(-80, 20 * Math.log10(Math.max(instPeak, .00001)));
                    emit("meter", String.valueOf(dbV));
                    if (mehfil) emit("mixmeters", dbV + "," + dbI);
                }
            }
        } catch (Exception ex) { emit("error", "Native mic interrupted: " + ex.getMessage()); }
        finally { running.set(false); emit("status", "MIC OFF"); }
    }
    synchronized void stop() {
        running.set(false);
        if (input != null) { try { input.stop(); } catch (Exception ignored) {} }
        if (worker != null && worker != Thread.currentThread()) {
            try { worker.join(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if (worker.isAlive()) {
                emit("error", "Audio worker still closing; microphone resources retained to avoid a concurrent release. Retry STOP.");
                return;
            }
        }
        worker = null;
        if (input != null) { input.release(); input = null; }
        if (agc != null) { agc.release(); agc = null; }
        if (aec != null) { aec.release(); aec = null; }
        if (ns != null) { ns.release(); ns = null; }
        if (output != null) {
            if (outputRoutingListener != null) {
                try { output.removeOnRoutingChangedListener(outputRoutingListener); } catch (Exception ignored) {}
                outputRoutingListener = null;
            }
            try { output.pause(); output.flush(); } catch (Exception ignored) {}
            output.release(); output = null;
        }
        requestedPlaybackDevice = null;
        selectedInputDevice = null;
        stageVocalOnly = false;
        bluetoothPlayback = false;
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
                wavChannels = mehfil ? 2 : 1;
                wavOut.write(new byte[44]); // Placeholder WAVE header; finalized on STOP.
                pcmBytes = 0; recordRaw = raw;
                emit("record", "RECORDING " + (mehfil ? "COMBINED MIC FX + DRY INSTRUMENTS (STEREO WAV)" : raw ? "RAW MIC" : "EFFECTED AUDIO"));
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
            h.putShort((short)1); h.putShort((short)wavChannels); // PCM mono or combined dual-mono stereo
            word(h,sampleRate); word(h,sampleRate * 2L * wavChannels);
            h.putShort((short)(2 * wavChannels)); h.putShort((short)16);
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