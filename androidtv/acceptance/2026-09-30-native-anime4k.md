# Native Anime4K implementation and verification

Date: 2026-09-30. Branch: `feat/native-compose-tv`. Local changes only.

## Scope

Native Media3 `GlEffect`, with no React/browser rendering and no Go/API changes.
`NativeAnime4K.kt` owns a signed RGBA16F multipass texture graph. Original CNN/GAN
coefficients are unchanged; this is neural-network inference, not a renamed
sharpen filter. SDR linear BT.709 frames are encoded for the original network
and decoded afterward. GLSL samplers and arithmetic explicitly request high
precision. Texture lifetime analysis prevents sampling from the current render
target and preserves skip-connected activations until the dense output pass.

Supported IDs: `mode-a`, `mode-b`, `mode-c`, `mode-aa`, `mode-bb`, `mode-ca`,
`cnn-2x-medium`, `cnn-2x-very-large`, `denoise-cnn-2x-very-large`,
`cnn-2x-ultra-large`, `gan-3x-large`, `gan-4x-ultra-large`. `off` removes effects.
All 12 preset IDs have native network implementations; actual device capability
and safe resource limits still apply. The host must surface GPU fallback reasons.

## Behavior provenance and intentional boundaries

Seanime's unchanged `video-core-anime-4k-manager.ts` selects corresponding
`anime4k-webgpu` classes. The HQ mode definitions were inspected from upstream
`src/pipelines/presets/{ModeA,ModeB,ModeC,ModeAA,ModeBB,ModeCA}/index.ts`:
https://github.com/Anime4KWebBoost/Anime4K-WebGPU/tree/main/src/pipelines/presets

This native planner follows those inspected definitions' network order and
resolution gates. It is not a general mpv shader interpreter. In particular,
mpv's deferred PREKERNEL clamp is executed with the clamp helper before restore,
as the WebGPU mode reference does. Individual `WHEN` directives are validated
against the known bundled expression, then replaced by whole-network gates:

- Both target dimensions must exceed 1.2 times current dimensions for a
  conditional upscale network to run
- If both target/native ratios are strictly between 1.2 and 2, auto-downscale
  to the target dimensions after the first upscale
- If both ratios are strictly between 2.4 and 4, auto-downscale to
  `ceil(target / 2)` before the final Medium upscale
- Each standalone CNN preset always performs its named 2x network, even when
  the display is smaller; it does not inherit the conditional HQ-mode gate
- The display target is aspect-fitted before planning, excluding letterbox bars

Network order (C = clamp helper, D = the conditional auto-downscale):

| ID | Ordered networks |
| --- | --- |
| mode-a | C, Restore VL, conditional Upscale VL, D, conditional Upscale M |
| mode-b | C, Restore Soft VL, conditional Upscale VL, D, conditional Upscale M |
| mode-c | C, conditional Denoise Upscale VL, D, conditional Upscale M |
| mode-aa | C, Restore VL, conditional Upscale VL, Restore M, D, conditional Upscale M |
| mode-bb | C, Restore Soft VL, conditional Upscale VL, D, Restore Soft M, conditional Upscale M |
| mode-ca | C, conditional Denoise Upscale VL, D, Restore M, conditional Upscale M |

The network implementation uses Seanime's original bundled GLSL shaders and
bilinear resampling. No claim is made that every output pixel matches WebGPU;
that would require a separate WebGPU reference comparison for each preset.

Inspected upstream mode source SHA-256 values (retrieved 2026-09-30):

- `ModeA.ts`: `55af57f365073f15b3b2e93f30a20cfeb679a6430ffc7828ff9d413659064b8c`
- `ModeAA.ts`: `8844816c78f77441f1bd95703e5277f38333fadea102485136e732481b04adfd`
- `ModeB.ts`: `6c87dae75c59f2fb0a6c741c5ed1d2d9fcf9eb84ecd5bd931e1cf7d8b025f779`
- `ModeBB.ts`: `1dc3ac0a841cde9e21a39a0d85164cafe370c66b6475e0c8ddc4ef0e96155db3`
- `ModeC.ts`: `b4086fab4cef3e82389f702b8143f3ec13d02b35d6f0aa4992deb199575064de`
- `ModeCA.ts`: `2449d476d851814e4eac898ce3d207ac95710944e4ed5ad7f1ed4190b0f02004`

