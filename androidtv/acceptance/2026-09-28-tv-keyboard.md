# Android TV text entry and OAuth keyboard mode — 2026-09-28

Source commit: `bf9abef6` (`codex/android-tv`).

## Changes and checks

- Configured `AuthWebViewActivity` to resize its window for the TV soft keyboard,
  matching the main activity.
- Added instrumentation checks for the OAuth window's adjust mode and for
  letter-key input plus D-pad navigation in a focused WebView text field.
- `:app:connectedDebugAndroidTest`: passed, 15/15 tests on Android TV API 31,
  ARM64, Android 12, WebView 91.0.4472.114, 4 KiB pages.
- The instrumentation run rebuilt the Android TV frontend and ARM64/x86_64
  APKs. Both APKs passed signature verification, 16 KiB zip alignment, and the
  native-library alignment verifier.

The test does not visually confirm the software keyboard on a physical TV and
does not complete sign-in against AniList or MAL. Those flows remain in the
device acceptance matrix.

## APK SHA-256

```text
app-arm64-v8a-debug.apk  3240cbe53c31d6df07556121440b7c681a6807b79eda75fd0725bc6f7b3f0b81
app-x86_64-debug.apk    ecb36c96534777f6101ff889fb968dbc96fe67b7707a1a3737790d894833124a
```
