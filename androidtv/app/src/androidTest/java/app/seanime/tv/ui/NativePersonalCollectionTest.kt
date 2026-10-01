package app.seanime.tv.ui

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.data.*
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
import java.util.concurrent.atomic.AtomicBoolean

class NativePersonalCollectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun customSourceRangeIdsOpenExactDetailsAndRestoreTheirOwnCards() = fixture(highIds = true) { f ->
        for (id in listOf(2_147_483_648L, 4_294_967_297L, 9_007_199_254_740_991L)) {
            awaitTag("media-$id")
            compose.onNodeWithTag("media-$id").performTvClick()
            awaitText("Update list")
            assertTrue(f.paths.contains("GET /api/v1/library/anime-entry/$id"))
            pressBack()
            awaitFocused("media-$id")
        }
        compose.onNodeWithTag("media-1").assertExists()
        assertTrue(f.catalogRequests.isEmpty())
        NativeScreenshotEvidence.capture("personal-collection-large-id-restoration")
    }

    @Test fun animePersonalSearchStatusSortAndDetailsRestoreWithoutCatalogRequests() = fixture { f ->
        awaitTag("media-3")
        choose("anime-collection-options", "collection-sort", "Title (A–Z)")
        closeOptions("anime-collection-options")
        awaitTag("media-1")
        assertTrue(compose.onNodeWithTag("media-1").fetchSemanticsNode().positionInRoot.x < compose.onNodeWithTag("media-2").fetchSemanticsNode().positionInRoot.x)
        choose("anime-collection-options", "collection-status", "Planning")
        closeOptions("anime-collection-options")
        compose.onNodeWithTag("media-2").assertExists()
        compose.onNodeWithTag("media-1").assertDoesNotExist()
        searchAnime("hidden alias")
        compose.onNodeWithTag("media-2").performTvClick()
        awaitText("Update list")
        pressBack()
        awaitFocused("media-2")
        assertTrue(f.catalogRequests.isEmpty())
        val card = compose.onNodeWithTag("media-2").fetchSemanticsNode()
        val grid = compose.onNodeWithTag("media-grid").fetchSemanticsNode().boundsInRoot
        assertTrue("Focused collection poster/title must fit", card.size.height <= grid.height + 1f &&
            card.positionInRoot.y >= grid.top - 1f && card.positionInRoot.y + card.size.height <= grid.bottom + 1f)
        NativeScreenshotEvidence.capture("personal-collection-restored-filtered-card")
        searchAnime("not in my list")
        awaitText("No matching titles")
        compose.onNodeWithTag("anime-discover").performTvClick()
        awaitTag("discovery-media-99")
        assertEquals(1, f.catalogRequests.size)
        assertFalse(f.catalogRequests.single().has("search"))
        pressBack()
        awaitFocused("anime-discover")
        compose.onNodeWithTag("anime-search-submit").assertTextContains("Search: not in my list")
        awaitText("No matching titles")
    }

    @Test fun collectionRefreshFailureRetriesWithoutDroppingPersonalFilters() = fixture { f ->
        awaitTag("media-3")
        choose("anime-collection-options", "collection-status", "Planning")
        f.failCollection.set(true)
        compose.onNodeWithTag("collection-refresh").performTvClick()
        awaitText("Fixture collection unavailable")
        compose.onNodeWithText("Try again").performTvClick()
        awaitTag("media-2")
        compose.onNodeWithTag("media-1").assertDoesNotExist()
        compose.onNodeWithTag("anime-collection-options").performTvClick()
        compose.onNodeWithTag("collection-status").assertTextContains("Planning")
        closeOptions("anime-collection-options")
        assertTrue(f.catalogRequests.isEmpty())
    }

    @Test fun mangaPersonalQueryAndStatusSurviveCatalogAndChapterBack() = fixture(manga = true) { f ->
        awaitTag("manga-collection-toolbar")
        toolbar("manga-search").performTvClick()
        awaitFocused("manga-search-editor")
        compose.onNodeWithTag("manga-search-editor").performTextReplacement("hidden alias")
        compose.onNodeWithTag("text-entry-save").performTvClick()
        awaitFocused("manga-search")
        choose("manga-collection-options", "collection-status", "Planning", manga = true)
        closeOptions("manga-collection-options")
        mangaList().performScrollToNode(hasTestTag("manga-collection-2"))
        compose.onNodeWithTag("manga-collection-2").performTvClick()
        awaitTag("manga-edit-list")
        pressBack()
        awaitFocused("manga-collection-2")
        assertTrue(f.catalogRequests.isEmpty())
        mangaList().performScrollToIndex(0)
        toolbar("manga-discover").performTvClick()
        compose.waitUntil(10_000) { f.catalogRequests.size == 1 }
        compose.onNodeWithTag("discovery-search-submit").performTvClick()
        awaitTag("discovery-search-field")
        compose.onNodeWithTag("discovery-search-field").performTextReplacement("catalog phrase")
        compose.onNodeWithTag("text-entry-save").performTvClick()
        compose.waitUntil(10_000) { f.catalogRequests.size == 2 }
        assertEquals("catalog phrase", f.catalogRequests.last().getString("search"))
        pressBack()
        awaitFocused("manga-discover")
        toolbar("manga-search").performTvClick()
        compose.onNodeWithTag("manga-search-editor").assertTextContains("hidden alias")
        compose.onNodeWithTag("text-entry-cancel").performTvClick()
        toolbar("manga-collection-options").performTvClick()
        compose.onNodeWithTag("collection-status").assertTextContains("Planning")
        closeOptions("manga-collection-options")
        assertTrue(f.paths.none { it.startsWith("POST /api/v1/anilist/list-entry") })
    }

    private fun choose(opener: String, field: String, value: String, manga: Boolean = false) {
        if (manga) toolbar(opener).performTvClick() else compose.onNodeWithTag(opener).performTvClick()
        compose.onNodeWithTag(field).performTvClick()
        val choices = hasScrollToNodeAction() and hasAnyAncestor(isDialog())
        compose.onNode(choices).performScrollToNode(hasText(value))
        compose.onNodeWithText(value).performTvClick()
        awaitFocused(field)
    }
    private fun closeOptions(opener: String) {
        compose.onNodeWithTag("collection-options-back").performTvClick()
        awaitFocused(opener)
    }
    private fun searchAnime(value: String) {
        compose.onNodeWithTag("anime-search-submit").performTvClick()
        awaitFocused("anime-search-field")
        compose.onNodeWithTag("anime-search-field").performTextReplacement(value)
        compose.onNodeWithTag("text-entry-save").performTvClick()
        awaitFocused("anime-search-submit")
    }
    private fun toolbar(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("manga-collection-toolbar").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun mangaList() = compose.onNode(hasScrollToNodeAction() and !hasTestTag("manga-collection-toolbar"))
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitText(value: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitFocused(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty() }
    private fun pressBack() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun fixture(manga: Boolean = false, highIds: Boolean = false, body: (CollectionFixture) -> Unit) {
        val f = CollectionFixture(highIds)
        MockWebServer().use { server ->
            server.dispatcher = f; server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
                val repo = SeanimeRepository(api)
                val status = SeanimeJson.status(JSONObject("""{"serverReady":true,"user":{"isSimulated":true},"settings":{"library":{}}}"""))
                compose.setContent { SeanimeTheme {
                    if (manga) NativeArtworkProvider(repo.client) { MangaScreen(repo) }
                    else SeanimeTvApp(repo, status, {}, {}, {})
                } }
                body(f)
            }
        }
    }
    private class CollectionFixture(private val highIds: Boolean) : Dispatcher() {
        val catalogRequests = CopyOnWriteArrayList<JSONObject>()
        val paths = CopyOnWriteArrayList<String>()
        val failCollection = AtomicBoolean(false)
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            paths.add("${request.method} $path")
            val data: Any = when {
                path in listOf("/api/v1/library/collection", "/api/v1/manga/collection", "/api/v1/anilist/collection/raw") -> {
                    if (failCollection.getAndSet(false)) return MockResponse().setResponseCode(503).setBody("""{"error":"Fixture collection unavailable"}""")
                    if (highIds) jsonObject("lists" to JSONArray().put(jsonObject("status" to "CURRENT", "entries" to JSONArray(
                        listOf(1L, 2_147_483_648L, 4_294_967_297L, 9_007_199_254_740_991L).map { id ->
                            jsonObject("media" to jsonObject("id" to id, "title" to "Title $id"), "listData" to jsonObject("status" to "CURRENT"))
                        }))))
                    else JSONObject("""{"lists":[{"status":"CURRENT","entries":[{"media":{"id":3,"title":"Zeta"},"listData":{"status":"REPEATING","score":70}},{"media":{"id":1,"title":"Alpha"},"listData":{"status":"CURRENT","score":90}}]},
                        {"status":"PLANNING","entries":[{"media":{"id":2,"title":"Beta","synonyms":["Hidden Alias"]},"listData":{"status":"PLANNING","score":80}}]}]}""")
                }
                path in listOf("/api/v1/anilist/list-anime", "/api/v1/manga/anilist/list") -> {
                    catalogRequests.add(JSONObject(request.body.readUtf8()))
                    JSONObject("""{"Page":{"media":[{"id":99,"title":"Catalog only"}],"pageInfo":{"currentPage":1,"lastPage":1,"hasNextPage":false}}}""")
                }
                path.startsWith("/api/v1/library/anime-entry/") || path.startsWith("/api/v1/manga/entry/") ->
                    jsonObject("media" to jsonObject("id" to path.substringAfterLast('/').toLong(), "title" to "Beta"), "episodes" to JSONArray())
                path == "/api/v1/manga/source-refresh" -> JSONObject.NULL
                path == "/api/v1/status" -> JSONObject("""{"serverReady":true,"user":{"isSimulated":true},"settings":{"library":{}}}""")
                path == "/api/v1/extensions/list/manga-provider" || path.startsWith("/api/v1/manga/downloaded-chapters/") -> JSONArray()
                else -> JSONObject()
            }
            return MockResponse().setBody(JSONObject().put("data", data).toString())
        }
    }
}
