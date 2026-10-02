package app.seanime.tv

import kotlin.math.pow
import kotlin.math.roundToInt

/** Test-only oracle for the generated 10 fps, neutral-chroma, limited-range BT.709 clip. */
internal object OwnedVideoColorOracle {
    const val ID = "bt709-limited-to-srgb-v1"
    const val CHANNEL_TOLERANCE = 12
    const val FRAME_COUNT = 300
    const val FRAME_DURATION_MS = 100L

    fun lumaForFrame(frame: Long): Int {
        require(frame in 0 until FRAME_COUNT.toLong())
        return 40 + (frame % 6).toInt() * 20
    }

    /**
     * U=V=128 makes R'=G'=B'=(Y-16)/219 before transfer conversion.
     * Android DATASPACE_BT709 uses the SMPTE170M/BT.709 transfer, while
     * Bitmap.getPixel returns sRGB. Invert the former, then apply the latter.
     * See acceptance/2026-10-02-cold-playback-color-oracle.md for primary sources.
     */
    fun srgbForLimitedBt709Luma(luma: Int): Double {
        require(luma in 16..235)
        val encoded = (luma - 16) / 219.0
        val linear = if (encoded < 0.081) encoded / 4.5 else ((encoded + 0.099) / 1.099).pow(1.0 / 0.45)
        val srgb = if (linear < 0.0031308) 12.92 * linear else 1.055 * linear.pow(1.0 / 2.4) - 0.055
        return (255.0 * srgb).coerceIn(0.0, 255.0)
    }

    fun expectedFrames(positionMs: Long): Map<Long, Int> {
        val frame = positionMs.coerceIn(0, FRAME_COUNT * FRAME_DURATION_MS - 1) / FRAME_DURATION_MS
        // Preserve the immediate-neighbor allowance for pause/render timing,
        // including the frame just before an exact 100 ms boundary.
        return (-1L..1L).map { (frame + it).coerceIn(0, FRAME_COUNT - 1L) }.distinct().associateWith {
            srgbForLimitedBt709Luma(lumaForFrame(it)).roundToInt()
        }
    }

    fun matchingFrame(positionMs: Long, minimum: IntArray, maximum: IntArray): Map.Entry<Long, Int>? {
        require(minimum.size == 3 && maximum.size == 3)
        require(minimum.indices.all { minimum[it] in 0..255 && maximum[it] in minimum[it]..255 })
        // Every sampled channel must fit one single candidate frame, so a mean
        // cannot hide black pixels, a colored overlay, or two different frames.
        return expectedFrames(positionMs).entries.firstOrNull { (_, gray) ->
            minimum.all { it >= gray - CHANNEL_TOLERANCE } && maximum.all { it <= gray + CHANNEL_TOLERANCE }
        }
    }
}
