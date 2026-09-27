# Android TV SAF write recovery

- Revision: `34abc080` (`Exercise SAF through the Go API`). The journal
  implementation is in `899e7f36` and began in `6c5f93f4`; later commits added
  cancellation, failure, invalid-journal, cross-root, and Go API coverage.
- Device: `sdk_google_atv64_arm64` (`emulator64_arm64`), API 31 Android TV
  ARM64, Android 12, WebView 91.0.4472.114, 4096-byte pages.
- Storage provider: app-owned synthetic DocumentsProvider
  `app.seanime.tv.test.documents`; physical USB storage was not attached.
- Command: `ANDROID_HOME=/Users/unoxyrich/Library/Android/sdk
  ANDROID_NDK_HOME=/Users/unoxyrich/Library/Android/sdk/ndk/27.2.12479018
  ./gradlew :app:connectedDebugAndroidTest --no-daemon` from `androidtv`.
- Result: **17/17 instrumentation tests passed**. The task built the Android TV
  frontend and debug package, compiled Android instrumentation code, then
  installed and ran the suite on the connected emulator.
- Both ABI debug APKs pass APK signature verification and the repository's
  16 KiB ELF/ZIP alignment checker. SHA-256:
  - ARM64: `355f6350d7cfb336a3c35a9602029a660733d3822ccc0736dd2c9cbd4fb2660c`
  - x86_64: `f9b23c133b6bb50380e165482670a83efb8db248311dd0ea3f4ab308d1de7596`
- The storage test seeds the synthetic DocumentsProvider and persisted journal,
  then constructs a fresh adapter to cover abandoned `.part` cleanup, restoring
  an old destination when replacement stopped halfway, keeping a replacement
  that had already committed, and removing a fallback copy marked incomplete.
  It verifies the old and committed bytes and that handled transactions and
  temporary documents are gone.
- It also verifies that cancellation deletes the temporary document, a failed
  attempt to write over a directory preserves that directory and cleans up its
  temp file, and an invalid transaction stays in the journal while storage
  access fails closed. Once the invalid entry is cleared, the same adapter can
  retry successfully.
- A second selected root is configured without a persisted grant. Its journal
  remains unresolved and access to that root fails, while another selected
  root can still be listed. This exercises per-root isolation with the test
  provider; it does not simulate unplugging physical USB hardware.
- The integration test launches `MainActivity` and its actual Go server, then
  posts the persisted virtual root and a nested directory to
  `/api/v1/directory-selector`. Both requests return HTTP 200 with the expected
  SAF folder names, exercising the registered Kotlin adapter through Go rather
  than calling the adapter directly.
- Journal updates are synchronously persisted before replacement steps. If the
  provider, grant, or journal cannot be recovered, the adapter keeps the
  transaction and refuses subsequent storage operations until recovery can
  complete. The synthetic test covers transaction recovery; it does not simulate
  an OS process kill or establish behavior with a physical USB provider.
- Physical USB unplug/replug, low-space and vendor-provider failures, API 23,
  x86_64 runtime, and actual 1080p/4K TV acceptance remain outstanding.
