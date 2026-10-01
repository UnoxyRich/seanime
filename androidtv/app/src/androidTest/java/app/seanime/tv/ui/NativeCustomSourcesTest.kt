package app.seanime.tv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

class NativeCustomSourcesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualExtensionRouteRetainsLargeIdsPageQueryAndFocusedTitleThroughBothDetailTypes() {
        val requests = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val paths = CopyOnWriteArrayList<String>()
        val high = 9_007_199_254_740_991L
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    paths += path
                    fun media(id: Long) = JSONObject().put("id", id).put("title", "Custom title $id")
                        .put("description", "<p>Owned &amp; readable</p><script>not executable</script>")
                    val data: Any = when {
                        path == "/api/v1/extensions/all" -> JSONObject().put("extensions", JSONArray())
                        path == "/api/v1/extensions/list/custom-source" -> JSONArray().put(JSONObject().put("id", "owned-source").put("name", "Owned source").put("type", "custom-source")
                            .put("settings", JSONObject().put("supportsAnime", true).put("supportsManga", true)))
                        path.startsWith("/api/v1/custom-source/provider/list/") -> {
                            val body = JSONObject(request.body.readUtf8()); requests += path to body
                            JSONObject().put("totalPages", 2).put("media", if (body.getString("search") == "Nothing") JSONObject.NULL
                                else if (body.getInt("page") == 1) JSONArray().put(media(2_147_483_648L))
                                else JSONArray((0 until 18).map { media(high - 17 + it) }))
                        }
                        path.startsWith("/api/v1/library/anime-entry/") -> JSONObject().put("media", media(path.substringAfterLast('/').toLong())).put("episodes", JSONArray())
                        path.startsWith("/api/v1/manga/entry/") -> JSONObject().put("media", media(path.substringAfterLast('/').toLong()))
                        path.startsWith("/api/v1/extensions/list/") || path.startsWith("/api/v1/manga/downloaded-chapters/") -> JSONArray()
                        path == "/api/v1/settings" -> JSONObject().put("manga", JSONObject())
                        else -> JSONObject()
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("data", data).toString())
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
                val repo = SeanimeRepository(api)
                compose.setContent { SeanimeTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    NativeArtworkProvider(repo.client) { FeatureScreen(TvFeature.EXTENSIONS, repo, {}, {}) }
                } } }
                awaitTag("extensions-custom-sources")
                compose.onNodeWithTag("extensions-custom-sources").performTvClick()
                awaitTag("custom-source-media-2147483648")
                compose.onNodeWithText("Owned & readable").assertIsDisplayed()
                compose.onNodeWithText("not executable").assertDoesNotExist()
                compose.onNodeWithTag("custom-source-next").performScrollTo().performTvClick()
                compose.waitUntil(10_000) { requests.lastOrNull()?.second?.optInt("page") == 2 && compose.onAllNodesWithTag("custom-source-media-${high - 17}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("custom-source-list").performScrollToNode(hasTestTag("custom-source-media-$high"))
                compose.onNodeWithTag("custom-source-media-$high").performScrollTo().performTvClick()
                compose.waitUntil(10_000) { paths.contains("/api/v1/library/anime-entry/$high") && compose.onAllNodesWithText("Update list").fetchSemanticsNodes().isNotEmpty() }
                back()
                awaitFocused("custom-source-media-$high")
                assertEquals(2, requests.last().second.getInt("page"))
                NativeScreenshotEvidence.capture("native-custom-source-large-id-return")
                compose.onNodeWithTag("custom-source-list").performScrollToNode(hasTestTag("custom-source-type-manga"))
                compose.onNodeWithTag("custom-source-type-manga").performTvClick()
                awaitTag("custom-source-media-2147483648")
                compose.onNodeWithTag("custom-source-media-2147483648").performScrollTo().performTvClick()
                compose.waitUntil(10_000) { paths.contains("/api/v1/manga/entry/2147483648") && compose.onAllNodesWithTag("manga-edit-list").fetchSemanticsNodes().isNotEmpty() }
                back()
                awaitFocused("custom-source-media-2147483648")
                compose.onNodeWithTag("custom-source-search").performScrollTo().performTvClick()
                compose.onNodeWithTag("custom-source-search-editor").performTextReplacement("Nothing")
                compose.onNodeWithTag("text-entry-save").performTvClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("No titles found").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("custom-source-back").assertIsFocused()
                assertTrue(requests.any { it.first.endsWith("/manga") })
                assertEquals("Nothing", requests.last().second.getString("search"))
                assertEquals(1, requests.last().second.getInt("page"))
                requests.forEach { assertEquals("owned-source", it.second.getString("provider")); assertEquals(20, it.second.getInt("perPage")) }
            }
        }
    }
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitFocused(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty() }
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
}
