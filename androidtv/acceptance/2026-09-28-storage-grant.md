# Android TV storage grant recovery — 2026-09-28

Source revision: `de6e33bc07eb6a9af670f6ebaac5c59597a3117b` on
`codex/android-tv`.

## Results

- **13/13 Android instrumentation tests passed** with
  `./gradlew :app:connectedDebugAndroidTest`.
- Device: API 31 ARM64 Android TV emulator (`sdk_google_atv64_arm64`), WebView
  91.0.4472.114, 4 KiB pages.
- The SAF instrumentation now releases the test provider's persisted
  read/write permission, confirms that the Android adapter throws
  `SecurityException`, restores the grant, and confirms access works again.

This checks the persisted-grant gate against a test DocumentsProvider. It does
not cover physical USB removal, reattachment, or vendor DocumentsProvider
behavior.
