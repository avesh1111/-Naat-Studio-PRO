# NAAT STUDIO PRO — iPhone source app

iOS project sources have been added **without changing the Android APK/audio engine**.

## Building for iPhone

On a Mac with Xcode and XcodeGen:

```bash
cd ios
xcodegen generate
open NaatStudioPRO.xcodeproj
```

Select your Apple developer team, connect an iPhone, and use **Run** in Xcode. The app includes the Android project's offline HTML, its mosque and studio pictures, all 61 presets, local custom presets and profile (Name, Phone, DOB, Email). Keep the full repository directory structure intact.

An IPA for other users needs iOS signing and provisioning, generally through an Apple Developer account and TestFlight/App Store or another Apple-supported distribution method. Do **not** rename an APK to IPA. An Xcode project or simulator build is not an installable iPhone IPA.

## iOS support scope

- Shared visual design: YES, same HTML and bundled picture backgrounds.
- CH1–CH5 preset selection, 61 preset names, local custom presets, profile: YES. Storage is per device, NOT synchronized between Android and iOS.
- Built-in iPhone microphone, monitoring, standard delay/Hall approximation and local WAV: Initial implementation; on-device verification required.
- Bluetooth playback: select an output using the native iOS route picker. Built-in mic + A2DP playback may have substantial lag.
- Live Mehfil USB mic-only: initial soundcheck implementation, compatible iOS USB input required.
- Two-channel USB mic + dry instruments, independent instrument mix and combined recording: **not ported to iOS yet**.
- Android-specific noise reduction, loop/warp/GLT/SHIM, and full 11 DD-8 native DSP algorithms: **not ported to iOS yet**.
- Hardware feedback detection or guaranteed low latency: NOT provided. Start with low PA/earbuds volume.

Important: This is a first iOS source implementation. It has not been signed, installed or tested on an iPhone. iOS simulator compile checks do not verify physical USB/Bluetooth routing.

## Smoke-test checklist

1. Install from Xcode on a real iPhone, enable Microphone permission.
2. Home: 3 photo cards, header mosque background, Preset Library, all five channels.
3. Profile: save name, phone, DOB and email; relaunch, confirm locally saved.
4. Mobile Mic: record a WAV and play last; test with monitoring off first.
5. Bluetooth: pair earbuds, select output via iOS route picker and evaluate noticeable latency.
6. USB mic-only: test interface with low PA volume, check input/output routing.
7. Ensure unsupported two-channel iOS stage mode displays a clear error rather than starting incorrectly.
