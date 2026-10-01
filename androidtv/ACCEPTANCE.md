# Android TV acceptance

## Native Compose migration

The active implementation is now native Compose for TV. Its current result,
feature matrix and device gates are recorded in the
[October 1 TV layout and live-data report](acceptance/2026-10-01-tv-layout-and-live-data.md).
Current source `43c2f02e` passes 366 JVM tests and 34 host layout comparisons,
but all 176 planned current-device methods remain unrun after a preinstall
framework gate failure. The [exact manifest](acceptance/2026-10-01-tv-device-manifest.json)
separates these from four retained direct-engine results and one externally
blocked scan/matching method. The
[September 30 native report](acceptance/2026-09-30-native-compose.md) retains
the earlier implementation history and API boundary evidence.
The evidence below is preserved historical evidence from base commit
`63200d6a850a6843b52f90ff7775d940416a534d` and earlier WebView APKs. It does not
prove any rewritten Compose navigation, feature UI or native API workflow.
No historical test result has been promoted to a new native pass.


The target remains the full Seanime feature set in the Android TV host. Device
acceptance requires running the scenarios below on the APK being evaluated.
Record its commit, ABI, Android/WebView versions, page size, storage provider,
and device model with each result.

## Historical source evidence (WebView baseline)

- The shared frontend suite passes 156 tests in 24 files in 3.05 seconds,
  including 19 focused source-conversion/player regressions. Coverage includes playback recovery,
  Media3 state/command adaptation, browser HLS handoff, source-refresh pause
  isolation, suppression of late browser playback events, and older-WebView
  bridge bootstrap, `Object.hasOwn` and modern Array/Promise polyfills, and a
  structured-clone fallback for older WebViews, Android stream routing,
  playlist playback selection, and watch-party player identity.
- The September 29 parity batch adds transactional SAF destinations for debrid
  and built-in torrent downloads, web source conversion with retained advanced
  player state, background queue/encoder suspension, trusted D-pad Back
  regression coverage, and external-link handling. Focused Go checks and a host
  FFmpeg integration smoke pass. See the
  [parity batch evidence](acceptance/2026-09-29-parity-batch.md) for source
  commit, commands, scope, and remaining acceptance gates.
- The current ARM64 and x86_64 debug APKs build in 2 minutes 52 seconds and
  pass signature verification and native-library 16 KiB ELF/ZIP alignment.
  All 23 current Android instrumentation tests pass on the fresh ARM64 APK
  using the API 31 TV emulator with WebView 91 and 4 KiB pages, including startup,
  bridge/lifecycle, D-pad Back, SAF adapter, native playback and media tools.
  APK hashes and scope are in the parity batch report. Physical USB acceptance
  remains pending.
- All six Go packages touched by the parity batch pass their complete test
  suites on the final checkout, including streaming, handlers, debrid, built-in
  torrents, and mobile lifecycle. See the parity batch report for the command.

## Earlier acceptance evidence

These dated reports retain their original source revisions and APKs. The
22-test instrumentation result below is earlier API 31 emulator evidence.

- Earlier ARM64 and x86_64 debug APKs built and passed APK signature verification.
- All 22 Android instrumentation tests passed on the API 31 ARM64 TV emulator
  with WebView 91.0.4472.114 and 4 KiB pages. They cover server
  startup/restart, bridge access, external-player scheme dispatch and focus
  return, injected Android D-pad key events through the
  focused WebView, spatial focus controls, a test document
  provider, native-player lifecycle/commands, missing-source recovery, WebView
  route restoration, callback intent delivery, persisted decoder/track state,
  an opaque-ticket-only recovery bridge response, dismissed playback
  checkpoint cleanup, fresh-Activity discovery of a persisted recovery ticket,
  and the bundled media tools.
  The document-provider tests also release and restore a persisted permission
  and verify that the adapter denies access while the grant is revoked. See the
  [storage grant run](acceptance/2026-09-28-storage-grant.md).
  SAF write-journal recovery also covers interrupted writes, replacement
  rollback, committed replacement preservation, incomplete fallback cleanup,
  isolation of an unavailable root, and calls through the Go directory API to
  the registered SAF adapter. A targeted regression also forces a
  fallback copy to fail while the provider refuses deletion; the adapter keeps
  the journal and blocks root access until cleanup succeeds. All five SAF
  adapter instrumentation tests pass on the API 31 ARM64 TV emulator. See the
  [write recovery run](acceptance/2026-09-28-saf-write-recovery.md) and the
  [partial destination recovery run](acceptance/2026-09-28-saf-partial-destination-recovery.md).
  The media test encodes H.264 with the bundled
  CPU encoder, checks it with ffprobe, decodes it with FFmpeg, and renders/seeks
  it in Media3 while paused. The startup test opens and dismisses the native
  storage fallback dialog, returns from the native player Activity, and checks
  restored focus styling in both cases. Another test verifies Leanback launcher
  discovery, icon/banner metadata, and optional touchscreen declaration. Latest
  focus restoration details are recorded in the
  [focus restoration run](acceptance/2026-09-28-focus-restoration.md). The
  update installer path and system-consent handoff are covered in the
  [update installer run](acceptance/2026-09-28-update-installer.md). The
  external-player intent handoff and focus return are recorded in the
  [external-player run](acceptance/2026-09-28-external-player-handoff.md).
