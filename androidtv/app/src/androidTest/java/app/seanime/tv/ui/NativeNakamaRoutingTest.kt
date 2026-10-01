package app.seanime.tv.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NativeNakamaRoutingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun initialRefreshAndReconnectAdvertiseNativeWatchPartyPlayback() {
        val requests = CopyOnWriteArrayList<JSONObject>()
        val socket = AtomicReference<WebSocket?>()
        val connections = AtomicInteger()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path.orEmpty().startsWith("/events?")) return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { socket.set(webSocket); connections.incrementAndGet() }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val event = JSONObject(text)
                            if (event.optString("type") == "nakama-status-requested") requests.add(event.getJSONObject("payload"))
                        }
                    })
                    val data = if (request.path == "/api/v1/settings") JSONObject().put("nakama", JSONObject().put("enabled", true))
                        else JSONObject().put("serverReady", true)
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("data", data).toString())
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val api = SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        val repo = SeanimeRepository(api)
        try {
            compose.setContent { FeatureScreen(TvFeature.NAKAMA, repo, {}, {}) }
            api.connectEvents()
            compose.waitUntil(10_000) { requests.isNotEmpty() }
            val beforeRefresh = requests.size
            compose.onNodeWithText("Refresh status").performTvClick()
            compose.waitUntil(10_000) { requests.size > beforeRefresh }
            val beforeReconnect = requests.size
            check(requireNotNull(socket.get()).close(1001, "Fixture reconnect"))
            compose.waitUntil(10_000) { connections.get() >= 2 && requests.size > beforeReconnect }
            requests.forEach { payload ->
                assertEquals(api.clientId, payload.getString("clientId"))
                // The unchanged backend uses this flag to choose VideoCore for
                // file, torrent and debrid parties instead of an external player.
                assertTrue("Nakama routed native TV playback externally: $payload", payload.getBoolean("useDenshiPlayer"))
            }
        } finally { api.close(); server.shutdown() }
    }
}
