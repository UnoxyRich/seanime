# Android TV active-playback force-stop recovery — 2026-09-28

Recovery code under test: `a7f48805` (native checkpoint lifecycle) and
`0c758c49` (persisted URL-source restoration), branch `codex/android-tv`.

## Environment

- Android TV emulator: `sdk_google_atv64_arm64`, API 31 / Android 12
- ABI: `arm64-v8a`; display: 1920 × 1080; guest page size: 4096 bytes
- WebView: `com.google.android.webview` 91.0.4472.114
- Task-owned emulator data partition was recreated at 2 GiB after the host
  volume ran short on space; no physical-device data was involved.
- Debug APK: ARM64 build with the embedded Seanime UI and Go server.

## Results

- Installed and cold-launched the ARM64 debug APK. Completed first-run setup
  with D-pad and Select, dismissed the optional AniList prompt with Back, and
  confirmed the main UI loaded from the app-hosted server (`HTTP 200`).
- Used a local seekable H.264/MP4 URL fixture and a persisted playback source
  descriptor. The fixture server recorded `HEAD` requests and byte-range `GET`
  requests from the Go direct-stream endpoint.
- While `NativePlayerActivity` was actively playing, Android force-stopped the
  app. `pidof app.seanime.tv` returned no process. The last durable snapshot
  reported `positionMs=51342`, `playWhenReady=true`, and `completed=false`.
- Relaunched `MainActivity` with no recovery intent. The app reopened
  `NativePlayerActivity`, created a new process session, and wrote a checkpoint
  at `positionMs=52444` with playback still requested. The H.264 MediaCodec
  decoder was initialized again, and the restored URL made new ranged requests.
- Screenshots captured the playing native player before force-stop and the
  native player after cold restoration:
  [before force-stop](screenshots/2026-09-28-api31-force-stop-player-active.png),
  [after restoration](screenshots/2026-09-28-api31-force-stop-player-restored.png).

## APK SHA-256

```text
app-arm64-v8a-debug.apk
129c2ad73e0a2de24cb104479f7033af0c63101c7f9b0e3ef53fdd21f5eab946

app-x86_64-debug.apk
8f90d7906a658f34f2e6c7aafa393951fdbea076e973e1bd6b37d79aa5368ed8
```

## Scope limits

This validates a URL-backed source on an API 31 emulator and exercises an
actual Android force-stop while the native player is in the foreground. It
does not establish recovery with local SAF media, live torrent/debrid
providers, online-stream plugins, Nakama rooms, or physical TV hardware.
USB-provider behavior, x86_64 runtime, physical 1080p/4K playback, and the
remaining feature matrix still need their respective device runs.
