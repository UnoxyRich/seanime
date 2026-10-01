package app.seanime.tv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import app.seanime.tv.data.jsonObject
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Every mutation targets an owned HTTP fixture; retained Go state is never opened. */
class NativeOfflineAndRefreshTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localAccountDisablesOfflineModeAndAniListSynchronization() {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        fixture({ request -> requests.add(request); offlineResponse(request, simulated = true) }) { repo, render ->
            render { FeatureScreen(TvFeature.OFFLINE, repo, {}, {}) }
            awaitText("Local account")
            compose.onNodeWithText("Use offline mode").assertIsNotEnabled()
            scrollToText("Save latest metadata").assertIsNotEnabled()
            scrollToText("Upload local progress").assertIsNotEnabled()
            scrollToText("Your local account keeps local files and downloaded manga available. Connect AniList in Settings to use offline mode and AniList synchronization.").assertIsDisplayed()
            assertTrue(requests.all { it.method == "GET" })
        }
    }

    @Test fun connectedAccountCanToggleAndSyncUsingFreshStatus() {
        val offline = AtomicBoolean(false)
        val writes = CopyOnWriteArrayList<Pair<String, String>>()
        fixture({ request ->
            if (request.method == "POST") {
                val body = request.body.clone().readUtf8()
                writes.add(request.path.orEmpty() to body)
                if (request.path == "/api/v1/local/offline") offline.set(JSONObject(body).getBoolean("enabled"))
                envelope(true)
            } else offlineResponse(request, simulated = false, offline = offline.get())
        }) { repo, render ->
            render { FeatureScreen(TvFeature.OFFLINE, repo, {}, {}) }
            awaitEnabled("Use offline mode")
            compose.onNodeWithText("Use offline mode").performTvClick()
            awaitEnabled("Go online")
            assertTrue(offline.get())
            scrollToText("Save latest metadata").assertIsNotEnabled()
            scrollToText("Go online").performTvClick()
            awaitEnabled("Use offline mode")
            scrollToText("Save latest metadata").performTvClick()
            compose.waitUntil(10_000) { writes.size == 3 }
            awaitEnabled("Upload local progress")
            scrollToText("Upload local progress").performTvClick()
            compose.waitUntil(10_000) { writes.size == 4 }
            awaitEnabled("Upload local progress")
            assertEquals(listOf("/api/v1/local/offline", "/api/v1/local/offline", "/api/v1/local/local", "/api/v1/local/anilist"), writes.map { it.first })
            assertEquals(true, JSONObject(writes[0].second).getBoolean("enabled"))
            assertEquals(false, JSONObject(writes[1].second).getBoolean("enabled"))
        }
    }

    @Test fun accountDisconnectedAfterRenderingCannotUseCachedEnabledToggle() {
        val simulated = AtomicBoolean(false)
        val writes = CopyOnWriteArrayList<RecordedRequest>()
        fixture({ request ->
            if (request.method != "GET") writes.add(request)
            offlineResponse(request, simulated.get())
        }) { repo, render ->
            render { FeatureScreen(TvFeature.OFFLINE, repo, {}, {}) }
            awaitEnabled("Use offline mode")
            simulated.set(true)
            compose.onNodeWithText("Use offline mode").performTvClick()
            awaitText("Local account")
            compose.onNodeWithText("Use offline mode").assertIsNotEnabled()
            assertTrue(writes.isEmpty())
        }
    }

    @Test fun explorerRefreshInvalidatesServerTreeBeforeReadingNewFiles() {
        val refreshed = AtomicBoolean(false)
        val calls = CopyOnWriteArrayList<String>()
        fixture({ request ->
            calls.add("${request.method} ${request.path}")
            when (request.path) {
                "/api/v1/library/explorer/file-tree/refresh" -> { assertEquals("POST", request.method); refreshed.set(true); envelope(true) }
                "/api/v1/library/explorer/file-tree" -> envelope(jsonObject("root" to jsonObject("path" to ".", "children" to
                    JSONArray().put(jsonObject("name" to if (refreshed.get()) "New fixture.mkv" else "Old fixture.mkv", "path" to "/fixture/video.mkv", "kind" to "file")))))
                else -> error("Unexpected fixture route")
            }
        }) { repo, render ->
            render { LibraryTools(repo, {}, initialTab = "Explorer", onClose = {}) }
            awaitText("Old fixture.mkv")
            compose.onNodeWithText("Refresh").performTvClick()
            awaitText("New fixture.mkv")
            compose.onNodeWithText("Old fixture.mkv").assertDoesNotExist()
            assertEquals(listOf("GET /api/v1/library/explorer/file-tree", "POST /api/v1/library/explorer/file-tree/refresh", "GET /api/v1/library/explorer/file-tree"), calls.toList())
        }
    }

    @Test fun returningFromPlayerRefreshesSavedPlaylistCompletion() {
        val completed = AtomicBoolean(false)
        fixture({ request -> check(request.path == "/api/v1/playlists"); envelope(JSONArray().put(playlist(completed.get()))) }) { repo, render ->
            lateinit var owner: FixtureLifecycleOwner
            InstrumentationRegistry.getInstrumentation().runOnMainSync { owner = FixtureLifecycleOwner().apply { registry.currentState = Lifecycle.State.RESUMED } }
            render { CompositionLocalProvider(LocalLifecycleOwner provides owner) { FeatureScreen(TvFeature.PLAYLISTS, repo, {}, {}) } }
            awaitText("1 unwatched · 1 episodes")
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED; completed.set(true); owner.registry.currentState = Lifecycle.State.RESUMED }
            awaitText("0 unwatched · 1 episodes")
        }
    }

    @Test fun playlistEventUpdatesCompletionBeforeAsynchronousDatabaseSave() {
        val socket = AtomicReference<WebSocket>()
        fixture({ request -> when (request.path?.substringBefore('?')) {
            "/api/v1/status" -> envelope(jsonObject("version" to "fixture", "serverReady" to true))
            "/api/v1/playlists" -> envelope(JSONArray().put(playlist(false)))
            "/events" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { socket.set(webSocket) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            })
            else -> error("Unexpected fixture route")
        } }) { repo, render ->
            render { FeatureScreen(TvFeature.PLAYLISTS, repo, {}, {}) }
            awaitText("1 unwatched · 1 episodes")
            repo.client.connectEvents()
            compose.waitUntil(10_000) { socket.get() != null && repo.client.connected.value }
            socket.get().send(jsonObject("type" to "playlist", "payload" to jsonObject("type" to "current-playlist", "payload" to jsonObject("playlist" to playlist(true)))).toString())
            awaitText("0 unwatched · 1 episodes")
        }
    }

    private class FixtureLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private fun playlist(completed: Boolean) = jsonObject("dbId" to 7, "name" to "Fixture queue", "episodes" to JSONArray().put(
        jsonObject("watchType" to "localfile", "isCompleted" to completed, "episode" to jsonObject("baseAnime" to jsonObject("id" to 42), "episodeNumber" to 1))))
    private fun offlineResponse(request: RecordedRequest, simulated: Boolean, offline: Boolean = false): MockResponse = envelope(when (request.path) {
        "/api/v1/local/track" -> JSONArray()
        "/api/v1/local/storage/size" -> "0 B"
        "/api/v1/local/updated" -> false
        "/api/v1/local/queue" -> jsonObject("animeTasks" to JSONObject(), "mangaTasks" to JSONObject())
        "/api/v1/status" -> jsonObject("version" to "fixture", "serverReady" to true, "isOffline" to offline, "user" to jsonObject("isSimulated" to simulated))
        else -> error("Unexpected fixture route: ${request.method} ${request.path}")
    })
    private fun envelope(value: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(jsonObject("data" to value).toString())
    private fun awaitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitEnabled(text: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    private fun scrollToText(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }
    private fun fixture(respond: (RecordedRequest) -> MockResponse, block: (SeanimeRepository, (@Composable () -> Unit) -> Unit) -> Unit) {
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = respond(request) }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val api = SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        val visible = mutableStateOf(true)
        try {
            block(SeanimeRepository(api)) { content -> compose.setContent { SeanimeTheme { if (visible.value) content() } } }
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            api.close()
            server.shutdown()
        }
    }
}
