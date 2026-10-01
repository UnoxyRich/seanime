package app.seanime.tv.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeJson
import app.seanime.tv.data.SeanimeRepository
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
import java.util.concurrent.atomic.AtomicReference

/** Real HTTP/WebSocket fixtures plus DPAD activation of the native adapters. */
class NativePluginActionsAndTabsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun highMediaIdsSurviveActualPluginNavigationOpenAndEpisodeSelection() = fixture { fixture ->
        fixture.holdEpisodeCollections = true
        for (id in listOf(2_147_483_648L, 4_294_967_317L, 9_007_199_254_740_991L)) {
            fixture.plugin("episode-source", "screen:navigate-to", JSONObject().put("path", "/entry?id=$id&tab=episodeTab%3Aepisode-source"))
            awaitOpen(fixture, id)
            fixture.collection(JSONObject().put("episodes", JSONArray().put(JSONObject().put("episodeNumber", 3)
                .put("episodeTitle", "High identity episode").put("baseAnime", JSONObject().put("id", id)))))
            awaitTag("plugin-episode-3")
            compose.onNodeWithTag("plugin-episode-3").performScrollTo().performTvClick()
            compose.waitUntil(10_000) { fixture.received.any { it.optString("type") == "anime:entry-episode-tab:select-episode" && it.getJSONObject("payload").optLong("mediaId") == id } }
            val selected = fixture.received.last { it.optString("type") == "anime:entry-episode-tab:select-episode" }.getJSONObject("payload")
            assertEquals(id, selected.getLong("mediaId"))
            assertEquals(id, selected.getJSONObject("episode").getJSONObject("baseAnime").getLong("id"))
            assertEquals(3, selected.getInt("episodeNumber"))
        }
        assertEquals(0, fixture.playRequests.get())
    }

    @Test fun libraryMediaPageAndEpisodeActionsKeepTheirPayloadsAndUnloadFocus() = fixture { fixture ->
        awaitTag("plugin-actions-ANIME_LIBRARY-0")
        compose.onNodeWithTag("plugin-actions-ANIME_LIBRARY-0").performTvClick()
        compose.onNodeWithTag("plugin-action-close").assertIsFocused()
        compose.onNodeWithTag("plugin-action-actions-plugin-library").performTvClick()
        val library = awaitAction(fixture, "library")
        assertEquals("actions-plugin", library.getString("extensionId"))
        assertEquals(0, library.getJSONObject("payload").getJSONObject("event").length())
        compose.onNodeWithTag("plugin-action-close").performTvClick()
        compose.onNodeWithTag("plugin-actions-ANIME_LIBRARY-0").assertIsFocused()
        compose.onNodeWithTag("plugin-actions-MEDIA_CARD-21").performTvClick()
        compose.onNodeWithTag("plugin-action-actions-plugin-blocked").assertIsNotEnabled()
        compose.onNodeWithTag("plugin-action-actions-plugin-manga-only").assertDoesNotExist()
        compose.onNodeWithTag("plugin-action-actions-plugin-media").performTvClick()
        assertEquals(21, awaitAction(fixture, "media").getJSONObject("payload").getJSONObject("event").getJSONObject("media").getInt("id"))
        compose.onNodeWithTag("plugin-action-close").performTvClick()
        compose.onNodeWithTag("media-21").performTvClick()
        awaitTag("plugin-actions-ANIME_PAGE_BUTTON-21")
        compose.onNodeWithTag("plugin-actions-ANIME_PAGE_BUTTON-21").performTvClick()
        compose.onNodeWithTag("plugin-action-actions-plugin-page").performTvClick()
        assertEquals("kept", awaitAction(fixture, "page").getJSONObject("payload").getJSONObject("event").getJSONObject("media").getString("custom"))
        compose.onNodeWithTag("plugin-action-close").performTvClick()
        compose.onNodeWithTag("anime-detail-content").performScrollToNode(hasTestTag("plugin-actions-EPISODE_CARD-3"))
        compose.onNodeWithTag("plugin-actions-EPISODE_CARD-3").performTvClick()
        compose.onNodeWithTag("plugin-action-actions-plugin-episode").performTvClick()
        val event = awaitAction(fixture, "episode").getJSONObject("payload").getJSONObject("event")
        assertEquals("S2", event.getJSONObject("episode").getString("aniDBEpisode"))
        assertFalse(event.has("type"))
        fixture.serverEvent("plugin-unloaded", "actions-plugin")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("plugin-action-actions-plugin-episode").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("plugin-action-close").assertIsFocused()
        NativeScreenshotEvidence.capture("plugin-action-unloaded-native-focus")
        compose.onNodeWithTag("plugin-action-close").performTvClick()
        compose.onNodeWithTag("plugin-actions-EPISODE_CARD-3").assertIsFocused()
        assertFalse(fixture.received.any { it.optString("type") == "action:clicked" && it.getJSONObject("payload").optString("actionId") == "blocked" })
    }

    @Test fun configuredPluginSourceOpensSelectsAndClosesWithoutOverridingManualChoice() = fixture { fixture ->
        awaitTag("media-21")
        compose.onNodeWithTag("media-21").performTvClick()
        awaitTag("plugin-episode-tab-episode-source")
        compose.onNodeWithText("Watch").performTvClick()
        awaitTag("plugin-episode-3")
        compose.onNodeWithText("✓ Bonus episodes").assertExists()
        assertTrue(fixture.received.any { it.optString("extensionId") == "episode-source" && it.optString("type") == "anime:entry-episode-tab:open" && it.getJSONObject("payload").getLong("mediaId") == 21L })
        compose.onNodeWithTag("source-content").performScrollToNode(hasTestTag("plugin-actions-EPISODE_CARD-3"))
        compose.onNodeWithTag("plugin-actions-EPISODE_CARD-3").performTvClick()
        compose.onNodeWithTag("plugin-action-actions-plugin-grid").performTvClick()
        assertEquals("episodeTab:episode-source", awaitAction(fixture, "grid").getJSONObject("payload").getJSONObject("event").getString("type"))
        compose.onNodeWithTag("plugin-action-close").performTvClick()
        compose.onNodeWithTag("plugin-episode-3").performScrollTo().performTvClick()
        compose.waitUntil(10_000) { fixture.received.any { it.optString("type") == "anime:entry-episode-tab:select-episode" } }
        val selected = fixture.received.last { it.optString("type") == "anime:entry-episode-tab:select-episode" }
        assertEquals("episode-source", selected.getString("extensionId"))
        val payload = selected.getJSONObject("payload")
        assertEquals(21L, payload.getLong("mediaId"))
        assertEquals(3, payload.getInt("episodeNumber"))
        assertEquals("S2", payload.getString("aniDbEpisode"))
        assertEquals(7, payload.getJSONObject("episode").getJSONObject("custom").getInt("x"))
        assertEquals(0, fixture.playRequests.get())
        compose.onNodeWithTag("source-mode-Online").performScrollTo().performTvClick()
        awaitText("No enabled provider")
        fixture.tabs()
        compose.onNodeWithText("✓ Online").assertExists()
        compose.waitUntil(10_000) { fixture.received.lastOrNull { it.optString("type") == "anime:entry-episode-tab:state-changed" && it.optString("extensionId") == "episode-source" }?.getJSONObject("payload")?.optBoolean("isOpen") == false }
        fixture.emptyEpisodes = true
        compose.onNodeWithTag("source-plugin-episode-source").performScrollTo().performTvClick()
        awaitText("No plugin episodes")
        fixture.plugin("episode-source", "fatal-error", JSONObject().put("error", "Fixture plugin failure"))
        awaitText("Fixture plugin failure")
        fixture.serverEvent("plugin-unloaded", "episode-source")
        awaitText("This plugin was unloaded. Choose another source.")
        compose.onNodeWithTag("source-mode-Device").performScrollTo().performTvClick()
        compose.onNodeWithText("✓ Device").assertExists()
        NativeScreenshotEvidence.capture("plugin-episode-source-manual-fallback")
    }

    @Test fun lateCollectionsCannotBecomeSelectableAfterSwitchingTitles() = fixture { fixture ->
        fixture.holdEpisodeCollections = true
        awaitTag("media-21")
        compose.onNodeWithTag("media-21").performTvClick()
        awaitTag("plugin-episode-tab-episode-source")
        compose.onNodeWithText("Watch").performTvClick()
        awaitOpen(fixture, 21)

        fixture.plugin("episode-source", "screen:navigate-to", JSONObject().put("path", "/entry?id=22&tab=episodeTab%3Aepisode-source"))
        awaitOpen(fixture, 22)
        fixture.collection(JSONObject("""{"episodes":[{"episodeNumber":91,"episodeTitle":"Late unidentified episode"}]}"""))
        awaitText("The plugin response has no reliable title identity. Native TV cannot safely show these episodes. Refresh or choose another source.")
        compose.onNodeWithTag("plugin-episode-91").assertDoesNotExist()

        fixture.collection(JSONObject("""{"episodes":[{"episodeNumber":92,"baseAnime":{"id":21}}],"metadata":{"mappings":{"anilistId":21}}}"""))
        awaitText("The plugin returned episodes for a different title. Refresh or choose another source.")
        compose.onNodeWithTag("plugin-episode-92").assertDoesNotExist()

        val matching = JSONObject("""{"episodes":[{"episodeNumber":4,"episodeTitle":"Current title episode","aniDBEpisode":"4"}],"metadata":{"mappings":{"anilistId":22}}}""")
        fixture.collection(matching)
        awaitTag("plugin-episode-4")
        // A later ambiguous reply also clears an already rendered collection, including its actions.
        fixture.collection(JSONObject("""{"episodes":[]}"""))
        awaitText("The plugin response has no reliable title identity. Native TV cannot safely show these episodes. Refresh or choose another source.")
        compose.onNodeWithTag("plugin-episode-4").assertDoesNotExist()
        assertFalse(fixture.received.any { it.optString("type") == "anime:entry-episode-tab:select-episode" })
        NativeScreenshotEvidence.capture("plugin-ambiguous-late-collection-rejected")

        fixture.collection(matching)
        awaitTag("plugin-episode-4")
        compose.onNodeWithTag("plugin-episode-4").performScrollTo().performTvClick()
        compose.waitUntil(10_000) { fixture.received.any { it.optString("type") == "anime:entry-episode-tab:select-episode" } }
        val selected = fixture.received.single { it.optString("type") == "anime:entry-episode-tab:select-episode" }.getJSONObject("payload")
        assertEquals(22L, selected.getLong("mediaId"))
        assertEquals(4, selected.getInt("episodeNumber"))
        assertEquals(0, fixture.playRequests.get())
    }

    private fun awaitOpen(fixture: PluginFixture, mediaId: Long) = compose.waitUntil(10_000) {
        fixture.received.any { it.optString("type") == "anime:entry-episode-tab:open" && it.getJSONObject("payload").optLong("mediaId") == mediaId }
    }

    private fun awaitAction(fixture: PluginFixture, id: String): JSONObject {
        compose.waitUntil(10_000) { fixture.received.any { it.optString("type") == "action:clicked" && it.getJSONObject("payload").optString("actionId") == id } }
        return fixture.received.last { it.optString("type") == "action:clicked" && it.getJSONObject("payload").optString("actionId") == id }
    }
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun fixture(test: (PluginFixture) -> Unit) {
        val fixture = PluginFixture()
        val server = MockWebServer().apply { dispatcher = fixture; start(InetAddress.getByName("127.0.0.1"), 0) }
        val api = SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        try {
            compose.setContent { SeanimeTheme { SeanimeTvApp(SeanimeRepository(api), SeanimeJson.status(fixture.status()), { fixture.playRequests.incrementAndGet() }, {}, {}) } }
            api.connectEvents()
            compose.waitUntil(10_000) { fixture.socket.get() != null }
            test(fixture)
        } finally { api.close(); server.shutdown() }
    }

    private class PluginFixture : Dispatcher() {
        val socket = AtomicReference<WebSocket?>()
        val received = CopyOnWriteArrayList<JSONObject>()
        val playRequests = AtomicInteger()
        @Volatile var emptyEpisodes = false
        @Volatile var holdEpisodeCollections = false
        private fun media(id: Long = 21L) = JSONObject("""{"title":"Plugin fixture","custom":"kept"}""").put("id", id)
        private fun episode(id: Long = 21L) = JSONObject("""{"episodeNumber":3,"aniDBEpisode":"S2","episodeTitle":"Bonus episode","isDownloaded":true,"localFile":{"path":"/fixture/episode.mkv"},"custom":{"x":7}}""")
            .put("baseAnime", JSONObject().put("id", id))
        fun status() = JSONObject("""{"version":"fixture","serverReady":true,"settings":{"library":{"enableOnlinestream":true,"defaultPlaybackSource":"ext:episode-source"}},"user":{"isSimulated":true}}""")
        fun serverEvent(type: String, payload: Any) { check(socket.get()!!.send(JSONObject().put("type", type).put("payload", payload).toString())) }
        fun plugin(id: String, type: String, payload: JSONObject = JSONObject()) = serverEvent("plugin", pluginEventEnvelope(id, type, payload))
        fun tabs() = plugin("episode-source", "anime:entry-episode-tabs:updated", JSONObject().put("tabs", JSONArray().put(JSONObject().put("name", "Bonus episodes"))))
        fun collection(value: JSONObject) = plugin("episode-source", "anime:entry-episode-tab:episode-collection", JSONObject().put("episodeCollection", value))
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            if (path.startsWith("/events?")) return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { socket.set(webSocket) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val envelope = JSONObject(text)
                    if (envelope.optString("type") != "plugin") return
                    val event = envelope.getJSONObject("payload"); received.add(event)
                    val kind = NativePluginActionKind.entries.firstOrNull { it.request == event.optString("type") }
                    if (kind != null) {
                        val items = JSONArray()
                        fun action(id: String, type: String? = null, mediaFor: String? = null, disabled: Boolean = false) {
                            items.put(JSONObject().put("id", id).put("label", "$id action").put("disabled", disabled).apply { type?.let { put("type", it) }; mediaFor?.let { put("for", it) } })
                        }
                        when (kind) {
                            NativePluginActionKind.ANIME_LIBRARY -> action("library")
                            NativePluginActionKind.MEDIA_CARD -> { action("media", mediaFor = "anime"); action("blocked", mediaFor = "both", disabled = true); action("manga-only", mediaFor = "manga") }
                            NativePluginActionKind.ANIME_PAGE_BUTTON -> action("page")
                            NativePluginActionKind.EPISODE_CARD -> action("episode", type = "library")
                            NativePluginActionKind.EPISODE_GRID -> action("grid", type = "episodeTab:episode-source")
                            else -> Unit
                        }
                        plugin("actions-plugin", kind.update, JSONObject().put(kind.field, items))
                    }
                    when (event.optString("type")) {
                        "anime:entry-episode-tabs:render" -> tabs()
                        "anime:entry-episode-tab:open" -> if (!holdEpisodeCollections) {
                            val id = event.getJSONObject("payload").getLong("mediaId")
                            collection(JSONObject().put("episodes", JSONArray().apply { if (!emptyEpisodes) put(episode(id)) })
                                .put("metadata", JSONObject().put("mappings", JSONObject().put("anilistId", id))))
                        }
                    }
                }
            })
            val data: Any = when {
                path == "/api/v1/status" -> status()
                path == "/api/v1/library/collection" -> JSONArray().put(media())
                path.startsWith("/api/v1/library/anime-entry/") -> {
                    val id = path.substringAfterLast('/').toLong()
                    JSONObject().put("media", media(id)).put("episodes", JSONArray().put(episode(id)))
                }
                path == "/api/v1/extensions/list/anime-entry-episode-tabs" -> JSONArray().put(JSONObject().put("id", "episode-source").put("name", "Fixture source").put("tabName", "Bonus episodes"))
                path.startsWith("/api/v1/extensions/list/") -> JSONArray()
                else -> JSONObject()
            }
            return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("data", data).toString())
        }
    }
}
