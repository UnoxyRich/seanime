# Android TV playback recovery run — 2026-09-27

Source commit: `c1988f49` (`codex/android-tv`). The debug artifacts and test
results below were built from that commit.

## Environment

- Android TV emulator image: `sdk_google_atv64_arm64`, API 31 / Android 12
- ABI: `arm64-v8a`
- WebView: `com.google.android.webview` 91.0.4472.114
- Guest page size: 4096 bytes
- Emulator display profile: 1920 × 1080

## Results

- `:app:connectedDebugAndroidTest`: **11 passed, 0 failed** in 24.836 seconds.
  Coverage includes embedded UI/server lifecycle, bridge restrictions, SAF
  test-provider operations, native-player commands and activity recreation,
  failure/retry flows, actual Android D-pad key events delivered to WebView,
  persisted decoder/track state, the WebView bridge
  returning only the opaque checkpoint ID, and bundled FFmpeg/ffprobe execution
  with Media3 encode/probe/decode/seek.
- Go `internal/directstream` and `mobile` tests passed, including matching
  source validation and checkpoint rotation after reopening a stream.
- Frontend TypeScript checking passed; Vitest passed **89 tests in 14 files**.
- ARM64 and x86_64 debug APK signatures verified with v1/v2 schemes. Each APK
  has four native libraries passing the 16 KiB ELF/ZIP alignment verifier.
- x86_64 emulator runtime and physical TV/USB-provider scenarios remain
  unverified. This run did not force-stop the app during active playback or
  exercise a real authenticated stream provider, so end-to-end cold-process
  restoration remains a required device scenario.
- Visible-launch attempts installed the ARM64 APK and `am start` accepted the
  MainActivity launch, but screenshot capture was unusable. The default
  emulator fell back to software GLES with about 2 GiB host memory available;
  ADB screenshot capture stalled. A retry with a 1536 MiB guest and SwiftShader
  let the instrumentation screenshot API return a 1920 × 1080 all-black frame
  (one sampled color, zero bright samples) while the DOM reported ready. No
  visual, focus styling, or safe-area acceptance result was captured, so the
  emulator was stopped.

## APK SHA-256

```text
app-arm64-v8a-debug.apk
0df370f6f94c4e894755beef5be4af04339e064002704f19bf1b95f998ae4ee4

app-x86_64-debug.apk
9f1dde5b68767f2ecbd3949289167c3085d8445c2e66605259b2abbb2acf7c18
```
