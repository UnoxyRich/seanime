package app.seanime.tv.ui

import app.seanime.tv.data.MangaPage
import app.seanime.tv.data.MangaPageDimensions
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
