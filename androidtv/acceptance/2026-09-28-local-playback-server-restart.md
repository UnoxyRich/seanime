# Android TV local playback server restart run — 2026-09-28

Source commit: `d1feca88` (`codex/android-tv`).

## Results

- Emulator: `sdk_google_atv64_arm64`, API 31, WebView 91.0.4472.114, 4096-byte
  guest page size.
- `go test ./mobile -count=1`: passed. The new integration test persists a
  local-file library row, captures its source checkpoint, stops and restarts the
  Go server against the same data directory, reloads the row from SQLite,
  restores playback for a new WebView client ID, and reads the expected fixture
  bytes from the reopened stream.
- `go test ./internal/library/anime -run '^TestLocalEpisodeConstructorsRetainAniDBIdentityWithoutMetadata$' -count=1`:
  passed. Both full and simple local episode constructors preserve the parsed
  AniDB episode identifier when remote metadata is unavailable.
- `go test ./internal/library/anime`: blocked by missing repository AniList
  fixtures in unrelated collection and entry tests. The failure asks for an
  authenticated client and `SEANIME_TEST_RECORD_ANILIST_FIXTURES=true` to create
  those fixtures.
- Android TV web build and TypeScript check passed. `:app:bindGoMobile` passed
  for ARM64 and x86_64 after reusing the compiler cache; the first attempt ran
  out of disk space.
- `:app:connectedDebugAndroidTest`: **18 passed, 0 failed, 0 skipped** on the
  API 31 ARM64 TV emulator. The suite was rebuilt against the updated Go AAR.
- Both debug APKs verify with APK Signature v1 and v2. All four native
  libraries in each APK pass the 16 KiB ELF/ZIP alignment check.

## Build artifact SHA-256

```text
seanime-mobile.aar
fab3bb5b5439c489af05f6f14619eb82420612caff0a49f4cbce28c2ce4c3db4

app-arm64-v8a-debug.apk
b36fa9e8e4efb293698441c5155fddebf4dc63fed2c34a332f5089c4f204fc9c

app-x86_64-debug.apk
92aa1da4b6fc5ca3e5242db7c8f9fb7efe4fd12c50a574a7882e1b0bdd51b158
```

## Scope limits

The restart test stops and starts the Go server in one host test process. It
does not force-stop Android, recreate the Activity and WebView from a saved
native-player snapshot, or decode real media from a codec-valid video. The
Android instrumentation suite covers other app flows; it does not simulate an
OS force-stop while native playback is active. Physical cold-process recovery
and hardware playback remain device acceptance gates.