- Remote text input is covered on the emulator: injected letter keys reach a
  focused WebView field and D-pad movement does not steal its focus. The OAuth
  WebView now requests resize behavior for the TV keyboard; its window mode is
  checked by instrumentation. See the
  [TV keyboard run](acceptance/2026-09-28-tv-keyboard.md).
- Android TV now supplies `Object.hasOwn`, the ES2023 Array methods and
  `Promise.withResolvers` missing from WebView 91. The startup instrumentation
  probe checks own-property behavior, executes all six Array methods, and
  resolves a deferred Promise inside the real WebView. Shared
  settings and data-grid code also use a tested clone fallback when
  `structuredClone()` is absent. Viewport sizing uses `dvh` when available and
  a tested `vh` fallback on older WebViews. The earlier home run reached the
  catalogue with visible D-pad focus and no renderer exception; the latest
  build passed the real-WebView API and viewport assertions and
  focus-restoration checks. See the
  [WebView compatibility run](acceptance/2026-09-28-webview-91-compatibility.md),
  [home screenshot](acceptance/screenshots/2026-09-28-webview-91-home-after-fix.png),
  and [latest D-pad screenshot](acceptance/screenshots/2026-09-28-webview-91-dpad-object-hasown.png).
- The Go mobile lifecycle test now starts with the app already backgrounded
  and verifies periodic work remains stopped until foreground return. The
  server applies the same suspension policy to Auto Downloader, Auto Scanner,
  manga downloads, and active built-in torrent downloads when that background
  transition arrives during startup. See the
  [background-at-start run](acceptance/2026-09-28-background-startup.md).
- Manga downloads now cancel active image requests when the app backgrounds,
  persist completed page files for resume, and recover interrupted queue intent
  when the server starts again. The targeted Go packages pass, and the final
  ARM64/x86_64 Go binding builds; all 18 Android instrumentation tests pass on
  the API 31 ARM64 TV emulator. See the
  [manga background/resume run](acceptance/2026-09-28-background-manga-resume.md)
  for APK hashes and verification details.
- Torrent hashes Seanime pauses are now atomically stored in app data and
  reloaded by a fresh server instance. Foreground return retries the resume
  request and retains recovery state on failure. The mobile lifecycle test,
  ARM64/x86_64 binding build, and all 18 Android instrumentation tests pass;
  a configured live torrent service still needs a device run. See the
  [torrent resume run](acceptance/2026-09-28-torrent-resume-intent.md).
- A visible host-GPU emulator run at 1920 × 1080 verified remote navigation to
  the USB-folder control and restoration of its purple focus ring after native
  dialog dismissal. See the captured
  [focus restoration screenshot](acceptance/screenshots/2026-09-28-api31-arm64-dpad-focus-restored.png).
- The freshly installed ARM64 debug APK launched its embedded first-run setup;
  an injected D-pad Right moved the visible focus ring to the next player card.
  See the [visible launch check](acceptance/2026-09-28-visible-launch-smoke.md).
- The emulator image lacks a usable system folder-picker activity. The native
  fallback dialog appears, D-pad dismisses it, and focus returns to the USB
  folder control. Selecting a real folder still requires a TV system picker;
  see the [picker smoke check](acceptance/2026-09-28-saf-picker-visible-smoke.md).
- The first-run setup now opens with a visible D-pad focus target, describes
  the built-in Android TV player, and configures qBittorrent/Transmission as
  network services without desktop executable paths. See the
  [onboarding run](acceptance/2026-09-28-onboarding-tv.md).
