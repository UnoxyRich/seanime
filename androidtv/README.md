# Seanime TV

Android TV client shell for Seanime. It packages the shared React client in an
Android WebView and starts the existing Go server inside the app process.
The application label and package ID are provisional (`Seanime TV`,
`app.seanime.tv`). The repository's GPL-3.0 license and upstream attribution
remain in place.

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
authorization, sandboxed iframe isolation, D-pad directional focus movement,
and server shutdown/restart. It also exercises selected SAF tree grants,
directory creation, ranged reads, chunked writes and replacement, listing, and
deletion through a debug-only in-memory document provider. The `Android TV
checks` workflow runs the suite on an API 36 x86_64 TV emulator.

## Android host behavior

- The app uses a Leanback TV launcher activity and a 320×180 TV banner.
- The local server binds only to `127.0.0.1:43211`; app data and cache are kept
  in separate Android-managed directories.
- The signed FFmpeg 8.1.3 source release is built with the pinned GPL x264
  revision for ARM64 and x86_64. Android extracts the matching executable tools
  into app-managed `files/seanime/bin`, ahead of the inherited process path, so
  Go's existing transcoder can use them without changing its API.
- OAuth and external web destinations stay inside an Android WebView. OAuth
  redirects to Seanime's local callback are returned to the app's main WebView.
- The JavaScript interface uses an origin-scoped document-start token, so
  embedded remote pages can load without receiving native storage or playback
  access. Devices need an Android System WebView with document-start script
  support for native bridge features.
- Browser-generated diagnostic profiles and issue-report archives use the
  Android document picker, then stream to the selected destination in bounded
  chunks instead of relying on WebView's unsupported Blob download behavior.
- The TV build reports its client identity as `androidtv` and keeps the shared
  Seanime routes and web playback UI.
- The shared player can hand a stream to an optional Media3 player. It supports
  HLS, remote seek/play/pause controls, embedded and external SRT/VTT/ASS/SSA
  subtitles, audio track selection, playback-position handoff, and web-driven
  playlist transitions. In the WebView player, D-pad input reveals and focuses
  the controls; arrow keys navigate focused controls instead of triggering
  desktop seek and volume shortcuts. Inline and mini-player playback leave
  focus with the page.
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
