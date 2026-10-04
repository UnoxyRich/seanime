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
signing identity or existing artifact was changed. Old output files and caches
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
- Gradle configuration and actual APK packaging validation are pending on the
  cached Mac toolchain. No new APK or device result is claimed here.

Official split behavior: [Android multiple-APK configuration](https://developer.android.com/build/configure-apk-splits).

This is a build correctness fix. Real anime playback, manga page rendering,
physical-TV acceptance and the unresolved Mac screenshot differences retain
their previous scope and status.
