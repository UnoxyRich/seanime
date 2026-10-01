# Native Anime4K shader assets

Nine GLSL files are byte-for-byte copies of Seanime's bundled
`seanime-denshi/assets/shaders/Anime4K_*.glsl`. The two GAN files are unmodified
upstream sources pinned to bloc97/Anime4K commit
`7684e9586f8dcc738af08a1cdceb024cc184f426`, paths
`glsl/Upscale/Anime4K_Upscale_GAN_x3_L.glsl` and
`glsl/Upscale/Anime4K_Upscale_GAN_x4_UUL.glsl`:
https://github.com/bloc97/Anime4K/tree/7684e9586f8dcc738af08a1cdceb024cc184f426/glsl/Upscale
All eleven files are originally authored by bloc97. Each file retains its complete MIT license
and copyright notice. The trained convolution weights are unchanged.

The 4x UUL source is stored at
`src/main/compressedAssets/anime4k/Anime4K_Upscale_GAN_x4_UUL.glsl.gz`
as deterministic gzip (level 9, `mtime=0`, no filename). Gradle's
`generateAnime4KAssets` task expands it at build time, verifies its original
1,091,539-byte length and SHA-256
`f4740658e3b8a15f3eb2e34d73a7b05d130bb2af11f3e71685636e4809e917ee`,
and packages the exact original bytes at
`assets/anime4k/Anime4K_Upscale_GAN_x4_UUL.glsl`. The gzip source is outside
the packaged asset directories. Compression changes repository storage only;
the complete upstream source, weights, license, runtime path and loading remain
unchanged. To reproduce it with Python, use
`gzip.GzipFile(filename='', mode='wb', compresslevel=9, mtime=0, fileobj=output)`
and write the pinned original source bytes to that stream.

NativeAnime4K adapts mpv texture bindings to GLES and executes every network
pass using signed RGBA16F activation buffers. It does not substitute a generic
sharpen filter. SDR Media3 linear BT.709 frames are encoded before inference
and decoded afterward. HDR is explicitly bypassed. GPU incompatibility, texture
size or allocation-budget failures report a reason and preserve source playback.

The A/B/C/A+A/B+B/C+A network order and conditional upscale/downscale sizes
follow Seanime's anime4k-webgpu 1.0.0 behavior reference:
https://github.com/Anime4KWebBoost/Anime4K-WebGPU/tree/main/src/pipelines/presets
The native implementation uses the original GLSL models and bilinear scaling;
it does not promise pixel-identical WebGPU output. GAN 3x Large and 4x Ultra-ultra-large use the complete 30/84-pass original
models, with hash-pinned asset tests and independent CPU/GLES reference tests.
Their public option IDs match Seanime's WebGPU options.

The 256 MiB intermediate/output allocation bound and 4096-pixel texture bound
are safety limits, not a claim of realtime performance on every television.
HQ modes require at least 15 fragment texture units and signed half-float render
targets. Medium requires fewer units; GAN 3x requires 9 and GAN 4x requires 16. Device-side EGL tests execute all supported
networks, compare Medium and both GAN models with independent CPU references, resize/reconfigure,
and verify memory-limit/HDR fallback. Physical-TV 1080p/4K sustained frame rate
must be measured separately.
