package app.seanime.tv.ui

import android.os.Build
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.seanime.tv.NativeScreenshotEvidence
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

class NativeActionRowFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun firstLastAndAutomaticallyScrolledButtonsKeepTheirFullScaledFocusBounds() {
        val labels = listOf("Additional anime folder", "Torrent download folder", "Manage folder access", "Connect MyAnimeList", "Screenshot folder", "Check app update")
        val activated = AtomicInteger(-1)
        compose.setContent { SeanimeTheme {
            val first = remember { FocusRequester() }
            val granted = remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp)) {
                Box(Modifier.width(420.dp)) {
                    ActionRow {
                        labels.forEachIndexed { index, label ->
                            ActionButton(label, modifier = Modifier.width(220.dp).testTag("action-row-button-$index")
                                .then(if (index == 0) Modifier.initialTvFocus(first, granted) else Modifier)) { activated.set(index) }
                        }
                    }
                }
            }
        } }
        awaitFocused(0)
        assertFocusedActionFitsViewport(compose, "action-row-button-0", verifyPixels = true)
        val firstX = compose.onNodeWithTag("action-row-button-0").fetchSemanticsNode().positionInRoot.x
        pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(0, activated.get())
        for (index in 1..labels.lastIndex) {
            pressRemote(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocused(index)
            val node = compose.onNodeWithTag("action-row-button-$index").fetchSemanticsNode()
            assertEquals("Scrolling must not shrink the button's inner width", with(node.layoutInfo.density) { 220.dp.toPx() }.roundToInt(), node.size.width)
            assertFocusedActionFitsViewport(compose, "action-row-button-$index", verifyPixels = index == labels.lastIndex)
            assertEquals("Moving focus must not activate a different action", 0, activated.get())
        }
        assertTrue("Long actions must cause horizontal scrolling", compose.onNodeWithTag("action-row-button-0").fetchSemanticsNode().positionInRoot.x < firstX)
        pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(labels.lastIndex, activated.get())
        NativeScreenshotEvidence.capture("native-action-row-last-focus")
        for (index in labels.lastIndex - 1 downTo 0) {
            pressRemote(KeyEvent.KEYCODE_DPAD_LEFT)
            awaitFocused(index)
            assertFocusedActionFitsViewport(compose, "action-row-button-$index")
        }
        NativeScreenshotEvidence.capture("native-action-row-first-focus")
    }

    private fun awaitFocused(index: Int) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("action-row-button-$index") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun pressRemote(key: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        compose.waitForIdle()
    }
}

/** Semantics are outside TV Material's scale layer; compare the full 1.1x painted bounds, not clipped node bounds. */
internal fun assertFocusedActionFitsViewport(compose: ComposeContentTestRule, tag: String, verifyPixels: Boolean = false) {
    compose.onNodeWithTag(tag).assertIsFocused().assertIsDisplayed()
    val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
    val viewportNode = generateSequence(node.parent) { it.parent }
        .firstOrNull { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
        ?: error("Focused action must have a horizontal scrolling viewport")
    val viewport = viewportNode.boundsInRoot
    val x = node.positionInRoot.x
    val y = node.positionInRoot.y
    // androidx.tv:tv-material:1.0.0 ButtonDefaults.scale() uses focusedScale=1.1f.
    val dx = node.size.width * .05f
    val dy = node.size.height * .05f
    val visual = Rect(x - dx, y - dy, x + node.size.width + dx, y + node.size.height + dy)
    assertTrue("$tag scaled visual bounds $visual exceed viewport $viewport",
        visual.left >= viewport.left - 1f && visual.right <= viewport.right + 1f &&
            visual.top >= viewport.top - 1f && visual.bottom <= viewport.bottom + 1f)
    if (verifyPixels && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val root = compose.onRoot()
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val pixels = root.captureToImage().toPixelMap()
        val left = floor(visual.left - rootBounds.left).toInt().coerceIn(0, pixels.width - 1)
        val right = ceil(visual.right - rootBounds.left).toInt().coerceIn(0, pixels.width - 1)
        val middle = (visual.center.y - rootBounds.top).roundToInt().coerceIn(0, pixels.height - 1)
        val painted = (left..right).filter { column ->
            (-2..2).any { delta ->
                val color = pixels[column, (middle + delta).coerceIn(0, pixels.height - 1)]
                color.red > .8f && color.green > .8f && color.blue > .8f && color.alpha > .9f
            }
        }
        assertTrue("Focused pill should remain painted across its full scaled width", painted.isNotEmpty())
        assertTrue("Focused left curve is clipped or focus scaling was removed", painted.first() <= left + 2)
        assertTrue("Focused right curve is clipped or focus scaling was removed", painted.last() >= right - 2)
    }
}
