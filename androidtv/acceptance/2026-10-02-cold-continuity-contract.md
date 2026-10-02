# Cold playback continuity contract and existing filepath limitation

## Observed R37 result

The Mac ran public source `4801c4d6e90379c8bec8824218d9abfa5f4f4aa2`,
tree `dc1462aa5c8206dd555febd6b447e47762ab0629`, with its locally built APK
SHA-256 `7f64b0ec63ad078198b8c1a81b6b04eeccb6e072834b41b67d8f224edfef7bfd`.
The cold isolated test passed real metadata matching, scan/import, generated-video
decode, and all three compositor pixel checks before, after seek, and after
play/pause. It then timed out at the continuity readback assertion. Resume was
not reached.

Read-only inspection of that test's retained, isolated `watch_history` found one
item: media ID 1, episode 1, current time 10.295 seconds, duration 30 seconds.
Its `filepath` was empty, so it failed the exact indexed-path assertion even
though the time requirement was satisfied. No retry, history mutation, network
change, or application change was used to establish these values.

## Existing unchanged Go behavior

The following behavior is present in the protected baseline
`63200d6a850a6843b52f90ff7775d940416a534d` as well as the current source:

- `internal/mediacore/mediacore.go`, `updateContinuityState`, passes media ID,
  episode, time, duration and kind to the continuity manager without `Filepath`
- `internal/continuity/history.go`, `UpdateWatchHistoryItem`, initializes
  `Filepath` only when creating an item. Updates preserve the original path
- `internal/continuity/manager_test.go`,
  `TestUpdateWatchHistoryItemCreatesAndUpdatesExistingItem`, explicitly expects
  the original filepath to remain after an update supplies a different one
- Local resume resolves the selected indexed file to media ID and episode in
  `GetExternalPlayerEpisodeWatchHistoryItem`; it matches those identifiers
  against history rather than requiring the saved history filepath

Android sends normal playback status events before its independent continuity
PATCH. A Go status write can therefore create an empty-path item before the
PATCH supplies the actual path. Subsequent PATCH calls cannot repair that field
under the existing update contract. **History filepath persistence remains an
existing backend limitation.** This change does not edit Go, remove history, or
claim filepath parity.

## Narrow fixture correction

The cold test now requires the supported history contract: `found`, exact media
ID, exact episode, time at least nine seconds, media-stream kind, and a valid
30-second clip duration. It rejects a history path that names an unrelated file.
The path must be either the exact indexed path or empty, and the manifest
explicitly distinguishes verified path persistence from
`existing-go-empty-filepath-limitation`.

Each poll retains fixed readback fields and path comparison booleans, so a
future timeout preserves its last observed state. No raw path is added to that
summary. The existing owned fixture manifest already retains its generated and
indexed paths for isolated local diagnosis.

The reopened Go stream must now pause at or beyond the recorded millisecond
position, strengthening the previous one-second allowance. A fourth compositor
pixel checkpoint validates the reopened frame using the same strict color
oracle, tolerance and sample region. A dedicated resumed-player screenshot is
retained. Existing metadata, scan, exact selected-file identity, native decode,
pause/seek, isolation and return checks remain.

## Verification limit

The edit is test-only. Source diff checks are available in the recovered Linux
workspace; Android compilation and the corrected fresh cold device run are still
pending. Earlier R37 pixel success does not establish a pass for the new fourth
checkpoint or resume. Generated owned video is not real provider anime.
