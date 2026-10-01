package app.seanime.tv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.data.*
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
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NativeMangaPreferencesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun providerFiltersLoadAndSparseLanguageEditSurvivesProviderSwitches() = fixture { fixture ->
        awaitText("A English A")
        openFilter("language")
        compose.onNodeWithTag("manga-filter-language-choice-0").assertIsFocused()
        assertFocusedActionFitsVerticalViewport(compose, hasTestTag("manga-filter-language-choice-0"))
        NativeScreenshotEvidence.capture("manga-language-filter-remote-focus")
        choose("language", "fr")
        compose.onNodeWithTag("manga-filter-language-cancel").performTvClick()
        compose.onNodeWithTag("manga-language-filter").assertIsFocused()
        assertTrue(fixture.writes.isEmpty())
        openFilter("language")
        choose("language", "fr")
        compose.onNodeWithTag("manga-filter-language-save").performTvClick()
        awaitText("A French A")
        compose.onNodeWithTag("manga-language-filter").assertIsFocused()
        val body = fixture.writes.single()
        assertEquals(setOf("filter"), body.keys().asSequence().toSet())
        assertEquals(setOf("provider", "language"), body.getJSONObject("filter").keys().asSequence().toSet())
        assertEquals("fr", body.getJSONObject("filter").getString("language"))
        switchProvider("Source B")
        awaitText("B French B")
        switchProvider("Source A")
        awaitText("A French A")
    }

    @Test fun failedScanlatorSaveRetainsSelectionForRetryAndLeavesLanguageUntouched() = fixture(failFilterOnce = true) { fixture ->
        awaitText("A English A")
        openFilter("scanlators")
        choose("scanlators", "All scanlators")
        choose("scanlators", "Group B")
        compose.onNodeWithTag("manga-filter-scanlators-save").performTvClick()
        awaitText("Fixture filter save failed")
        compose.onNodeWithText("✓ Group B").assertExists()
        compose.onNodeWithTag("manga-filter-scanlators-save").assertIsFocused()
        NativeScreenshotEvidence.capture("manga-scanlator-filter-retry-draft")
        compose.onNodeWithTag("manga-filter-scanlators-save").performTvClick()
        awaitText("A English B")
        compose.onNodeWithTag("manga-scanlator-filter").assertIsFocused()
        assertEquals(2, fixture.writes.size)
        fixture.writes.forEach { body ->
            val filter = body.getJSONObject("filter")
            assertEquals(setOf("provider", "scanlators"), filter.keys().asSequence().toSet())
            assertEquals("Group B", filter.getJSONArray("scanlators").getString(0))
        }
    }

    @Test fun matchingPreferenceEventReloadsCurrentProviderFilters() = fixture { fixture ->
        awaitText("A English A")
        fixture.api.connectEvents()
        compose.waitUntil(10_000) { fixture.socket.get() != null && fixture.api.connected.value }
        fixture.changeLanguage("source-a", "fr")
        fixture.socket.get().send(jsonObject("type" to "manga-preferences-updated", "payload" to jsonObject("mediaIds" to JSONArray().put(904203))).toString())
        awaitText("A French A")
        assertTrue(fixture.preferenceReads.get() >= 2)
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun downloadedOfflineFiltersNeedNoSourcePreferenceRequests() = fixture(offline = true) { fixture ->
        awaitText("A English A")
        openFilter("language")
        choose("language", "fr")
        compose.onNodeWithTag("manga-filter-language-save").performTvClick()
        awaitText("A French A")
        compose.onNodeWithTag("manga-language-filter").assertIsFocused()
        assertEquals(0, fixture.preferenceReads.get())
        assertTrue(fixture.writes.isEmpty())
        assertTrue(fixture.paths.none { it in setOf("/api/v1/extensions/list/manga-provider", "/api/v1/manga/chapters", "/api/v1/manga/get-mapping") })
    }

    private fun openFilter(field: String) {
        val tag = if (field == "language") "manga-language-filter" else "manga-scanlator-filter"
        compose.onNodeWithTag("manga-chapter-filters").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performTvClick()
    }
    private fun choose(field: String, value: String) {
        val target = hasText(value) or hasText("✓ $value")
        compose.onNodeWithTag("manga-filter-$field-choices").performScrollToNode(target)
        compose.onNode(target and hasAnyAncestor(hasTestTag("manga-filter-$field"))).performTvClick()
    }
    private fun switchProvider(name: String) {
        compose.onNodeWithTag("manga-chapter-providers").performScrollToNode(hasText(name))
        compose.onNodeWithText(name).performTvClick()
    }
    private fun awaitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun fixture(offline: Boolean = false, failFilterOnce: Boolean = false, block: (Fixture) -> Unit) {
        val fixture = Fixture(offline, failFilterOnce)
        fixture.server.start(InetAddress.getByName("127.0.0.1"), 0)
        fixture.api = SeanimeApiClient(fixture.server.url("/").newBuilder().host("127.0.0.1").build().toString())
        val repo = SeanimeRepository(fixture.api)
        var visible by mutableStateOf(true)
        try {
            compose.setContent { SeanimeTheme { if (visible) MangaTitleScreen(repo, MediaCard(904203, "Owned filter fixture", isManga = true), false, { visible = false }) } }
            block(fixture)
        } finally {
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            fixture.api.close(); fixture.server.shutdown()
        }
    }

    private class Fixture(val offline: Boolean, failFilterOnce: Boolean) : Dispatcher() {
        lateinit var api: SeanimeApiClient
        val server = MockWebServer().apply { dispatcher = this@Fixture }
        val socket = AtomicReference<WebSocket>()
        val preferenceReads = AtomicInteger()
        val writes = CopyOnWriteArrayList<JSONObject>()
        val paths = CopyOnWriteArrayList<String>()
        private val failFilter = AtomicBoolean(failFilterOnce)
        private val entry = jsonObject("provider" to "source-a", "filters" to jsonObject(
            "source-a" to jsonObject("language" to "en", "scanlators" to JSONArray().put("Group A")),
            "source-b" to jsonObject("language" to "fr", "scanlators" to JSONArray().put("Group B"))))
        @Synchronized fun changeLanguage(provider: String, language: String) { entry.getJSONObject("filters").getJSONObject(provider).put("language", language) }
        @Synchronized private fun currentEntry() = JSONObject(entry.toString())
        @Synchronized private fun patch(body: JSONObject): JSONObject {
            if (body.has("provider")) entry.put("provider", body.getString("provider"))
            body.optJSONObject("filter")?.let { filter ->
                val current = entry.getJSONObject("filters").getJSONObject(filter.getString("provider"))
                if (filter.has("language")) current.put("language", filter.getString("language"))
                if (filter.has("scanlators")) current.put("scanlators", filter.getJSONArray("scanlators"))
            }
            return currentEntry()
        }
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty().substringBefore('?')
            paths += path
            return when (path) {
                "/api/v1/status" -> envelope(jsonObject("isOffline" to offline, "serverReady" to true))
                "/api/v1/extensions/list/manga-provider" -> envelope(JSONArray().put(jsonObject("id" to "source-a", "name" to "Source A")).put(jsonObject("id" to "source-b", "name" to "Source B")))
                "/api/v1/settings" -> envelope(jsonObject("manga" to jsonObject("defaultMangaProvider" to "source-a")))
                "/api/v1/manga/preferences" -> { preferenceReads.incrementAndGet(); envelope(jsonObject("entries" to jsonObject("904203" to currentEntry()))) }
                "/api/v1/manga/preferences/904203" -> {
                    val body = JSONObject(request.body.readUtf8()); writes += body
                    if (body.has("filter") && failFilter.compareAndSet(true, false)) MockResponse().setResponseCode(503).setBody("""{"error":"Fixture filter save failed"}""")
                    else envelope(patch(body))
                }
                "/api/v1/manga/chapters" -> envelope(jsonObject("chapters" to chapters(JSONObject(request.body.readUtf8()).getString("provider"))))
                "/api/v1/manga/get-mapping" -> envelope(jsonObject("mangaId" to JSONObject.NULL))
                "/api/v1/manga/downloaded-chapters/904203" -> envelope(JSONArray().put(jsonObject("provider" to "source-a", "chapters" to chapters("source-a"))))
                "/events" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { socket.set(webSocket) }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                })
                else -> MockResponse().setResponseCode(404).setBody("""{"error":"Unexpected fixture route"}""")
            }
        }
        private fun chapters(provider: String): JSONArray {
            fun chapter(id: String, title: String, language: String, group: String) = jsonObject("id" to id, "title" to title, "chapter" to "1", "provider" to provider, "language" to language, "scanlator" to group)
            return if (provider == "source-a") JSONArray().put(chapter("a-en-a", "A English A", "en", "Group A"))
                .put(chapter("a-fr-a", "A French A", "fr", "Group A")).put(chapter("a-en-b", "A English B", "en", "Group B"))
            else JSONArray().put(chapter("b-fr-b", "B French B", "fr", "Group B")).put(chapter("b-en-b", "B English B", "en", "Group B"))
        }
        private fun envelope(value: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(jsonObject("data" to value).toString())
    }
}
