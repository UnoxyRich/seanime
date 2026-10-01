package app.seanime.tv.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeJson
import app.seanime.tv.data.SeanimeRepository
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class NativeOnlineSourceSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun findingSourcesBeforeEpisodeListStillPlaysTheReturnedCanonicalSpecial() = fixture("Online") { fixture ->
        openEditor("episode")
        compose.onNodeWithTag("source-editor-episode").performTextReplacement("13")
        compose.onNodeWithText("Save").performTvClick()
        compose.onNodeWithTag("source-episode-edit").assertTextEquals("Episode: 13").assertIsFocused()
        compose.onNodeWithTag("source-find-sources").performTvClick()
        compose.waitUntil(10_000) { fixture.sourceRequests.isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("source-find-sources") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("source-content").performScrollToNode(hasText("Play Fixture 1080p"))
        assertEquals("Find Sources must not already depend on a list request", 0, fixture.listRequests.get())
        compose.onNodeWithText("Play Fixture 1080p").performTvClick()
        compose.waitUntil(10_000) { fixture.playback.get() != null }
        val request = requireNotNull(fixture.playback.get())
        val episode = requireNotNull(request.episode)
        assertEquals(3, episode.number)
        assertEquals("S2", episode.aniDbEpisode)
        assertEquals(0, episode.progressNumber)
        assertEquals("Canonical special", episode.title)
        assertEquals("Canonical special", episode.raw.getString("displayTitle"))
        assertEquals("preserved", episode.raw.getString("custom"))
        assertFalse(episode.raw.has("originalOnly"))
        assertEquals(13, episode.raw.getJSONObject("onlinestreamParams").getInt("episodeNumber"))
        assertEquals("fixture", episode.raw.getJSONObject("onlinestreamParams").getString("provider"))
        assertEquals(13, fixture.sourceRequests.single().getInt("episodeNumber"))
        assertEquals(1, fixture.listRequests.get())
    }

    @Test fun sourceEditorsValidateSaveCancelAndRestoreTheirRemoteOpeners() = fixture("Torrent", providerCount = 12) { fixture ->
        openEditor("episode")
        compose.onNodeWithTag("source-editor-episode").performTextReplacement("0")
        compose.onNodeWithText("Save").performTvClick()
        compose.onNodeWithTag("source-episode-edit").assertTextEquals("Episode: 1").assertIsFocused()
        // Providers push the old end-of-page error offscreen. Validation must stay beside its field.
        compose.onNodeWithTag("source-episode-error").assertTextEquals("Choose a positive episode number").assertIsDisplayed()
        openEditor("episode")
        compose.onNodeWithTag("source-editor-episode").performTextReplacement("4")
        compose.onNodeWithText("Cancel").performTvClick()
        compose.onNodeWithTag("source-episode-edit").assertTextEquals("Episode: 1").assertIsFocused()
        openEditor("episode")
        compose.onNodeWithTag("source-editor-episode").performTextReplacement("4")
        compose.onNodeWithText("Save").performTvClick()
        compose.onNodeWithTag("source-episode-edit").assertTextEquals("Episode: 4").assertIsFocused()
        compose.onNodeWithTag("source-episode-error").assertDoesNotExist()
        openEditor("query")
        compose.onNodeWithTag("source-editor-query").performTextReplacement("Discard this")
        compose.onNodeWithText("Cancel").performTvClick()
        compose.onNodeWithTag("source-query-edit").assertTextEquals("Torrent search: Fixture anime").assertIsFocused()
        openEditor("query")
        compose.onNodeWithTag("source-editor-query").performTextReplacement("Batch query")
        compose.onNodeWithTag("source-editor-query").assertIsDisplayed()
        NativeScreenshotEvidence.capture("source-bounded-query-editor")
        compose.onNodeWithText("Save").performTvClick()
        compose.onNodeWithTag("source-query-edit").assertTextEquals("Torrent search: Batch query").assertIsFocused()
        compose.onNodeWithTag("source-content").performScrollToNode(hasTestTag("source-find-sources"))
        compose.onNodeWithTag("source-find-sources").performTvClick()
        compose.waitUntil(10_000) { fixture.searchRequests.isNotEmpty() }
        assertEquals(4, fixture.searchRequests.single().getInt("episodeNumber"))
        assertEquals("Batch query", fixture.searchRequests.single().getString("query"))
        assertNull(fixture.playback.get())
    }

    @Test fun switchingSourceClearsOldProvidersAndFailedLoadCanBeRetried() = fixture("Online") { fixture ->
        fixture.failNextTorrentProviders.set(true)
        compose.onNodeWithTag("source-mode-Torrent").performTvClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("source-provider-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("source-provider-error").assertIsDisplayed()
        compose.onNodeWithTag("source-provider-fixture").assertDoesNotExist()
        compose.onNodeWithTag("source-find-sources").assertIsNotEnabled()
        compose.onNodeWithTag("source-provider-retry").assertIsDisplayed().performTvClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("✓ Fixture provider").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("source-provider-error").assertDoesNotExist()
        compose.onNodeWithTag("source-find-sources").assertIsEnabled()
    }

    private fun openEditor(field: String) {
        val tag = if (field == "episode") "source-episode-edit" else "source-query-edit"
        compose.onNodeWithTag("source-content").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performTvClick()
        compose.onNodeWithTag("source-editor-$field").assertIsDisplayed().assertIsFocused()
    }

    private fun fixture(mode: String, providerCount: Int = 1, test: (SourceFixture) -> Unit) {
        val fixture = SourceFixture(providerCount)
        val server = MockWebServer().apply { dispatcher = fixture; start(InetAddress.getByName("127.0.0.1"), 0) }
        val api = SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        try {
            val repo = SeanimeRepository(api)
            val media = SeanimeJson.media(JSONObject("""{"id":21,"title":"Fixture anime"}"""))
            val original = SeanimeJson.episode(JSONObject("""{"episodeNumber":1,"aniDBEpisode":"1","progressNumber":1,"displayTitle":"Original episode","originalOnly":true,"baseAnime":{"id":21}}"""))
            compose.setContent { SeanimeTheme { SourceScreen(media, original, repo, { fixture.playback.set(it) }, {}, initialMode = mode) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("✓ Fixture provider").fetchSemanticsNodes().isNotEmpty() }
            test(fixture)
        } finally { api.close(); server.shutdown() }
    }

    private class SourceFixture(private val providerCount: Int) : Dispatcher() {
        val failNextTorrentProviders = AtomicBoolean(false)
        val listRequests = AtomicInteger()
        val sourceRequests = CopyOnWriteArrayList<JSONObject>()
        val searchRequests = CopyOnWriteArrayList<JSONObject>()
        val playback = AtomicReference<PlaybackRequest?>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path == "/api/v1/extensions/list/anime-torrent-provider" && failNextTorrentProviders.compareAndSet(true, false)) {
                return MockResponse().setResponseCode(503).setBody(JSONObject().put("error", "Torrent provider lookup failed").toString())
            }
            val data: Any = when (request.path) {
                "/api/v1/extensions/list/onlinestream-provider", "/api/v1/extensions/list/anime-torrent-provider" ->
                    JSONArray().apply {
                        put(JSONObject().put("id", "fixture").put("name", "Fixture provider"))
                        for (index in 1 until providerCount) put(JSONObject().put("id", "fixture-$index").put("name", "Additional provider $index"))
                    }
                "/api/v1/extensions/list/anime-entry-episode-tabs" -> JSONArray()
                "/api/v1/settings" -> JSONObject().put("library", JSONObject())
                "/api/v1/onlinestream/episode-list" -> {
                    listRequests.incrementAndGet()
                    JSONObject("""{"episodes":[{"number":13,"title":"Provider special","metadata":{"episodeNumber":3,"aniDBEpisode":"S2","progressNumber":0,"type":"special","displayTitle":"Canonical special","custom":"preserved","baseAnime":{"id":21}}}]}""")
                }
                "/api/v1/onlinestream/episode-source" -> {
                    sourceRequests.add(JSONObject(request.body.readUtf8()))
                    JSONObject("""{"number":13,"videoSources":[{"url":"https://media.invalid/fixture.m3u8","label":"Fixture","quality":"1080p","server":"Fixture","type":"m3u8"}]}""")
                }
                "/api/v1/torrent/search" -> {
                    searchRequests.add(JSONObject(request.body.readUtf8()))
                    JSONObject().put("torrents", JSONArray())
                }
                else -> JSONObject()
            }
            return MockResponse().setBody(JSONObject().put("data", data).toString())
        }
    }
}
