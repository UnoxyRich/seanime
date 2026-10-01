package app.seanime.tv.platform

import org.junit.Assert.*
import org.junit.Test

class NativePlayerFocusTransferTest {
    @Test fun unchangedPlacedPlayTargetCanAcceptAnotherTransferWithoutAnotherLayout() {
        val transfers = PlayerFocusTransfers()
        val initial = transfers.begin("play").apply { placed = true; applied = true }
        val afterEpisodeControlDisabled = transfers.begin("play")
        assertNotSame(initial, afterEpisodeControlDisabled)
        assertTrue("The still-present Play node already has coordinates", afterEpisodeControlDisabled.placed)
        assertFalse("The new request must still execute instead of reusing its old completion", afterEpisodeControlDisabled.applied)
    }

    @Test fun changedAndHiddenTargetsCannotReuseOldPlacement() {
        val transfers = PlayerFocusTransfers()
        transfers.begin("play").placed = true
        assertFalse(transfers.begin("root").placed)
        transfers.begin("play").placed = true
        assertFalse(transfers.begin(null).placed)
        assertFalse("A returning HUD must wait for its new placement", transfers.begin("play").placed)
    }

    @Test fun repeatedPendingRequestStillWaitsForItsFirstPlacement() {
        val transfers = PlayerFocusTransfers()
        transfers.begin("play")
        assertFalse(transfers.begin("play").placed)
    }
}
