package app.seanime.tv.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class AnimeDiscoveryTest {
    @Test fun blankSearchRequestsTrendingWithoutCollectionFallback() = runBlocking {
        withRepository { server, repo ->
            server.enqueue(MockResponse().setBody("""{"data":{"Page":{"pageInfo":{"currentPage":1,"hasNextPage":true,"lastPage":12,"total":277},"media":[{"id":42,"title":{"romaji":"Space fixture"}}]}}}"""))
            val page = repo.discoverAnime(AnimeDiscoveryFilters(search = "  "))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/anilist/list-anime", request.path)
            val body = JSONObject(request.body.readUtf8())
            assertEquals(1, body.getInt("page"))
            assertEquals(24, body.getInt("perPage"))
            assertEquals("TRENDING_DESC", body.getJSONArray("sort").getString(0))
            assertFalse(body.has("search"))
            assertFalse(body.getBoolean("isAdult"))
            assertFalse(body.has("countryOfOrigin"))
            assertEquals("Space fixture", page.media.single().title)
            assertTrue(page.hasNextPage)
            assertEquals(12, page.lastPage)
            assertEquals(277, page.total)
        }
    }

    @Test fun advancedFiltersAndPaginationUseExactExistingContract() = runBlocking {
        withRepository { server, repo ->
            server.enqueue(MockResponse().setBody("""{"data":{"Page":{"pageInfo":{"currentPage":3,"hasNextPage":false},"media":[]}}}"""))
            val filters = AnimeDiscoveryFilters(search = " Cowboy ", sort = "SCORE_DESC", statuses = listOf("FINISHED", "RELEASING"),
                genres = listOf("Action", "Sci-Fi"), tags = listOf("Space", "Time Travel"), averageScoreGreater = 80,
                season = "SPRING", seasonYear = 1998, format = "TV", isAdult = true)
            val page = repo.discoverAnime(filters, 3)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(3, body.getInt("page"))
            assertEquals("Cowboy", body.getString("search"))
            assertEquals("SCORE_DESC", body.getJSONArray("sort").getString(0))
            assertEquals(listOf("FINISHED", "RELEASING"), body.getJSONArray("status").let { (0 until it.length()).map(it::getString) })
            assertEquals("Sci-Fi", body.getJSONArray("genres").getString(1))
            assertEquals("Time Travel", body.getJSONArray("tags").getString(1))
            assertEquals(80, body.getInt("averageScore_greater"))
            assertEquals("SPRING", body.getString("season"))
            assertEquals(1998, body.getInt("seasonYear"))
            assertEquals("TV", body.getString("format"))
            assertTrue(body.getBoolean("isAdult"))
            assertEquals(3, page.currentPage)
            assertFalse(page.hasNextPage)
            assertTrue(page.media.isEmpty())
        }
    }

    @Test fun savedStatePreservesAutomaticSortAndOptionalFilters() {
        val filters = AnimeDiscoveryFilters(search = "Bebop", statuses = listOf("FINISHED"), genres = listOf("Action"),
            tags = listOf("Space"), averageScoreGreater = 0, season = "SPRING", seasonYear = 1998, format = "TV")
        assertEquals(filters, AnimeDiscoveryFilters.restore(filters.save()))
        assertEquals("SEARCH_MATCH", filters.payload(1).getJSONArray("sort").getString(0))
        val empty = AnimeDiscoveryFilters.restore(AnimeDiscoveryFilters().save())
        assertFalse(empty.payload(1).has("status"))
        assertFalse(empty.payload(1).has("seasonYear"))
        assertFalse(empty.payload(1).has("averageScore_greater"))
        assertEquals(listOf("Space", "Time Travel"), AnimeDiscoveryFilters.parseTags(" Space, Time Travel, ,Space "))
    }

    @Test fun tagSuggestionsUseTheExistingCollectionTagMap() = runBlocking {
        withRepository { server, repo ->
            server.enqueue(MockResponse().setBody("""{"data":{"42":["Space","Time Travel"],"43":["Space",""]}}"""))
            assertEquals(listOf("Space", "Time Travel"), repo.animeDiscoveryTags())
            assertEquals("/api/v1/anilist/collection/raw/tags", server.takeRequest().path)
            server.enqueue(MockResponse().setBody("""{"data":{}}"""))
            assertTrue(repo.animeDiscoveryTags().isEmpty())
        }
    }

    @Test fun missingPageMetadataNeverInventsAnotherPage() {
        val page = AnimeDiscoveryPage.fromJson(JSONObject("""{"Page":{"media":[{"id":1,"title":"Fixture"}]}}"""), 4)
        assertEquals(4, page.currentPage)
        assertFalse(page.hasNextPage)
        assertNull(page.lastPage)
        assertNull(page.total)
        assertTrue(AnimeDiscoveryPage.fromJson(JSONObject("""{"Page":{}}"""), 1).media.isEmpty())
        assertTrue(runCatching { AnimeDiscoveryPage.fromJson(JSONObject("""{"Page":{"media":{}}}"""), 1) }.exceptionOrNull() is ApiException)
    }

    @Test fun invalidInputsFailBeforeSendingHttp() = runBlocking {
        withRepository { server, repo ->
            assertTrue(runCatching { repo.discoverAnime(AnimeDiscoveryFilters(), 0) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { repo.discoverAnime(AnimeDiscoveryFilters(averageScoreGreater = 100)) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { repo.discoverAnime(AnimeDiscoveryFilters(seasonYear = 0)) }.exceptionOrNull() is IllegalArgumentException)
            assertNull(server.takeRequest(50, TimeUnit.MILLISECONDS))
        }
    }

    private suspend fun withRepository(block: suspend (MockWebServer, SeanimeRepository) -> Unit) {
        val server = MockWebServer().apply { start() }
        val client = SeanimeApiClient(server.url("/").toString())
        try { block(server, SeanimeRepository(client)) } finally { client.close(); server.shutdown() }
    }
}
