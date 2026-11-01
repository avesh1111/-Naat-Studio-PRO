package com.naatstudio.nativepro;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native Android mic I/O; the HTML UI never calls getUserMedia. */
public class MainActivity extends Activity {
    private static final int REQUEST_MIC = 403;
    private WebView web;
    private NativeAudioEngine engine;
    private LiveAudioService liveService;
    private boolean serviceBound = false;
    private boolean pendingServiceStart = false;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            liveService = ((LiveAudioService.LocalBinder) binder).service();
            engine = liveService.getEngine();
            liveService.setUiListener((kind, value) -> postEvent(kind, value));
            serviceBound = true;
            // The WebView may have loaded while the foreground audio service was
            // still binding. Re-populate USB mic/output lists as soon as it is ready.
            refreshUiAudioDevices();
            if (pendingServiceStart) { pendingServiceStart = false; startRequested(); }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            serviceBound = false; liveService = null; engine = null;
            postEvent("error", "Live audio service disconnected. Reopen the app.");
        }
    };
    private void bindAudioService() {
        if (!serviceBound) bindService(new Intent(this, LiveAudioService.class), connection, Context.BIND_AUTO_CREATE);
    }
    private void refreshUiAudioDevices() {
        if (web == null || engine == null) return;
        web.evaluateJavascript(
            "if (typeof refreshDevices === 'function' && document.getElementById('device')) {" +
            "refreshDevices();" +
            "if (typeof refreshStageOutputs === 'function' && document.getElementById('stageOutput')) refreshStageOutputs();" +
            "}", null);
    }
    private boolean pendingStart = false;
    private boolean pendingBluetooth = false;
    private boolean requestedBluetooth = false;
    private boolean requestedMonitor = false;
    private boolean requestedMehfil = false;
    private boolean requestedStageMono = false;
    private String requestedDevice = "default";
    private String requestedOutput = "default";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xff080c12);
        getWindow().setNavigationBarColor(0xff080c12);
        bindAudioService();
        web = new WebView(this);
        web.setBackgroundColor(0xff080c12);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setMediaPlaybackRequiresUserGesture(true);
        web.setWebChromeClient(new android.webkit.WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                refreshUiAudioDevices();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                // UI is a bundled, trusted asset; do not navigate to remote content with JS bridge enabled.
                return !"file:///android_asset/index.html".equals(request.getUrl().toString());
            }
        });
        web.addJavascriptInterface(new Bridge(), "NativeApp");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }
    private void postEvent(String type, String message) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.nativeEvent(" + JSONObject.quote(type) + "," + JSONObject.quote(message) + ")", null);
        });
    }
    private void startRequested() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        if (requestedBluetooth && Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pendingBluetooth = true;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 404);
            postEvent("hint", "Allow Nearby devices for Bluetooth speaker/earbuds.");
            return;
        }
        if (!serviceBound || liveService == null || engine == null) {
            pendingServiceStart = true;
            bindAudioService();
            return;
        }
        try {
            // A visible activity must initiate microphone foreground service startup.
            Intent i = new Intent(this, LiveAudioService.class).setAction(LiveAudioService.ACTION_LIVE);
            startForegroundService(i);
            liveService.startLive(requestedDevice, requestedMonitor, requestedMehfil, requestedOutput, requestedStageMono);
        } catch (Exception e) {
            postEvent("error", "Unable to start foreground microphone: " + e.getMessage());
        }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQUEST_MIC && pendingStart) {
            pendingStart = false;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startRequested();
            else postEvent("error", "Microphone permission denied. Enable it in Android App Settings.");
        } else if (code == 404 && pendingBluetooth) {
            pendingBluetooth = false;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startRequested();
            else postEvent("error", "Nearby devices permission denied. Bluetooth output is unavailable.");
        }
    }
    @Override protected void onDestroy() {
        if (liveService != null) liveService.clearUiListener();
        if (serviceBound) { unbindService(connection); serviceBound = false; }
        engine = null;
        if (web != null) { web.removeJavascriptInterface("NativeApp"); web.destroy(); }
        super.onDestroy();
    }
    private final class Bridge {
        @JavascriptInterface public void start(String deviceId, boolean monitor) {
            requestedDevice = (deviceId == null || deviceId.isEmpty()) ? "default" : deviceId;
            requestedMonitor = monitor;
            requestedMehfil = false;
            requestedStageMono = false;
            requestedOutput = "default";
            requestedBluetooth = false;
            runOnUiThread(MainActivity.this::startRequested);
        }
        @JavascriptInterface public void startMehfil(String deviceId, boolean monitor, String outputId, boolean stereo) {
            requestedDevice = (deviceId == null || deviceId.isEmpty()) ? "default" : deviceId;
            requestedMonitor = monitor;
            requestedMehfil = stereo;
            requestedStageMono = !stereo;
            requestedOutput = (outputId == null || outputId.isEmpty()) ? "default" : outputId;
            requestedBluetooth = false;
            runOnUiThread(MainActivity.this::startRequested);
        }
        @JavascriptInterface public void startBluetoothLive(String phoneMicId, String outputId) {
            requestedDevice = phoneMicId == null ? "default" : phoneMicId;
            requestedOutput = outputId == null ? "default" : outputId;
            requestedMonitor = true;
            requestedMehfil = false;
            requestedStageMono = false;
            requestedBluetooth = true;
            // Bluetooth Live must always use the short-buffer real-time path.
            if (engine != null) engine.setFastLiveMode(true);
            runOnUiThread(MainActivity.this::startRequested);
        }
        @JavascriptInterface public void hall(boolean enabled) { if (engine != null) engine.setHallEnabled(enabled); }
        @JavascriptInterface public void noiseReduction(int strength) { if (engine != null) engine.setNoiseReduction(strength); }
        @JavascriptInterface public void fastLive(boolean enabled) { if (engine != null) engine.setFastLiveMode(enabled); }
        @JavascriptInterface public void bluetoothDryTest(boolean enabled) { if (engine != null) engine.setBluetoothDryTest(enabled); }
        @JavascriptInterface public String outputs() { return engine == null ? "[]" : engine.audioOutputs().toString(); }
        @JavascriptInterface public void mix(double vocal, double instrument) { if (engine != null) engine.setMixLevels(vocal, instrument); }
        @JavascriptInterface public void stop() { runOnUiThread(() -> { if (liveService != null) liveService.stopLive(); }); }
        @JavascriptInterface public void params(double level, double feedback, int ms, String mode, int gateDb, double master, boolean bypass) {
            if (engine != null) engine.setParameters(level, feedback, ms, mode, gateDb, master, bypass);
        }
        @JavascriptInterface public String microphones() { return engine == null ? "[]" : engine.microphones().toString(); }
        @JavascriptInterface public void requestBluetooth() {
            if (Build.VERSION.SDK_INT >= 31) runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 404);
                else postEvent("hint", "Nearby devices permission is already enabled. Refresh microphone list.");
            });
        }
        @JavascriptInterface public void recordStart(String label, boolean raw) { if (engine != null) engine.beginRecording(label, raw); }
        @JavascriptInterface public void recordStop() { if (engine != null) engine.endRecording(); }
        @JavascriptInterface public void warp(boolean enabled) { if (engine != null) engine.setWarp(enabled); }
        @JavascriptInterface public void loop(String action) { if (engine != null) engine.loopCommand(action); }
        @JavascriptInterface public void playLast() { runOnUiThread(() -> { if (engine != null) engine.playLastRecording(); }); }
        @JavascriptInterface public String getPresets() {
            return getSharedPreferences("presets", MODE_PRIVATE).getString("five", "");
        }
        @JavascriptInterface public void savePresets(String json) {
            if (json != null && json.length() < 20000)
                getSharedPreferences("presets", MODE_PRIVATE).edit().putString("five", json).apply();
        }
    }
}