- The Android TV manga reader now exposes focusable previous/next page
  controls beside the page selector; in double-page mode they move by spread.
  The Android TV frontend typecheck, production bundle, and API 31 ARM64
  instrumentation suite pass. See the
  [manga remote-navigation run](acceptance/2026-09-28-manga-tv-navigation.md);
  D-pad operation on a populated manga reader still needs device confirmation.
- Android TV settings no longer expose desktop process-launch controls for
  MPV/VLC/IINA/MPC-HC. Device playback and external app schemes remain under
  Video Playback and External Player Link; old `media-player` settings links
  route to the TV playback controls. The TV web build, shared frontend suite,
  Android package build, and instrumentation suite passed for this revision.
  See the [settings platform run](acceptance/2026-09-28-settings-platform.md).
- The four transcoding capability tests and seven playback checkpoint tests
  pass. Physical-device scenarios below still need recorded passing runs.
- Android storage, direct-stream, playlist, and Nakama Go package tests pass,
  including
  six new tests for seekable SAF readers, ranges/HEAD/thumbnail requests,
  matching sidecar subtitles, size limits, simulated revoked access and
  restoration, and ordinary filesystem reads. The synthetic adapter tests do
  not establish physical USB behavior.
- All four native libraries in each debug APK pass the 16 KiB ELF/ZIP check
  in `scripts/verify_android_native_alignment.py`; its seven regression tests
  pass. Runtime operation on a 16 KiB device still needs a separate test run.
- The API 31 ARM64 TV emulator uses a task-owned sparse data partition. See
  the [media pipeline run](acceptance/2026-09-26-api31-arm64.md),
  [playback routing run](acceptance/2026-09-26-playback-routing.md),
  [latest APK run](acceptance/2026-09-27-android-tv-build.md), and
  [playback recovery run](acceptance/2026-09-27-playback-recovery.md) for
  source revisions, APK hashes, environment, and test scope.

- The earlier playback lifecycle run confirms that explicit Back dismissal
  serializes checkpoint deletion after any in-flight write, stop/destroy cannot
  recreate the dismissed snapshot, and a fresh MainActivity discovers a ticket
  persisted by an earlier process. All 22 Android instrumentation tests
  passed on the ARM64 TV emulator; that run's APK hashes and build limitations are
  recorded in the [dismissal recovery run](acceptance/2026-09-28-playback-dismissal-recovery.md).
- An end-to-end Android force-stop and cold relaunch restored a URL-backed
  playback checkpoint on the API 31 ARM64 TV emulator. The native player
  fetched and decoded a seekable H.264 fixture, resumed beyond the seeded
  10-second position, and persisted a fresh checkpoint. The test completed
  first-run setup by D-pad and verified the main WebView, server, and player
  surfaces. See the [force-stop recovery run](acceptance/2026-09-28-force-stop-playback-recovery.md)
  and screenshots of the [active player](acceptance/screenshots/2026-09-28-api31-force-stop-player-active.png)
  and [restored player](acceptance/screenshots/2026-09-28-api31-force-stop-player-restored.png).

## Process-death playback restoration

The cold-process recovery path is implemented. Go stores an app-private source
descriptor for local, torrent, debrid, HTTP URL, and Nakama streams. Android
atomically stores the opaque ticket and native decoder state, including
position, play/pause, subtitle preferences, speed, volume, and track selection.
After process recreation, the host waits for the Go server and a new WebView
client, reopens the selected source through the existing playback modules,
checks that the reopened stream matches the checkpoint, rotates the ticket,
and hands the fresh loopback URL to Media3. The full source description and
Nakama credentials stay in Go's private data; the bridge carries only the
checkpoint ID.

Go tests cover checkpoint reload/replacement, source identity checks and ticket
rotation. The Android instrumentation suite covers durable snapshot roundtrip
and track-preference serialization. An end-to-end force-stop and cold launch
has now passed on the API 31 ARM64 emulator for an HTTP URL source using a
seekable H.264 fixture. This does not establish recovery for physical storage,
authenticated torrent/debrid providers, Nakama room reconnection, or playlist
continuity; those live flows still need device verification.

A host integration test reloads a persisted local library row after a Go server
restart, restores the local checkpoint under a new WebView client ID, and reads
fixture bytes through the reopened stream. Its companion test does the same for
HTTP URL and Nakama sources, checks that restoration makes a fresh remote range
request, and verifies that a restored Nakama stream forwards its saved host
credential. Both pass in `go test ./mobile -count=1`. These tests do not exercise
Android process death, a real USB device, or native media decoding. See the
[playback restart run](acceptance/2026-09-28-local-playback-server-restart.md).

