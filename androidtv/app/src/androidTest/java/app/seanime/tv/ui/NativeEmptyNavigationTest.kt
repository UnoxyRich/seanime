package app.seanime.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.data.SeanimeRepository
import app.seanime.tv.data.SeanimeJson
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** No account/provider dependency: real HTTP responses drive native empty/loading/error UI. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class NativeEmptyNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyLibraryAndZeroSearchResultsKeepRemoteControlsFocused() {
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"data":{"lists":[],"Page":{"media":[]}}}""")
                    .setBodyDelay(if (request.path?.contains("list-anime") == true) 300 else 0, TimeUnit.MILLISECONDS)
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val api = SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        try {
            show(api)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Your collection starts here").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("nav-LIBRARY").assertIsFocused()
            NativeScreenshotEvidence.capture("library-empty-rail-focus")
            compose.onNodeWithTag("nav-LIBRARY").performKeyInput { pressKey(Key.DirectionDown) }
            compose.onNodeWithTag("nav-ANILIST").assertIsFocused()
            compose.onNodeWithTag("nav-ANILIST").performKeyInput { pressKey(Key.DirectionRight) }
            compose.onNodeWithTag("anime-search-submit").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.onNodeWithTag("anime-search-field").performTextInput("No results fixture")
            compose.onNodeWithTag("text-entry-save").performTvClick()
            compose.onNodeWithTag("anime-search-submit").assertIsFocused()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Your collection starts here").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("anime-search-submit").assertIsFocused()
            compose.onNodeWithTag("anime-search-submit").performKeyInput { pressKey(Key.DirectionLeft) }
            compose.onNodeWithTag("nav-LIBRARY").assertIsFocused()
        } finally { api.close(); server.shutdown() }
    }

    @Test fun retryAfterServerFailureRestoresStableRemoteFocus() {
        val requests = AtomicInteger()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val first = request.path == "/api/v1/library/collection" && requests.getAndIncrement() == 0
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setResponseCode(if (first) 503 else 200)
                        .setBody(if (first) """{"error":"Fixture server unavailable"}""" else """{"data":{"lists":[]}}""")
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val api = SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        try {
            show(api)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Try again").performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNodeWithText("Try again").performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Your collection starts here").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("anime-search-submit").assertIsFocused()
            compose.onNodeWithTag("anime-search-submit").performKeyInput { pressKey(Key.DirectionLeft) }
            compose.onNodeWithTag("nav-LIBRARY").assertIsFocused()
        } finally { api.close(); server.shutdown() }
    }

    @Test fun returningFromOffscreenDetailsRestoresCardButSearchKeepsItsFocus() {
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val id = request.path?.substringAfterLast('/')?.toIntOrNull()
                    val data: Any = if (request.path?.contains("/library/anime-entry/") == true) {
                        JSONObject().put("media", JSONObject().put("id", id).put("title", "Fixture $id"))
                            .put("episodes", org.json.JSONArray())
                    } else org.json.JSONArray().apply {
                        (1..40).forEach { put(JSONObject().put("id", it).put("title", "Fixture $it")) }
                    }
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(JSONObject().put("data", data).toString())
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val api = SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        try {
            show(api)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("media-grid").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("media-grid").performScrollToIndex(35)
            compose.onNodeWithTag("media-36").performTvClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Update list").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Back").performTvClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("media-36") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("media-36").assertIsFocused()
            NativeScreenshotEvidence.capture("library-fixture-restored-card-focus")
            compose.onNodeWithTag("anime-search-submit").performTvClick()
            compose.onNodeWithTag("anime-search-field").performTextInput("Fixture")
            compose.onNodeWithTag("text-entry-save").performTvClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("media-grid").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("anime-search-submit").assertIsFocused()
        } finally { api.close(); server.shutdown() }
    }

    private fun show(api: SeanimeApiClient) {
        val status = SeanimeJson.status(JSONObject("""{"version":"fixture","serverReady":true,"settings":{"library":{}},"user":{"isSimulated":true}}"""))
        compose.setContent { SeanimeTheme { SeanimeTvApp(SeanimeRepository(api), status, {}, {}, {}) } }
    }
}
