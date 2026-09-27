# Android TV SAF write recovery

- Revision: `6c5f93f4` (`Recover abandoned Android SAF writes`).
- Emulator: API 31 Android TV ARM64, Android 12, WebView 91.0.4472.114,
  4096-byte pages.
- Command: `ANDROID_HOME=/Users/unoxyrich/Library/Android/sdk
  ANDROID_NDK_HOME=/Users/unoxyrich/Library/Android/sdk/ndk/27.2.12479018
  ./gradlew :app:connectedDebugAndroidTest --no-daemon` from `androidtv`.
- Result: **16/16 instrumentation tests passed**. The task built the Android TV
  frontend and debug package, compiled Android instrumentation code, then
  installed and ran the suite on the connected emulator.
- The storage test seeds the synthetic DocumentsProvider and persisted journal,
  then constructs a fresh adapter to cover abandoned `.part` cleanup, restoring
  an old destination when replacement stopped halfway, keeping a replacement
  that had already committed, and removing a fallback copy marked incomplete.
  It verifies the old and committed bytes and that handled transactions and
  temporary documents are gone.
- Journal updates are synchronously persisted before replacement steps. If the
  provider, grant, or journal cannot be recovered, the adapter keeps the
  transaction and refuses subsequent storage operations until recovery can
  complete. The synthetic test covers transaction recovery; it does not simulate
  an OS process kill or establish behavior with a physical USB provider.
- Physical USB unplug/replug, low-space and vendor-provider failures, API 23,
  x86_64 runtime, and actual 1080p/4K TV acceptance remain outstanding.
