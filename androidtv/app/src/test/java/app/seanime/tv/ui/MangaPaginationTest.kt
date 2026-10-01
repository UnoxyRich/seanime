package app.seanime.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaPaginationTest {
    @Test fun emptyChapterHasNoPage() {
        assertEquals(emptyList<Int>(), MangaPagination.visible(4, 0, true))
        assertEquals(0, MangaPagination.clamp(4, 0))
    }
    @Test fun savedPageOutsideChapterIsClamped() {
        assertEquals(0, MangaPagination.clamp(-10, 3))
        assertEquals(2, MangaPagination.clamp(20, 3))
    }
    @Test fun singlePageTurnsStayWithinChapter() {
        assertEquals(1, MangaPagination.move(0, 1, 3, false))
        assertEquals(0, MangaPagination.move(0, -1, 3, false))
        assertEquals(2, MangaPagination.move(2, 1, 3, false))
    }
    @Test fun spreadsHandleOddPageCountsWithoutMissingTheLastPage() {
        assertEquals(listOf(0, 1), MangaPagination.visible(0, 5, true))
        assertEquals(2, MangaPagination.move(0, 1, 5, true))
        assertEquals(listOf(4), MangaPagination.visible(4, 5, true))
        assertEquals(4, MangaPagination.move(4, 1, 5, true))
        assertEquals(2, MangaPagination.move(4, -1, 5, true))
    }
    @Test fun readingDirectionReversesRemoteKeysOnly() {
        assertEquals(1, MangaPagination.keyDelta(right = true, rightToLeft = false))
        assertEquals(-1, MangaPagination.keyDelta(right = false, rightToLeft = false))
        assertEquals(-1, MangaPagination.keyDelta(right = true, rightToLeft = true))
        assertEquals(1, MangaPagination.keyDelta(right = false, rightToLeft = true))
    }
}
