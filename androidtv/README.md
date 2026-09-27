# Seanime TV

Android TV client shell for Seanime. It packages the shared React client in an
Android WebView and starts the existing Go server inside the app process.
The application label and package ID are provisional (`Seanime TV`,
`app.seanime.tv`). The repository's GPL-3.0 license and upstream attribution
remain in place.

See [the acceptance matrix](ACCEPTANCE.md) for the full feature scope and
remaining device validation.

## Build

Install Go at the version required by the repository, Node.js, JDK 17, Android
SDK platform 36, and Android NDK `27.2.12479018`. The first build also needs
`curl`, `gpg`, `git`, `make`, `nasm`, `pkg-config`, and a C toolchain. Gradle verifies and builds the
FFmpeg source release and pinned x264 source for both supported ABIs. It finds
the NDK in the Android SDK automatically. Run from this directory:

```sh
./gradlew :app:assembleDebug
```

The Gradle task builds the `androidtv` frontend, copies it under `mobile/web`
for Go embedding, creates a gomobile AAR for ARM64 and x86_64, and packages
ABI-split APKs for ARM64 and x86_64. Each APK includes only the media binaries
for its matching ABI. The debug APKs are signed with Gradle's debug key.
FFmpeg and the Go bridge are linked for 16 KiB pages. Check every native library
and any uncompressed native ZIP entries in the built APKs from the repo root:

```sh
python3 scripts/verify_android_native_alignment.py androidtv/app/build/outputs/apk/debug/*.apk
```

The CI checks and release workflow run this verifier. Alignment is a build
requirement; playback and storage still need runtime testing on both page sizes.
Publishing an Android TV update requires a persistent release keystore. Configure
the GitHub Actions secrets `SEANIME_ANDROID_KEYSTORE_BASE64`,
`SEANIME_ANDROID_KEYSTORE_PASSWORD`, `SEANIME_ANDROID_KEY_ALIAS`, and
`SEANIME_ANDROID_KEY_PASSWORD` in the fork, then publish a stable GitHub release.
The Android TV workflow builds signed APKs and attaches the ARM64 and x86_64
variants to that release. Keep using the same keystore for every update so
Android accepts each APK as an in-place upgrade.

Run the Android host smoke test on an Android TV emulator or device with:

```sh
./gradlew :app:connectedDebugAndroidTest
```

The instrumentation suite checks embedded UI loading, top-level bridge
authorization, sandboxed iframe isolation, D-pad movement through controls,
menus, sliders, dialogs, and server shutdown/restart. It also exercises
selected SAF tree grants, directory creation, ranged reads, chunked writes and
replacement, listing, and deletion through a debug-only in-memory document
provider. The `Android TV
checks` workflow runs the suite on an API 36 x86_64 TV emulator.
The native-player lifecycle test uses local WAV fixtures to check stop/resume
and activity recreation, including playlist handoffs, pause state, position,
speed, volume, and track preferences.
It also checks source-bound native play/pause/seek commands and the initial
speed, volume, and mute settings passed by the web client.
Missing-source tests exercise D-pad retry and return controls, including a
source becoming available after an error while playback is paused.
The media-tools test launches both packaged executables through the same
command paths used by the Go server. It encodes raw video with libx264, probes
the output, decodes it with FFmpeg, and checks Media3 frame rendering and seeking.

## Android host behavior

- The app uses a Leanback TV launcher activity and a 320×180 TV banner.
- The local server binds only to `127.0.0.1:43211`; app data and cache are kept
  in separate Android-managed directories.
- Startup polling begins after the Go start request is registered, avoiding
  an incorrect stopped-server error while the worker thread is starting.
- Recreated main and OAuth activities restore their WebView page and history.
  Main-page restoration waits for server readiness. OAuth callbacks can reopen
  the main activity, use its canonical local origin, and are consumed from the
  launch intent once so recreation does not replay that intent.
- The signed FFmpeg 8.1.3 source release is built with the pinned GPL x264
  revision for ARM64 and x86_64. Android extracts the matching executables into
  its installed native-library directory. The app creates `ffmpeg`/`ffprobe`
  symlinks under `files/seanime/bin`, ahead of the inherited process path, so
  Go's existing transcoder uses the installed binaries without executing code
  copied into writable app data. Links are refreshed after app updates.
- Hardware encoder detection supplies a raw YUV420 frame through stdin, so it
  works with the packaged FFmpeg build that omits libavdevice. The bundled
  FFmpeg and x264 CPU detectors require base SVE support before selecting SVE2
  instructions; this handles Android kernels that report inconsistent flags
  while retaining SVE2 acceleration when both capabilities are available.
- OAuth and external web destinations stay inside an Android WebView. OAuth
  redirects to Seanime's local callback are returned to the app's main WebView.
  Both the main and OAuth activities request window resizing for the Android TV
  on-screen keyboard.
- Before React mounts, the Android TV web build supplies missing `Object.hasOwn`,
  modern Array methods, and `Promise.withResolvers` for older System WebView
  releases.
