# Android TV update installer handoff

- Device: `sdk_google_atv64_arm64` (`emulator64_arm64`), API 31 Android TV
  ARM64, Android 12, WebView 91.0.4472.114, 4096-byte pages.
- Command: `ANDROID_HOME=/Users/unoxyrich/Library/Android/sdk
  ANDROID_NDK_HOME=/Users/unoxyrich/Library/Android/sdk/ndk/27.2.12479018
  ./gradlew :app:connectedDebugAndroidTest --no-daemon` from `androidtv`.
- Result: **18/18 instrumentation tests passed**, zero skipped or failed.
  This run rebuilt the Android TV frontend and instrumentation APK, then ran
  the suite on the connected API 31 TV emulator. The debug app APK itself was
  up to date.
- `updateInstallerRestrictsApkPathsAndRequestsUnknownSourceConsent` gives the
  installer a bogus APK outside its allowed update directories and verifies it
  is rejected without changing the pending install path. It then gives the
  installer a path inside `files/seanime/updates`, verifies the canonical path
  is saved, waits for Android's install-source Settings screen to take focus,
  and returns with Back. Test files and the prior preference are restored.
- This covers path validation and the unknown-source consent handoff. It does
  not install a signed update, validate an update signature, or test replacing
  the app with a release-signed build.
