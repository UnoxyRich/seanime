# Android TV settings platform check — 2026-09-28

Source revision: `cbf3b11e858074128537025e1d42824f8ba36995` on
`codex/android-tv`.

The settings screen hides the desktop MPV/VLC/IINA/MPC-HC process controls in
the Android TV build. Android playback remains available in Video Playback,
and the custom external player scheme remains in External Player Link. Direct
links to the old `media-player` tab now open TV playback settings.

## Results

- `npm run build:androidtv` passed, including TypeScript checking and Rsbuild.
- `npm test` passed: **89 tests in 14 files**.
- The Android debug build passed and packaged ARM64 and x86_64 APKs.
- Both APKs passed APK signature verification and the 16 KiB ZIP alignment
  check.
- **12/12 Android instrumentation tests passed** on the API 31 ARM64 Android
  TV emulator with WebView 91.0.4472.114 and 4 KiB pages.

These checks compile the settings change and rerun the Android startup,
embedded UI, storage, player, and D-pad instrumentation. They do not include a
manual D-pad pass through every settings control or a physical TV run.

## APK SHA-256

```text
app-arm64-v8a-debug.apk
3353853a2665355953f68286e66a8555b6e2c04783a73ed51402b83ae5c1dcae

app-x86_64-debug.apk
c1e20eddc918fe135587d646c21c22b99724c20c6c4b122aa9e012674ea081ac
```
