# Android TV build and routing check — 2026-09-27

Source content: `2ff9a8eb` (`codex/android-tv`). The APKs were built from this
source after the commit was recorded. They are debug builds.

This revision contains the direct-stream SAF reader and sidecar subtitle
support, Android TV stream routing, playlist player selection, and integrated
player identity for Nakama watch parties.

## Results

- The complete frontend suite passed: **89 tests in 14 files**. TypeScript
  checking passed. Platform tests cover shared player mounting, torrent/debrid
  selection, playlist settings, Nakama player identity, and unchanged desktop
  playback selection.
- Android storage, direct-stream, playlist, and Nakama Go package checks passed.
  The direct-stream/storage suites include SAF random access, byte ranges,
  HEAD and thumbnail requests, sidecar subtitles, size limits, simulated grant
  revocation and recovery, and ordinary local file reads.
- ARM64 and x86_64 debug APK builds and signature verification passed.
- All four native libraries in each APK passed the 16 KiB ELF/ZIP alignment
  verifier; all **7** verifier regression tests passed.
- **10/10 Android instrumentation tests passed in 23.403 seconds** on the API
  31 ARM64 TV emulator with WebView 91 and 4 KiB pages. The tests cover Go
  server readiness/restart, embedded UI and bridge access, SAF test-provider
  operations, native-player recreation and commands, and H.264 CPU encode,
  ffprobe inspection, FFmpeg decode, Media3 frame rendering, and paused seeking.

## APK SHA-256

```text
app-arm64-v8a-debug.apk
89651159bea51a1d1dfbde56182692c43c58602943352cba219240accd2a6d24

app-x86_64-debug.apk
ea26fdc49ba48e33775066a4f5b658e84606e061f9b960ead7e47e4014282763
```

Live authenticated torrent/debrid providers, process-death playback recovery,
physical USB grants, x86_64 runtime, physical 1080p/4K sets, and the full D-pad
feature matrix still need their own device runs. See [ACCEPTANCE.md](../ACCEPTANCE.md)
for the scenarios.
