# Android TV acceptance

The target remains the full Seanime feature set in the Android TV host. Device
acceptance requires running the scenarios below on the APK being evaluated.
Record its commit, ABI, Android/WebView versions, page size, storage provider,
and device model with each result.

## Current evidence

- The shared frontend suite passes 73 tests, including playback recovery,
  Media3 state/command adaptation, browser HLS handoff, source-refresh pause
  isolation, and suppression of late browser playback events.
- ARM64 and x86_64 debug APKs build; their debug signatures verify.
- Android instrumentation sources compile. They cover server startup/restart,
  bridge access, sample D-pad widgets, a test document provider, native player
  lifecycle/commands, missing-source recovery, WebView route restoration,
  callback intent delivery, and execution of the bundled media tools.
- The latest instrumentation suite and physical-device scenarios below have
  no recorded passing run for the current implementation.
- All four native libraries in each debug APK pass the 16 KiB ELF/ZIP check
  in `scripts/verify_android_native_alignment.py`; its seven regression tests
  pass. Runtime operation on a 16 KiB device still needs a separate test run.
- A checksum-verified API 31 ARM64 TV image is installed locally using sparse
  files. The emulator currently refuses to boot because the host fails its
  free-disk-space check. This installation provides no runtime test evidence.

## Remaining process-restoration work

Main and OAuth activity recreation now restores their page/history, and native
player recreation restores its media and decoder settings in the existing
process. These are distinct from reconstruction after Android kills the entire
app process. A restored native player can hold a loopback stream URL whose Go
server and in-memory stream session no longer exist. Restarting the backend and
recreating the source session, then reconnecting playlist/progress handling,
still need implementation and a real process-death test. The saved URL alone
does not establish recovery for torrent, debrid or transcode sessions.

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
