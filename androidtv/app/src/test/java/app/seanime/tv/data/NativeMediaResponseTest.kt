package app.seanime.tv.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMediaResponseTest {
    @Test fun legitimateAbsentCollectionsAndOmittedGoSlicesStayEmpty() {
        listOf(null, JSONObject.NULL, JSONObject(), JSONArray(),
            JSONObject("""{"MediaListCollection":null}"""),
            JSONObject("""{"MediaListCollection":{"lists":null}}"""),
            JSONObject("""{"lists":[{"entries":null}],"stream":{"anime":null}}""")).forEach {
            assertTrue(parsePersonalCollection(it).entries.isEmpty())
        }
        listOf("""{"Page":{}}""", """{"Page":{"media":null,"pageInfo":{"currentPage":2}}}""").forEach {
            assertTrue(AnimeDiscoveryPage.fromJson(JSONObject(it), 2).media.isEmpty())
        }
    }

    @Test fun malformedCollectionShapesAndEntriesCannotLookEmpty() {
        listOf<Any>("bad", true, 1, JSONObject("""{"lists":{}}"""),
            JSONObject("""{"MediaListCollection":"bad"}"""),
            JSONObject("""{"lists":[1]}"""), JSONObject("""{"lists":[{"entries":"bad"}]}"""),
            JSONObject("""{"lists":[{"entries":[{}]}]}"""),
            JSONObject("""{"stream":{"anime":[{"id":0}]}}"""),
            JSONArray("""[{"id":1},null]""")).forEach { raw ->
            assertTrue("Must reject $raw", runCatching { parsePersonalCollection(raw) }.exceptionOrNull() is ApiException)
        }
    }

    @Test fun discoveryRejectsMalformedCardsInsteadOfCreatingAnActionableZeroId() {
        listOf("""{"Page":{"media":[{}]}}""", """{"Page":{"media":[{"id":0}]}}""",
            """{"Page":{"media":[{"id":-1}]}}""", """{"Page":{"media":[null]}}""",
            """{"Page":{"media":["bad"]}}""", """{"Page":{"media":[],"pageInfo":"bad"}}""").forEach { raw ->
            listOf(false, true).forEach { manga ->
                assertTrue(raw, runCatching { AnimeDiscoveryPage.fromJson(JSONObject(raw), 1, manga) }.exceptionOrNull() is ApiException)
            }
        }
    }

    @Test fun sparseMediaAndLargeIdsRemainLosslessWithoutRequiringOptionalTitleFields() {
        val ids = listOf(2_147_483_648L, 4_294_967_297L, 9_007_199_254_740_991L)
        val media = JSONArray(ids.map { jsonObject("id" to it) })
        assertEquals(ids, parsePersonalCollection(media).entries.map { it.media.id })
        assertEquals(ids, AnimeDiscoveryPage.fromJson(jsonObject("Page" to jsonObject("media" to media)), 1).media.map { it.id })
        ids.forEach { id -> assertEquals(id, nativeResponseEntry(jsonObject("media" to jsonObject("id" to id)), id, "/entry").id) }
    }

    @Test fun detailApiRejectsMissingMismatchedOrMalformedMediaWithoutLosingValidEmptyEpisodes() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val repo = SeanimeRepository(api)
                val invalid = listOf("{}", """{"media":null}""", """{"media":"bad"}""",
                    """{"media":{"id":2}}""", """{"media":{"id":1},"episodes":{}}""",
                    """{"media":{"id":1},"episodes":[null]}""")
                invalid.forEach { raw ->
                    server.enqueue(MockResponse().setBody("{\"data\":$raw}"))
                    assertTrue(raw, runCatching { repo.animeDetails(1L) }.exceptionOrNull() is ApiException)
                    assertEquals("/api/v1/library/anime-entry/1", server.takeRequest().path)
                }
                server.enqueue(MockResponse().setBody("""{"data":{"media":{"id":1},"episodes":null}}"""))
                assertTrue(repo.animeDetails(1L).episodes.isEmpty())
                server.takeRequest()
                server.enqueue(MockResponse().setBody("""{"data":{}}"""))
                assertTrue(runCatching { repo.mangaDetails(1L) }.exceptionOrNull() is ApiException)
                assertEquals("/api/v1/manga/entry/1", server.takeRequest().path)
            }
        }
    }
}
