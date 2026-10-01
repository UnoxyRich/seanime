package app.seanime.tv

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.platform.NativePlaybackCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Exercise actual coordinator request/event construction without providers, Go data or a decoder. */
class NativePlaybackMediaIdentityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun customMediaIdsRemainExactInPlaylistContinuityAndPlaybackStateCommands() {
        val requests = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request.path.orEmpty() to JSONObject(request.body.readUtf8()))
                    return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":true}""")
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val api = SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        val socket = RecordingSocket()
        field(api, "socket").set(api, socket)
        var owner: NativePlaybackCoordinator? = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            compose.setContent { }
            instrumentation.runOnMainSync {
                owner = NativePlaybackCoordinator(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single(), api)
            }
            val coordinator = requireNotNull(owner)
            for (id in listOf(2_147_483_648L, 4_294_967_297L, 9_007_199_254_740_991L)) {
                val episode = JSONObject().put("baseAnime", JSONObject().put("id", id)).put("episodeNumber", 3)
                    .put("progressNumber", 3).put("aniDBEpisode", "3").put("localFile", JSONObject().put("path", "/owned-fixture/episode.mkv"))
                for ((kind, route) in listOf("torrent" to "/api/v1/torrentstream/start", "debrid" to "/api/v1/debrid/stream/start", "nakama" to "/api/v1/nakama/play")) {
                    var job: Job? = null
                    instrumentation.runOnMainSync {
                        job = method("playPlaylistItem", JSONObject::class.java).invoke(coordinator,
                            JSONObject().put("episode", episode).put("watchType", kind)) as Job
                    }
                    runBlocking { withTimeout(10_000) { requireNotNull(job).join() } }
                    val wire = requests.last { it.first == route }.second
                    assertEquals("$kind truncated custom media ID", id, wire.getLong("mediaId"))
                    if (kind != "nakama") assertEquals(3, wire.getInt("episodeNumber"))
                }
                val source = "https://video.example/$id.mp4"
                instrumentation.runOnMainSync {
                    field(coordinator, "info").set(coordinator, JSONObject().put("id", "playback-$id").put("streamUrl", source)
                        .put("media", JSONObject().put("id", id)).put("episode", episode).put("playbackType", "onlinestream"))
                    field(coordinator, "expectedPlaybackUrl").set(coordinator, source)
                    field(coordinator, "watchContinuityEnabled").setBoolean(coordinator, true)
                    method("saveContinuity", JSONObject::class.java).invoke(coordinator,
                        JSONObject().put("positionMs", 12_000L).put("durationMs", 120_000L))
                    method("handleCommand", String::class.java, Any::class.java).invoke(coordinator, "get-playback-state", null)
                }
                compose.waitUntil(10_000) { requests.any { it.first == "/api/v1/continuity/item" && it.second.getJSONObject("options").getLong("mediaId") == id } }
                val state = socket.events.last { it.getJSONObject("payload").optString("type") == "video-playback-state" }
                    .getJSONObject("payload").getJSONObject("payload").getJSONObject("state").getJSONObject("playbackInfo")
                assertEquals(id, state.getJSONObject("media").getLong("id"))
                assertEquals(id, state.getJSONObject("episode").getJSONObject("baseAnime").getLong("id"))
                assertEquals("playback-$id", state.getString("id"))
            }
        } finally { instrumentation.runOnMainSync { owner?.close() }; api.close(); server.shutdown() }
    }

    private fun field(value: Any, name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun method(name: String, vararg types: Class<*>) = NativePlaybackCoordinator::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
    private class RecordingSocket : WebSocket {
        val events = CopyOnWriteArrayList<JSONObject>()
        override fun request(): Request = Request.Builder().url("http://127.0.0.1/").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { events.add(JSONObject(text)); return true }
        override fun send(bytes: ByteString): Boolean = false
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }
}
