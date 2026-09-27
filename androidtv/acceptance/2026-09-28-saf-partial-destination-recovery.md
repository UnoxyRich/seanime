# SAF partial destination recovery

- Source commit: `4cc99756deaee04a8439b13547c5d6ac2408b279`
- Target: Android TV emulator `sdk_google_atv64_arm64`, Android 12 / API 31,
  ARM64, 1920 × 1080, 4 KiB pages
- WebView: `com.google.android.webview` 91.0.4472.114
- Tested APK: `app-arm64-v8a-debug.apk`
- SHA-256: `2381b8c8b6ef2cad7c2eb4f2d31e5f3921a61371c0e29635aae293c00493a7f8`
- Packaged x86_64 APK SHA-256:
  `4cefaf09a164a2271564f97541fbcab5ee9cb740cbfb40be6686ff02721b9137`

## Scenario

The test document provider rejects rename, fails writes to the final filename,
and initially refuses deletion. The adapter reports the copy error, preserves
the write journal, and blocks reads of that storage root while the partial
destination remains. After deletion is allowed, the next root listing retries
recovery, removes the partial document, and clears the journal.

## Verification

- A clean `:app:assembleDebug :app:compileDebugAndroidTestKotlin` passed with
  frontend typecheck/build and Go binding generation enabled.
- The targeted `AndroidSafStorageAdapterTest` run passed all five tests.
- The complete `:app:connectedDebugAndroidTest` run on the rebuilt ARM64 APK
  passed all 21 tests with zero skips and zero failures.
- `go test ./mobile -count=1` passed.
- Both ABI APKs passed `scripts/verify_android_native_alignment.py`; all four
  native libraries in each APK meet the 16 KiB ELF/ZIP alignment check.
- x86_64 runtime coverage remains open. SDK Manager could not prepare an
  Android 12 x86_64 system image because the host ran out of disk space. This
  still failed after cleaning Gradle intermediates to make 3.4 GiB available;
  SDK Manager removed the incomplete package. The x86_64 APK was built and
  statically checked, but was not run on an x86_64 device.

This exercises the adapter contract through an in-memory Android document
provider. It does not establish cleanup behavior for a physical USB provider.
