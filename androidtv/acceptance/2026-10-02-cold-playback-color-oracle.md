# Cold playback grayscale oracle correction

## Scope and cause

This changes only the generated-media instrumentation fixture and its test-only
oracle. The 160×90, 10 fps, 300-frame source remains YUV420p with
`Y = 40 + (frame % 6) * 20` and neutral `U = V = 128`. Playback, Go HTTP source,
READY/render-buffer, pause/play, seek, isolation, continuity and resume assertions
remain in place. This is not new Android runtime acceptance.

The old comparison, `1.164 * (Y - 16)`, expands limited-range video code values
but does not convert the video transfer curve to the screenshot's color space.
[Android Bitmap.getPixel](https://developer.android.com/reference/android/graphics/Bitmap#getPixel(int,%20int))
returns sRGB channel values. Android's
[DATASPACE_BT709](https://developer.android.com/reference/android/hardware/DataSpace#DATASPACE_BT709)
uses limited range and the SMPTE170M transfer curve. Its source definition groups
BT.709 with that curve and separately defines the sRGB curve and 8-bit limited
luma endpoints in
[Dataspace.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/graphics/common/aidl/android/hardware/graphics/common/Dataspace.aidl).

For the neutral-chroma fixture, derive the expected screenshot channel as follows:

1. Normalize the encoded video value: `E = (Y - 16) / 219`
2. Invert the video curve: `L = E / 4.5` when `E < 0.081`, otherwise
   `L = ((E + 0.099) / 1.099)^(1 / 0.45)`
3. Encode sRGB: `S = 12.92 * L` when `L < 0.0031308`, otherwise
   `S = 1.055 * L^(1 / 2.4) - 0.055`
4. Round `255 * S` to the nearest integer

This produces the six expected channels `44, 67, 89, 112, 133, 155`. The prior
reported R34 observations support the transform: at 2 ms the sampled minimum and
maximum were 43–45, consistent with frame 0 / Y40; at 10002 ms they were 155–156,
consistent with frame 101 / Y140; at 10274 ms they were 67–68, consistent with
frame 103 / Y60. These observations corroborate the equations; they are not used
to calibrate a replacement lookup table.

## Declared source metadata and strict comparison

The FFmpeg command now explicitly declares `tv` range and `bt709` primaries,
transfer and matrix on both raw input and encoded output. These are documented
[FFmpeg color options](https://ffmpeg.org/ffmpeg-codecs.html#Codec-Options).
Input declarations matter: the Linux FFmpeg 7.1.5 check found that output-only
options left transfer and primaries unspecified. The final input-and-output form
preserved all four fields, including H.264 VUI codes `1/1/1` and
`video_full_range_flag=0`.

The device test now checks the actual Media3 video format for BT.709, limited
range and SDR transfer before sampling pixels. In the pinned Media3 1.11.1
[ColorInfo source](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/ColorInfo.java),
ISO BT.709 transfer code 1 maps to `C.COLOR_TRANSFER_SDR`.
[MediaCodecVideoRenderer](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/MediaCodecVideoRenderer.java)
and
[MediaFormatUtil](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/util/MediaFormatUtil.java)
forward the format's color metadata to the decoder's standard/range/transfer
keys. This checks the declared decoder input; it does not replace pixel evidence.

The original channel tolerance of 12, immediate frame-neighbor allowance and
center sample region (47–53% width, 40–46% height, every second pixel) are
unchanged. Every sampled channel's minimum and maximum must fit the same single
candidate frame. There is one color transform, with no alternate-oracle fallback.
The shared helper lives in `src/sharedTest/java` and is included only in JVM and
instrumentation test source sets, not the app's production source set.

## Validation and remaining gate

- Six standalone JVM tests pass using Kotlin 2.2.10 and JUnit 4.13.2. They cover
  independent numeric vectors at endpoints and both sides of the video-transfer
  branch, the six-level cycle, timestamp boundaries, the three reported device
  observations, and rejection of black, stale, incorrect-transfer or contaminated
  samples
- Linux FFmpeg 7.1.5 / libx264 generated the final 30-second fixture; ffprobe
  confirmed H.264, 160×90, yuv420p, 10 fps, 300 frames and all four color fields.
  All 300 decoded frames retained the authored luma levels exactly and both
  chroma planes remained 128. This is host encoder evidence, not evidence for the
  APK's separately bundled FFmpeg 8.1.3 or Android compositor
- `git diff --check` passes. No Android build, emulator, instrumentation run or
  CI run was started for this correction. The next authorized cold invocation
  must still pass metadata, pixel, state and continuity/resume checks

On a pixel timeout, the fixture retains `videoPaintFailure` in its existing
`fixture.json` and saves `video-paint-failure.png` from the exact sampled bitmap
under that same `files/native-go-fixture-<UUID>/` root. Failure JSON contains
only a fixed checkpoint enum, the oracle ID, numeric position/dimensions/sample
count, three-channel means/minima/maxima, at most three expected frame/channel
pairs, tolerance, HUD-hidden boolean and the fixed screenshot basename.
`videoColorInfo` records the three Media3 integer color fields. Screenshot write
errors remain suppressed evidence on the original assertion failure.

These files are retained locally with the other owned fixture data. The generic
artifact collector does not currently collect this optional cold scan fixture;
the change does not claim the new fields or PNG are present in a downloaded CI
artifact. The owner must export the verified exact UUID root before any reviewed
cleanup, following the existing cold-process and retained-data restoration rules.
