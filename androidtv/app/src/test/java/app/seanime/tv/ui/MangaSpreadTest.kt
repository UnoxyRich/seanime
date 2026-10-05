package app.seanime.tv.ui

import app.seanime.tv.data.MangaPage
import app.seanime.tv.data.MangaPageDimensions
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MangaSpreadTest {
    private val pages = listOf(4, 8, 12, 20, 21, 25).map { MangaPage(it, "page-$it") }
    private val dimensions = pages.associate { it.index to MangaPageDimensions(800, 1200) } +
        (12 to MangaPageDimensions(1600, 1000))

    @Test fun mixedPortraitAndWidePagesAppearExactlyOnceInBothDirections() {
        val spreads = MangaPagination.spreads(pages, dimensions, true, false)
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3, 4), listOf(5)), spreads)
        assertEquals(pages.indices.toList(), spreads.flatten())
        assertEquals(2, MangaPagination.move(1, 1, spreads))
        assertEquals(3, MangaPagination.move(2, 1, spreads))
        assertEquals(2, MangaPagination.move(4, -1, spreads))
        assertEquals(listOf(3, 4), MangaPagination.visible(4, spreads))
    }

    @Test fun coverOffsetKeepsTheAnchorVisibleWithoutPairingAWidePage() {
        val spreads = MangaPagination.spreads(pages, dimensions, true, true)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4), listOf(5)), spreads)
        assertTrue(4 in MangaPagination.visible(4, spreads))
        assertEquals(listOf(4), MangaPagination.visible(4, MangaPagination.spreads(pages, dimensions, false, true)))
    }

    @Test fun resumedSecondPageStaysVisibleAndTurnsByWholeSpreadsIncludingOddTail() {
        val portraits = pages.take(5)
        val sizes = portraits.associate { it.index to MangaPageDimensions(800, 1200) }
        val paired = MangaPagination.spreads(portraits, sizes, true, false)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), paired)
        assertEquals(listOf(2, 3), MangaPagination.visible(3, paired))
        assertEquals(4, MangaPagination.move(3, 1, paired))
        assertEquals(2, MangaPagination.move(4, -1, paired))
        assertEquals(0, MangaPagination.move(2, -1, paired))
        assertEquals(4, MangaPagination.move(4, 1, paired))
        val cover = MangaPagination.spreads(portraits, sizes, true, true)
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), cover)
        assertTrue(3 in MangaPagination.visible(3, cover))
        assertEquals(portraits.indices.toList(), cover.flatten())
    }

    @Test fun coldResumeProbesOnlyUnknownNeighborsAndKeepsAnchorBesideWideBoundary() = runBlocking {
        val known = mapOf(pages[3].index to dimensions.getValue(pages[3].index), pages[2].index to dimensions.getValue(pages[2].index))
        val targets = MangaPagination.dimensionProbePositions(3, pages, known, true, false)
        assertEquals(listOf(4), targets)
        val probes = probeMangaDimensions(pages, targets) { dimensions[it.index] }
        val spreads = MangaPagination.spreads(pages, known + probes.dimensions, true, false)
        assertEquals(listOf(3, 4), MangaPagination.visible(3, spreads))
        assertEquals(listOf(2), MangaPagination.visible(2, spreads))
        assertEquals(pages.indices.toList(), spreads.flatten())
        assertTrue(probes.failedPositions.isEmpty())
        assertFalse("Probed final pages are not rendered evidence", mangaEndPageRendered(listOf(4, 5), 6, setOf(4)))
    }

    @Test fun dimensionProbesRespectSingleModeCoverWideKnownPairsAndChapterBounds() {
        assertEquals(emptyList<Int>(), MangaPagination.dimensionProbePositions(0, emptyList(), emptyMap(), true, false))
        assertEquals(emptyList<Int>(), MangaPagination.dimensionProbePositions(3, pages, emptyMap(), false, false))
        assertEquals(emptyList<Int>(), MangaPagination.dimensionProbePositions(0, pages, emptyMap(), true, true))
        assertEquals(listOf(2), MangaPagination.dimensionProbePositions(1, pages, emptyMap(), true, true))
        assertEquals(emptyList<Int>(), MangaPagination.dimensionProbePositions(2, pages, dimensions, true, false))
        assertEquals(emptyList<Int>(), MangaPagination.dimensionProbePositions(0, pages, dimensions, true, false))
        assertEquals(listOf(1), MangaPagination.dimensionProbePositions(0, pages, emptyMap(), true, false))
        assertEquals(listOf(4), MangaPagination.dimensionProbePositions(5, pages, emptyMap(), true, false))
        assertEquals(listOf(2, 4), MangaPagination.dimensionProbePositions(3, pages, emptyMap(), true, false))
    }

    @Test fun failedProbeBatchIsFiniteAndNeverGuessesDimensions() = runBlocking {
        val attempted = mutableListOf<Int>()
        val result = probeMangaDimensions(pages, listOf(1, 1, 3, 4, 5)) {
            attempted += it.index
            if (attempted.size == 1) throw IllegalStateException("Unavailable") else MangaPageDimensions(0, 800)
        }
        assertEquals(listOf(pages[1].index, pages[3].index), attempted)
        assertEquals(setOf(1, 3), result.failedPositions)
        assertTrue(result.dimensions.isEmpty())
    }

    @Test fun cancelledProbeDoesNotCommitAnAttemptAndCanRunAgainOnReturn() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var committed: MangaDimensionProbeResult? = null
        val pending = launch {
            committed = probeMangaDimensions(pages, listOf(1)) { started.complete(Unit); awaitCancellation() }
        }
        started.await()
        pending.cancelAndJoin()
        assertNull(committed)
        val retried = probeMangaDimensions(pages, listOf(1)) { dimensions[it.index] }
        assertEquals(mapOf(pages[1].index to dimensions.getValue(pages[1].index)), retried.dimensions)
        assertTrue(retried.failedPositions.isEmpty())
    }

    @Test fun missingDimensionsDoNotGuessSpreadsAndLastVisiblePagesMustAllBeDecoded() {
        val unknown = MangaPagination.spreads(pages, emptyMap(), true, false)
        assertEquals(pages.indices.map { listOf(it) }, unknown)
        val finalPair = MangaPagination.spreads(pages.take(5), dimensions, true, false).last()
        assertEquals(listOf(3, 4), finalPair)
        assertFalse(mangaEndPageRendered(finalPair, 5, setOf(4)))
        assertTrue(mangaEndPageRendered(finalPair, 5, setOf(3, 4)))
        assertFalse(mangaEndPageRendered(listOf(2), 5, setOf(2, 4)))
        assertTrue(mangaEndPageRendered(listOf(4), 5, setOf(4)))
    }
}
