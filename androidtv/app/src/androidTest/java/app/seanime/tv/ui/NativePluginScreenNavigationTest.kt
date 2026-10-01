package app.seanime.tv.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
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

/** Screen requests originate on the real WebSocket while no plugin tray is open. */
class NativePluginScreenNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test @SdkSuppress(minSdkVersion = 28)
    fun globalPluginDeviceRequestsUseNativeViewportAndConfirmedClipboardWithEmptyDomResults() = fixture { fixture ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var original: ClipData? = null
        compose.runOnIdle { original = clipboard.primaryClip; clipboard.setPrimaryClip(ClipData.newPlainText("Owned fixture", "Before plugin")) }
        try {
            compose.waitUntil(10_000) { fixture.received.any { it.optString("extensionId").isEmpty() && it.optString("type") == "dom:viewport-size" } }
            fixture.send("dom:get-viewport-size", requester = "native-size")
            compose.waitUntil(10_000) { fixture.received.any { it.optString("extensionId") == "native-size" && it.optString("type") == "dom:viewport-size" } }
            val size = fixture.received.last { it.optString("extensionId") == "native-size" }.getJSONObject("payload")
            assertTrue(size.getInt("width") > size.getInt("height"))
            assertTrue(size.getInt("height") > 0)
            compose.onNodeWithTag("plugin-screen-notice-close").assertDoesNotExist()

            fixture.send("dom:clipboard:write", JSONObject().put("text", "Cancelled text"))
            awaitText("Copy plugin text")
            compose.onNodeWithTag("plugin-copy-cancel").assertIsFocused().performTvClick()
            compose.runOnIdle { assertEquals("Before plugin", clipboard.primaryClip!!.getItemAt(0).text.toString()) }
            fixture.send("dom:clipboard:write", JSONObject().put("text", "Native copy\n第二行"))
            awaitText("Copy plugin text")
            NativeScreenshotEvidence.capture("plugin-native-clipboard-confirmation")
            compose.onNodeWithTag("plugin-copy-confirm").performTvClick()
            compose.runOnIdle { assertEquals("Native copy\n第二行", clipboard.primaryClip!!.getItemAt(0).text.toString()) }
            compose.onNodeWithTag("plugin-copy-confirm").assertDoesNotExist()

            fixture.send("dom:query", JSONObject().put("requestId", "owned-query").put("selector", ".browser-element"))
            compose.waitUntil(10_000) { fixture.received.any { it.optString("type") == "dom:query-result" } }
            val query = fixture.received.last { it.optString("type") == "dom:query-result" }
            assertEquals("fixture-plugin", query.getString("extensionId"))
            assertEquals("owned-query", query.getJSONObject("payload").getString("requestId"))
            assertEquals(0, query.getJSONObject("payload").getJSONArray("elements").length())
            compose.onNodeWithTag("plugin-screen-notice-close").performTvClick()
            fixture.send("dom:query-one", JSONObject().put("requestId", "owned-one").put("selector", "body"))
            compose.waitUntil(10_000) { fixture.received.any { it.optString("type") == "dom:query-one-result" } }
            assertTrue(fixture.received.last { it.optString("type") == "dom:query-one-result" }.getJSONObject("payload").isNull("element"))
            compose.onNodeWithTag("plugin-screen-notice-close").assertDoesNotExist()
            assertEquals(0, fixture.platformRequests.get())
            assertFalse(fixture.received.any { it.optString("type") == "webview:loaded" })
        } finally {
            compose.runOnIdle { original?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() }
        }
    }

    @Test fun webSocketNavigationAndReloadCallbacksAlwaysRunOnAndroidMainThread() {
        val fixture = PluginFixture()
        val server = MockWebServer().apply { dispatcher = fixture; start(InetAddress.getByName("127.0.0.1"), 0) }
        val api = SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        val navigationThread = AtomicReference<Thread?>()
        val reloadThread = AtomicReference<Thread?>()
        val target = AtomicReference<NativePluginDestination?>()
        val repo = SeanimeRepository(api)
        try {
            compose.setContent {
                val screens = remember { NativePluginScreens() }
                NativePluginScreenBridge(repo, screens, onNavigate = {
                    target.set(it); navigationThread.set(Thread.currentThread())
                }, onReload = { reloadThread.set(Thread.currentThread()) })
            }
            api.connectEvents()
            compose.waitUntil(10_000) { fixture.socket.get() != null && fixture.received.any { it.optString("type") == "screen:changed" } }
            fixture.send("screen:navigate-to", JSONObject().put("path", "/settings?tab=debrid"))
            compose.waitUntil(10_000) { navigationThread.get() != null }
            assertSame(Looper.getMainLooper().thread, navigationThread.get())
            assertEquals("debrid", target.get()?.settingsSection)
            fixture.send("screen:reload")
            compose.waitUntil(10_000) { reloadThread.get() != null }
            assertSame(Looper.getMainLooper().thread, reloadThread.get())
        } finally { api.close(); server.shutdown() }
    }

    @Test fun globalPluginNavigationOpensSubpagesReportsManualBackAndReloadsTheActualScreen() = fixture { fixture ->
        fixture.send("screen:navigate-to", JSONObject().put("path", "/settings?tab=debrid"))
        awaitText("Debrid provider")
        assertCurrent(fixture, "settings-check", "/settings", "?tab=debrid")
        val requests = fixture.paths.count { it == "/api/v1/debrid/settings" }
        fixture.send("screen:reload")
        compose.waitUntil(10_000) { fixture.paths.count { it == "/api/v1/debrid/settings" } > requests }
        awaitText("Debrid provider")
        assertCurrent(fixture, "reload-check", "/settings", "?tab=debrid")
        compose.onNodeWithText("All settings").performTvClick()
        awaitText("Device & accounts")
        assertCurrent(fixture, "manual-navigation-check", "/settings", "")
        fixture.send("screen:navigate-to", JSONObject().put("path", "/entry?id=21&tab=onlinestream&episode=3"))
        awaitText("No enabled provider")
        compose.onNodeWithText("✓ Online").assertExists()
        compose.onNodeWithTag("source-episode-edit").assertTextEquals("Episode: 3")
        assertCurrent(fixture, "episode-check", "/entry", "?episode=3&id=21&tab=onlinestream")
        assertEquals(0, fixture.playRequests.get())
        pressBack()
        awaitText("Update list")
        assertCurrent(fixture, "details-back-check", "/entry", "?id=21")
        NativeScreenshotEvidence.capture("plugin-global-native-entry-navigation")
        assertTrue(fixture.received.any { it.optString("extensionId").isEmpty() && it.optString("type") == "screen:changed" && it.getJSONObject("payload").optString("pathname") == "/settings" })
        assertFalse(fixture.received.any { it.optString("type") == "tray:render" })
    }

    @Test fun filteredDiscoveryAndMangaEntryUseTypedTargetsAndUnsupportedRequestsStayNative() = fixture { fixture ->
        fixture.send("screen:navigate-to", JSONObject().put("path", "/search?genre=Sci-Fi&sorting=SCORE_DESC&year=2026&page=2"))
        compose.waitUntil(10_000) { fixture.searches.isNotEmpty() && compose.onAllNodesWithTag("discovery-grid").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, fixture.searches.last().getInt("page"))
        assertEquals("Sci-Fi", fixture.searches.last().getJSONArray("genres").getString(0))
        assertEquals(2026, fixture.searches.last().getInt("seasonYear"))
        val count = fixture.searches.size
        fixture.send("screen:reload")
        compose.waitUntil(10_000) { fixture.searches.size > count }
        assertEquals(2, fixture.searches.last().getInt("page"))
        assertEquals("SCORE_DESC", fixture.searches.last().getJSONArray("sort").getString(0))
        fixture.send("screen:navigate-to", JSONObject().put("path", "/manga/entry?id=7"))
        awaitText("Manga fixture 7")
        assertCurrent(fixture, "manga-check", "/manga/entry", "?id=7")
        fixture.send("screen:navigate-to", JSONObject().put("path", "https://example.com/"))
        awaitText("Plugin screen request")
        compose.onNodeWithTag("plugin-screen-notice-close").assertIsFocused().performTvClick()
        assertCurrent(fixture, "after-rejected-navigation", "/manga/entry", "?id=7")
        fixture.send("dom:query")
        awaitText("Plugin screen request")
        compose.onNodeWithTag("plugin-screen-notice-close").performTvClick()
        repeat(3) { fixture.send("dom:query") }
        assertCurrent(fixture, "after-repeated-dom", "/manga/entry", "?id=7")
        compose.onNodeWithTag("plugin-screen-notice-close").assertDoesNotExist()
        assertEquals(0, fixture.platformRequests.get())
        assertFalse(fixture.received.any { it.optString("type").startsWith("dom:") && it.optString("type") != "dom:viewport-size" })
    }

    private fun assertCurrent(fixture: PluginFixture, requester: String, pathname: String, query: String) {
        fixture.send("screen:get-current", requester = requester)
        compose.waitUntil(10_000) { fixture.received.any { it.optString("extensionId") == requester && it.optString("type") == "screen:changed" } }
        val response = fixture.received.last { it.optString("extensionId") == requester }.getJSONObject("payload")
        assertEquals(pathname, response.getString("pathname"))
        assertEquals(query, response.getString("query"))
    }

    private fun awaitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun pressBack() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }

    private fun fixture(test: (PluginFixture) -> Unit) {
        val fixture = PluginFixture()
        val server = MockWebServer().apply { dispatcher = fixture; start(InetAddress.getByName("127.0.0.1"), 0) }
        val api = SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        try {
            val status = SeanimeJson.status(JSONObject("""{"version":"fixture","serverReady":true,"settings":{"library":{}},"user":{"isSimulated":true}}"""))
            compose.setContent { SeanimeTheme { SeanimeTvApp(SeanimeRepository(api), status, { fixture.playRequests.incrementAndGet() }, { fixture.platformRequests.incrementAndGet() }, {}) } }
            api.connectEvents()
            val started = SystemClock.elapsedRealtime()
            var stage = "websocket connection"
            try {
                compose.waitUntil(10_000) { fixture.socket.get() != null && api.connected.value }
                Log.i("NativePluginStartup", "Connected after ${SystemClock.elapsedRealtime() - started}ms")
                stage = "spontaneous screen announcement"
                // Preserve the original total deadline and unsolicited announcement assertion.
                compose.waitUntil((10_000 - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(1)) {
                    fixture.received.any { it.optString("type") == "screen:changed" }
                }
            } catch (failure: Throwable) {
                Log.e("NativePluginStartup", "Failed during $stage after ${SystemClock.elapsedRealtime() - started}ms; " +
                    "socket=${fixture.socket.get() != null}, connected=${api.connected.value}; " +
                    "paths=${fixture.paths.map { it.substringBefore('?') }}; " +
                    "eventTypes=${fixture.received.map { it.optString("type") }}")
                throw failure
            }
            test(fixture)
        } finally { api.close(); server.shutdown() }
    }

    private class PluginFixture : Dispatcher() {
        val socket = AtomicReference<WebSocket?>()
        val received = CopyOnWriteArrayList<JSONObject>()
        val paths = CopyOnWriteArrayList<String>()
        val searches = CopyOnWriteArrayList<JSONObject>()
        val playRequests = AtomicInteger()
        val platformRequests = AtomicInteger()
        fun send(type: String, payload: JSONObject = JSONObject(), requester: String = "fixture-plugin") {
            check(socket.get()!!.send(JSONObject().put("type", "plugin").put("payload", pluginEventEnvelope(requester, type, payload)).toString()))
        }
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            paths.add(path)
            if (path.startsWith("/events?")) return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { socket.set(webSocket) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val envelope = JSONObject(text)
                    if (envelope.optString("type") == "plugin") envelope.optJSONObject("payload")?.let(received::add)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            })
            val data: Any = when {
                path == "/api/v1/status" -> JSONObject().put("serverReady", true)
                path == "/api/v1/library/collection" || path == "/api/v1/anilist/collection/raw" -> JSONObject().put("lists", JSONArray())
                path == "/api/v1/settings" -> JSONObject().put("library", JSONObject()).put("manga", JSONObject())
                path == "/api/v1/debrid/settings" -> JSONObject().put("enabled", false)
                path.startsWith("/api/v1/library/anime-entry/") -> JSONObject().put("media", JSONObject().put("id", 21).put("title", "Anime fixture 21")).put("episodes", JSONArray())
                path == "/api/v1/manga/entry/7" -> JSONObject().put("media", JSONObject().put("id", 7).put("title", "Manga fixture 7"))
                path.startsWith("/api/v1/extensions/list/") || path.startsWith("/api/v1/manga/downloaded-chapters/") -> JSONArray()
                path == "/api/v1/anilist/list-anime" -> {
                    val body = JSONObject(request.body.readUtf8()); searches.add(body)
                    JSONObject().put("Page", JSONObject().put("media", JSONArray().put(JSONObject().put("id", 42).put("title", "Discovery fixture")))
                        .put("pageInfo", JSONObject().put("currentPage", body.getInt("page")).put("hasNextPage", false).put("lastPage", 2)))
                }
                else -> JSONObject()
            }
            return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("data", data).toString())
        }
    }
}
