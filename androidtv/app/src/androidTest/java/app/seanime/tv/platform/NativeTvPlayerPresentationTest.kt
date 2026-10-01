package app.seanime.tv.platform

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.ui.performTvClick
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the fresh Compose presentation without creating a media decoder. */
@RunWith(AndroidJUnit4::class)
class NativeTvPlayerPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun errorScreenKeepsExternalPlayerAndReturnActionsVisibleToTheRemote() {
        val state = NativeTvPlayerState().apply { error = "This video format could not be decoded." }
        var selected = ""
        compose.setContent { NativeTvPlayerPresentation(state, onAction = { selected = it }, onSeek = {}) }
        compose.onNodeWithTag("native-player-retry").assertIsFocused()
        val root = compose.onNodeWithTag("native-player-error").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("native-player-retry", "native-player-convert", "native-player-external", "native-player-exit")) {
            val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            assertTrue("$tag must retain its complete height", node.boundsInRoot.height >= with(node.layoutInfo.density) { 56.dp.toPx() } - 1)
            assertTrue("$tag must fit the TV error screen", node.positionInRoot.y >= root.top && node.positionInRoot.y + node.size.height <= root.bottom)
        }
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("native-player-convert").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("native-player-external").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("external", selected)
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("native-player-exit").assertIsFocused()
        NativeScreenshotEvidence.capture("player-error-external-route-focus")
    }

    @Test fun unavailableEpisodeControlsAreDisabledAndReturnFocusToPlay() {
        val state = NativeTvPlayerState().apply { paused = true; canPrevious = true; canNext = true }
        compose.setContent { NativeTvPlayerPresentation(state, onAction = {}, onSeek = {}) }
        compose.onNodeWithTag("native-player-play").assertIsFocused()
        compose.onNodeWithTag("native-player-previous").assertIsEnabled().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("native-player-previous").assertIsFocused()
        compose.runOnIdle { state.canPrevious = false }
        compose.onNodeWithTag("native-player-previous").assertIsNotEnabled().assertIsNotFocused()
        compose.onNodeWithTag("native-player-play").assertIsFocused()
        compose.onNodeWithTag("native-player-next").assertIsEnabled().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("native-player-next").assertIsFocused()
        compose.runOnIdle { state.canNext = false }
        compose.onNodeWithTag("native-player-next").assertIsNotEnabled().assertIsNotFocused()
        compose.onNodeWithTag("native-player-play").assertIsFocused()
    }

    @Test fun skipActivationRestoresPlayWhenTheNextIntervalAppearsInTheSameFrame() {
        val state = NativeTvPlayerState().apply {
            title = "Adjacent skip intervals"
            paused = true
            duration = 12_000
            position = 1_000
            skipLabel = "Skip intro"
        }
        var skipped = 0
        compose.setContent {
            NativeTvPlayerPresentation(state, onAction = { action ->
                if (action == "skip") {
                    skipped++
                    state.position = 3_000
                    state.skipLabel = ""
                    // Model a following server seek in the same UI turn. The
                    // absence of Skip is deliberately never composed.
                    state.position = 7_000
                    state.skipLabel = "Skip ending"
                }
            }, onSeek = {})
        }
        compose.onNodeWithTag("native-player-skip").assertTextEquals("Skip intro").performTvClick()
        compose.onNodeWithTag("native-player-skip").assertTextEquals("Skip ending").assertIsNotFocused()
        compose.onNodeWithTag("native-player-play").assertIsFocused()
        compose.runOnIdle { assertEquals(1, skipped); assertEquals(7_000L, state.position); assertTrue(state.paused) }
    }

    @Test fun manyTrackChoicesKeepSelectionVisibleAndRestoreFocusAcrossReopen() {
        val state = NativeTvPlayerState().apply { title = "Many audio tracks"; paused = true }
        var selected = 18
        var selectionCount = 0
        fun choices() = PlayerChoiceDialog("Audio language", (0 until 24).map { index ->
            PlayerChoice("track-$index", "Audio track ${index + 1} · Stereo", selected = index == selected) {
                selected = index
                selectionCount++
            }
        })
        compose.setContent {
            NativeTvPlayerPresentation(state, onAction = { if (it == "audio") state.dialog = choices() }, onSeek = {})
        }
        compose.onNodeWithTag("native-player-audio").performTvClick()
        assertVisibleFocus("native-player-choice-track-18")
        assertDialogFitsViewport()
        NativeScreenshotEvidence.capture("player-many-tracks-selected-18")

        remote(KeyEvent.KEYCODE_DPAD_UP)
        assertVisibleFocus("native-player-choice-track-17")
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        assertVisibleFocus("native-player-choice-track-18")
        for (index in 19..23) {
            remote(KeyEvent.KEYCODE_DPAD_DOWN)
            assertVisibleFocus("native-player-choice-track-$index")
        }
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        assertVisibleFocus("native-player-dialog-close", minimumHeightDp = 48)
        NativeScreenshotEvidence.capture("player-many-tracks-close-focus")
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("native-player-dialog").assertDoesNotExist()
        compose.onNodeWithTag("native-player-audio").assertIsFocused()
        compose.runOnIdle { assertEquals(0, selectionCount) }

        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertVisibleFocus("native-player-choice-track-18")
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        assertVisibleFocus("native-player-choice-track-19")
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        assertVisibleFocus("native-player-choice-track-20")
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("native-player-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(20, selected); assertEquals(1, selectionCount) }
        compose.onNodeWithTag("native-player-audio").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertVisibleFocus("native-player-choice-track-20")
        compose.onNodeWithTag("native-player-choice-track-20").assertTextContains("✓", substring = true)
        assertDialogFitsViewport()
        NativeScreenshotEvidence.capture("player-many-tracks-selected-20")
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("native-player-audio").assertIsFocused()
    }

    private fun remote(key: Int) = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)

    private fun assertDialogFitsViewport() {
        val dialog = compose.onNodeWithTag("native-player-dialog").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("native-player-presentation").fetchSemanticsNode().boundsInRoot
        val maximum = with(compose.density) { 460.dp.toPx() }
        assertTrue("Choice dialog exceeds its TV height cap: $dialog", dialog.height <= maximum + 1)
        assertTrue("Choice dialog is taller than the player viewport", dialog.height <= viewport.height)
        assertTrue("Choice dialog is wider than the player viewport", dialog.width <= viewport.width)
    }

    private fun assertVisibleFocus(tag: String, minimumHeightDp: Int = 56) {
        val item = compose.onNodeWithTag(tag).assertIsFocused().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("native-player-choices").fetchSemanticsNode().boundsInRoot
        val minimum = with(compose.density) { minimumHeightDp.dp.toPx() }
        assertTrue("Focused choice is clipped vertically: $item within $viewport", item.height >= minimum - 1 && item.top >= viewport.top - 1 && item.bottom <= viewport.bottom + 1)
        assertTrue("Focused choice is clipped horizontally", item.left >= viewport.left - 1 && item.right <= viewport.right + 1)
    }
}
