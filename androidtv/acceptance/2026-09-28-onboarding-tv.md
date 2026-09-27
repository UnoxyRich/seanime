# Android TV first-run setup — 2026-09-28

Source commit: `798037d6` (`codex/android-tv`).

## Environment

- Android TV emulator image: `sdk_google_atv64_arm64`, API 31 / Android 12
- ABI: `arm64-v8a`
- WebView: `com.google.android.webview` 91.0.4472.114
- Display: 1920 × 1080

## Results

- `npm run build:androidtv` passed its TypeScript check and Rsbuild production
  build. Rsbuild reported the existing ambiguous Tailwind class warning for
  `ease-[cubic-bezier(0.25,1,0.5,1)]`.
- `:app:bindGoMobile` and `:app:assembleDebug` passed for ARM64 and x86_64.
- Both APKs verified with `apksigner` v1/v2 and passed `zipalign -c -P 16`.
- Installed the ARM64 APK and launched the first-run page from the embedded
  web build. The first setup step receives a visible focus ring at launch.
- From that focus, D-pad Right and Select opened the Android TV player step;
  the screen explains the built-in player instead of asking for MPV/VLC. D-pad
  navigation reached the Downloading step, which now describes connecting to
  qBittorrent/Transmission over the network and omits desktop executable paths.
- Screenshots: [cold-start focus](screenshots/2026-09-28-onboarding-dpad-cold-start.png),
  [built-in player](screenshots/2026-09-28-onboarding-built-in-player.png),
  [torrent LAN setup](screenshots/2026-09-28-onboarding-torrent-lan.png).
- This run verifies setup UI only. No real qBittorrent/Transmission service,
  authenticated playback source, physical TV, or USB document provider was
  available for end-to-end validation; existing acceptance notes track those
  remaining device and provider checks.

## APK SHA-256

```text
app-arm64-v8a-debug.apk
0eb014a478c524ab5fb256eff46b72f998afee9e36520344cc5d8e6ebfce8575

app-x86_64-debug.apk
d665e5633707270e3eec45947669b99f7fba2df51aed7a94d4ddd512dc392bfe
```
