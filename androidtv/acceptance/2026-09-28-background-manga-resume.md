# Android TV manga background/resume run — 2026-09-28

Source commit: `5b335796` (`codex/android-tv`).

## Environment

- Android TV emulator image: `sdk_google_atv64_arm64`, API 31 / Android 12
- ABI: `arm64-v8a`
- WebView: `com.google.android.webview` 91.0.4472.114
- Guest page size: 4096 bytes

## Results

- `GOPROXY=off go test -p=1 ./internal/manga ./internal/manga/downloader ./internal/manga/providers ./internal/util/proxies ./mobile`: passed. The downloader tests pause an active image request, verify its cancellation and saved page progress, resume, and confirm completed pages are not requested again. Queue lifecycle coverage also verifies interrupted work is picked up by a new Downloader.
- `:app:bindGoMobile`: passed for Android ARM64 and x86_64 from the source at this commit.
- `:app:connectedDebugAndroidTest`: **18 passed, 0 failed, 0 skipped** on the API 31 ARM64 TV emulator. This rebuilt the Android host with the binding from this commit.
- Both APKs pass `apksigner verify` for v1 and v2. All four native libraries in each APK pass the 16 KiB ELF/ZIP alignment verifier.
- Host Go tests use a local HTTP server. Real provider downloads and background recovery on a physical TV remain device acceptance items.

## Build artifacts

```text
seanime-mobile.aar
63330d876f10e7d4fcca13bf5983038d8227220b2afdbe2f0a02eeb6866bad5f

app-arm64-v8a-debug.apk
6718434e7b34063597d40aed4bdbecd2b92dd07cfc722b390afe6cab2d2ea5de

app-x86_64-debug.apk
f89178eeba0d639833f0bfdc6966ce77ae10c9d7230a8a17ca97c4a7d0436056
```
