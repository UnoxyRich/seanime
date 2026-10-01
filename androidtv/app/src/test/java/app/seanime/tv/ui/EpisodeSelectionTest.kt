package app.seanime.tv.ui

import app.seanime.tv.data.Episode
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeJson
import app.seanime.tv.data.SeanimeRepository
import kotlinx.coroutines.runBlocking
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
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class EpisodeSelectionTest {
    @Test fun duplicateReleasesAndSpecialsRemainDistinct() {
        val main = Episode(1, "One", localPath = "/library/a.mkv", raw = JSONObject().put("type", "main"))
        val alternate = main.copy(localPath = "/library/b.mkv")
        val special = Episode(1, "Special one", aniDbEpisode = "S1", raw = JSONObject().put("type", "special"))
        assertEquals(3, listOf(main, alternate, special).map(::episodeIdentity).distinct().size)
        assertEquals(episodeIdentity(main), episodeIdentity(main.copy(title = "Renamed metadata")))
    }

    @Test fun unchangedSpecialRetainsItsRecordedIdentityProgressAndMetadata() = runBlocking {
        val special = SeanimeJson.episode(JSONObject("""{"episodeNumber":3,"aniDBEpisode":"S2","progressNumber":0,"type":"special","baseAnime":{"id":21},"custom":"kept"}"""))
        val resolved = resolveStreamEpisode(21, special, 3) { error("An already identified selection must not be replaced") }
        assertSame(special, resolved)
        assertEquals("S2", resolved.aniDbEpisode)
        assertEquals(0, resolved.progressNumber)
        assertEquals("kept", resolved.raw.getString("custom"))
    }

    @Test fun changedNumberAndPlaceholderUseServerEpisodeIdentity() = runBlocking {
        val serverEpisode = SeanimeJson.episode(JSONObject("""{"episodeNumber":4,"aniDBEpisode":"S3","progressNumber":0,"type":"special","baseAnime":{"id":21}}"""))
        val prior = SeanimeJson.episode(JSONObject("""{"episodeNumber":3,"aniDBEpisode":"3"}"""))
        assertSame(serverEpisode, resolveStreamEpisode(21, prior, 4) { listOf(serverEpisode) })
        assertSame(serverEpisode, resolveStreamEpisode(21, Episode(4, "Placeholder"), 4) { listOf(serverEpisode) })
    }

    @Test fun ambiguousMissingOrWrongTitleEpisodeMetadataStopsSelection() = runBlocking {
        val main = SeanimeJson.episode(JSONObject("""{"episodeNumber":4,"aniDBEpisode":"4","type":"main","baseAnime":{"id":21}}"""))
        val special = SeanimeJson.episode(JSONObject("""{"episodeNumber":4,"aniDBEpisode":"S3","type":"special","baseAnime":{"id":21}}"""))
        val wrongTitle = SeanimeJson.episode(JSONObject("""{"episodeNumber":4,"aniDBEpisode":"4","baseAnime":{"id":22}}"""))
        val unrecorded = SeanimeJson.episode(JSONObject("""{"episodeNumber":4}"""))
        listOf(emptyList(), listOf(main, special), listOf(wrongTitle), listOf(unrecorded), listOf(main, unrecorded)).forEach { rows ->
            assertTrue(runCatching { resolveStreamEpisode(21, Episode(3, "Original"), 4) { rows } }.isFailure)
        }
    }

    @Test fun torrentAndDebridRequestsCarryResolvedSpecialIdentityAndExplicitFileSelection() = runBlocking {
        val requests = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val special = SeanimeJson.episode(JSONObject("""{"episodeNumber":3,"aniDBEpisode":"S2","progressNumber":0,"type":"special","baseAnime":{"id":21}}"""))
        val changed = SeanimeJson.episode(JSONObject("""{"episodeNumber":4,"aniDBEpisode":"S3","progressNumber":0,"type":"special","baseAnime":{"id":21}}"""))
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path?.startsWith("/events?") == true -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) = Unit
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                    })
                    request.path == "/api/v1/anime/episode-collection/21" -> MockResponse().setBody(JSONObject().put("data",
                        JSONObject().put("episodes", JSONArray().put(changed.raw))).toString())
                    request.method == "POST" -> {
                        requests.add(request.path.orEmpty() to JSONObject(request.body.readUtf8()))
                        MockResponse().setBody("""{"data":true}""")
                    }
                    else -> MockResponse().setBody("""{"data":{"serverReady":true}}""")
                }
            }
            start()
        }
        val api = SeanimeApiClient(server.url("/").toString())
        try {
            val repo = SeanimeRepository(api)
            val torrent = SeanimeJson.torrent(JSONObject("""{"name":"Fixture release","infoHash":"fixture"}"""))
            val original = resolveStreamEpisode(21, special, 3) { repo.episodeCollection(21) }
            repo.startTorrentStream(21, original, torrent, fileIndex = 7, autoSelect = false)
            val resolved = resolveStreamEpisode(21, special, 4) { repo.episodeCollection(21) }
            repo.startDebridStream(21, resolved, torrent, fileId = "special-file", fileIndex = 8, autoSelect = false)
            assertEquals(listOf("/api/v1/torrentstream/start", "/api/v1/debrid/stream/start"), requests.map { it.first })
            assertEquals("S2", requests[0].second.getString("aniDBEpisode"))
            assertEquals(3, requests[0].second.getInt("episodeNumber"))
            assertEquals(7, requests[0].second.getInt("fileIndex"))
            assertEquals("S3", requests[1].second.getString("aniDBEpisode"))
            assertEquals(4, requests[1].second.getInt("episodeNumber"))
            assertEquals(8, requests[1].second.getInt("fileIndex"))
            assertEquals("special-file", requests[1].second.getString("fileId"))
            requests.forEach { (_, body) ->
                assertEquals(21, body.getInt("mediaId"))
                assertEquals("nativeplayer", body.getString("playbackType"))
                assertFalse(body.getBoolean("autoSelect"))
            }
        } finally { api.close(); server.shutdown() }
    }
}
