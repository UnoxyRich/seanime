# Seanime TV

Native Android TV client built with Jetpack Compose for TV and Media3. The app
hosts the existing Go server in-process and uses its unchanged REST and WebSocket
APIs. The launcher, first-run setup, navigation, feature screens, dialogs and
manga reader are native Kotlin. The React app is only a behavior/API reference:
no React page, component, style, hook or bundle is loaded or packaged by this
Android build. A restricted separate browser activity is used only for provider
OAuth.

The package ID remains `app.seanime.tv`, so signed in-place upgrades retain the
existing Android data/cache directories, databases and persisted SAF grants.
The repository's GPL-3.0 license and upstream attribution remain unchanged.

Current review source `43c2f02e` passes 366 host JVM tests, 34 layout comparisons,
lint and both ABI builds. Device acceptance is incomplete: the cloud TV
framework failed before installation, and live AniList returned HTTP 403.
See the [current layout/data report and exact APK hashes](acceptance/2026-10-01-tv-layout-and-live-data.md)
and [remaining device manifest](acceptance/2026-10-01-tv-device-manifest.json).

## Build

Required tools: repository-pinned Go, JDK 17 or newer, Android SDK platform 36,
build tools 36, NDK `27.2.12479018`, and `curl`, `gpg`, `gpgv`, `git`, `make`, `nasm`,
`pkg-config`, Python 3 and a host C toolchain. The Gradle wrapper is 8.11.1. Node/npm are
not required to build this app. Run these commands from the repository root.

```sh
./androidtv/gradlew -p androidtv :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2
```

Gradle builds the signed-source FFmpeg release and pinned x264 dependency,
creates the real gomobile AAR for Android ARM64 and x86_64, and packages two
ABI-split APKs. The original `mobile/web/.gitkeep` satisfies Go's unchanged embed
directive; there is no generated frontend bundle. Keep build/dependency caches.
`SEANIME_GO_BUILD_PARALLELISM` controls Go workers (default 2).

The Android bind uses a generated driver module and a checksum-verified copy of
`modernc.org/libc v1.41.0` under `app/build/generated`. Its Android/amd64 adapter
maps legacy Linux filesystem calls to Android-permitted equivalents; the pinned
module cache, root `go.mod`/`go.sum`, server code and ARM64 implementations stay
unchanged. The generator fails on dependency/source drift. This is build-host
compatibility, not a relaxed Android sandbox. See the acceptance report for the
exact mapping, semantic tests and documented pointer/clock edge limits.

```sh
python3 scripts/check-native-tv-contracts.py --baseline 63200d6a850a6843b52f90ff7775d940416a534d
python3 scripts/verify_android_native_alignment.py androidtv/app/build/outputs/apk/debug/*.apk
./androidtv/gradlew -p androidtv :app:connectedDebugAndroidTest --max-workers=1
```

The isolated Go scan/import/playback, raw-media playback, sustained external-player
handoff and owned-library-management scenarios are opt-in and skipped by the
ordinary connected suite. Run each method alone with its own opt-in flag in a
cold process, following the data-restoration protocol in the acceptance report.
The raw-media case uses generated owned video and signed Go ranges. The library
case imports only a generated unmatched index through the existing API. Neither
establishes public scan/matching parity. Never enable these scenarios in a whole
suite. The external-probe cancellation case has a separate recovery-preserving
wrapper. Use the [finite runtime manifest](acceptance/2026-09-30-next-native-test-manifest.md)
for exact filters, ownership guards and evidence requirements. On a constrained
host, finish Gradle builds before starting emulator test runs.

Source checks are guardrails, not proof of functional parity. See the dated
[native acceptance report](acceptance/2026-09-30-native-compose.md) for the exact
new build/test results and remaining gates. September 26–29 reports describe the
old WebView APKs and must not be represented as native Compose acceptance.

## Native architecture

- `MainActivity` owns Android launch, server readiness, first-run setup and
  provider callbacks. It uses a `ComponentActivity` and `setContent`, with no
  WebView or JavaScript interface
