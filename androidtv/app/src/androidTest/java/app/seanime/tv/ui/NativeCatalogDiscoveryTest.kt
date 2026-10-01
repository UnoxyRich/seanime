package app.seanime.tv.ui

import android.view.KeyEvent
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

class NativeCatalogDiscoveryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun advancedMangaRouteFiltersPagesAndDetailsUseNativeControlsAndExactPayloads() = fixture(manga = true) { f ->
        awaitTag("discovery-media-4294967297")
        val first = f.manga.single()
        assertEquals(2, first.getInt("page")); assertEquals(2024, first.getInt("year"))
        assertEquals("KR", first.getString("countryOfOrigin")); assertEquals(8, first.getInt("averageScore_greater"))
        assertFalse(first.has("seasonYear")); assertFalse(first.has("season"))
        compose.onNodeWithTag("discovery-filters").performTvClick()
        compose.onNodeWithTag("discovery-filter-list").performScrollToNode(hasTestTag("discovery-filter-country"))
        compose.onNodeWithTag("discovery-filter-country").performTvClick()
        compose.onNodeWithTag("discovery-choice-JP").performScrollTo().performTvClick()
        awaitFocused("discovery-filter-country")
        compose.onNodeWithTag("discovery-filter-apply").performTvClick()
        compose.waitUntil(10_000) { f.manga.size == 2 }
        awaitTag("discovery-media-2147483648")
        assertEquals("JP", f.manga.last().getString("countryOfOrigin"))
        assertEquals(1, f.manga.last().getInt("page"))
        compose.onNodeWithTag("discovery-next").performTvClick()
        awaitTag("discovery-media-4294967297")
        compose.onNodeWithTag("discovery-media-4294967297").performTvClick()
        awaitTag("manga-edit-list")
        assertTrue(f.paths.contains("/api/v1/manga/entry/4294967297"))
        back()
        awaitFocused("discovery-media-4294967297")
        compose.onNodeWithText("Page 2 of 2").assertExists()
        val before = f.manga.size
        compose.onNodeWithTag("discovery-filters").performTvClick()
        compose.onNodeWithTag("discovery-filter-reset").performTvClick()
        compose.onNodeWithTag("discovery-filter-cancel").performTvClick()
        awaitFocused("discovery-filters")
        assertEquals(before, f.manga.size)
        NativeScreenshotEvidence.capture("manga-discovery-filtered-page-restoration")
    }

    @Test fun nativeAiringEntryPaginatesUsesActualEpisodeAndRestoresDiscoveryAfterDetails() = fixture { f ->
        openAiring()
        assertEquals("TIME", f.airing.single().getJSONArray("sort").getString(0))
        assertTrue(f.airing.single().getBoolean("notYetAired"))
        assertEquals(16 * 86_400L, f.airing.single().getLong("airingAt_lesser") - f.airing.single().getLong("airingAt_greater"))
        assertFalse(f.airing.single().has("search"))
        compose.onNodeWithTag("airing-next").performTvClick()
        awaitTag("airing-title-4294967297-7")
        awaitFocused("airing-previous")
        compose.onNodeWithText("Episode 7 ·", substring = true).assertExists()
        compose.onNodeWithTag("airing-title-4294967297-7").performTvClick()
        awaitText("Update list")
        assertTrue(f.paths.contains("/api/v1/library/anime-entry/4294967297"))
        back()
        awaitFocused("airing-title-4294967297-7")
        compose.onNodeWithText("Page 2 of 2").assertExists()
        NativeScreenshotEvidence.capture("native-airing-page-two-restored-focus")
        back()
        awaitFocused("discovery-airing")
        compose.onNodeWithText("Discover anime").assertExists()
    }

    @Test fun airingFailuresRetryAndEmptyPagesKeepUsableNavigation() = fixture { f ->
        openAiring()
        f.failAiring.set(true); f.emptyAiring.set(true)
        compose.onNodeWithTag("airing-later").performTvClick()
        awaitText("Fixture airing unavailable")
        compose.onNodeWithText("Try again").performTvClick()
        awaitText("No matching airings on this page")
        compose.onNodeWithTag("airing-back").assertIsEnabled()
        compose.onNodeWithTag("airing-next").assertIsNotEnabled()
        compose.onNodeWithTag("airing-back").performTvClick()
        awaitFocused("discovery-airing")
    }

    private fun openAiring() {
        awaitTag("anime-discover")
        compose.onNodeWithTag("anime-discover").performTvClick()
        awaitTag("discovery-media-99")
        compose.onNodeWithTag("discovery-airing").performTvClick()
        awaitTag("airing-title-2147483648-7")
    }
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitFocused(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty() }
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun fixture(manga: Boolean = false, test: (CatalogFixture) -> Unit) {
        val f = CatalogFixture()
        MockWebServer().use { server ->
            server.dispatcher = f; server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
                val repo = SeanimeRepository(api)
                val status = SeanimeJson.status(JSONObject("""{"serverReady":true,"user":{"isSimulated":true},"settings":{"library":{}}}"""))
                compose.setContent { SeanimeTheme {
                    if (manga) FeatureScreen(TvFeature.MANGA, repo, {}, {}, resolveNativePluginDestination("/search?type=manga&year=2024&format=MANGA&countryOfOrigin=KR&scoreAbove=8&sorting=CHAPTERS_DESC&page=2"))
                    else SeanimeTvApp(repo, status, {}, {}, {})
                } }
                test(f)
            }
        }
    }
    private class CatalogFixture : Dispatcher() {
        val manga = CopyOnWriteArrayList<JSONObject>()
        val airing = CopyOnWriteArrayList<JSONObject>()
        val paths = CopyOnWriteArrayList<String>()
        val failAiring = AtomicBoolean(false)
        val emptyAiring = AtomicBoolean(false)
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty(); paths.add(path)
            val data: Any = when {
                path == "/api/v1/library/collection" || path == "/api/v1/manga/collection" -> JSONObject().put("lists", JSONArray())
                path == "/api/v1/manga/anilist/list" -> {
                    val body = JSONObject(request.body.readUtf8()); manga.add(body)
                    page(body.getInt("page"), "media", JSONArray().put(jsonObject("id" to if (body.getInt("page") == 1) 2147483648L else 4294967297L, "title" to "Manga fixture", "chapters" to 12)))
                }
                path == "/api/v1/anilist/list-recent-anime" -> {
                    val body = JSONObject(request.body.readUtf8()); airing.add(body)
                    if (failAiring.getAndSet(false)) return MockResponse().setResponseCode(503).setBody("""{"error":"Fixture airing unavailable"}""")
                    page(body.getInt("page"), "airingSchedules", JSONArray().apply { if (!emptyAiring.get()) put(jsonObject("id" to 17, "episode" to 7,
                        "airingAt" to body.getLong("airingAt_greater") + 4 * 86_400,
                        "media" to jsonObject("id" to if (body.getInt("page") == 1) 2147483648L else 4294967297L, "title" to "Airing fixture", "type" to "ANIME", "countryOfOrigin" to "JP", "format" to "TV", "isAdult" to false, "nextAiringEpisode" to jsonObject("episode" to 99)))) }, !emptyAiring.get())
                }
                path == "/api/v1/anilist/list-anime" -> page(1, "media", JSONArray().put(jsonObject("id" to 99, "title" to "Anime fixture")), false)
                path.startsWith("/api/v1/library/anime-entry/") || path.startsWith("/api/v1/manga/entry/") -> jsonObject("media" to jsonObject("id" to path.substringAfterLast('/').toLong(), "title" to "Details fixture"), "episodes" to JSONArray())
                path == "/api/v1/status" -> jsonObject("serverReady" to true, "user" to jsonObject("isSimulated" to true), "settings" to JSONObject())
                path == "/api/v1/manga/source-refresh" -> JSONObject.NULL
                path == "/api/v1/extensions/list/manga-provider" || path.startsWith("/api/v1/manga/downloaded-chapters/") -> JSONArray()
                else -> JSONObject()
            }
            return MockResponse().setBody(JSONObject().put("data", data).toString())
        }
        private fun page(page: Int, key: String, rows: JSONArray, paginated: Boolean = true) = jsonObject("Page" to jsonObject(
            "pageInfo" to jsonObject("currentPage" to page, "hasNextPage" to (paginated && page == 1), "lastPage" to if (paginated) 2 else 1))
            // Match Go's omitempty slice serialization on the actual empty-airing path.
            .apply { if (rows.length() > 0) put(key, rows) })
    }
}
