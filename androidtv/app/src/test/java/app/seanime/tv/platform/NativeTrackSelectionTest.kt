package app.seanime.tv.platform

import org.junit.Assert.*
import org.junit.Test

class NativeTrackSelectionTest {
    @Test fun `caption ordinal never collides with an original track number`() {
        val groups = listOf(listOf(2, 7), listOf(0, 1031))
        assertEquals(1 to 0, NativeTrackSelection.resolve(groups, 2, matchTrackNumber = false))
        assertEquals(0 to 0, NativeTrackSelection.resolve(groups, 2, matchTrackNumber = true))
        assertEquals(0 to 0, NativeTrackSelection.resolve(groups, 0, matchTrackNumber = false))
        assertEquals(1 to 0, NativeTrackSelection.resolve(groups, 0, matchTrackNumber = true))
    }

    @Test fun `ordinals span groups and missing IDs without enabling invalid choices`() {
        val groups = listOf(emptyList(), listOf(null, 42), listOf(null))
        assertEquals(1 to 0, NativeTrackSelection.resolve(groups, 0, false))
        assertEquals(2 to 0, NativeTrackSelection.resolve(groups, 2, false))
        assertEquals(1 to 1, NativeTrackSelection.resolve(groups, 42, true))
        assertNull(NativeTrackSelection.resolve(groups, 42, false))
        assertNull(NativeTrackSelection.resolve(groups, 3, false))
        assertNull(NativeTrackSelection.resolve(groups, -1, false))
    }
}