- `ui/TvApp.kt` implements native navigation, library, search, show details and
  list editing. Saveable destination state preserves query, scroll and selected
  card across details and Activity recreation. Focusable TV components, explicit
  Back handling and 40 dp horizontal/28 dp vertical safe margins are used
- `ui/SourceScreen.kt` implements local, online, torrent and debrid selection,
  provider/audio selection, episode lookup, torrent-file selection and release
  downloads to server-known library folders
- `ui/FeatureScreens.kt` and `ui/MangaReader.kt` contain native offline,
  playlist, extension, download, Nakama, settings, report and reader workflows
- `data/SeanimeApiClient.kt` handles HTTP envelopes, cancellation, same-origin
  authentication/identity headers, bounded WebSocket reconnect and identity
  refresh. REST and event payloads follow the existing server contracts
- `platform/NativePlaybackCoordinator.kt` consumes playback events directly,
  drives Media3, sends progress/continuity, handles playlist/source transitions
  and restores playback checkpoints without a retained browser
- `NativePlayerActivity` uses newly authored Compose audio/subtitle selectors, seeking,
  speed, pause, screenshots, decoder lifecycle and persisted track preferences
- `platform/NativePlatformActions.kt` owns SAF permissions, document export,
  account OAuth state, ABI-matched APK updates and the Android package installer
- `SeanimeTvApplication` and `AndroidSafStorageAdapter` retain the existing
  Android hosting, foreground/background suspension and transactional SAF
  adapter. Go core, API routes/payloads, database models and schemas are unchanged

## Feature behavior and boundaries

Library/search/detail flows use actual server collections, list mutations and
source selection. First-run setup can use the app's own media directory or a
persisted USB/SAF root. A protected local server has a native password form; its
hash is held only for the current Activity session. Media3 requests send server
credentials only to the canonical loopback origin and provider headers only to
the selected source origin.

The manga reader uses native image loading and remote page controls. Its optional
automatic tracking advances after the final page renders; rereading does not
lower progress. Anime and manga list editors load current values, submit only
edited status/progress/rating fields, and confirm list removal without deleting
media files. Offline metadata and downloaded chapters remain server-owned. Playlist storage uses the
existing CRUD APIs; playback uses the real WebSocket protocol rather than the
legacy no-op playlist REST handlers. Nakama room/chat/watch-party state uses its
existing API and event protocol. Server plugin controls, grants, page actions,
custom episode tabs, trays and command palettes are native. Arbitrary plugin
HTML, DOM scripts and custom CSS remain an explicit native presentation gap.

Manage accounts in Settings or AniList & MAL to connect or disconnect accounts.
AniList connection changes require explicitly returning online first, because
the existing server does not clear its offline flag when replacing that account.

Unavailable server APIs, including independent MAL list/search and legacy
playlist routes, and separate native plugin presentation gaps are documented in the
[API contract notes](app/src/main/java/app/seanime/tv/data/README.md). Missing
capabilities must be shown honestly; do not add a hidden browser or modify the
server to make a parity checkbox appear complete.

## Storage, lifecycle and upgrades

The server still binds only to `127.0.0.1:43211`. Existing data and cache paths
are retained under `files/seanime/data` and `cache/seanime`. Background work
suspends through the existing application lifecycle bridge. Media3 releases its
decoder when stopped and restores state on return. SAF roots retain their
persisted grants; primary library, additional library, manga, torrent and
screenshot destinations are handled separately.

The Android updater checks the fork's GitHub release for a matching ABI APK,
asks before download and hands installation to the system installer. A stable
release requires a persistent signing key; every update must use that same key.
Unsigned artifacts and debug APKs are not production upgrade evidence. Configure
`SEANIME_ANDROID_KEYSTORE_FILE`, `SEANIME_ANDROID_KEYSTORE_PASSWORD`,
`SEANIME_ANDROID_KEY_ALIAS` and `SEANIME_ANDROID_KEY_PASSWORD` together when
building release APKs. Never commit signing secrets.

Physical USB provider recovery, 1080p/4K decoding/transcoding, live provider and
account flows, remote Nakama sessions, API 23/current TV/16 KiB runtime and a
same-key release upgrade each require their own acceptance run.
