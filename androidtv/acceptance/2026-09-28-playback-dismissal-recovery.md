# Android TV playback dismissal recovery run — 2026-09-28

Application source commit: `a7f48805` (`codex/android-tv`).
Cold-launch instrumentation: `1ca2c8aa`.

## Results

- Added a lifecycle regression test that seeds a persisted native-player
  checkpoint, dismisses the player with Back, waits for Activity stop/destroy,
  and verifies the checkpoint remains deleted after the asynchronous writer
  finishes.
- Recovery writes and clears now share one serial executor. Dismissing or
  completing playback invalidates pending source-capture callbacks and queued
  snapshot writes. Starting a different media item re-enables checkpointing.
- Added a fresh-MainActivity instrumentation case that seeds a ticket with an
  earlier process ID, launches the host without a recovery intent, and verifies
  the persisted ticket is discovered while only its opaque ID reaches the
  bridge. This exercises launch-time discovery but does not kill the Android
  process.
- `:app:connectedDebugAndroidTest`: **20 passed, 0 failed, 0 skipped** on the
  API 31 ARM64 TV emulator (Android 12, 1920 × 1080, WebView 91.0.4472.114).
  The run compiled both Android Kotlin source sets and executed the full
  instrumentation suite.
- A fresh Go mobile relink failed because the host ran out of temporary disk
  space. The successful incremental run skipped `:app:bindGoMobile` and
  `:app:buildAndroidWeb`, reusing the existing Go binding and embedded web build;
  no production Go source changed since that binding was built. The separate Go
  restart integration tests passed in `0c758c49`.
- Both current debug APKs verify with APK Signature v1 and v2, and pass 16 KiB
  ZIP alignment checks. The ARM64 emulator executed the ARM64 APK; x86_64 runtime
  behavior remains a separate device gate.

## APK SHA-256

```text
app-arm64-v8a-debug.apk
129c2ad73e0a2de24cb104479f7033af0c63101c7f9b0e3ef53fdd21f5eab946

app-x86_64-debug.apk
8f90d7906a658f34f2e6c7aafa393951fdbea076e973e1bd6b37d79aa5368ed8
```

## Scope limits

This validates persisted snapshot dismissal and Activity cleanup. It does not
force-stop Android during active media playback or prove cold-process source
restoration, physical USB access, or hardware decoding. Those remain device
acceptance gates.