## Feature scenarios

Use only D-pad, Select, Back, and media keys for every UI action. Check visible
focus, text readability, safe margins, dialog dismissal, and focus restoration
after returning from another screen/activity.

| Feature group | Required device evidence |
| --- | --- |
| Startup and lifecycle | Cold launch, ready/error UI, embedded assets, stop/restart, cleanup, process recreation, Home/return during idle and playback. |
| Library and discovery | Scan, browse, search, filters, lists, schedules, entry details, episode selection, metadata edits, scan summaries. |
| AniList and MAL | Keyboard input, sign-in, OAuth return, token persistence, list/progress updates, sign-out and reauthentication. |
| Manga | Provider search, chapter navigation and reader controls, bookmarks, download/resume, local folders and CBZ/ZIP, offline reader. |
| Offline anime | Downloaded entry playback and metadata while disconnected, reconnect, progress reconciliation. |
| Playlists and continuity | Next/previous, automatic next, global playlists, position restoration, completion exactly once, URL refresh while paused. |
| Extensions and plugins | Installation/update, repository management, configuration, playground, plugin actions and player event/control integration. |
| Streaming | Local, online, torrent and debrid sources; unsupported WebView codec conversion and Media3 handoff; seek, pause/resume, source switching and recovery. |
| Native player | Audio/subtitle selection, external SRT/VTT/ASS/SSA, style preferences, speed/volume/mute, screenshot destination, remote media keys, Home/return, failed-source retry and return to web playback. |
| Advanced web player | Libass, subtitle translation, Anime4K, chapters/skip controls, screenshots, insight and plugin player actions using the WebView player. |
| Transcoding | Packaged ffmpeg/ffprobe launch, metadata extraction, CPU fallback, supported hardware paths, seek, shutdown and staging-file cleanup. |
| Downloads and torrents | Destination selection, queue order, pause/resume/cancel, automatic downloading, torrent completion copy to SAF, external torrent-client screens. |
| Nakama | Connection, room/participant controls, playback status and synchronized play/pause/seek, reconnect and leave. |
| Settings, logs and reports | Reach every section, edit/save/reload, storage roots, log viewers, export diagnostic profiles/report archives through the document picker. |
| Android integrations | External player launch and return, folder grants, OAuth/browser return, signed update download/install and same-key upgrade. |

## Storage and background scenarios

- Select internal-provider and USB trees, restart the app, and confirm persisted
  grants, listing, reads, writes, overwrite, directory creation and deletion.
- Unplug/replug USB and revoke/regrant access during scans, playback and writes.
  Existing library records must survive temporary storage loss.
- Exercise low storage space, failed writes and provider errors. Check partial
  files and app-cache staging cleanup after failure, cancellation and restart.
- Leave the foreground during idle and active download work. Confirm that
  non-playback work pauses, queues persist, and foreground return resumes work.
- Return immediately while canceled debrid/torrent workers or source encoders
  are still stopping. Confirm one resumed worker per destination/session and
  preservation of destination, queue metadata, playback position, and pause state.
- Check the app while the native player, OAuth activity or document picker is
  foreground, including transitions back to the main WebView.

## Device matrix

Run the suite on ARM64 and x86_64 targets and representative 1080p and 4K TVs.
Include the minimum supported Android API 23, a current Android TV
image, a 16 KiB target, hardware decoding/transcoding capabilities, and at least
one real USB document provider. The in-memory test provider covers adapter
contracts; real USB behavior requires the physical storage scenarios above.

Recorded passing runs are still required for physical USB unplug/replug and
revoked grants, representative 1080p/4K hardware, API 23 and a current Android TV
API, 16 KiB runtime operation, and x86_64 runtime operation. Live online,
torrent and debrid providers, AniList/MAL OAuth, and Nakama synchronization and
reconnection also remain device acceptance gates.

## Commands

From the repository root:

```sh
cd seanime-web && npm test
```

From `androidtv`:

```sh
./gradlew :app:assembleDebug :app:compileDebugAndroidTestKotlin
./gradlew :app:connectedDebugAndroidTest
python3 ../scripts/verify_android_native_alignment.py app/build/outputs/apk/debug/*.apk
```

Release installation requires APKs signed with the persistent release key.
Debug builds exercise the local development install path.
