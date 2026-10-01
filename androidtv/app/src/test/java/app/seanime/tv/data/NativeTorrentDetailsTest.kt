package app.seanime.tv.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NativeTorrentDetailsTest {
    private fun raw(hash: String = "owned-hash") = JSONObject().put("torrent", JSONObject().put("hash", hash).put("name", "Owned release")
        .put("destination", "/owned/library").put("queueIndex", 1).put("paused", false).put("forceStart", false).put("sequential", false)
        .put("length", 4_294_967_297L).put("completed", 10L))
        .put("files", JSONArray().put(JSONObject().put("index", 0).put("path", "Season/Episode.mkv").put("length", 4_294_967_297L).put("priority", 1)))
        .put("trackers", JSONArray().put("udp://tracker.example:80/announce")).put("peers", JSONArray())

    @Test fun detailsRequireExactHashAndStableUniqueFileIdentities() {
        assertEquals(4_294_967_297L, parseNativeTorrentDetails(raw(), "owned-hash").files.single().length)
        assertTrue(runCatching { parseNativeTorrentDetails(raw("other"), "owned-hash") }.isFailure)
        val duplicate = raw().apply { getJSONArray("files").put(getJSONArray("files").getJSONObject(0)) }
        assertTrue(runCatching { parseNativeTorrentDetails(duplicate, "owned-hash") }.isFailure)
        val missingIndex = raw().apply { getJSONArray("files").getJSONObject(0).remove("index") }
        assertTrue(runCatching { parseNativeTorrentDetails(missingIndex, "owned-hash") }.isFailure)
    }

    @Test fun typedActionsPreserveFalseZeroAndExactRegisteredFields() {
        val file = parseNativeTorrentDetails(raw(), "owned-hash").files.single()
        val cases = listOf(
            NativeTorrentCommand.ForceStart(false) to "force-start",
            NativeTorrentCommand.Sequential(false) to "set-sequential",
            NativeTorrentCommand.Queue(true) to "queue-up", NativeTorrentCommand.Queue(false) to "queue-down",
            NativeTorrentCommand.Recheck to "recheck", NativeTorrentCommand.Reannounce to "reannounce",
            NativeTorrentCommand.FilePriority(file, 0) to "set-file-priority",
            NativeTorrentCommand.AddTracker(" https://tracker.example/announce ") to "add-tracker",
            NativeTorrentCommand.RemoveTracker("udp://tracker.example:80/announce") to "remove-tracker",
        )
        cases.forEach { (command, name) ->
            val body = command.payload("owned-hash")
            assertEquals(name, body.getString("action")); assertEquals("owned-hash", body.getString("hash"))
            when (command) {
                is NativeTorrentCommand.ForceStart, is NativeTorrentCommand.Sequential -> { assertFalse(body.getBoolean("value")); assertEquals(3, body.length()) }
                is NativeTorrentCommand.FilePriority -> { assertEquals(0, body.getInt("index")); assertEquals(0, body.getInt("priority")); assertEquals(4, body.length()) }
                is NativeTorrentCommand.AddTracker, is NativeTorrentCommand.RemoveTracker -> assertEquals(3, body.length())
                else -> assertEquals(2, body.length())
            }
        }
        assertTrue(runCatching { NativeTorrentCommand.FilePriority(file, 3).payload("owned-hash") }.isFailure)
        listOf("file:///tmp/a", "https://user:password@tracker.example/a", "udp://tracker.example/a#fragment", "not a URL").forEach {
            assertTrue(runCatching { nativeTrackerUrl(it) }.isFailure)
        }
    }

    @Test fun priorityMutationUsesFreshExactFileAndVerifiesReadback() = runBlocking {
        fixture { repo, state, requests, posts ->
            val reviewed = parseNativeTorrentDetails(raw(), "owned-hash")
            val result = applyNativeTorrentCommand(repo, reviewed, NativeTorrentCommand.FilePriority(reviewed.files.single(), 0))
            assertEquals(0, result.details.files.single().priority)
            assertEquals("File priority updated.", result.message)
            assertEquals(listOf("GET /api/v1/settings", "GET /api/v1/torrent-client/details?hash=owned-hash", "POST /api/v1/torrent-client/action", "GET /api/v1/torrent-client/details?hash=owned-hash"), requests.toList())
            assertEquals(0, posts.single().getInt("index")); assertEquals(0, posts.single().getInt("priority"))
            assertEquals(4_294_967_297L, state.getJSONArray("files").getJSONObject(0).getLong("length"))
            // A lost response is reconciled from the current desired state, without a repeated mutation.
            applyNativeTorrentCommand(repo, reviewed, NativeTorrentCommand.FilePriority(reviewed.files.single(), 0))
            assertEquals(1, posts.size)
        }
    }

    @Test fun staleFileOrTorrentAndChangedClientCannotReachMutation() = runBlocking {
        fixture { repo, state, _, posts ->
            val reviewed = parseNativeTorrentDetails(raw(), "owned-hash")
            state.getJSONArray("files").getJSONObject(0).put("path", "Different.mkv")
            assertTrue(runCatching { applyNativeTorrentCommand(repo, reviewed, NativeTorrentCommand.FilePriority(reviewed.files.single(), 2)) }.exceptionOrNull()?.message.orEmpty().contains("selected file changed"))
            state.getJSONObject("torrent").put("name", "Renamed elsewhere")
            assertTrue(runCatching { applyNativeTorrentCommand(repo, reviewed, NativeTorrentCommand.ForceStart(true)) }.isFailure)
            assertTrue(posts.isEmpty())
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"torrent":{"defaultTorrentClient":"qbittorrent"}}}"""))
            SeanimeApiClient(server.url("/").toString()).use { client ->
                assertTrue(runCatching { applyNativeTorrentCommand(SeanimeRepository(client), parseNativeTorrentDetails(raw(), "owned-hash"), NativeTorrentCommand.Recheck) }.isFailure)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun unchangedReadbackIsNotReportedAsSuccessAndRecheckOnlyClaimsRequested() = runBlocking {
        fixture(apply = false) { repo, _, _, posts ->
            val reviewed = parseNativeTorrentDetails(raw(), "owned-hash")
            val failure = runCatching { applyNativeTorrentCommand(repo, reviewed, NativeTorrentCommand.Sequential(true)) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("refreshed state does not match"))
            val recheck = applyNativeTorrentCommand(repo, reviewed, NativeTorrentCommand.Recheck)
            assertEquals("recheck", posts.last().getString("action"))
            assertTrue(recheck.message.startsWith("Recheck requested.")); assertTrue(recheck.message.contains("does not confirm it has finished"))
        }
    }

    @Test fun trackerAndQueueActionsUseFreshReadbackWithoutChangingHash() = runBlocking {
        fixture { repo, _, _, posts ->
            var current = parseNativeTorrentDetails(raw(), "owned-hash")
            listOf(NativeTorrentCommand.ForceStart(true), NativeTorrentCommand.Sequential(true), NativeTorrentCommand.Queue(true),
                NativeTorrentCommand.Queue(false), NativeTorrentCommand.AddTracker("https://new.example/announce"), NativeTorrentCommand.RemoveTracker("https://new.example/announce"),
                NativeTorrentCommand.Reannounce).forEach { current = applyNativeTorrentCommand(repo, current, it).details }
            assertTrue(current.forceStart); assertTrue(current.sequential); assertEquals(1, current.queueIndex)
            assertEquals(listOf("udp://tracker.example:80/announce"), current.trackers)
            assertTrue(posts.all { it.getString("hash") == "owned-hash" })
        }
    }

    @Test fun sessionLimitsValidateBeforeRequestAndDoNotPretendSettingsReadback() = runBlocking {
        fixture { repo, _, requests, posts ->
            listOf("-1", "1.5", "2147483647", "").forEach { assertTrue(runCatching { applyNativeTorrentLimits(repo, it, "0") }.isFailure) }
            assertTrue(requests.isEmpty())
            applyNativeTorrentLimits(repo, "2048", "0")
            assertEquals(listOf("GET /api/v1/settings", "POST /api/v1/torrent-client/action"), requests.toList())
            assertEquals(2048, posts.single().getInt("downloadLimit")); assertEquals(0, posts.single().getInt("uploadLimit"))
            assertFalse(posts.single().has("hash")); assertEquals(3, posts.single().length())
        }
    }

    private suspend fun fixture(apply: Boolean = true, block: suspend (SeanimeRepository, JSONObject, CopyOnWriteArrayList<String>, CopyOnWriteArrayList<JSONObject>) -> Unit) {
        val state = raw(); val requests = CopyOnWriteArrayList<String>(); val posts = CopyOnWriteArrayList<JSONObject>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += "${request.method} ${request.path}"
                    val data: Any = when (request.requestUrl!!.encodedPath) {
                        "/api/v1/settings" -> JSONObject().put("torrent", JSONObject().put("defaultTorrentClient", "seanime"))
                        "/api/v1/torrent-client/details" -> state
                        else -> {
                            val body = JSONObject(request.body.readUtf8()); posts += body
                            if (apply) when (body.getString("action")) {
                                "force-start" -> state.getJSONObject("torrent").put("forceStart", body.getBoolean("value"))
                                "set-sequential" -> state.getJSONObject("torrent").put("sequential", body.getBoolean("value"))
                                "set-file-priority" -> state.getJSONArray("files").getJSONObject(0).put("priority", body.getInt("priority"))
                                "queue-up" -> state.getJSONObject("torrent").put("queueIndex", 0)
                                "queue-down" -> state.getJSONObject("torrent").put("queueIndex", 1)
                                "add-tracker" -> state.getJSONArray("trackers").put(body.getString("tracker"))
                                "remove-tracker" -> state.put("trackers", JSONArray(listOf("udp://tracker.example:80/announce")))
                            }
                            true
                        }
                    }
                    return MockResponse().setBody(JSONObject().put("data", data).toString())
                }
            }
            SeanimeApiClient(server.url("/").toString()).use { client -> block(SeanimeRepository(client), state, requests, posts) }
        }
    }
}
