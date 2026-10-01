package app.seanime.tv.data

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

class NativeMediaIdentityTest {
    private val ids = listOf(2_147_483_648L, 4_294_967_297L, 9_007_199_254_740_991L)

    @Test fun safeMediaRangeIsLosslessAndInvalidValuesNeverWrapIntoAnotherTitle() {
        ids.forEach { id ->
            assertEquals(id, parseNativeMediaId(id.toString()))
            assertEquals(id, JSONObject("{\"id\":$id}").optMediaId())
            assertEquals(id, JSONObject().put("id", id.toString()).optMediaId())
            assertEquals(id, SeanimeJson.media(JSONObject("{\"id\":$id,\"title\":\"Custom\"}")).id)
        }
        listOf("0", "-1", "9007199254740992", "18446744073709551617", "1.5", "broken").forEach { value ->
            assertNull(value, parseNativeMediaId(value))
        }
        assertEquals(0L, JSONObject().put("id", 1.5).optMediaId())
        assertEquals(0L, JSONObject().put("id", 9_007_199_254_740_992L).optMediaId())
    }

    @Test fun streamCollectionKeepsHighIdsAndTheirSeparateListDataKeys() {
        val allIds = listOf(1L) + ids
        val anime = JSONArray(allIds.map { jsonObject("id" to it, "title" to "Title $it") })
        val listData = JSONObject().apply { allIds.forEachIndexed { index, id -> put(id.toString(), jsonObject("status" to "CURRENT", "progress" to index)) } }
        val collection = SeanimeJson.collection(jsonObject("stream" to jsonObject("anime" to anime, "listData" to listData)))
        assertEquals(allIds, collection.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), collection.map { it.progress })
        assertEquals(4, collection.distinctBy { it.id }.size)
    }

    @Test fun highMediaIdsReachExactRoutesAndNumericListOfflineMangaAndStreamPayloads() = runBlocking {
        val bodies = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val routes = CopyOnWriteArrayList<String>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    routes.add(path)
                    if (path == "/events") return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) = Unit
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                    })
                    val data: Any = if (request.method == "POST" || request.method == "DELETE") {
                        val body = JSONObject(request.body.readUtf8()); bodies.add(path to body)
                        when (path) {
                            "/api/v1/onlinestream/episode-list" -> jsonObject("episodes" to JSONArray())
                            "/api/v1/onlinestream/episode-source" -> jsonObject("videoSources" to JSONArray())
                            else -> true
                        }
                    } else {
                        val id = path.substringAfterLast('/').toLongOrNull() ?: 1L
                        when {
                            path.startsWith("/api/v1/playlist/episodes/") -> JSONArray()
                            path.startsWith("/api/v1/anime/episode-collection/") -> jsonObject("episodes" to JSONArray())
                            else -> jsonObject("media" to jsonObject("id" to id, "title" to "Title $id"), "episodes" to JSONArray())
                        }
                    }
                    return MockResponse().setBody(jsonObject("data" to data).toString())
                }
            }
            server.start()
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val repo = SeanimeRepository(api)
                ids.forEach { id ->
                    assertEquals(id, repo.animeDetails(id).media.id)
                    assertEquals(id, repo.mangaDetails(id).media.id)
                    repo.episodeCollection(id); repo.playlistEpisodes(id)
                    repo.editListEntry(id, "CURRENT", 3); repo.deleteListEntry(id, manga = true)
                    repo.trackOffline(id); repo.downloadMangaChapters(id, "fixture", listOf("chapter-3"))
                    repo.updateMangaProgress(id, 3); repo.onlineEpisodes(id, "fixture"); repo.onlineSources(id, 3, "fixture")
                    val episode = Episode(3, "Special", aniDbEpisode = "S2", progressNumber = 0,
                        raw = jsonObject("episodeNumber" to 3, "aniDBEpisode" to "S2", "baseAnime" to jsonObject("id" to id)))
                    repo.startTorrentStream(id, episode, fileIndex = 4, autoSelect = false)
                    repo.startDebridStream(id, episode, fileId = "special", fileIndex = 4, autoSelect = false)
                }
            }
            ids.forEach { id ->
                listOf("library/anime-entry", "manga/entry", "anime/episode-collection", "playlist/episodes").forEach { route ->
                    assertTrue("Missing full ID route $route/$id", routes.contains("/api/v1/$route/$id"))
                }
                listOf("anilist/list-entry", "manga/download-chapters", "manga/update-progress", "onlinestream/episode-list",
                    "onlinestream/episode-source", "torrentstream/start", "debrid/stream/start").forEach { route ->
                    val body = bodies.first { it.first == "/api/v1/$route" && it.second.optLong("mediaId") == id }.second
                    assertEquals(id, body.getLong("mediaId")); assertTrue(body.get("mediaId") is Number)
                }
                assertTrue(bodies.any { it.first == "/api/v1/local/track" && it.second.getJSONArray("media").getJSONObject(0).getLong("mediaId") == id })
            }
            bodies.filter { it.first.endsWith("stream/start") }.forEach { (_, body) ->
                assertEquals(3, body.getInt("episodeNumber")); assertEquals(4, body.getInt("fileIndex")); assertEquals("S2", body.getString("aniDBEpisode"))
            }
        }
    }

    @Test fun mangaPreferenceKeysAndEventsKeepAllMediaBits() {
        ids.forEach { id ->
            val values = NativeMangaReaderSettings(true, false, true).storedValues(id)
            assertTrue(values.containsKey("media:$id:rtl"))
            assertTrue(nativeMangaPreferenceEventAffects(jsonObject("mediaIds" to JSONArray().put(id)), id))
            assertFalse(nativeMangaPreferenceEventAffects(jsonObject("mediaIds" to JSONArray().put(id)), id.toInt().toLong()))
        }
    }
}
