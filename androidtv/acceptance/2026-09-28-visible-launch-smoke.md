# Android TV visible launch and D-pad smoke check

- Source revision: `40be51a3` (`Cover cancelled and failed SAF writes`).
- Device: `sdk_google_atv64_arm64` (`emulator64_arm64`), API 31 / Android 12,
  ARM64, WebView 91.0.4472.114, 1920 × 1080, 4096-byte pages.
- Installed the generated ARM64 debug APK with `adb install -r` and launched
  `app.seanime.tv/.MainActivity` successfully. The foreground activity was
  `MainActivity`; the app process was alive after launch.
- The embedded first-run setup rendered on the emulator. Injecting
  `KEYCODE_DPAD_RIGHT` moved the visible focus ring from “Local Anime Library”
  to “Media Player”. The captured screen is
  [1920 × 1080 screenshot](screenshots/2026-09-28-api31-arm64-fresh-install-dpad-focus.png).
- This confirms install, embedded UI load, and one visible D-pad focus move. It
  does not complete onboarding or validate library scanning, remote player
  setup, USB access, or physical-TV behavior.
