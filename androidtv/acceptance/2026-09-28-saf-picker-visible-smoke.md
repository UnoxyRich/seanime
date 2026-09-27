# Android TV SAF picker fallback smoke check

- Source revision: `899e7f36` (`Isolate SAF recovery per storage root`).
- Device: `sdk_google_atv64_arm64` (`emulator64_arm64`), API 31 / Android 12,
  ARM64, WebView 91.0.4472.114, 1920 × 1080, 4096-byte pages.
- Installed and launched the current ARM64 debug APK. D-pad Down moved focus
  from the first setup card to “Choose library folder on USB”. D-pad Center
  opened the app's native fallback dialog because this emulator image does not
  provide a usable system `ACTION_OPEN_DOCUMENT_TREE` activity.
- D-pad Center dismissed the fallback dialog. The purple focus ring returned
  to “Choose library folder on USB”. Captures:
  [picker unavailable](screenshots/2026-09-28-api31-arm64-folder-picker-unavailable.png)
  and [focus restored](screenshots/2026-09-28-api31-arm64-picker-fallback-focus-restored.png).
- This verifies focus movement, fallback messaging, and focus restoration on
  the emulator. Folder selection and persisted-grant return cannot be exercised
  on this image; those still require a TV image or device with a working system
  document picker. Adapter contract coverage uses the app-owned synthetic
  DocumentsProvider and does not replace that device check.
