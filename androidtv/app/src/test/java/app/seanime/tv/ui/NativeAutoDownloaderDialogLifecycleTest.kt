package app.seanime.tv.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import app.seanime.tv.data.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicInteger

/** Host reproduction of the device's uncertain-result close/reopen dialog sequence. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34],
    qualifiers = "w960dp-h540dp-land-television-mdpi-notouch-nokeys-navexposed-dpad")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class NativeAutoDownloaderDialogLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun uncertainResultCanReopenAndReconcileWithoutAnotherPost() {
        val creates = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    fun data(value: Any) = MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(jsonObject("data" to value).toString())
                    return when (request.path) {
                        "/api/v1/auto-downloader/rules", "/api/v1/auto-downloader/profiles",
                        "/api/v1/auto-downloader/items", "/api/v1/extensions/list/anime-torrent-provider" -> data(JSONArray())
                        "/api/v1/settings" -> data(jsonObject("autoDownloader" to jsonObject("enabled" to false, "interval" to 20),
                            "library" to jsonObject("libraryPath" to "/library")))
                        "/api/v1/status" -> data(jsonObject("isOffline" to false, "serverReady" to true))
                        "/api/v1/library/collection" -> data(JSONArray().put(jsonObject("id" to 42L, "title" to "Owned first anime")))
                        "/api/v1/auto-downloader/rule" -> {
                            assertEquals("POST", request.method)
                            creates.incrementAndGet()
                            MockResponse().setResponseCode(503).setBody("""{"error":"Owned create rejection"}""")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val repo = SeanimeRepository(api)
                val visible = mutableStateOf(true)
                compose.setContent { SeanimeTheme { NativeArtworkProvider(api) {
                    if (visible.value) AutoDownloaderScreen(repo) { visible.value = false }
                } } }
                try {
                    awaitEnabled("auto-batch-open")
                    clickMain("auto-batch-open")
                    awaitFocused("auto-batch-add")
                    clickMain("auto-batch-add")
                    awaitEnabled("media-picker-42")
                    click("media-picker-42")
                    awaitFocused("auto-batch-add")
                    clickMain("auto-batch-review")
                    awaitFocused("auto-batch-confirm-cancel")
                    click("auto-batch-confirm")
                    compose.waitUntil(15_000) {
                        creates.get() == 1 && compose.onAllNodesWithTag("auto-batch-confirmation").fetchSemanticsNodes().isEmpty()
                    }
                    awaitFocused("auto-batch-review")
                    assertEquals(1, creates.get())

                    repeat(3) {
                        clickMain("auto-batch-close")
                        awaitFocused("auto-batch-open")
                        click("auto-batch-open")
                        awaitFocused("auto-batch-review")
                        click("auto-batch-review")
                        awaitFocused("auto-batch-confirm-cancel")
                        click("auto-batch-confirm")
                        compose.waitUntil(15_000) { compose.onAllNodesWithTag("auto-batch-confirmation").fetchSemanticsNodes().isEmpty() }
                        awaitFocused("auto-batch-review")
                        compose.onNodeWithTag("auto-batch-confirmation").assertDoesNotExist()
                        scrollMain("auto-batch-result-42").assertTextContains(
                            "The earlier request is still unconfirmed. Refresh results or inspect Rules before creating another rule.")
                        assertEquals(1, creates.get())
                    }
                } finally {
                    compose.runOnIdle { visible.value = false }
                    compose.waitForIdle()
                }
            }
        }
    }

    private fun clickMain(tag: String) { scrollMain(tag); click(tag) }
    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag(tag).assertIsFocused()
        // Target the focused node's window; Activity.dispatchKeyEvent would send
        // a dialog's key to the obscured activity instead of its dialog root.
        compose.onNodeWithTag(tag).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
    }
    private fun scrollMain(tag: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }
    private fun awaitEnabled(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitFocused(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
    }
}
