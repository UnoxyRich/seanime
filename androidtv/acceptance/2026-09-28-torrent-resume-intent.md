# Android TV torrent resume-intent run — 2026-09-28

Source commit: `b03729d7` (`codex/android-tv`).

## Environment

- Android TV emulator image: `sdk_google_atv64_arm64`, API 31 / Android 12
- ABI: `arm64-v8a`
- WebView: `com.google.android.webview` 91.0.4472.114
- Guest page size: 4096 bytes

## Results

- `GOPROXY=off go test -p=1 ./mobile`: passed. The lifecycle test writes a deduplicated paused-torrent list into app data, starts a fresh server instance in the background, and confirms that the new instance restores the hashes.
- `:app:bindGoMobile`: passed for Android ARM64 and x86_64 from this source.
- `:app:connectedDebugAndroidTest`: **18 passed, 0 failed, 0 skipped** on the API 31 ARM64 TV emulator. The Android package was rebuilt with this commit's Go binding.
- Both APKs pass `apksigner verify` for v1 and v2. All four native libraries in each APK pass the 16 KiB ELF/ZIP alignment verifier.
- No live qBittorrent, Transmission, or Seanime torrent service was configured for this emulator run. The test confirms durable intent restoration; external-client pause/resume calls need a device run.

## Build artifacts

```text
seanime-mobile.aar
d70a6acd569b189cbd2dbdbef431d23f26b2c717ddf8c3e6f495d42f20b2b197

app-arm64-v8a-debug.apk
4faa17864bdb562619bef17e343dfc2dbaa01258b66539ebb5a819153d49d685

app-x86_64-debug.apk
7f800c0f9342ad5f881dc14543c2d8bd08c3dbaaf437e2e4e44d4dbb45514d13
```
