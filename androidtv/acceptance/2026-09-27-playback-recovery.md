# Android TV playback recovery run — 2026-09-27

Source commit: `ee92f874` (`codex/android-tv`). The debug artifacts and test
results below were built from that commit.

## Environment

- Android TV emulator image: `sdk_google_atv64_arm64`, API 31 / Android 12
- ABI: `arm64-v8a`
- WebView: `com.google.android.webview` 91.0.4472.114
- Guest page size: 4096 bytes
- Emulator display profile: 1920 × 1080

## Results

- `:app:connectedDebugAndroidTest`: **11 passed, 0 failed** in 27.281 seconds.
  Coverage includes embedded UI/server lifecycle, bridge restrictions, SAF
  test-provider operations, native-player commands and activity recreation,
  failure/retry flows, persisted decoder/track state, and bundled FFmpeg and
  ffprobe execution with Media3 encode/probe/decode/seek.
- Go `internal/directstream` and `mobile` tests passed, including matching
  source validation and checkpoint rotation after reopening a stream.
- Frontend TypeScript checking passed; Vitest passed **89 tests in 14 files**.
- ARM64 and x86_64 debug APK signatures verified with v1/v2 schemes. Each APK
  has four native libraries passing the 16 KiB ELF/ZIP alignment verifier.
- x86_64 emulator runtime and physical TV/USB-provider scenarios remain
  unverified. This run did not force-stop the app during active playback or
  exercise a real authenticated stream provider, so end-to-end cold-process
  restoration remains a required device scenario.
- A separate visible-launch attempt installed the ARM64 APK and `am start`
  accepted the MainActivity launch, but the emulator fell back to software GLES
  with about 2 GiB host memory available. Screenshot capture and later device
  shell requests stopped responding, so this attempt produced no visual,
  focus-navigation, or safe-area acceptance result; the emulator was stopped.

## APK SHA-256

```text
app-arm64-v8a-debug.apk
29b12c7c54f9aa6fd344c0451e443ec174bf4fa4dd8a63542a8436965cad34b8

app-x86_64-debug.apk
e42b7cad9ee8364c792b234a4686574f12d07d4edd0a271edd877f8d781220d1
```
