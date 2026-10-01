package app.seanime.tv.ui

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
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
import java.util.concurrent.atomic.AtomicReference

class NativePluginPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun globalTrayOpenRendersOnlyItsSurfaceAndBackRestoresTheOpener() = fixture { fixture ->
        focusOpener()
        fixture.send("tray-plugin", "tray:open", JSONObject().put("extensionId", "tray-plugin"))
        awaitText("Fixture tray action")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("plugin-panel-back") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Fixture tray action").performTvClick()
        awaitEvent(fixture, "tray-plugin", "handler:triggered")
        assertEquals("fixture-handler", fixture.events.last { it.optString("type") == "handler:triggered" }.getJSONObject("payload").getString("handlerName"))
        fixture.send("tray-plugin", "tray:open", JSONObject().put("extensionId", "tray-plugin"))
        synchronize(fixture, "duplicate-open-check")
        assertEquals(1, fixture.count("tray-plugin", "tray:opened"))
        assertEquals(0, fixture.count("tray-plugin", "command-palette:opened"))
        NativeScreenshotEvidence.capture("plugin-global-native-tray")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitClosed(fixture, "tray-plugin", "tray:closed")
        compose.onNodeWithTag("media-21").assertIsFocused()
    }

    @Test fun globalCommandsUseTheirOwnerAndIgnoreUnrelatedCloseRequests() = fixture { fixture ->
        focusOpener()
        // One batch cannot interleave a composition between open and the input round-trip.
        fixture.serverEvent("plugin", pluginEventEnvelope("", "plugin:batch-events", JSONObject().put("events", JSONArray()
            .put(pluginEventEnvelope("command-plugin", "command-palette:open"))
            .put(pluginEventEnvelope("command-plugin", "command-palette:set-input", JSONObject().put("value", "fixture")))
            .put(pluginEventEnvelope("command-plugin", "command-palette:get-input")))))
        awaitEvent(fixture, "command-plugin", "command-palette:input")
        assertEquals("fixture", fixture.events.single { it.optString("type") == "command-palette:input" }.getJSONObject("payload").getString("value"))
        awaitTag("plugin-command-fixture-command")
        compose.onNodeWithTag("plugin-command-fixture-command").performTvClick()
        awaitEvent(fixture, "command-plugin", "command-palette:item-selected")
        assertEquals("fixture-command", fixture.events.last { it.optString("type") == "command-palette:item-selected" }.getJSONObject("payload").getString("itemId"))
        assertEquals(0, fixture.count("command-plugin", "tray:opened"))
        fixture.send("other-plugin", "command-palette:close")
        fixture.send("command-plugin", "tray:close", JSONObject().put("extensionId", "command-plugin"))
        synchronize(fixture, "unrelated-close-check")
        compose.onNodeWithTag("plugin-command-fixture-command").assertExists()
        fixture.send("command-plugin", "command-palette:close")
        awaitClosed(fixture, "command-plugin", "command-palette:closed")
        compose.onNodeWithTag("media-21").assertIsFocused()
        fixture.send("", "tray:open", JSONObject().put("extensionId", "tray-plugin"))
        awaitText("Fixture tray action")
        fixture.serverEvent("plugin-unloaded", "tray-plugin")
        awaitClosed(fixture, "tray-plugin", "tray:closed")
        compose.onNodeWithTag("media-21").assertIsFocused()
    }

    @Test fun relativeAnchorItemsNavigateNativelyAndHandlerLinksKeepTheirCallbackContract() = fixture { fixture ->
        fixture.trayComponents.set(JSONObject("""{"components":[
            {"id":"handled","type":"a","props":{"href":"/manga/entry?id=7","items":[{"type":"text","props":{"text":"Handled manga link"}}],"onClick":"link-handler"}},
            {"id":"unsupported","type":"anchor","props":{"href":"/arbitrary-dom","text":"Unsupported link"}},
            {"id":"manga","type":"a","props":{"href":"/manga/entry?id=7","items":[{"type":"text","props":{"text":"Read"}},"Fixture manga"]}}
        ]}"""))
        focusOpener()
        fixture.send("tray-plugin", "tray:open", JSONObject().put("extensionId", "tray-plugin"))
        awaitTag("plugin-link-handled")
        compose.onNodeWithTag("plugin-link-handled").assertTextContains("Handled manga link").performTvClick()
        awaitEvent(fixture, "tray-plugin", "handler:triggered")
        val callback = fixture.events.last { it.optString("type") == "handler:triggered" }.getJSONObject("payload")
        assertEquals("link-handler", callback.getString("handlerName"))
        assertEquals(setOf("href"), callback.getJSONObject("event").keys().asSequence().toSet())
        compose.onNodeWithTag("plugin-presentation").assertExists()
        compose.onNodeWithTag("plugin-link-unsupported").performScrollTo().performTvClick()
        awaitText("This screen has no native TV adapter. Arbitrary webview and browser DOM pages cannot open here.")
        compose.onNodeWithTag("plugin-presentation").assertExists()
        compose.onNodeWithTag("plugin-link-manga").performScrollTo().assertTextContains("Read Fixture manga").performTvClick()
        awaitClosed(fixture, "tray-plugin", "tray:closed")
        awaitText("Manga fixture 7")
        synchronize(fixture, "anchor-navigation-check")
        val screen = fixture.events.last { it.optString("extensionId") == "anchor-navigation-check" }.getJSONObject("payload")
        assertEquals("/manga/entry", screen.getString("pathname"))
        assertEquals("?id=7", screen.getString("query"))
    }

    private fun focusOpener() { awaitTag("media-21"); compose.onNodeWithTag("media-21").performSemanticsAction(SemanticsActions.RequestFocus); compose.onNodeWithTag("media-21").assertIsFocused() }
    private fun awaitText(value: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitTag(value: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(value).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitEvent(fixture: PluginFixture, id: String, type: String) = compose.waitUntil(10_000) { fixture.count(id, type) > 0 }
    private fun awaitClosed(fixture: PluginFixture, id: String, type: String) = compose.waitUntil(10_000) {
        fixture.count(id, type) == 1 && compose.onAllNodesWithTag("plugin-presentation").fetchSemanticsNodes().isEmpty()
    }
    private fun synchronize(fixture: PluginFixture, id: String) { fixture.send(id, "screen:get-current"); awaitEvent(fixture, id, "screen:changed") }
    private fun fixture(test: (PluginFixture) -> Unit) {
        val fixture = PluginFixture()
        val server = MockWebServer().apply { dispatcher = fixture; start(InetAddress.getByName("127.0.0.1"), 0) }
        val api = SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        try {
            val status = SeanimeJson.status(JSONObject("""{"serverReady":true,"settings":{"library":{}},"user":{"isSimulated":true}}"""))
            compose.setContent { SeanimeTheme { SeanimeTvApp(SeanimeRepository(api), status, {}, {}, {}) } }
            api.connectEvents()
            compose.waitUntil(10_000) { fixture.socket.get() != null && fixture.events.any { it.optString("type") == "screen:changed" } }
            test(fixture)
        } finally { api.close(); server.shutdown() }
    }

    private class PluginFixture : Dispatcher() {
        val socket = AtomicReference<WebSocket?>()
        val events = CopyOnWriteArrayList<JSONObject>()
        val trayComponents = AtomicReference(JSONObject("""{"components":[{"type":"button","props":{"label":"Fixture tray action","onClick":"fixture-handler"}}]}"""))
        fun count(id: String, type: String) = events.count { it.optString("extensionId") == id && it.optString("type") == type }
        fun serverEvent(type: String, payload: Any) { check(socket.get()!!.send(JSONObject().put("type", type).put("payload", payload).toString())) }
        fun send(id: String, type: String, payload: JSONObject = JSONObject()) = serverEvent("plugin", pluginEventEnvelope(id, type, payload))
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            if (path.startsWith("/events?")) return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { socket.set(webSocket) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val envelope = JSONObject(text)
                    if (envelope.optString("type") != "plugin") return
                    val event = envelope.getJSONObject("payload"); events.add(event)
                    when (event.optString("type")) {
                        "tray:render" -> send(event.getString("extensionId"), "tray:updated", trayComponents.get())
                        "command-palette:render" -> send(event.getString("extensionId"), "command-palette:updated", JSONObject("""{"items":[{"id":"fixture-command","label":"Fixture command","value":"fixture","filterType":"includes"}]}"""))
                    }
                }
            })
            val data: Any = when (path) {
                "/api/v1/library/collection" -> JSONArray().put(JSONObject().put("id", 21).put("title", "Plugin opener"))
                "/api/v1/extensions/all" -> JSONObject("""{"extensions":[{"id":"tray-plugin","name":"Tray fixture","type":"plugin"},{"id":"command-plugin","name":"Commands fixture","type":"plugin"}]}""")
                "/api/v1/status" -> JSONObject().put("serverReady", true)
                "/api/v1/manga/entry/7" -> JSONObject().put("media", JSONObject().put("id", 7).put("title", "Manga fixture 7"))
                "/api/v1/extensions/list/manga-provider", "/api/v1/manga/downloaded-chapters/7" -> JSONArray()
                else -> JSONObject()
            }
            return MockResponse().setBody(JSONObject().put("data", data).toString())
        }
    }
}
