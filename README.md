# Naat Studio PRO — Native Android Audio Edition

This is an original Android **source project**, not an APK. It uses Android `AudioRecord` directly instead of using WebView `getUserMedia`, the interface that was failing with `NotReadableError` on the user's phone.

## What is built

- Black/gold stage-friendly DD-8-inspired UI
- Native phone/USB/Bluetooth microphone selection, where Android permits routing
- Native `AudioRecord` capture -> PCM DSP -> optional `AudioTrack` monitoring
- 11 effect algorithms: STANDARD, ANALOG, TAPE, WARM, REVERSE, +RV, SHIM (approximation), MOD, WARP, GLT, LOOP
- Five recall/save/rename Naat presets (saved as Android app preferences)
- WAV recording: save directly to **Music/Naat Studio PRO** via Android MediaStore
- Raw/processed recording choice, looper up to 40 seconds, gate, limiter and bypass
- Monitoring is OFF by default to avoid feedback and reduce latency effects
- No external Android libraries or Internet permission

### Build APK (requires Android SDK and Java 17)

With Gradle 8.10.2 and Android SDK platform 35 available:

```sh
gradle --no-daemon :app:assembleDebug
```

The debug APK appears at `app/build/outputs/apk/debug/app-debug.apk` and can be installed as an Android app. Android Studio may install/download an appropriate Gradle version when you open/import the folder.

**GitHub Actions alternative:** Push this project to a **new** repository (do not mix it into the Mobile Technician app) and enable Actions. The included `.github/workflows/build-apk.yml` builds a debug-signed APK and exposes it under the workflow's Artifacts section. Building on GitHub requires GitHub account/repository access and Actions enabled. No Replit is needed.

### Android behavior and limitations

- This source has been written and checked for JavaScript syntax and source structure but **has NOT been compiled with Android SDK, installed, or tested with live microphones** in this environment. It must not be called a production-verified APK yet.
- Requires Android 10 or later. Package name `com.naatstudio.nativepro` intentionally differs from the experimental WebView-only APK to avoid signature conflicts.
- Bluetooth headset microphones may require **Nearby devices** permission and use the hands-free/call profile. Some Android devices disallow selecting them for media recording. Route changes or `setPreferredDevice` requests are best-effort. Phone mic or USB interface is the reliable fallback.
- `SHIM` uses approximate overlapping time-domain pitch-shift grains; `REVERSE` is buffered. This is **not** a bit-perfect BOSS DD-8 replacement.
- Some Bluetooth pathways have large or unpredictable latency. Earbuds are best for recording playback, not live mic-to-earbud monitoring.
- Never use a loudspeaker close to a live microphone. Monitor OFF is selected by default.
- Permissions: `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `BLUETOOTH_CONNECT`. No storage permission needed on Android 10+ for own MediaStore recordings.

## Files

- `app/src/main/java/com/naatstudio/nativepro/MainActivity.java`: Android permissions, packaged UI and JS bridge.
- `app/src/main/java/com/naatstudio/nativepro/NativeAudioEngine.java`: Android `AudioRecord` capture, effects processing, WAV recording and BT input selection.
- `app/src/main/assets/index.html`: local premium interface and five channel presets.
- `.github/workflows/build-apk.yml`: build APK in GitHub Actions using Android SDK.
