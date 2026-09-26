# Seanime TV

Android TV client shell for Seanime. It packages the shared React client in an
Android WebView and starts the existing Go server inside the app process.
The application label and package ID are provisional (`Seanime TV`,
`app.seanime.tv`). The repository's GPL-3.0 license and upstream attribution
remain in place.

## Build

Install Go at the version required by the repository, Node.js, JDK 17, Android
SDK platform 35, and an Android NDK. Gradle runs gomobile from the version
already pinned by the root Go module. Run from this directory:

```sh
./gradlew :app:assembleDebug
```

The Gradle task builds the `androidtv` frontend, copies it under `mobile/web`
for Go embedding, creates a gomobile AAR for ARMv7, ARM64, and x86_64, and
packages ABI-split APKs for ARMv7, ARM64, and x86_64. The debug APKs are
signed with Gradle's debug key.
Release signing is not configured; provide a project signing configuration
before distributing a release build.

## Android host behavior

- The app uses a Leanback TV launcher activity and a 320×180 TV banner.
- The local server binds only to `127.0.0.1:43211`; app data and cache are kept
  in separate Android-managed directories.
- OAuth and external web destinations stay inside an Android WebView. OAuth
  redirects to Seanime's local callback are returned to the app's main WebView.
- The TV build reports its client identity as `androidtv` and keeps the shared
  Seanime routes and web playback UI.
- The shared player can hand a stream to an optional Media3 player. It supports
  HLS, remote seek/play/pause controls, embedded and external SRT/VTT/ASS/SSA
  subtitles, audio track selection, playback-position handoff, and web-driven
  playlist transitions.
- The native host exposes a Storage Access Framework picker, persists grants,
  and implements listing, metadata, ranged reads, chunked writes, directory
  creation, and deletion through a gomobile adapter. Library settings can use
  selected SAF roots, and the Go scanner and directory selector traverse them.
  SAF media uses direct range streaming to the Media3 player. The server skips
  FFprobe and attachment extraction for those paths, and selects direct play
  until Android transcoding is available. Scans retain existing library rows
  when a selected SAF tree is unplugged or its grant is revoked, then rescan
  them after access returns. Torrent storage and manga local sources still
  need SAF integration.
- Android ffmpeg/ffprobe executables are not bundled yet. Transcoding still
  needs an Android-compatible toolchain and device capability configuration.
- The APK installation bridge can open an APK staged in Seanime's app cache;
  an Android APK release feed and settings flow are not wired yet. Screenshot
  export and advanced subtitle rendering also need Android-specific integration.

This is an implementation foundation, not a feature-parity claim. Validate the
remaining SAF, transcoding, background-download, and TV input behavior on
physical Android TV devices before treating this fork as a complete TV app.
