# Android TV parity batch — September 29, 2026

Source revision: `40c6198df63dde3de9c2572da91df1bed7f0c22b`.

## Changes

- Debrid downloads stage and extract in app storage, then copy transactionally
  to SAF destinations. Queue intent retains the selected destination/provider;
  cancellation and failed writes retain queue intent and existing destination files.
- Built-in torrents export completed files to SAF and retain their storage
  manifest across restart. Move, pause, queue activation, and deletion share
  synchronization; missing staged files and provider failures preserve the
  existing exported files.
- Web source conversion uses an authenticated range gateway and a full source
  timeline. The shared player retains original source identity, MKV chapters,
  subtitle/font managers, track selection, position, pause state, and advanced
  web controls. Source replacement/unmount cancels pending conversion work and
  stops its session. Plugin, progress, and Nakama events retain original
  playback identity.
- Backgrounding suspends debrid/torrent queues and source encoders. Foreground
  return preserves queue metadata and wakes the current loop even when an old
  worker is still canceling; replacement loops own separate wake channels.
- TV Back regression coverage creates SPA history through a trusted D-pad
  button activation, traverses previous entries, and permits exit at the oldest
  entry. External links use the Android host integration.
- The TV web build disables Rspack `optimization.realContentHash` to avoid a
  final-asset hash dependency panic. Content-hash filenames and all plugins and
  features remain enabled. Rspack computes hashes from compilation data in this
  mode; see the [official option documentation](https://rspack.rs/config/optimization#optimizationrealcontenthash).

Built-in torrent storage retains app-private piece data for random writes and
seeding, and exports completed copies to SAF. Media can occupy both locations
while the torrent remains retained. Web conversion requires enabled FFmpeg
settings and finite, seekable VOD of up to 24 hours; it allows four sessions and
128 MiB of completed segment cache per session. Original native playback sources
and metadata remain available.

## Packaged APKs and emulator checks

The final combined `:app:assembleDebug :app:compileDebugAndroidTestKotlin`
build passed in **2 minutes 52 seconds**, with 8 tasks executed and 42 reused.
The TV web bundle and TypeScript check passed; bundling took 10.4 seconds.
Both Go bindings target API 23. These are debug-signed development APKs.

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| `app-arm64-v8a-debug.apk` | 93,184,425 | `7732f8335eb8bdf5a9ae110934de75bc0e3869edaf2bac7947821ad3af4bc928` |
| `app-x86_64-debug.apk` | 100,255,798 | `4c13fad8ed98d7d71b57eda21783b6931c9bbd62cc7d29583a283410ce3fbd03` |

- Both APKs passed `apksigner verify` with exit code 0.
- All four native libraries in each APK passed the 16 KiB ELF/ZIP alignment
  check. This checks packaging, not runtime operation on a 16 KiB device.
- The fresh ARM64 APK passed both targeted `AndroidTvStartupTest` methods on
  `seanime-tv-api31-batch`, Android 12 / API 31, ARM64, 4 KiB pages, WebView
  91.0.4472.114: `embeddedUiBridgeAndServerLifecycleWork` and
  `remoteBackDismissesWebUiThenReturnsToTheFirstHistoryEntryAndExits`.
  The XML report records two tests, zero failures/errors, and 10.234 seconds
  of test execution. The Gradle run completed in 39 seconds with 64 tasks
  reused, including web build, web embedding and Go binding.
- The Back test verifies trusted D-pad activation, overlay dismissal, reader
  Escape handling, hidden-dialog filtering, SPA history traversal and exit at
  the oldest entry. The startup test verifies the embedded UI, host bridge,
  lifecycle and focus restoration. The full instrumentation suite was not
  rerun for this batch; its earlier result is recorded separately below.
- A static emitted-asset check verified 135 distinct local targets with zero
  missing references. Runtime filename maps covered 105 async JS and 18 async
  CSS chunks; all 107 bundled JS and 19 CSS files retain hashed filenames.
  This check does not execute arbitrary application-created URLs.

Focused emulator command, from `androidtv`:

```sh
./gradlew :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=app.seanime.tv.AndroidTvStartupTest#remoteBackDismissesWebUiThenReturnsToTheFirstHistoryEntryAndExits,app.seanime.tv.AndroidTvStartupTest#embeddedUiBridgeAndServerLifecycleWork' \
  --no-daemon --max-workers=1
```

## Recorded source checks

| Check | Recorded result | Scope |
| --- | --- | --- |
| Full affected Go packages | Pass | All six changed packages pass their complete test suites. |
| Source gateway/conversion (`internal/mediastream`) | Pass, 2.160 s | Authenticated remote ranges, source sessions, conversion and suspension. |
| Cassette (`internal/mediastream/cassette`) | Pass, 1.973 s | Source conversion and encoder teardown regressions. |
| Handlers (`internal/handlers`) | 1.930 s, no tests selected | Package compiled; the focused expression selected no handler tests. |
| Final debrid focused regressions | Pass, 2.159 s | SAF destinations, cancellation, immediate foreground return, wake ownership and queued destination/provider preservation. |
| Final mobile lifecycle regressions | Pass, 2.083 s | Server lifecycle and background/foreground suspension. |
| Built-in torrent focused regressions | Pass, 1.762 s | Export failure/retry, pause/restart, move/delete/persistence, same-size ownership and partial staging loss. |
| Focused frontend regressions | 19 tests, 4 files, 945 ms | Conversion state/errors, original native source identity, audio publication and subtitle preservation. |
| Shared frontend suite | 156 tests, 24 files, 3.05 s | Full shared frontend suite. |
| Frontend TypeScript check | Pass | `tsc --noEmit --incremental false --pretty false`. |

Complete package command, run from the repository root:

```sh
go test -p 2 ./internal/mediastream ./internal/mediastream/cassette ./internal/handlers ./internal/debrid/client ./internal/torrent_clients/builtin_client ./mobile -count=1
```

It passed on the final checkout: `mediastream` 2.710 s, `cassette` 2.319 s,
`handlers` 1.545 s, `debrid/client` 2.392 s, `builtin_client` 1.178 s and
`mobile` 1.663 s.

Source/cassette/handlers command, run from the parity worktree root:

```sh
go test -p 2 ./internal/mediastream ./internal/mediastream/cassette ./internal/handlers -run 'TestSource|TestRemote|TestAndroidTVSource|TestCassetteDestroy' -count=1
```

Focused frontend command, run from `seanime-web`:

```sh
./node_modules/.bin/vitest run \
  'src/app/(main)/_features/video-core/_lib/use-androidtv-source-transcode.test.tsx' \
  'src/app/(main)/_features/video-core/video-core-hls.test.tsx' \
  'src/app/(main)/_features/video-core/_lib/audio-track-source.test.ts' \
  'src/app/(main)/_features/video-core/_lib/subtitle-source.test.ts'
./node_modules/.bin/tsc --noEmit --incremental false --pretty false
```

The host FFmpeg smoke
`TestAndroidTVSourceConversionFFmpegSeekAudioSuspendAndRestart` passed in
0.51 s. It generated a 7.4-second, 64 × 64 FFV1 MKV with two PCM audio tracks
and chapter metadata, converted an authenticated HTTP range source to H.264/AAC
using the second audio track, and checked actual 4.0-second transport-stream
timestamps with ffprobe. It also checked retained chapters, refusal while
backgrounded, foreground restart of the same session, and cache removal on
stop. This proves the host conversion/session path on that fixture; Android
media-tool execution and 1080p/4K performance require device runs.

The earlier 22-test instrumentation result belongs to the API 31 ARM64 TV
emulator with WebView 91.0.4472.114 and 4 KiB pages. Its source/APK evidence is
recorded in the
[September 28 playback lifecycle report](2026-09-28-playback-dismissal-recovery.md).

## Remaining acceptance gates

- Physical USB SAF scans, downloads, overwrite/move/delete, revoked/regranted
  access and unplug/replug during active work, including failed export recovery.
- Representative 1080p and 4K TV hardware: direct playback, converted playback,
  subtitles/fonts, track changes, seeking, remote controls and background return.
- Runtime coverage on minimum API 23, a current Android TV API, 16 KiB page-size
  devices, and x86_64. Earlier static alignment checks and ARM64 API 31 runs
  retain their recorded scope.
- Live online/torrent/debrid provider flows, queue resume and source refresh;
  AniList/MAL OAuth and progress updates; Nakama synchronization, reconnect,
  plugin playback controls and playlist continuity.
- D-pad-only acceptance of every feature group in
  [the acceptance scenarios](../ACCEPTANCE.md#feature-scenarios), including
  populated libraries, manga/offline content, extensions, settings and reports.
- A persistent release signing key and a verified same-key release upgrade.