## License and weight provenance

Nine native shader assets are byte-for-byte copies from
`seanime-denshi/assets/shaders`. Two GAN assets are unmodified official upstream
GLSL sources pinned to commit `7684e9586f8dcc738af08a1cdceb024cc184f426`:

- [GAN x3 L](https://github.com/bloc97/Anime4K/blob/7684e9586f8dcc738af08a1cdceb024cc184f426/glsl/Upscale/Anime4K_Upscale_GAN_x3_L.glsl)
- [GAN x4 UUL](https://github.com/bloc97/Anime4K/blob/7684e9586f8dcc738af08a1cdceb024cc184f426/glsl/Upscale/Anime4K_Upscale_GAN_x4_UUL.glsl)

The upstream graphs contain 30 and 84 complete network passes respectively,
requiring no change to the runner's binding or geometry semantics. They use the
same supported WHEN expression, dense skip connections, signed activation maps,
fractional bilinear convolution offsets and original RGB residual. These are
native implementations of the actual GAN models, not approximate CNN aliases. Complete MIT license/copyright headers remain
in every file. Original author: bloc97, https://github.com/bloc97/Anime4K.
The JVM test compares every existing copy with its repository source and both new GAN files with their pinned SHA-256 hashes.

- `Anime4K_Clamp_Highlights.glsl`: `a2a9bf7fbc1d75d09660ca2e701e4d7fb0cf5457b94da47e1825032fa2b3671a`
- `Anime4K_Restore_CNN_M.glsl`: `67ea3ed26539e8de3b7d307688535d2ff17e8d147e11dda0247da7770dbecf41`
- `Anime4K_Restore_CNN_Soft_M.glsl`: `a78a2c76898e08e09e442a9628c64208c26e8e15789649b8755223f009794c02`
- `Anime4K_Restore_CNN_Soft_VL.glsl`: `094334b0e20c1a201fe4941c7c68de72451e5aee9efb5524d7fb82b12dca64b9`
- `Anime4K_Restore_CNN_VL.glsl`: `35036722733305cd4d4e57660b883bbe2569ba2914033c254327107d7b77e35e`
- `Anime4K_Upscale_CNN_x2_M.glsl`: `716e02098a68f0d648761f2b96b4dd139e1cb09b174bb369fca3aa34328fff7e`
- `Anime4K_Upscale_CNN_x2_UL.glsl`: `fa7cf0ecc1cca84d8291bbff5a42b60f5816d57b4e97d42bc377235ac8db02e8`
- `Anime4K_Upscale_CNN_x2_VL.glsl`: `5638fe31c37c151a3443fea3451a3ef91af073f4dbb9615f6c0d1e29db11493d`
- `Anime4K_Upscale_Denoise_CNN_x2_VL.glsl`: `359c48fe5a317fbc6b706ce368401eef496e84ed98abac7a43efebca2b65d79b`

## Safety and device limits

The renderer refuses a graph above 256 MiB of owned activation plus final
output storage, or a dimension above min(GL_MAX_TEXTURE_SIZE, 4096). It checks
fragment texture-unit count and actual half-float framebuffer completeness.
It never substitutes RGBA8 for signed activations. Unsupported HDR, allocation
limits and GL failures report a bounded reason and preserve the original frame.
Callback delivery is once per effect instance. All programs/textures/FBOs are
released on reconfiguration, failure and release. The host must marshal failure
callbacks to its application looper and reject callbacks from stale selections.

Large networks can exceed those limits at common HD/4K sizes. Their presence
in the chooser is capability, not a promise of realtime performance. Physical
TV sustained decode/processing frame rate, thermals and dropped frames remain
separate acceptance gates.

## Latest device result — 18:16:49 UTC

All 12 supported non-Off presets **compiled and rendered real pixels without
fallback** in `everySupportedPresetCompilesAndRendersRealPixels` on the immutable
f66 pair. The runner completed `OK (1 test)` in 214.884s, shell exit 0. Every preset
produced the expected output size, nonempty RGB and opaque alpha. GAN 3× and 4×
outputs also met their independent CPU-reference tolerances. This closes the
previous ANR-interrupted all-presets gate.

- Source: `f66d183cdd364a24c478f264d9c530bfc35cacc9`
- App SHA-256: `5c8588cd41015416dab3bf4c74b6a82aaa019cc8e5ce080174dbce5e40fae6ce`
- Test SHA-256: `0c78feafe3bc1be42cddec9839e88562514ae2e25c42e352dd5af4d50ba61598`
- Device: API 36 Android TV x86_64, ES2 context, SwiftShader, no KVM
- Evidence: `toolchain/logs/native-discovery-all-presets.log` and
  `native-discovery-all-presets-logcat.txt`
- Exact filter: `app.seanime.tv.platform.NativeAnime4KShaderTest#everySupportedPresetCompilesAndRendersRealPixels`

The input fixture is 8×8. This proves these execution/pixel assertions at the
fixture size; it does not establish HD/4K allocation, realtime throughput,
thermals, physical-TV driver compatibility or universal WebGPU equivalence.
The earlier Medium-reference/reconfiguration and forced memory/HDR passthrough
passes retain their own dd77/a87 artifact boundary below. Source/fixture
compilation alone is not counted as a runtime result.

## Verification

- `NativeAnime4KPlanTest`: original pass count/weights, all twelve dependency and
  slot-lifetime graphs, exact network order and output transitions at 1x, 1.3x,
  2x, 3x and 4x, aspect fitting, original license/asset identity, fail-closed
  dimensions/unknown presets
- `NativeAnime4KShaderTest`: production GLES ES2 context; all twelve networks
  compile and render actual pixels; Medium and both GAN outputs compared with independent
  CPU references; reconfigure to a different source size and back; forced
  memory/HDR passthrough preserves original pixels and reports once
- `tools/generate_anime4k_reference.py`: independent NumPy CPU evaluation of
  original Medium mat4 weights, column-major conventions, edge clamping,
  positive/negative ReLU branches, signed float16 intermediate rounding,
  bilinear residual and depth-to-space. Fixture activations include negative
  values down to -1.195312, so an unsigned intermediate shortcut is detectable

Results at document creation: Kotlin compilation passed; the first focused JVM
run found an overstrict test assumption that a standalone Medium graph could
reuse texture storage. Its dense skip connections keep all same-sized maps
live until the dense pass, then the 2x output needs different-sized storage.
The test was corrected to assert that fact and verify reuse across HQ stages.
Final rerun and device-side EGL results are pending; no pixel equivalence or
realtime-performance pass is claimed until those results are recorded below.


## GAN provenance, bounds and reference fixtures

- `Anime4K_Upscale_GAN_x3_L.glsl`: SHA-256 `fecde271daf90df63d03f9999da57080b268d085cce1b60c0fbfe4589688da21`
- `Anime4K_Upscale_GAN_x4_UUL.glsl`: SHA-256 `f4740658e3b8a15f3eb2e34d73a7b05d130bb2af11f3e71685636e4809e917ee`

The CPU generator independently evaluates every original mat4 term and bias,
parses the original positive/negative feature-map branches, clamps texture edges,
implements fractional bilinear source offsets, preserves every float16 signed
intermediate, and adds the original RGB residual. It does not execute the GLES
wrapper or substitute conventional scaling. Generated 8x8-input references are
24x24 (2304 bytes) and 32x32 (4096 bytes); minimum signed intermediate values are
-4.507812 and -7.203125 respectively. Runtime pixel checks allow bounded rounding
differences; this is not a claim of equivalence to WebGPU's different texture-load
edge/resampling behavior.

Calculated allocation limits (owned activation buffers plus one Media3 output,
excluding decoder, drivers, compiled programs and other UI allocations):

| Preset | Allocated slots | Bytes per input pixel | 640x360 | 854x480 | 1280x720 |
| --- | --- | ---: | ---: | ---: | ---: |
| CNN 2x M | 9 native-size + 1 2x | 120 | 26.37 MiB | 46.91 MiB | 105.47 MiB |
| GAN 3x L | 13 native-size + 3 3x | 356 | 78.22 MiB | 139.17 MiB | 312.89 MiB |
| GAN 4x UUL | 23 native-size + 7 4x | 1144 | 251.37 MiB | 447.22 MiB | 1005.47 MiB |

Thus GAN 3x at 720p and GAN 4x at 480p exceed the current 256 MiB safety bound and
explicitly fall back. GAN 4x at 720p also exceeds the 4096 width cap. Even a graph
below these bounds can fail GPU allocation or miss realtime frame budgets on a
television. CNN 2x M at 1920x1080 needs 237.30 MiB and produces 3840x2160, within
these static bounds but still requiring physical-device performance acceptance.

Recovery checkpoint 2026-09-30 15:20 UTC: original 10-preset implementation and
90-test JVM suite had passed compilation/tests before disconnection. The two
GAN assets/branches survived recovery and their CPU golden fixtures are now
successfully generated. Final 12-preset JVM rerun, actual EGL shader/pixel tests
and sustained-device measurements are still pending. No GAN runtime pass is
claimed by fixture generation alone.

Verification update, 2026-09-30 15:30 UTC:
- Fresh `NativeAnime4KPlanTest` JUnit XML: 7 tests, 0 failures, 0 errors,
  timestamp 15:28:21 UTC, including both GAN asset/hash/graph tests
- Coordinated `native-fresh-player-tests.log`: production Kotlin and AndroidTest
  Kotlin compilation succeeded (`BUILD SUCCESSFUL in 42s`)
- The CPU fixtures were regenerated successfully; actual EGL pixel tests remain
  pending the final coordinated APK/device run

Verification update, 2026-09-30 16:40 UTC:
- The immutable runtime APK pair was app SHA-256
  `dd77ef69fb0047ae5dbf9d52c3896a6031065fc4c8150d73219e2e1bac6b4294`
  and test SHA-256
  `a87a8b8c415a66c9a54340c2e4ee9bfcb2149ed88ea1fa8299c009294e6e236c`.
- On Android TV API 36 x86_64 in the production-style ES2 context,
  `originalMediumCnnMatchesIndependentCpuReferenceAndReconfigures` and
  `memoryLimitAndHdrBypassKeepOriginalPixelsAndReportOnce` both passed.
- `everySupportedPresetCompilesAndRendersRealPixels` started but was interrupted
  by a delayed operating-system startup ANR kill. This is not a shader pass,
  assertion failure, or demonstrated shader crash. The all-preset warm retry
  remains pending.
- Diagnostic evidence is in the workspace's
  `toolchain/logs/native-resumed-six-engines.log`, corresponding
  `native-resumed-six-engines-logcat.txt` (lines 14525–14578), and
  `native-resumed-app-anr.txt` (PID 1625 entry and main stack at line 3185).
  Android reported `failed to complete startup`, then killed the process for
  `bg anr` with 81,428 ms reporting latency amid other system startup ANRs.
  The captured main stack was in framework StatsLog/ART CheckJNI during
  AndroidX EmojiCompat startup, with 2.92 s CPU time versus 27.49 s scheduler
  wait; it contained no Anime4K/GLES call. System CPU pressure was 88.14% over
  ten seconds with negligible memory pressure. No shader change is justified
  by this startup failure; the original networks and safety limits are intact.
