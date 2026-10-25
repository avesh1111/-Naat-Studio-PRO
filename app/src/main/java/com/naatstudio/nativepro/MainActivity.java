package com.naatstudio.nativepro;

import android.Manifest;
import android.app.Activity;
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
        engine = new NativeAudioEngine(this, (type, message) -> postEvent(type, message));
        web = new WebView(this);
        web.setBackgroundColor(0xff080c12);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true); s.setMediaPlaybackRequiresUserGesture(true);
        web.setWebChromeClient(new android.webkit.WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                // UI is a bundled, trusted asset; do not navigate to remote content with JS bridge enabled.
                return !"file".equals(request.getUrl().getScheme());
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
        engine.start(requestedDevice, requestedMonitor, requestedMehfil, requestedOutput, requestedStageMono);
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
        if (engine != null) engine.close();
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
            runOnUiThread(MainActivity.this::startRequested);
        }
        @JavascriptInterface public void hall(boolean enabled) { engine.setHallEnabled(enabled); }
        @JavascriptInterface public void noiseReduction(int strength) { engine.setNoiseReduction(strength); }
        @JavascriptInterface public void fastLive(boolean enabled) { engine.setFastLiveMode(enabled); }
        @JavascriptInterface public String outputs() { return engine.audioOutputs().toString(); }
        @JavascriptInterface public void mix(double vocal, double instrument) { engine.setMixLevels(vocal, instrument); }
        @JavascriptInterface public void stop() { engine.stop(); }
        @JavascriptInterface public void params(double level, double feedback, int ms, String mode, int gateDb, double master, boolean bypass) {
            engine.setParameters(level, feedback, ms, mode, gateDb, master, bypass);
        }
        @JavascriptInterface public String microphones() { return engine.microphones().toString(); }
        @JavascriptInterface public void requestBluetooth() {
            if (Build.VERSION.SDK_INT >= 31) runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 404);
                else postEvent("hint", "Nearby devices permission is already enabled. Refresh microphone list.");
            });
        }
        @JavascriptInterface public void recordStart(String label, boolean raw) { engine.beginRecording(label, raw); }
        @JavascriptInterface public void recordStop() { engine.endRecording(); }
        @JavascriptInterface public void warp(boolean enabled) { engine.setWarp(enabled); }
        @JavascriptInterface public void loop(String action) { engine.loopCommand(action); }
        @JavascriptInterface public void playLast() { runOnUiThread(() -> engine.playLastRecording()); }
        @JavascriptInterface public String getPresets() {
            return getSharedPreferences("presets", MODE_PRIVATE).getString("five", "");
        }
        @JavascriptInterface public void savePresets(String json) {
            if (json != null && json.length() < 20000)
                getSharedPreferences("presets", MODE_PRIVATE).edit().putString("five", json).apply();
        }
    }
}