- The JavaScript interface uses a token delivered only to the local main frame.
  WebViews with document-start scripts receive it before the page runs; older
  WebViews use an origin-scoped message handshake before React mounts. Embedded
  remote pages cannot obtain native storage or playback access. The WebView must
  support document-start scripts or `WEB_MESSAGE_LISTENER`.
- Browser-generated diagnostic profiles and issue-report archives use the
  Android document picker, then stream to the selected destination in bounded
  chunks instead of relying on WebView's unsupported Blob download behavior.
- The TV build reports its client identity as `androidtv` and keeps the shared
  Seanime routes and web playback UI.
- Viewport-relative layouts use `dvh` when the WebView supports it and fall
  back to `vh` on older Android System WebView releases. The startup
  instrumentation checks the generated `calc()` utility against that fallback.
- The shared player can hand a stream to an optional Media3 player. It supports
  HLS, remote seek/play/pause controls, embedded and external SRT/VTT/ASS/SSA
  subtitles, audio track selection, playback-position handoff, and web-driven
  playlist transitions. In the WebView player, D-pad input reveals and focuses
  the controls, keeps navigation inside the open dialog, and lets menus and
  sliders handle their arrow keys. Left and right on the playback timeline use
  the configured fine-seek interval. Inline and mini-player playback leave
  focus with the page.
- Native playback releases its decoder while the activity is stopped and
  recreates it on return with the latest episode and playback settings. The
  retained WebView resumes receiving progress and playlist events on return.
- Native direct-stream playback checkpoints the underlying source in private
  Go app data and saves an opaque checkpoint ID in the activity state. Local,
  torrent, debrid, URL and Nakama source selections can be reopened through the
  Go binding for a new WebView client. The cold-process restoration path is
  implemented, but a force-stop during real playback followed by a successful
  restored session has not yet been verified end to end; see the acceptance
  matrix.
- Native playback errors show focused remote controls to retry the source or
  return to the web player. Retrying retains the position, speed, volume and
  pause state; failures do not advance the playlist.
- Media3 reports duration, position, buffering, pause state, speed, volume,
  and completion to the shared player. Its per-element adapter supplies these
  values to watch continuity, progress updates, playlists, and player events,
  including files whose metadata WebView cannot decode. Shared play/pause/seek
  and audio/speed controls are forwarded to the matching native stream. The
  browser HLS loader pauses during native playback and resumes without
  autoplay when returning to the web player.
- Source refresh pauses only the browser decoder, preserving Media3's playing
  or paused state. Late browser pause/completion events are suppressed during
  native playback so they cannot alter native progress or advance playlists.
- The native host exposes a Storage Access Framework picker, persists grants,
  and implements listing, metadata, ranged reads, chunked writes, directory
  creation, and deletion through a gomobile adapter. Library settings can use
  selected SAF roots, and the Go scanner and directory selector traverse them.
  SAF media uses direct range streaming to the Media3 player by default. When
  transcoding is enabled, the server stages the selected media in app cache for
  FFmpeg and removes it when the transcode stream shuts down. Direct-play media
  metadata and embedded subtitles/fonts are inspected through a temporary,
  loopback-only range source, without staging the complete video. Scans retain
  existing library rows when a selected SAF tree is unplugged or its grant is
  revoked, then rescan them after access returns. The manga local provider can
  scan SAF roots and stage CBZ/ZIP archives in the app cache when required.
  The shared direct-stream player also reads SAF documents through seekable
  ranges, including HEAD/thumbnail requests and matching sidecar subtitles.
  Subtitle reads are capped at 20 MiB. Storage and direct-stream package tests
  cover these paths and simulated access loss/recovery; physical USB testing
  remains necessary.
  Torrent-stream
  active torrent pieces remain in app-local storage for random-access
  streaming; after the selected file completes, Seanime copies it to the
  configured SAF folder. This completion copy has not yet been verified on a
  physical Android TV device. Some TV firmware images only provide placeholder
  document-picker activities; Seanime detects those and explains that a file
  manager with a working document provider is needed for SAF storage features.
- Android ffmpeg/ffprobe are bundled with MediaCodec support and libx264 CPU
  encoding fallback. Hardware transcoding capability and performance still
  need validation on representative Android TV hardware.
- The native player can capture and save the current video frame to a selected
  SAF folder. On Android 8 and later it captures the composed player window so
  Media3-rendered subtitles are included while player controls are hidden.
  Web-rendered libass/Anime4K overlays are not part of native playback
  screenshots.
- Media3 receives the saved subtitle and caption appearance settings for text
  size, color, background, outline or shadow, and font family where available.
  Style-only updates do not reload the current stream. Advanced libass
  rendering and Anime4K processing still remain browser-player features.
- Android TV checks the fork's GitHub releases, chooses the APK matching the
  device ABI, downloads it with Android Download Manager, and opens the Android
  package installer. The release flow remains unavailable until a signed APK
  release is published with the same persistent keystore.

The Android native player, SAF adapter, background lifecycle, transcode staging,
and update installer still need end-to-end validation on physical Android TV
devices, including 1080p and 4K hardware.
