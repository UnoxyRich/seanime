package app.seanime.tv.ui

import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
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

class NativeMangaWorkflowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offlineCollectionEntryOpensDownloadsWithoutAnOnlineSourceLookup() = fixture(offline = true) { fixture ->
        awaitText("Downloaded fixture chapter")
        assertTrue(fixture.requests.none { it.path in setOf("/api/v1/manga/chapters", "/api/v1/extensions/list/manga-provider", "/api/v1/manga/preferences", "/api/v1/manga/get-mapping") })
        compose.onNodeWithText("Read").performScrollTo().performTvClick()
        compose.waitUntil(10_000) { fixture.requests.any { it.path == "/api/v1/manga/pages" } }
        val pages = fixture.requests.single { it.path == "/api/v1/manga/pages" }.body
        assertEquals(904202, pages.getInt("mediaId"))
        assertEquals("downloaded-1", pages.getString("chapterId"))
        assertEquals("fixture-source", pages.getString("provider"))
        compose.onNodeWithTag("manga-reader").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("manga-reader").assertDoesNotExist()
        awaitText("Downloaded fixture chapter")
    }

    @Test fun unavailableProviderFallsBackToExistingDownloads() = fixture(unavailable = true) { fixture ->
        awaitText("Downloaded fixture chapter")
        compose.onNodeWithText("Source unavailable. Showing downloaded chapters.").assertIsDisplayed()
        assertEquals(1, fixture.requests.count { it.path == "/api/v1/manga/chapters" })
        assertTrue(fixture.requests.none { it.method == "DELETE" })
    }

    @Test fun explicitRefreshAndConfirmedMappingResetRefetchTheRightChapterList() = fixture { fixture ->
        awaitText("Cached fixture chapter")
        assertTrue(fixture.requests.none { it.method == "DELETE" })
        compose.onNodeWithTag("manga-refresh-chapters").performScrollTo().performTvClick()
        awaitText("Fresh fixture chapter")
        val mutation = fixture.requests.indexOfFirst { it.path == "/api/v1/manga/entry/cache" }
        assertTrue(mutation >= 0)
        assertEquals("DELETE", fixture.requests[mutation].method)
        assertEquals(setOf("mediaId"), fixture.requests[mutation].body.keys().asSequence().toSet())
        assertEquals(904202, fixture.requests[mutation].body.getInt("mediaId"))
        assertEquals("/api/v1/manga/chapters", fixture.requests[mutation + 1].path)

        compose.onNodeWithTag("manga-reset-match").performScrollTo().performTvClick()
        compose.onNodeWithText("Cancel").assertIsFocused()
        pressBack()
        assertTrue(fixture.requests.none { it.path == "/api/v1/manga/remove-mapping" })
        compose.onNodeWithTag("manga-fix-match").assertIsFocused()
        compose.onNodeWithTag("manga-reset-match").performScrollTo().performTvClick()
        compose.onNodeWithText("Confirm").performTvClick()
        awaitText("Automatic fixture chapter")
        val reset = fixture.requests.indexOfFirst { it.path == "/api/v1/manga/remove-mapping" }
        assertEquals("POST", fixture.requests[reset].method)
        assertEquals(setOf("mediaId", "provider"), fixture.requests[reset].body.keys().asSequence().toSet())
        assertEquals(904202, fixture.requests[reset].body.getInt("mediaId"))
        assertEquals("fixture-source", fixture.requests[reset].body.getString("provider"))
        assertEquals("/api/v1/manga/chapters", fixture.requests[reset + 1].path)
        compose.onNodeWithTag("manga-reset-match").assertDoesNotExist()
        compose.onNodeWithTag("manga-fix-match").assertIsFocused()
        assertEquals(1, fixture.requests.count { it.path == "/api/v1/manga/entry/cache" })
    }

    private fun awaitText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.waitForIdle()
    }

    private fun fixture(offline: Boolean = false, unavailable: Boolean = false, test: (Fixture) -> Unit) {
        val fixture = Fixture(offline, unavailable)
        var showing by mutableStateOf(true)
        fixture.server.start(InetAddress.getByName("127.0.0.1"), 0)
        val client = SeanimeApiClient(fixture.server.url("/").newBuilder().host("127.0.0.1").build().toString())
        try {
            val repo = SeanimeRepository(client)
            compose.setContent { SeanimeTheme {
                if (showing) MangaTitleScreen(repo, MediaCard(904202, "Owned workflow fixture", isManga = true), downloadedOnly = false, onBack = { showing = false })
            } }
            test(fixture)
        } finally {
            compose.runOnIdle { showing = false }
            compose.waitForIdle()
            client.close()
            fixture.server.shutdown()
        }
    }

    private data class Request(val method: String, val path: String, val body: JSONObject)
    private class Fixture(offline: Boolean, unavailable: Boolean) {
        val requests = CopyOnWriteArrayList<Request>()
        private val refreshed = AtomicBoolean()
        private val mapped = AtomicBoolean(true)
        val server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = request.body.readUtf8().let { if (it.isBlank()) JSONObject() else JSONObject(it) }
                requests += Request(request.method.orEmpty(), path, body)
                return when (path) {
                    "/api/v1/status" -> envelope(jsonObject("isOffline" to offline))
                    "/api/v1/extensions/list/manga-provider" -> envelope(JSONArray().put(jsonObject("id" to "fixture-source", "name" to "Fixture source")))
                    "/api/v1/manga/preferences" -> envelope(JSONObject())
                    "/api/v1/settings" -> envelope(jsonObject("manga" to jsonObject("defaultMangaProvider" to "fixture-source", "mangaAutoUpdateProgress" to false)))
                    "/api/v1/manga/chapters" -> if (unavailable) MockResponse().setResponseCode(503).setBody("""{"error":"Fixture provider unavailable"}""")
                        else envelope(jsonObject("chapters" to JSONArray().put(chapter("online-1", when {
                            !mapped.get() -> "Automatic fixture chapter"
                            refreshed.get() -> "Fresh fixture chapter"
                            else -> "Cached fixture chapter"
                        }))))
                    "/api/v1/manga/entry/cache" -> { refreshed.set(true); envelope(true) }
                    "/api/v1/manga/get-mapping" -> envelope(jsonObject("mangaId" to if (mapped.get()) "manual-edition" else JSONObject.NULL))
                    "/api/v1/manga/remove-mapping" -> { mapped.set(false); envelope(true) }
                    "/api/v1/manga/downloaded-chapters/904202" -> envelope(JSONArray().put(jsonObject("provider" to "fixture-source", "chapters" to JSONArray().put(chapter("downloaded-1", "Downloaded fixture chapter")))))
                    "/api/v1/manga/pages" -> envelope(jsonObject("isDownloaded" to true, "pages" to JSONArray()))
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"Unexpected fixture request"}""")
                }
            }
        } }
        private fun chapter(id: String, title: String) = jsonObject("id" to id, "title" to title, "chapter" to "1", "provider" to "fixture-source")
        private fun envelope(value: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(jsonObject("data" to value).toString())
    }
}
