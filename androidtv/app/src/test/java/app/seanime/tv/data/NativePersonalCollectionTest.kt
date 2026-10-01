package app.seanime.tv.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePersonalCollectionTest {
    @Test fun largeCustomSourceIdsStayDistinctInCollectionsStreamsAndDiscovery() {
        val ids = listOf(1L, 2_147_483_648L, 4_294_967_297L, 9_007_199_254_740_991L)
        val media = JSONArray(ids.map { jsonObject("id" to it, "title" to "Title $it") })
        val raw = jsonObject("stream" to jsonObject("anime" to media, "listData" to JSONObject().apply {
            ids.forEach { put(it.toString(), jsonObject("status" to "CURRENT", "progress" to 3)) }
        }))
        val collection = parsePersonalCollection(raw)
        assertEquals(ids, collection.entries.map { it.media.id })
        assertTrue(collection.entries.all { it.media.progress == 3 })
        assertEquals(listOf(ids[2]), filterPersonalCollection(collection, query = ids[2].toString()).ids())
        assertEquals(ids, AnimeDiscoveryPage.fromJson(jsonObject("Page" to jsonObject("media" to media)), 1).media.map { it.id })
    }
    @Test fun rawAndLibraryListsPreserveOrderAndPersonalStatusInsteadOfReleaseStatus() {
        val raw = parsePersonalCollection(JSONObject("""{"MediaListCollection":{"lists":[
            {"name":"Planned","status":"PLANNING","entries":[{"status":"PLANNING","progress":0,"score":70,"media":{"id":2,"title":{"userPreferred":"Zeta"},"status":"FINISHED"}}]},
            {"name":"Watching","status":"CURRENT","entries":[{"status":"REPEATING","progress":4,"media":{"id":1,"title":{"userPreferred":"Alpha"}}}]}
        ]}}"""))
        assertEquals(listOf(2L, 1L), raw.entries.map { it.media.id })
        assertEquals(listOf("PLANNING", "REPEATING"), raw.entries.map { it.status })
        assertEquals("Planned", raw.entries[0].listName)
        assertEquals(70, raw.entries[0].listData.getInt("score"))
        assertEquals(listOf(1L), filterPersonalCollection(raw, status = "REPEATING").ids())
        assertEquals("FINISHED", raw.entries[0].media.raw.getString("status"))
        assertEquals("PLANNING", raw.entries[0].media.status)
    }

    @Test fun streamTitlesUseTheirOwnListDataAndDoNotDuplicateLocalEntries() {
        val collection = parsePersonalCollection(JSONObject("""{"lists":[{"status":"CURRENT","entries":[{"media":{"id":1,"title":"Local"},"listData":{"status":"REPEATING","score":90}}]}],
            "stream":{"anime":[{"id":1,"title":"Duplicate"},{"id":2,"title":"Stream"}],"listData":{"2":{"status":"CURRENT","progress":6,"score":50}}}}"""))
        assertEquals(listOf(1L, 2L), collection.entries.map { it.media.id })
        assertEquals("REPEATING", collection.entries[0].status)
        assertEquals(6, collection.entries[1].media.progress)
        assertEquals(50, collection.entries[1].listData.getInt("score"))
    }

    @Test fun personalSearchUsesAliasesAndNeverCreatesCatalogEntries() {
        val collection = parsePersonalCollection(JSONObject("""{"lists":[{"status":"CURRENT","entries":[
            {"media":{"id":1,"title":{"userPreferred":"First","native":"日本語"},"synonyms":["Hidden   Alias"]},"listData":{"status":"CURRENT"}},
            {"media":{"id":2,"title":"Second"},"listData":{"status":"COMPLETED"}}
        ]}]}"""))
        assertEquals(listOf(1L), filterPersonalCollection(collection, query = "  hidden alias ").ids())
        assertEquals(listOf(1L), filterPersonalCollection(collection, query = "日本語").ids())
        assertTrue(filterPersonalCollection(collection, query = "Catalog-only title").isEmpty())
        assertTrue(filterPersonalCollection(collection, query = "First", status = "COMPLETED").isEmpty())
    }

    @Test fun scoreProgressAudienceAndDatesSortWithUnknownsLastAndStableTies() {
        val collection = fixtureCollection()
        assertEquals(listOf(2L, 1L, 3L), filterPersonalCollection(collection, sort = PersonalCollectionSort.SCORE_DESC).ids())
        assertEquals(listOf(1L, 2L, 3L), filterPersonalCollection(collection, sort = PersonalCollectionSort.SCORE).ids())
        assertEquals(listOf(1L, 3L, 2L), filterPersonalCollection(collection, sort = PersonalCollectionSort.PROGRESS).ids())
        assertEquals(listOf(2L, 1L, 3L), filterPersonalCollection(collection, sort = PersonalCollectionSort.AUDIENCE_SCORE_DESC).ids())
        assertEquals(listOf(2L, 1L, 3L), filterPersonalCollection(collection, sort = PersonalCollectionSort.START_DATE_DESC).ids())
        assertEquals(listOf(1L, 2L, 3L), filterPersonalCollection(collection, sort = PersonalCollectionSort.TITLE_DESC).ids())
        assertEquals(3, filterPersonalCollection(collection, sort = PersonalCollectionSort.END_DATE).size)
    }

    @Test fun animeSortsUseActualLocalCountsContinuityAndEpisodeAirDates() {
        val collection = fixtureCollection()
        val context = PersonalCollectionSortContext(
            library = JSONObject("""{"lists":[{"entries":[{"media":{"id":1},"libraryData":{"mainFileCount":10,"unwatchedCount":0}}]}],
                "continueWatchingList":[{"baseAnime":{"id":2},"episodeMetadata":{"airDate":"2026-03-01"}}]}"""),
            watchHistory = JSONObject("""{"1":{"timeUpdated":"2026-02-01T00:00:00Z"},"2":{"timeUpdated":"2026-03-01T00:00:00Z"}}"""))
        assertEquals(listOf(2L, 1L, 3L), filterPersonalCollection(collection, sort = PersonalCollectionSort.LAST_WATCHED_DESC, context = context).ids())
        assertEquals(listOf(2L, 1L, 3L), filterPersonalCollection(collection, sort = PersonalCollectionSort.AIRDATE_DESC, context = context).ids())
        assertEquals(1L, filterPersonalCollection(collection, sort = PersonalCollectionSort.UNWATCHED_EPISODES, context = context).first().media.id)
    }

    @Test fun omittedGoZeroProgressSortsBeforePositiveProgressForAnimeAndManga() {
        // EntryListData.progress is an int with json omitempty in both Go collection models.
        val response = JSONObject("""{"lists":[{"status":"CURRENT","entries":[
            {"media":{"id":1,"title":"In progress"},"listData":{"status":"CURRENT","progress":5}},
            {"media":{"id":2147483648,"title":"Not started"},"listData":{"status":"CURRENT"}},
            {"media":{"id":9007199254740991,"title":"Also not started"},"listData":{"status":"CURRENT","progress":0}}
        ]}]}""")
        listOf(false, true).forEach { manga ->
            val collection = parsePersonalCollection(response, manga)
            assertEquals(listOf(2147483648L, 9007199254740991L, 1L),
                filterPersonalCollection(collection, sort = PersonalCollectionSort.PROGRESS).ids())
            assertEquals(listOf(1L, 2147483648L, 9007199254740991L),
                filterPersonalCollection(collection, sort = PersonalCollectionSort.PROGRESS_DESC).ids())
        }
    }

    @Test fun watchHistoryOrdersVariableFractionalPrecisionWithoutLosingNanoseconds() {
        val timestamps = listOf(
            "2026-01-01T00:00:00.11Z", "2026-01-01T00:00:00.1Z", "2026-01-01T00:00:00Z",
            "2026-01-01T00:00:00.100000001Z", "2026-01-01T00:00:00.100000000Z",
            "2026-01-01T00:00:00.999999999Z", "2026-01-01T00:00:01Z",
        )
        assertEquals(listOf(3L, 2L, 5L, 4L, 1L, 6L, 7L), sortedHistoryIds(timestamps))
        assertEquals(listOf(7L, 6L, 1L, 4L, 2L, 5L, 3L), sortedHistoryIds(timestamps, descending = true))
        val equivalentPrecision = (1..9).map { "2026-01-01T00:00:00.${"1".padEnd(it, '0')}Z" }
        assertEquals((1L..9L).toList(), sortedHistoryIds(equivalentPrecision))
        assertEquals((1L..9L).toList(), sortedHistoryIds(equivalentPrecision, descending = true))
    }

    @Test fun watchHistoryNormalizesOffsetsAcrossDaysAndKeepsEquivalentInstantsStable() {
        val timestamps = listOf(
            "2026-01-01T01:00:00.123456789+01:00", "2025-12-31T19:00:00.123456789-05:00",
            "2026-01-01T00:00:00.123456789Z", "2026-01-01T00:30:00+01:00",
            "2026-01-01T00:00:00-00:30", "2026-01-01T00:00:00.123456788Z",
        )
        assertEquals(listOf(4L, 6L, 1L, 2L, 3L, 5L), sortedHistoryIds(timestamps))
        assertEquals(listOf(5L, 1L, 2L, 3L, 6L, 4L), sortedHistoryIds(timestamps, descending = true))
    }

    @Test fun malformedWatchHistoryValuesRemainLastInBothDirectionsWithStableUnknownTies() {
        val invalid = listOf(null, "", "not-a-date", 1700000000,
            "2026-02-29T00:00:00Z", "2024-02-30T00:00:00Z", "2026-13-01T00:00:00Z",
            "2026-00-01T00:00:00Z", "2026-01-00T00:00:00Z", "2026-01-01T24:00:00Z",
            "2026-01-01T00:60:00Z", "2026-01-01T00:00:60Z", "2026-01-01T00:00:00",
            "2026-01-01T00:00:00.1234567890Z", "2026-01-01T00:00:00.Z", "2026-01-01T00:00:00+24:00",
            "2026-01-01T00:00:00+00:60", "2026-01-01T00:00:00+01", "2026-01-01T00:00:00Z trailing")
        val timestamps = invalid + listOf("2024-02-29T00:00:00Z", "2026-01-01T00:00:00Z")
        val unknownIds = (1..invalid.size).map(Int::toLong)
        val oldest = invalid.size.toLong() + 1
        val newest = oldest + 1
        assertEquals(listOf(oldest, newest) + unknownIds, sortedHistoryIds(timestamps))
        assertEquals(listOf(newest, oldest) + unknownIds, sortedHistoryIds(timestamps, descending = true))
    }

    @Test fun watchHistoryUsesProlepticGregorianDatesAndHandlesPreEpochSeconds() {
        val timestamps = listOf(
            "1500-02-29T00:00:00Z", // Invalid in the Gregorian calendar, although valid in the Julian calendar.
            "1582-10-10T00:00:00Z", "1582-10-05T00:00:00Z", "0000-02-29T00:00:00Z",
            "0001-01-01T00:00:00Z", "1969-12-31T23:59:59.999999999Z", "1970-01-01T00:00:00Z",
            "9999-12-31T23:59:59.999999999Z",
        )
        assertEquals(listOf(4L, 5L, 3L, 2L, 6L, 7L, 8L, 1L), sortedHistoryIds(timestamps))
        assertEquals(listOf(8L, 7L, 6L, 2L, 3L, 5L, 4L, 1L), sortedHistoryIds(timestamps, descending = true))
    }

    @Test fun mangaUnreadSortRequiresMatchingSavedSourceAndFilters() {
        val collection = fixtureCollection()
        val context = PersonalCollectionSortContext(
            mangaPreferences = JSONObject("""{"entries":{"1":{"provider":"a","filters":{"a":{"language":"en","scanlators":["Team"]}}},"2":{"provider":"b"}}}"""),
            latestChapters = JSONObject("""{"1":[{"provider":"a","language":"en","scanlator":"Team","number":4},{"provider":"a","language":"ja","scanlator":"Team","number":100},{"provider":"other","number":200}],"2":[{"provider":"b","number":20}]}"""))
        assertEquals(listOf(2L, 1L, 3L), filterPersonalCollection(collection, sort = PersonalCollectionSort.UNREAD_CHAPTERS_DESC, context = context).ids())
        assertEquals(listOf(1L, 2L, 3L), filterPersonalCollection(collection, sort = PersonalCollectionSort.UNREAD_CHAPTERS, context = context).ids())
        assertEquals(19, personalCollectionSorts(true).size)
        assertEquals(23, personalCollectionSorts(false).size)
    }

    @Test fun collectionAndSupplementalSortRequestsUseExistingGetContracts() = runBlocking {
        MockWebServer().use { server ->
            repeat(6) { server.enqueue(MockResponse().setBody("""{"data":{"lists":[],"entries":{}}}""")) }
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val repo = SeanimeRepository(api)
                loadPersonalCollection(repo)
                loadPersonalCollection(repo, library = false)
                loadPersonalCollection(repo, manga = true)
                loadPersonalCollectionSortContext(repo, PersonalCollectionSort.LAST_WATCHED_DESC)
                loadPersonalCollectionSortContext(repo, PersonalCollectionSort.UNREAD_CHAPTERS)
                val paths = (1..6).map { server.takeRequest().also { assertEquals("GET", it.method); assertEquals(0L, it.bodySize) }.path }
                assertEquals(listOf("/api/v1/library/collection", "/api/v1/anilist/collection/raw", "/api/v1/manga/collection",
                    "/api/v1/continuity/history", "/api/v1/manga/latest-chapter-numbers", "/api/v1/manga/preferences"), paths)
            }
        }
    }

    private fun List<PersonalCollectionEntry>.ids() = map { it.media.id }
    private fun sortedHistoryIds(timestamps: List<Any?>, descending: Boolean = false): List<Long> {
        val entries = JSONArray()
        val history = JSONObject()
        timestamps.forEachIndexed { index, timestamp ->
            val id = index.toLong() + 1
            entries.put(jsonObject("media" to jsonObject("id" to id, "title" to "Entry $id")))
            history.put(id.toString(), jsonObject("timeUpdated" to timestamp))
        }
        return filterPersonalCollection(parsePersonalCollection(entries),
            sort = if (descending) PersonalCollectionSort.LAST_WATCHED_DESC else PersonalCollectionSort.LAST_WATCHED,
            context = PersonalCollectionSortContext(watchHistory = history)).ids()
    }
    private fun fixtureCollection() = parsePersonalCollection(JSONObject("""{"lists":[{"status":"CURRENT","entries":[
        {"media":{"id":1,"title":"Zeta","meanScore":70,"episodes":12},"listData":{"score":60,"startedAt":"2024-01-01"}},
        {"media":{"id":2,"title":"Beta","meanScore":90,"episodes":12},"listData":{"score":90,"progress":5,"startedAt":{"year":2025,"month":3,"day":4}}},
        {"media":{"id":3,"title":"Alpha"},"listData":{}}
    ]}]}"""))
}
