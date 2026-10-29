package com.naatstudio.nativepro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

/**
 * Owns the native microphone stream independently from the WebView activity.
 * A visible activity starts this microphone foreground service only after
 * RECORD_AUDIO has been granted, as required on Android 14+.
 */
public final class LiveAudioService extends Service {
    static final String ACTION_LIVE = "com.naatstudio.nativepro.LIVE";
    static final String ACTION_STOP = "com.naatstudio.nativepro.STOP";
    private static final String CHANNEL = "naat_studio_live_mic";
    private static final int NOTIFICATION_ID = 2208;
    private final IBinder binder = new LocalBinder();
    private NativeAudioEngine engine;
    private volatile NativeAudioEngine.Listener uiListener;
    private boolean foreground = false;

    public final class LocalBinder extends Binder {
        LiveAudioService service() { return LiveAudioService.this; }
    }

    @Override public void onCreate() {
        super.onCreate();
        engine = new NativeAudioEngine(this, (kind, message) -> {
            NativeAudioEngine.Listener l = uiListener;
            if (l != null) l.onEvent(kind, message);
        });
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(
            CHANNEL, "Naat Studio live microphone", NotificationManager.IMPORTANCE_LOW));
    }

    NativeAudioEngine getEngine() { return engine; }
    void setUiListener(NativeAudioEngine.Listener l) { uiListener = l; }
    void clearUiListener() { uiListener = null; }

    void promoteForeground() {
        if (foreground) return;
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, LiveAudioService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Naat Studio PRO — Live microphone")
            .setContentText("Live audio is running. Tap to return to the studio.")
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "STOP LIVE", stopIntent)
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        foreground = true;
    }

    void startLive(String input, boolean monitor, boolean mehfil, String output, boolean mono) {
        promoteForeground();
        engine.start(input, monitor, mehfil, output, mono);
        if (!engine.isRunning()) {
            NativeAudioEngine.Listener l = uiListener;
            if (l != null) l.onEvent("hint", "Microphone did not start. Check permissions and selected device.");
            stopLive();
        }
    }

    void stopLive() {
        if (engine != null) engine.stop();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        stopSelf();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) { stopLive(); return START_NOT_STICKY; }
        if (ACTION_LIVE.equals(action)) promoteForeground();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        clearUiListener();
        if (engine != null) engine.close();
        super.onDestroy();
    }
}