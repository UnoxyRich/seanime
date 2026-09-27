# Android TV acceptance

The target remains the full Seanime feature set in the Android TV host. Device
acceptance requires running the scenarios below on the APK being evaluated.
Record its commit, ABI, Android/WebView versions, page size, storage provider,
and device model with each result.

## Current evidence

- The shared frontend suite passes 96 tests in 17 files, including playback recovery,
  Media3 state/command adaptation, browser HLS handoff, source-refresh pause
  isolation, suppression of late browser playback events, and older-WebView
  bridge bootstrap, `Object.hasOwn` and modern Array/Promise polyfills, and a
  structured-clone fallback for older WebViews, Android stream routing,
  playlist playback selection, and watch-party player identity.
- ARM64 and x86_64 debug APKs build and pass APK signature verification.
- All 17 Android instrumentation tests pass on the API 31 ARM64 TV emulator
  with WebView 91.0.4472.114 and 4 KiB pages. They cover server
  startup/restart, bridge access, injected Android D-pad key events through the
  focused WebView, spatial focus controls, a test document
  provider, native-player lifecycle/commands, missing-source recovery, WebView
  route restoration, callback intent delivery, persisted decoder/track state,
  an opaque-ticket-only recovery bridge response, and the bundled media tools.
  The document-provider tests also release and restore a persisted permission
  and verify that the adapter denies access while the grant is revoked. See the
  [storage grant run](acceptance/2026-09-28-storage-grant.md).
  SAF write-journal recovery also covers interrupted writes, replacement
  rollback, committed replacement preservation, incomplete fallback cleanup,
  isolation of an unavailable root, and calls through the Go directory API to
  the registered SAF adapter. See the
  [write recovery run](acceptance/2026-09-28-saf-write-recovery.md).
  The media test encodes H.264 with the bundled
  CPU encoder, checks it with ffprobe, decodes it with FFmpeg, and renders/seeks
  it in Media3 while paused. The startup test opens and dismisses the native
  storage fallback dialog, returns from the native player Activity, and checks
  restored focus styling in both cases. Another test verifies Leanback launcher
  discovery, icon/banner metadata, and optional touchscreen declaration. Latest
  run details are recorded in
  [focus restoration run](acceptance/2026-09-28-focus-restoration.md).
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
and track-preference serialization. A force-stop while a real source is
playing, followed by successful cold-launch restoration, has **not** been
exercised end to end. Authenticated torrent/debrid providers, revoked USB
grants, Nakama room reconnection, and playlist continuity also need device
verification; passing snapshot and source-identity tests alone does not prove
those live flows.

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
| Streaming | Local, online, torrent and debrid sources; unsupported WebView codec handoff to Media3; seek, pause/resume, source switching and recovery. |
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
- Check the app while the native player, OAuth activity or document picker is
  foreground, including transitions back to the main WebView.

## Device matrix

Run the suite on ARM64 and x86_64 targets and representative 1080p and 4K TVs.
Include the minimum supported Android API 23, a current Android TV
image, a 16 KiB target, hardware decoding/transcoding capabilities, and at least
one real USB document provider. The in-memory test provider covers adapter
contracts; real USB behavior requires the physical storage scenarios above.

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
