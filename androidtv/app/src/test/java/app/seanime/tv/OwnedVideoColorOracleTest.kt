package app.seanime.tv

import org.junit.Assert.*
import org.junit.Test

class OwnedVideoColorOracleTest {
    @Test fun videoTransferThenSrgbMatchesReferenceGrayValues() {
        // Independently evaluated reference vectors, not measured screenshot targets.
        val vectors = mapOf(16 to 0.0, 17 to 3.3430745814, 33 to 35.5340259541, 34 to 36.6650840537,
            40 to 43.7242241761, 60 to 66.8042443727, 80 to 89.3610218567,
            100 to 111.5274368633, 120 to 133.3833562215, 140 to 154.9820323387, 235 to 255.0)
        for ((luma, expected) in vectors) {
            assertEquals("limited BT.709 Y=$luma to sRGB", expected,
                OwnedVideoColorOracle.srgbForLimitedBt709Luma(luma), 0.0001)
        }
    }

    @Test fun fixtureLumaCycleAndTimestampNeighborsStayBounded() {
        assertEquals(listOf(40, 60, 80, 100, 120, 140, 40), (0L..6L).map(OwnedVideoColorOracle::lumaForFrame))
        assertEquals(setOf(0L, 1L), OwnedVideoColorOracle.expectedFrames(0).keys)
        assertEquals(setOf(0L, 1L), OwnedVideoColorOracle.expectedFrames(-1).keys)
        assertEquals(setOf(0L, 1L, 2L), OwnedVideoColorOracle.expectedFrames(100).keys)
        assertEquals(setOf(99L, 100L, 101L), OwnedVideoColorOracle.expectedFrames(10_002).keys)
        assertEquals(setOf(101L, 102L, 103L), OwnedVideoColorOracle.expectedFrames(10_274).keys)
        assertEquals(setOf(298L, 299L), OwnedVideoColorOracle.expectedFrames(30_000).keys)
        assertEquals(setOf(298L, 299L), OwnedVideoColorOracle.expectedFrames(Long.MAX_VALUE).keys)
    }

    @Test fun recordedColdPlaybackSamplesMatchOneTimestampCandidate() {
        // R34 device measurements: position, minimum, maximum. They corroborate
        // the derived transform but do not define it or increase its tolerance.
        assertEquals(0L, match(2, 43, 45)?.key)
        assertEquals(101L, match(10_002, 155, 156)?.key)
        assertEquals(103L, match(10_274, 67, 68)?.key)
    }

    @Test fun rejectsBlackStaleAndWrongTransferPixels() {
        assertNull(match(2, 0, 0))
        assertNull(match(10_002, 43, 45)) // Stale pre-seek frame.
        assertNull(match(10_274, 27, 27)) // Y=40 range expansion without transfer conversion.
        assertNull(match(10_274, 51, 67)) // Different candidates cannot explain one region.
    }

    @Test fun checksEveryChannelAndExtremeWithoutIncreasingTolerance() {
        assertEquals(12, OwnedVideoColorOracle.CHANNEL_TOLERANCE)
        assertEquals(0L, match(2, 32, 56)?.key)
        assertNull(match(2, 31, 56))
        assertNull(match(2, 32, 57))
        assertNull(OwnedVideoColorOracle.matchingFrame(2, intArrayOf(44, 0, 44), intArrayOf(44, 44, 44)))
        assertNull(OwnedVideoColorOracle.matchingFrame(2, intArrayOf(44, 44, 44), intArrayOf(44, 255, 44)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonLimitedLuma() {
        OwnedVideoColorOracle.srgbForLimitedBt709Luma(0)
    }

    private fun match(positionMs: Long, minimum: Int, maximum: Int) =
        OwnedVideoColorOracle.matchingFrame(positionMs, IntArray(3) { minimum }, IntArray(3) { maximum })
}
