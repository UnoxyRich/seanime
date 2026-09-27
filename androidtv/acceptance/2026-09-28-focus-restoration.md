# Android TV launcher and focus restoration run — 2026-09-28

Android application source commit: `e6a8b723` (`codex/android-tv`).
Instrumentation test source commit: `c03566f9`.

## Environment

- Android TV emulator image: `sdk_google_atv64_arm64`, API 31 / Android 12
- ABI: `arm64-v8a`
- WebView: `com.google.android.webview` 91.0.4472.114
- Guest page size: 4096 bytes
- Display: 1920 × 1080, rendered with the host GPU

## Results

- `:app:connectedDebugAndroidTest`: **12 passed, 0 failed**. The startup test
  creates a focused WebView control, invokes the native Android storage-picker
  path, dismisses the TV image's fallback dialog with Back, and checks that the
  same control is active again with the Android TV 3 px outline and visible
  focus ring. It also verifies the activity loses and regains window focus.
- The package-manager test confirms the app is discoverable from the Leanback
  launcher, resolves to `MainActivity`, has an icon and TV banner, requires the
  Leanback feature, and declares touchscreen support optional.
- Manual D-pad acceptance rendered the actual first-run Seanime screen, moved
  focus to “Choose library folder on USB,” opened the native fallback dialog,
  and returned to the same button with its purple focus ring visible. The
  [1920 × 1080 screenshot](screenshots/2026-09-28-api31-arm64-dpad-focus-restored.png)
  records the returned state.
- The TV emulator has no usable `ACTION_OPEN_DOCUMENT_TREE` provider; the
  intent resolves to Android's `DocumentsStub`. The native fallback message is
  verified, but selecting a real USB folder and exercising persisted grants
  still require a device or emulator image with a document provider.
- Frontend `pnpm typecheck` passed and Vitest passed **89 tests in 14 files**.
- `:app:assembleDebug` produced ARM64 and x86_64 APKs. `apksigner` verified
  both with v1/v2 using the Android debug certificate. All four native
  libraries in each APK passed the 16 KiB ELF/ZIP alignment verifier.
- Physical Android TV, USB-provider operations, x86_64 runtime, 4K devices,
  and authenticated live playback sources remain unverified. The host had only
  1.7 GiB free and no x86_64 TV image installed. The available API 36 image
  archive is about 990 MB and contains an 8.6 GB logical `system.img`; I left it
  uninstalled to avoid exhausting local storage.

## APK SHA-256

```text
app-arm64-v8a-debug.apk
4ed6e477d54b86e6c1bef28c2e7a2126152b2f6dc4af7488f0f0822aabadbd7a

app-x86_64-debug.apk
0bfa82b8f7a31a0c51c3c481da2f56e85c16e56aa3e912e9d2b42b5da92950d8
```
