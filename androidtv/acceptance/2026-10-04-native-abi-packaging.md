# Native ABI packaging correction

Base: `eaf64784f333cbd30944165edfad5c217c62f19b` (R43).

## Observed problem

A prior Mac build selected `SEANIME_ANDROID_ABIS=arm64-v8a`, but Gradle still
declared both APK splits. Its x86_64 APK consequently lacked `libffmpeg.so` and
`libffprobe.so`. The separate alignment verifier accepted the native libraries
that were present. The Desktop delivery was subsequently rebuilt and verified
complete; this correction prevents recurrence and does not describe that repaired
delivery as incomplete.

## Change and scope

The validated ABI selection now controls both APK splits and FFmpeg generation.
Unset means both supported ABIs; a single ABI is supported; blank or unsupported
values fail during configuration. Whitespace is normalized before passing the
selection to the FFmpeg shell process, so a newline cannot silently omit the
second ABI. The existing both-ABI gomobile AAR and NDK runtime caches are retained;
Android's configured splits filter the packaged application payload.

The existing artifact verifier now requires `libgojni.so`, `libffmpeg.so`,
`libffprobe.so`, and `libc++_shared.so` for each ABI in an APK. This matches the
existing native contract check's minimum runtime inventory. Other native libraries
still receive their existing architecture and alignment checks. A complete
single-ABI APK remains valid.

No Kotlin runtime/UI source, Go source, API, schema, provider, network setting,
signing configuration or existing artifact was changed. Old output files and caches
are preserved; consult current `output-metadata.json` instead of assuming every
APK in an output directory was built by the latest single-ABI invocation.

## Verification

- `python3 -m unittest discover -s scripts -p test_android_native_alignment.py -v`:
  **11 tests passed**, including each missing required library on both ABIs,
  complete single-ABI APKs, the historical partial x86_64 shape, mixed complete/
  incomplete ABIs, real CLI rejection, architecture and alignment regressions.
- A synthetic x86_64 APK with valid aligned Go/C++ ELF files and missing media
  tools passes the unchanged R43 verifier (exit 0), and fails the correction
  (exit 1) with both exact missing paths.
- The complete CI x86_64 APK from R41, SHA-256
  `dc50bf45640e6e012796bfcb66057db50234acd1bbda0de14a04b50a3c9d11bb`,
  passes the corrected verifier with all seven native libraries. Its immutable
  bytes were recovered from the previously saved Library artifact and rehashed.

## Mac validation, 2026-10-04

The cached Mac toolchain validated the exact published
[R44 source `db3af2ab`](https://github.com/UnoxyRich/seanime/commit/db3af2ab7d99b15e00ce4a280c0bd0ec251b20f9),
parent `eaf64784f333cbd30944165edfad5c217c62f19b`, tree
`a08709fd0998106a3b0eea9fe5ec0dd588a7d3af`, in an isolated detached worktree.
The known `gradlew.bat` checkout line-ending status was the only tracked status
difference; no content change remained when ignoring end-of-line whitespace.

- The same **11 Python tests passed**.
- **Seven Gradle configuration cases passed**: unset/default both, explicit both,
  ARM64-only, x86_64-only, repeated whitespace/multiline deduplication, blank
  rejection and unsupported-value rejection. APK output ABI selection and the
  FFmpeg process selection agreed.
- Offline x86_64 `assembleDebug` **passed in 59 seconds**. It reused task-local
  R43 native outputs whose FFmpeg build-script checksum matches R44 (`2444766455`).
  Gradle ran offline; Go resolved dependencies from the existing local file cache.
  The first attempt with `GOPROXY=off` stopped at Go Mobile initialization because
  `gobind@latest` needed module resolution; the cached-resolution retry succeeded.
- Current `output-metadata.json` names exactly one x86_64 APK. The verifier finds
  **all seven native libraries**, including every required tool, with 16 KiB
  ELF/ZIP alignment.

The validation APK is `app-x86_64-debug.apk`, 72,875,481 bytes, package
`app.seanime.tv`, version `3.10.3` / code `3010003`:

- APK SHA-256: `d897fd433148c0aa99b2d9e29009fb58d343f4ac39994ff729d779f6c87b9ebf`
- Signer certificate SHA-256: `55191c377ace14f43280f973bdb55cbb0b3588dc030714bd2cd691ab02314ccf`

Its signer differs from the delivered Mac preview's certificate
(`71f5eab50002408a822bebb66433bdf555ba3756fcb174e5d406874cce01953f`).
It was **not installed** and did not replace Desktop APKs or user data.
ARM64/default-both assembly, x86_64 runtime/UI, broad CI and provider requests
were **not run** for this correction. The build result is not a playback result.

Official split behavior: [Android multiple-APK configuration](https://developer.android.com/build/configure-apk-splits).

This is a build correctness fix. Real anime playback, manga page rendering,
physical-TV acceptance and the unresolved Mac screenshot differences retain
their previous scope and status.
