# Android TV SAF write recovery

- Revision: `40be51a3` (`Cover cancelled and failed SAF writes`). The recovery
  implementation is in parent commit `6c5f93f4`.
- Device: `sdk_google_atv64_arm64` (`emulator64_arm64`), API 31 Android TV
  ARM64, Android 12, WebView 91.0.4472.114, 4096-byte pages.
- Storage provider: app-owned synthetic DocumentsProvider
  `app.seanime.tv.test.documents`; physical USB storage was not attached.
- Command: `ANDROID_HOME=/Users/unoxyrich/Library/Android/sdk
  ANDROID_NDK_HOME=/Users/unoxyrich/Library/Android/sdk/ndk/27.2.12479018
  ./gradlew :app:connectedDebugAndroidTest --no-daemon` from `androidtv`.
- Result: **16/16 instrumentation tests passed**. The task built the Android TV
  frontend and debug package, compiled Android instrumentation code, then
  installed and ran the suite on the connected emulator.
- Both ABI debug APKs pass APK signature verification and the repository's
  16 KiB ELF/ZIP alignment checker. SHA-256:
  - ARM64: `550d97ba0c1268f9fc422648a20f1da8cb64ae157588dfa69b2eadc9282c837c`
  - x86_64: `41f8a77437132883312c13d6b0a24a49e4a0a88a1a6545989531b93b58abafba`
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
- Journal updates are synchronously persisted before replacement steps. If the
  provider, grant, or journal cannot be recovered, the adapter keeps the
  transaction and refuses subsequent storage operations until recovery can
  complete. The synthetic test covers transaction recovery; it does not simulate
  an OS process kill or establish behavior with a physical USB provider.
- Physical USB unplug/replug, low-space and vendor-provider failures, API 23,
  x86_64 runtime, and actual 1080p/4K TV acceptance remain outstanding.
