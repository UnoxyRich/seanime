# Android TV manga remote navigation — 2026-09-28

Source commit: `c6ae46c4` (`codex/android-tv`).

## Changes and checks

- Added Android TV focusable previous/next page controls next to the manga page
  selector. In double-page mode they navigate between spreads; single-page and
  long-strip modes move one page at a time. The buttons expose accessible names
  and disable at the ends of the chapter.
- Added three helper tests for all reader modes, double-page spreads, and page
  boundaries.
- `npm run typecheck`: passed.
- `npm test`: passed, 96 tests in 17 files.
- `npm run build:androidtv`: passed. Rsbuild reports the existing ambiguous
  Tailwind `ease-[cubic-bezier(0.25,1,0.5,1)]` warning.
- `:app:connectedDebugAndroidTest`: passed, 13/13 tests on Android TV API 31,
  ARM64, Android 12, WebView 91.0.4472.114, 4 KiB pages. The suite covers the
  embedded app startup and host integrations; it does not open a populated
  manga chapter.
- ARM64 and x86_64 debug APKs pass `apksigner`, `zipalign -c -P 16`, and the
  repository's native-library alignment verifier (four native libraries per
  APK).

## APK SHA-256

```text
app-arm64-v8a-debug.apk  b345ffaa628457c1f347afd35d03f625224551107931b71d7f33b952b1d6d5b6
app-x86_64-debug.apk    8d4c834b535109b35bef161040be03931e6d08d3286be567eed48afaf426bd61
```

Remote operation of the page buttons on a populated chapter and behavior with
real manga providers remain to be checked on a device.
