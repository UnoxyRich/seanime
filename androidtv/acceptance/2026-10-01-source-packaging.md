# Native TV source packaging — October 1, 2026

This is a build-time storage correction following the
[R21 layout and live-data verification](2026-10-01-tv-layout-and-live-data.md).
It does not change runtime Kotlin, Go, API contracts, shader logic or weights.
The previously delivered R21 APKs and source bundle remain intact.

The publishing transport did not complete requests containing the 1,091,539-byte
GAN 4x shader source. The repository now stores that one source as deterministic
gzip under `app/src/main/compressedAssets/anime4k/`. Its 333,937-byte compressed
form preserves the full original source and MIT notice. Gradle expands it into
the original `assets/anime4k/Anime4K_Upscale_GAN_x4_UUL.glsl` APK path. The task
declares its input and output, bounds expansion, and validates the original
length and SHA-256 before writing. The compressed source is not packaged in the
APK. No network download is required to reconstruct it.

The expanded SHA-256 is
`f4740658e3b8a15f3eb2e34d73a7b05d130bb2af11f3e71685636e4809e917ee`.
Both newly built ABI APKs contain exactly the same shader bytes as R21. The
host provenance test still checks all eleven original shader sources; the
independent reference generator reads the compressed source without changing
its graph calculations or reference fixtures.

The R22 aggregate ran from 08:15:50 to 08:20:09 UTC in 259 seconds. It passed
all 366 JVM tests with no failures, errors or skips, all 34 screenshot
comparisons, lint, ARM64 and x86_64 debug APK builds, and the instrumentation APK
build. Screenshot references were not updated. Production Kotlin and Java
compilation remained up to date. The build-script edit caused Gradle to rerun
the Go binding task using the unchanged sources and toolchain; whole APK byte
identity is not claimed.

Lint reported zero errors and 83 warnings. The additional warning compared
with R21 is an available-version advisory for the unchanged DocumentFile
dependency. All three APKs verify V1/V2 signatures with the same existing debug
certificate. Seven native libraries per ABI pass 16 KiB ELF/ZIP alignment, and
the existing 145 API-contract checks and unchanged-Go guard pass.

No APK entries were added or removed. Apart from the asset README and signing
metadata, the only changed entries are the two `libgojni.so` files. Each differs
in 139 bytes comprising Go/GNU build IDs and four temporary build-directory
numbers; all other bytes and executable sections match R21. All shader,
DEX and resource bytes match R21. The instrumentation APK is byte-identical.

| R22 build artifact | SHA-256 |
| --- | --- |
| ARM64 | `66146286ccea7292d56c92fe5a0853c9dbefe8e9a2553ab2257911083326df99` |
| x86_64 | `9e977d5f6e79b7682afe098fc24102e9c0cd2523fcf1aed6e8c9a3396c7b0f7f` |
| Instrumentation | `eeadac9dba09772199e7c0e8b1ceb551a4f93ed5a200c0653c8c7180e7b983e0` |

No emulator or device test ran for this correction. The failed pre-install
Android framework gate, live AniList HTTP 403, unverified Bloom Into You
playback, and all remaining physical-device/provider/storage checks in the
R21 report remain open. Host checks do not close those acceptance gates.
