# Android TV external-player handoff

- Source commit: `47e28716665f6cb71ffec598b58aa709789d8618`
- Target: `sdk_google_atv64_arm64`, Android 12 / API 31, ARM64, 1920 × 1080,
  4 KiB pages
- WebView: `com.google.android.webview` 91.0.4472.114
- Tested APK: `app-arm64-v8a-debug.apk`
- SHA-256: `aee0e4e65a2ad6dec5bc1e7b1d10de4e5412a698b5eac0e2e7b461f8fce1089b`
- Packaged x86_64 APK SHA-256:
  `175d2feeb9ca37907217bf78bcb400427d49ff3a883ed620f177f55e4635656a`

## Scenario

The test launches the real Seanime `MainActivity`, opens a configured custom
player scheme through the Android host, and verifies that Android delivers the
exact `ACTION_VIEW` URI to a debug-only test activity. That activity finishes
immediately; the test verifies Seanime regains window focus and stops its Go
server during teardown.

## Verification

- The targeted `AndroidExternalPlayerTest` passed.
- The full Android TV instrumentation suite passed all 22 tests with zero
  skips and zero failures.
- Both ABI APKs passed signature verification and the 16 KiB native alignment
  check.

The debug-only test activity does not ship in release APKs. This test confirms
Android intent dispatch and return focus; it does not verify a particular
installed player such as VLC or MPV.
