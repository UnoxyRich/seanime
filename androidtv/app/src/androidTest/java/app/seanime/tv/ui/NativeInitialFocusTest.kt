package app.seanime.tv.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import app.seanime.tv.SetupScreen
import app.seanime.tv.UnlockScreen
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeJson
import app.seanime.tv.data.SeanimeRepository
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress

/** Exercises Compose/window readiness and fixture servers without touching the embedded server's data. */
class NativeInitialFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun latePlacementWaitsForWindowAndFulfilledFocusDoesNotStealButCanBeRearmed() {
        var windowFocused by mutableStateOf(false)
        var targetVisible by mutableStateOf(false)
        val granted = mutableStateOf(false)
        compose.setContent {
            ControlledWindow(windowFocused) {
                SeanimeTheme {
                    val requester = remember { FocusRequester() }
                    Column {
                        Button(onClick = {}, modifier = Modifier.testTag("user-target")) { Text("User choice") }
                        if (targetVisible) Button(onClick = {}, modifier = Modifier.testTag("pending-target").initialTvFocus(requester, granted)) { Text("Pending focus") }
                    }
                }
            }
        }
        compose.onNodeWithTag("user-target").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { targetVisible = true }
        compose.onNodeWithTag("user-target").assertIsFocused()
        compose.runOnIdle { windowFocused = true }
        compose.onNodeWithTag("pending-target").assertIsFocused()
        compose.onNodeWithTag("user-target").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { windowFocused = false }
        compose.runOnIdle { windowFocused = true }
        compose.onNodeWithTag("user-target").assertIsFocused()
        compose.runOnIdle { granted.value = false }
        compose.onNodeWithTag("pending-target").assertIsFocused()
    }

    @Test fun setupFocusWaitsForWindowAndOnlyTheExplicitContinueActionCompletes() {
        var windowFocused by mutableStateOf(false)
        var completed = 0
        compose.setContent { ControlledWindow(windowFocused) { SeanimeTheme {
            SetupScreen("/fixture/unchanged-library", {}, { _, _ -> completed++ })
        } } }
        compose.onNodeWithText("Online streaming: On").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { assertEquals(0, completed); windowFocused = true }
        compose.onNodeWithTag("setup-continue").assertIsFocused()
        compose.onNodeWithText("Choose USB / media folder").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { windowFocused = false }
        compose.runOnIdle { windowFocused = true }
        compose.onNodeWithText("Choose USB / media folder").assertIsFocused()
        compose.onNodeWithTag("setup-continue").performTvClick()
        compose.runOnIdle { assertEquals(1, completed) }
    }

    @Test fun lockedServerFocusWaitsForWindowAndSubmittingClearsThePasswordField() {
        var windowFocused by mutableStateOf(false)
        var submitted = ""
        compose.setContent { ControlledWindow(windowFocused) { SeanimeTheme { UnlockScreen { submitted = it } } } }
        compose.onNodeWithTag("server-password").assertIsNotFocused()
        compose.onNodeWithTag("server-unlock").assertIsNotEnabled()
        compose.runOnIdle { windowFocused = true }
        compose.onNodeWithTag("server-password").assertIsFocused().performTextInput("fixture-only-password")
        compose.onNodeWithTag("server-unlock").performTvClick()
        compose.onNodeWithTag("server-unlock").assertIsNotEnabled()
        compose.runOnIdle { assertEquals("fixture-only-password", submitted) }
    }

    @Test fun rootInitialFocusWaitsForWindowThenPreservesUserFocus() = fixture { api ->
        var windowFocused by mutableStateOf(false)
        val status = readyStatus()
        compose.setContent { ControlledWindow(windowFocused) { SeanimeTheme {
            SeanimeTvApp(SeanimeRepository(api), status, {}, {}, {})
        } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Your collection starts here").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("anime-search-submit").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { windowFocused = true }
        compose.onNodeWithTag("nav-LIBRARY").assertIsFocused()
        compose.onNodeWithTag("anime-search-submit").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { windowFocused = false }
        compose.runOnIdle { windowFocused = true }
        compose.onNodeWithTag("anime-search-submit").assertIsFocused()
    }

    @Test fun bottomRailRowsStayAboveFooterAndBackRestoresAnOffscreenSelectedRow() = fixture { api ->
        val status = readyStatus()
        compose.setContent { SeanimeTheme { SeanimeTvApp(SeanimeRepository(api), status, {}, {}, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Your collection starts here").fetchSemanticsNodes().isNotEmpty() }
        for (tag in listOf("nav-SETTINGS", "nav-LOGS")) {
            compose.onNodeWithTag("navigation-rail").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNodeWithTag(tag).assertIsDisplayed().assertIsFocused()
            val row = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val rail = compose.onNodeWithTag("navigation-rail").fetchSemanticsNode().boundsInRoot
            val footer = compose.onNodeWithTag("navigation-footer").fetchSemanticsNode().boundsInRoot
            assertTrue("Focused rail row must remain fully visible", row.top >= rail.top && row.bottom <= rail.bottom)
            assertTrue("Footer must not overlap focused navigation", row.bottom < footer.top)
        }
        compose.onNodeWithTag("nav-LOGS").performTvClick()
        compose.onNodeWithTag("navigation-rail").performScrollToIndex(0)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("nav-LOGS") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav-LOGS").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText("Leave Seanime?").assertDoesNotExist()
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun compactRailReturnsToLastContentControlWithoutMovingContentBounds() = fixture { api ->
        compose.setContent { SeanimeTheme { SeanimeTvApp(SeanimeRepository(api), readyStatus(), {}, {}, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Your collection starts here").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav-LIBRARY").assertIsFocused()
        val expandedContent = compose.onNodeWithTag("native-content").fetchSemanticsNode().boundsInRoot
        val expandedRail = compose.onNodeWithTag("navigation-rail").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("nav-LIBRARY").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("anime-search-submit").assertIsFocused()
        compose.onNodeWithTag("anime-search-submit").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("anime-collection-options").assertIsFocused()
        val collapsedContent = compose.onNodeWithTag("native-content").fetchSemanticsNode().boundsInRoot
        val collapsedRail = compose.onNodeWithTag("navigation-rail").fetchSemanticsNode().boundsInRoot
        assertEquals("Drawer expansion must not reflow posters", expandedContent, collapsedContent)
        assertTrue("Browsing releases the wide navigation labels", collapsedRail.width < expandedRail.width / 2)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("nav-LIBRARY").assertIsFocused()
        compose.onNodeWithTag("nav-LIBRARY").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("anime-collection-options").assertIsFocused()
        assertEquals(expandedContent, compose.onNodeWithTag("native-content").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Leave Seanime?").assertDoesNotExist()
    }

    private fun readyStatus() = SeanimeJson.status(JSONObject("""{"version":"fixture","serverReady":true,"settings":{"library":{}},"user":{"isSimulated":true}}"""))

    private fun fixture(test: (SeanimeApiClient) -> Unit) {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"data":{"lists":[],"Page":{"media":[]}}}""")
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use(test)
        }
    }
}

@Composable
private fun ControlledWindow(focused: Boolean, content: @Composable () -> Unit) {
    val actual = LocalWindowInfo.current
    val controlled = remember(actual, focused) { object : WindowInfo by actual { override val isWindowFocused = focused } }
    CompositionLocalProvider(LocalWindowInfo provides controlled, content = content)
}
