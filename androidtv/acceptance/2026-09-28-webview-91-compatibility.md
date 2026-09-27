# Android TV WebView 91 compatibility — 2026-09-28

Source commit: `df37ce6e` (`codex/android-tv`).

## Environment

- Android TV emulator: API 31 / Android 12, ARM64, 1920 × 1080
- Android System WebView: 91.0.4472.114
- Kernel page size: 4 KiB

## Finding and fix

The first real app launch failed after onboarding with `toSorted is not a
function`. The WebView also predates `at`, `findLast`, `findLastIndex`,
`toReversed`, `toSpliced`, and `Promise.withResolvers`, which are used by the
shared UI. Syntax transpilation does not add these runtime methods.

The frontend now installs missing methods before React starts, preserving any
native implementations. Non-mutating Array methods keep their source arrays
unchanged. The `toSpliced` implementation covers omitted and explicit
`deleteCount` behavior.

## Verification

- `npm run typecheck` passed.
- `npm test` passed: 91 tests in 15 files.
- `ANDROID_HOME=/Users/unoxyrich/Library/Android/sdk ./gradlew :app:connectedDebugAndroidTest`
  passed: 13/13 instrumentation tests on the API 31 ARM64 TV emulator.
- The startup instrumentation test now calls all six Array methods and checks
  `Promise.withResolvers()` inside the embedded WebView before continuing.
- Manually installed and launched the rebuilt ARM64 APK. D-pad navigation
  opened the TV setup steps; launching the app reached the home screen. After
  dismissing the AniList prompt with D-pad, the home catalogue rendered and a
  D-pad focus ring appeared on the trending filter row. The active app process
  logged no renderer exception.
- Both ABI APKs passed v1/v2 APK signature verification and `zipalign -c -P 16`.
  The native alignment verifier confirmed all four packaged libraries in each
  APK have 16 KiB ELF/ZIP alignment.

Screenshot: [home with D-pad focus](screenshots/2026-09-28-webview-91-home-after-fix.png).

## Debug APK SHA-256

```text
app-arm64-v8a-debug.apk
ccb87c85b5482cc3d212665165fcdc3ce7b1e6766f51c6168bd72cc80071bd2f

app-x86_64-debug.apk
3a7369be5cbed2f07fc116ff37696306814e7a50d4def77a50b7d39d7548b09a
```

This run establishes startup compatibility and WebView API availability. It
does not cover a physical TV, USB storage, authenticated sources, or actual
video playback.

## Follow-up: structured-clone fallback — 2026-09-28

Source commit: `e09da4af` (`codex/android-tv`).

WebView 91 also lacks `structuredClone()`. Manga provider updates and editable
data-grid copies now use `cloneApiData`: it delegates to the native API when
available and falls back to cloning the plain API/settings values used by
these screens on older WebViews.

## Verification

- `npm run typecheck` passed as part of the Android TV build.
- `npm test` passed: 92 tests in 16 files.
- `ANDROID_HOME=/Users/unoxyrich/Library/Android/sdk ./gradlew :app:connectedDebugAndroidTest`
  passed: both ABI packages built and all 13 instrumentation tests passed on
  the API 31 ARM64 TV emulator.
- Installed and launched the ARM64 build on the emulator. D-pad navigation
  moved through the setup steps and reached the Next control. Opening and
  dismissing the Android numeric keyboard with Back restored focus to the
  active field.
- `apksigner verify --verbose` and `zipalign -c -P 16 4` passed for both APKs.
  `scripts/verify_android_native_alignment.py` confirmed all four native
  libraries in each APK have 16 KiB ELF/ZIP alignment.

Screenshot: [Media Player setup with D-pad focus](screenshots/2026-09-28-structured-clone-dpad-media-player.png).

## Follow-up debug APK SHA-256

```text
app-arm64-v8a-debug.apk
83091b7db9cfb839e2ecd8f761958c6f138947a0984076bbb0a153f4c602810c

app-x86_64-debug.apk
3e4c82fdf7667ce29f476ee6bc6686c707d4deffb4313860f11dc0691855e529
```

## Follow-up: dynamic viewport fallback — 2026-09-28

Source commit: `1d4e3d74` (`codex/android-tv`).

WebView 91 does not support dynamic viewport units (`dvh`). Shared manga,
streaming, file-selection, log, and drawer layouts now use viewport custom
properties: modern engines select `dvh` through `@supports`, while older
engines use the `vh` fallback. The remaining `:has()` selectors only affect
hover auto-hide, table spacing, and calendar rounding; core controls and
selection state do not depend on them.

## Verification

- `npm run typecheck` passed as part of the Android TV build.
- `npm test` passed: 92 tests in 16 files.
- `ANDROID_HOME=/Users/unoxyrich/Library/Android/sdk ./gradlew :app:connectedDebugAndroidTest`
  passed: both ABI packages built and all 13 instrumentation tests passed on
  the API 31 ARM64 TV emulator. The real-WebView startup probe confirms
  `100dvh` is unsupported there, the fallback is `100vh`, and a generated
  `calc(var(--viewport-height) - 3rem)` utility computes a positive height.
- Installed and launched the ARM64 package at 1920 × 1080. The setup page
  rendered and showed a visible D-pad focus ring.
- Both APKs passed APK signature verification and `zipalign -c -P 16 4`.
  `scripts/verify_android_native_alignment.py` confirmed all four native
  libraries in each APK have 16 KiB ELF/ZIP alignment.

Screenshot: [current build with visible D-pad focus](screenshots/2026-09-28-webview-91-dpad-current-build.png).

## Viewport-fallback debug APK SHA-256

```text
app-arm64-v8a-debug.apk
f2f948c992fffe775726a3eedbd1e99c325031698239a86c3ee0d01f5b562d28

app-x86_64-debug.apk
a734c6720cfc04feace1311c5e9302318397ed45051a97364ead6f3bb45547c0
```

## Follow-up: Object.hasOwn fallback — 2026-09-28

Source commit: `dba88453` (`codex/android-tv`).

The generated Android TV bundle uses `Object.hasOwn()` in a virtualization
dependency without a feature check. WebView 91 predates that API, so startup
polyfills now provide its own-property semantics before the shared React
application loads. A frontend regression test removes the native method and
checks inherited, `__proto__`, symbol, and null-input behavior.

## Verification

- `npm run typecheck` passed as part of the Android TV build.
- `npm test` passed: 93 tests in 16 files.
- `ANDROID_HOME=/Users/unoxyrich/Library/Android/sdk ./gradlew :app:connectedDebugAndroidTest`
  passed: both ABI packages built and all 13 instrumentation tests passed on
  the API 31 ARM64 TV emulator. The embedded WebView startup probe verifies
  `Object.hasOwn` distinguishes own from inherited properties.
- Installed and launched the ARM64 package at 1920 × 1080; the setup page
  rendered with a visible D-pad focus ring.
- Both APKs passed APK signature verification and `zipalign -c -P 16 4`.
  `scripts/verify_android_native_alignment.py` confirmed all four native
  libraries in each APK have 16 KiB ELF/ZIP alignment.

Screenshot: [latest build with visible D-pad focus](screenshots/2026-09-28-webview-91-dpad-object-hasown.png).

## Latest debug APK SHA-256

```text
app-arm64-v8a-debug.apk
234434f8ac74c928da49beb97d01187dabb9310f1d7fa3301b7ea2a60a493668

app-x86_64-debug.apk
76b64d174eb1c2c6d0b8e0a03cdfca3d549c75ef3143a33b36a4597d8e73d106
```
