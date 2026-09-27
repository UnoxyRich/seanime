# Android TV local playback server restart run — 2026-09-28

Source commit: `d1feca88` (`codex/android-tv`).

## Results

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

## Scope limits

The restart test stops and starts the Go server in one host test process. It
does not force-stop Android, recreate the Activity and WebView from a saved
native-player snapshot, or decode real media. The TV web build and TypeScript
check passed with `ANDROID_HOME=/Users/unoxyrich/Library/Android/sdk`, but
`:app:bindGoMobile` failed while compiling both Go ABIs with no space left on
the device. The binding, APKs, and instrumentation suite have not been rebuilt
from this commit. Physical process-death recovery and native playback remain
device acceptance gates.
