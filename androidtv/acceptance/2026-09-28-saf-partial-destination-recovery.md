# SAF partial destination recovery

- Source commit: `4cc99756deaee04a8439b13547c5d6ac2408b279`
- Target: Android TV emulator `sdk_google_atv64_arm64`, Android 12 / API 31,
  ARM64, 1920 × 1080, 4 KiB pages
- WebView: `com.google.android.webview` 91.0.4472.114
- Tested APK: `app-arm64-v8a-debug.apk`
- SHA-256: `10e4af0bd2e389f2bd495c26bba4efc6491c9b283c07d5afd0c6f6e272662849`

## Scenario

The test document provider rejects rename, fails writes to the final filename,
and initially refuses deletion. The adapter reports the copy error, preserves
the write journal, and blocks reads of that storage root while the partial
destination remains. After deletion is allowed, the next root listing retries
recovery, removes the partial document, and clears the journal.

## Verification

- `:app:assembleDebug :app:compileDebugAndroidTestKotlin` passed.
- The targeted `AndroidSafStorageAdapterTest` run passed all five tests.
- The complete `:app:connectedDebugAndroidTest` run passed all 21 tests with
  zero skips and zero failures.

This exercises the adapter contract through an in-memory Android document
provider. It does not establish cleanup behavior for a physical USB provider.
