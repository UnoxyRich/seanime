# Android TV background-at-start lifecycle run — 2026-09-28

Source commit: `af40a7da` (`codex/android-tv`).

## Environment

- Android TV emulator image: `sdk_google_atv64_arm64`, API 31 / Android 12
- ABI: `arm64-v8a`
- WebView: `com.google.android.webview` 91.0.4472.114
- Guest page size: 4096 bytes

## Results

- `GOPROXY=off go test ./mobile`: passed. The lifecycle coverage records a
  background request before server startup, confirms periodic work remains
  stopped after readiness, and confirms it restarts after foreground return.
  A separate test confirms a foreground update during `starting` records the
  requested state without falsely changing server status.
- `:app:connectedDebugAndroidTest`: **12 passed, 0 failed** on the API 31
  ARM64 TV emulator. This packages the updated Go binding and runs the complete
  Android instrumentation suite, including server lifecycle/restart, D-pad
  focus, storage bridge, player lifecycle/recovery, and bundled media tools.
- ARM64 and x86_64 debug APK signatures verified with v1 and v2 schemes.
- All four native libraries in each APK pass the 16 KiB ELF/ZIP alignment
  verifier.
- The x86_64 APK was built and verified, but x86_64 emulator runtime remains
  untested. Physical USB storage and playback scenarios remain untested.

## APK SHA-256

```text
app-arm64-v8a-debug.apk
a7305c95e4a46210a87b43e9b6f2ad6690639d6d75eb16fd13a530270c906ca3

app-x86_64-debug.apk
634fe58a56fb505d97936f6d77167e199a5e3a29de546bbe1243607d4e9fc30d
```
