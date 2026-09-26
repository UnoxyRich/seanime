# Android TV playback routing check — 2026-09-26

Source content: `17b59fb2` (`codex/android-tv`). Debug APKs were built from this
source before the commit was recorded.

The shared player listener now mounts in Android TV's main and offline layouts.
Default torrent/debrid requests select that listener's player. Imported desktop
mpv preferences cannot select Electron's player on Android. Explicit external
player choices require their configured Android app link.

## Results

- Shared frontend: **87 tests in 14 files passed**; TypeScript checking passed.
  Tests cover actual rendering of the shared player selector, TV routing and
  preservation of non-Android playback selection precedence.
- Go platform routing and independent player-target tests: **2 passed**.
- ARM64 and x86_64 debug APK builds and signature verification passed.
- All four native libraries in each APK passed the 16 KiB ELF/ZIP alignment check.
- **10/10 instrumentation tests passed in 27.941 seconds** on the same API 31
  ARM64 emulator, WebView 91 and 4 KiB configuration described in the
  [media pipeline run](2026-09-26-api31-arm64.md). This includes encoding,
  probing, decoding, Media3 frame rendering and seeking while paused.

## APK SHA-256

```text
app-arm64-v8a-debug.apk
ff5a109a351f69fc11253ea67493d2efd3344999029fd30433c0553fe6aa9373

app-x86_64-debug.apk
2bd3de80adb90e06d1448177e35a99c7b6f0228ff0cc3066bb6e2ae13d6b2608
```

These checks establish routing and the existing host/media regression coverage.
They do not establish live torrent/debrid provider playback, full process-death
restoration, physical USB behavior, or the full remote-only feature matrix.
See [ACCEPTANCE.md](../ACCEPTANCE.md) for the remaining scenarios.
