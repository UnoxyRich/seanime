package app.seanime.tv.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeCatalogDiscoveryTest {
    @Test fun omittedAndNullCatalogSlicesAreEmptyButPresentWrongTypesFail() {
        for (manga in listOf(false, true)) {
            for (raw in listOf("""{"Page":{"pageInfo":{"currentPage":3,"hasNextPage":false}}}""",
                """{"Page":{"media":null,"pageInfo":{"currentPage":3,"hasNextPage":false}}}""")) {
                val result = AnimeDiscoveryPage.fromJson(JSONObject(raw), 1, manga)
                assertTrue(result.media.isEmpty())
                assertEquals(3, result.currentPage)
                assertFalse(result.hasNextPage)
            }
            for (wrong in listOf("{}", "\"not an array\"", "7", "true")) {
                assertTrue(wrong, runCatching { AnimeDiscoveryPage.fromJson(JSONObject("{\"Page\":{\"media\":$wrong}}"), 1, manga) }.isFailure)
            }
            assertTrue(runCatching { AnimeDiscoveryPage.fromJson(JSONObject(), 1, manga) }.isFailure)
        }
    }

    @Test fun omittedAndNullAiringSlicesKeepPageMetadataWhileWrongTypesFail() {
        for (raw in listOf("""{"Page":{"pageInfo":{"currentPage":2,"hasNextPage":false}}}""",
            """{"Page":{"airingSchedules":null,"pageInfo":{"currentPage":2,"hasNextPage":false}}}""")) {
            val result = RecentAiringPage.parse(JSONObject(raw), 1)
            assertTrue(result.items.isEmpty())
            assertEquals(2, result.page)
            assertFalse(result.hasNext)
        }
        for (wrong in listOf("{}", "\"not an array\"", "7", "true")) {
            assertTrue(wrong, runCatching { RecentAiringPage.parse(JSONObject("{\"Page\":{\"airingSchedules\":$wrong}}"), 1) }.isFailure)
        }
        assertTrue(runCatching { RecentAiringPage.parse(JSONObject(), 1) }.isFailure)
    }
    @Test fun mangaFiltersUseMangaYearCountryAndTenPointScoreContract() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"Page":{"media":[{"id":4294967297,"title":"Fixture manga","chapters":50}],"pageInfo":{"currentPage":2,"lastPage":2,"hasNextPage":false}}}}"""))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val filters = AnimeDiscoveryFilters(search = " Manga ", sort = "CHAPTERS_DESC", statuses = listOf("RELEASING"),
                    genres = listOf("Action"), tags = listOf("Travel"), averageScoreGreater = 8, seasonYear = 2024,
                    format = "MANGA", isAdult = true, manga = true, countryOfOrigin = "KR")
                val page = discoverNativeCatalog(SeanimeRepository(api), filters, 2)
                val request = server.takeRequest()
                assertEquals("/api/v1/manga/anilist/list", request.path)
                val body = JSONObject(request.body.readUtf8())
                assertEquals(setOf("page", "perPage", "search", "sort", "status", "genres", "tags", "averageScore_greater", "year", "format", "isAdult", "countryOfOrigin"), body.keys().asSequence().toSet())
                assertEquals(8, body.getInt("averageScore_greater")) // Go multiplies by ten exactly once.
                assertEquals(2024, body.getInt("year")); assertFalse(body.has("seasonYear"))
                assertEquals("KR", body.getString("countryOfOrigin")); assertEquals("Manga", body.getString("search"))
                assertEquals(4294967297L, page.media.single().id); assertTrue(page.media.single().isManga)
                assertEquals(50, page.media.single().totalEpisodes); assertFalse(page.hasNextPage)
                assertEquals(filters.copy(search = "Manga"), AnimeDiscoveryFilters.restore(filters.save()))
            }
        }
    }

    @Test fun mangaOnlyFieldsAndKnownQueryExclusionsFailBeforeRequests() {
        listOf(AnimeDiscoveryFilters(countryOfOrigin = "JP"), AnimeDiscoveryFilters(format = "MUSIC"),
            AnimeDiscoveryFilters(manga = true, format = "NOVEL"), AnimeDiscoveryFilters(manga = true, season = "SPRING"),
            AnimeDiscoveryFilters(manga = true, averageScoreGreater = 10), AnimeDiscoveryFilters(manga = true, format = "TV")).forEach {
            assertTrue(it.toString(), runCatching { it.payload(1) }.isFailure)
        }
        assertEquals(99, AnimeDiscoveryFilters(averageScoreGreater = 99).payload(1).getInt("averageScore_greater"))
    }

    @Test fun mangaSuggestionsUseTheMangaTagEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"7":["Travel","Space"],"8":["Travel"]}}"""))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                assertEquals(listOf("Space", "Travel"), nativeDiscoveryTags(SeanimeRepository(api), true))
                assertEquals("/api/v1/manga/anilist/collection/raw/tags", server.takeRequest().path)
            }
        }
    }

    @Test fun airingRequestsUseTheRealTimeWindowSortAndPageWithoutIgnoredSearch() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"Page":{"airingSchedules":[],"pageInfo":{"currentPage":3,"hasNextPage":true}}}}"""))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val filters = RecentAiringFilters.around(1700000000L)
                val page = loadRecentAiring(SeanimeRepository(api), filters, 3)
                val request = server.takeRequest()
                assertEquals("/api/v1/anilist/list-recent-anime", request.path)
                val body = JSONObject(request.body.readUtf8())
                assertEquals(3, body.getInt("page")); assertEquals(24, body.getInt("perPage"))
                assertEquals(1699827200L, body.getLong("airingAt_greater"))
                assertEquals(1701209600L, body.getLong("airingAt_lesser"))
                assertTrue(body.getBoolean("notYetAired")); assertEquals("TIME", body.getJSONArray("sort").getString(0))
                assertFalse(body.has("search")); assertTrue(page.hasNext)
                assertTrue(runCatching { filters.copy(until = filters.from).payload(1) }.isFailure)
            }
        }
    }

    @Test fun airingRowsKeepActualEpisodeNumbersAndDistinctHighIdTitles() {
        val page = RecentAiringPage.parse(JSONObject("""{"Page":{"airingSchedules":[
            {"id":1,"airingAt":1700000000,"episode":7,"media":{"id":4294967297,"title":"Fixture","type":"ANIME","countryOfOrigin":"JP","format":"TV","isAdult":false,"nextAiringEpisode":{"episode":99}}},
            {"id":2,"airingAt":1700000010,"episode":8,"media":{"id":4294967297,"title":"Fixture","type":"ANIME","countryOfOrigin":"JP","format":"TV","isAdult":false}},
            {"id":3,"airingAt":1700000010,"episode":1,"media":{"id":2,"type":"ANIME","countryOfOrigin":"JP","format":"TV_SHORT"}},
            {"id":4,"airingAt":1700000010,"episode":1,"media":{"id":3,"type":"ANIME","countryOfOrigin":"JP","isAdult":true}}
        ],"pageInfo":{"hasNextPage":false}}}"""), 2)
        assertEquals(listOf(7, 8), page.items.map { it.episode })
        assertEquals(listOf(4294967297L, 4294967297L), page.items.map { it.media.id })
        assertEquals(2, page.items.map { it.key }.distinct().size)
        assertEquals(2, page.page); assertFalse(page.hasNext)
        assertTrue(runCatching { RecentAiringPage.parse(JSONObject(), 1) }.isFailure)
    }
}
