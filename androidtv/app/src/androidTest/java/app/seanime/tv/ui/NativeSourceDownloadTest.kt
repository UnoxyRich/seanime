package app.seanime.tv.ui

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeJson
import app.seanime.tv.data.SeanimeRepository
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeSourceDownloadTest {
    @get:Rule val compose = createComposeRule()

    @Test fun remoteSearchDownloadUsesFoldersSupportsCancelAndRetriesServerFailure() = fixture("torrent") { fixture ->
        awaitSelectedProvider(fixture)
        verifySourceFocusBounds(fixture)
        compose.onNodeWithText("Find sources").performScrollTo().performTvClick()
        awaitTag("source-download-fixture-hash")
        compose.onNodeWithTag("source-download-fixture-hash").performScrollTo().performTvClick()
        awaitTag("source-download-confirm")
        compose.onNodeWithTag("source-download-cancel").assertIsFocused().performTvClick()
        assertTrue(fixture.downloads.isEmpty())
        compose.onNodeWithTag("source-download-fixture-hash").assertIsFocused().performTvClick()
        awaitText("Open Fixture series")
        compose.onNodeWithTag("source-download-folders").performScrollToNode(hasTestTag("source-download-child-Fixture series"))
        compose.onNodeWithTag("source-download-child-Fixture series").performTvClick()
        awaitText("/media/Anime/Fixture series")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("source-download-confirm") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("source-download-confirm").performTvClick()
        compose.waitUntil(10_000) { fixture.downloads.size == 1 && compose.onAllNodes(hasTestTag("source-download-confirm") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("source-download-folders").performScrollToNode(hasText("Fixture download failure"))
        awaitText("Fixture download failure")
        assertEquals(1, fixture.downloads.size)
        compose.onNodeWithTag("source-download-confirm").performTvClick()
        compose.waitUntil(10_000) { fixture.downloads.size == 2 && compose.onAllNodesWithTag("source-download-confirm").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("source-content").performScrollToNode(hasText("Download requested. Manage its progress in Downloads."))
        awaitText("Download requested. Manage its progress in Downloads.")
        assertEquals(2, fixture.downloads.size)
        val request = fixture.downloads.last()
        assertEquals("/api/v1/torrent-client/download", request.first)
        assertEquals("/media/Anime/Fixture series", request.second.getString("destination"))
        assertEquals(21, request.second.getJSONObject("media").getInt("id"))
        assertEquals("fixture-hash", request.second.getJSONArray("torrents").getJSONObject(0).getString("infoHash"))
        compose.onNodeWithTag("source-download-fixture-hash").assertIsFocused()
        assertFocusedSourceActionFitsViewport("source-download-fixture-hash")
        assertEquals(0, fixture.playRequests.get())
        NativeScreenshotEvidence.capture("source-search-download-requested")
    }

    @Test fun remoteDebridDownloadUsesChosenConfiguredFolder() = fixture("debrid") { fixture ->
        awaitSelectedProvider(fixture)
        verifySourceFocusBounds(fixture)
        compose.onNodeWithText("Find sources").performScrollTo().performTvClick()
        awaitTag("source-download-fixture-hash")
        NativeUiStepWatchdog("source-debrid-download-steps").use { steps ->
            steps.step("Open release destination dialog") {
                compose.onNodeWithTag("source-download-fixture-hash").performScrollTo().performTvClick()
            }
            steps.step("Wait for configured destination choices") { awaitTag("source-download-root-1") }
            steps.step("Select debrid download") { compose.onNodeWithTag("source-download-debrid").performTvClick() }
            steps.step("Choose the additional configured folder") {
                compose.onNodeWithTag("source-download-root-1").performScrollTo().performTvClick()
            }
            steps.step("Wait for selected folder") { awaitText("/storage/Extra") }
            steps.step("Wait for enabled download confirmation") {
                compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("source-download-confirm") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            }
            steps.step("Confirm debrid download") { compose.onNodeWithTag("source-download-confirm").performTvClick() }
            steps.step("Wait for download request and dialog dismissal") {
                compose.waitUntil(10_000) { fixture.downloads.size == 1 && compose.onAllNodesWithTag("source-download-confirm").fetchSemanticsNodes().isEmpty() }
            }
            steps.step("Check source download confirmation message") {
                compose.onNodeWithTag("source-content").performScrollToNode(hasText("Release added to debrid for download. Manage its progress in Downloads."))
                awaitText("Release added to debrid for download. Manage its progress in Downloads.")
            }
            steps.step("Check exact download request and restored source focus") {
                val request = fixture.downloads.single()
                assertEquals("/api/v1/debrid/torrents", request.first)
                assertEquals("/storage/Extra", request.second.getString("destination"))
                assertFalse(request.second.has("smartSelect"))
                assertEquals(0, fixture.playRequests.get())
                compose.onNodeWithTag("source-download-fixture-hash").assertIsFocused()
                assertFocusedSourceActionFitsViewport("source-download-fixture-hash")
            }
            steps.step("Capture completed debrid download") { NativeScreenshotEvidence.capture("source-debrid-download-requested") }
        }
    }

    private fun awaitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun verifySourceFocusBounds(fixture: DownloadFixture) {
        compose.onNodeWithTag("source-provider-fixture").performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        awaitFocused("source-provider-fixture")
        assertFocusedSourceActionFitsViewport("source-provider-fixture")
        NativeScreenshotEvidence.capture("source-provider-${fixture.name}-focused")

        compose.onNodeWithTag("source-mode-Device").performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        awaitFocused("source-mode-Device")
        assertFocusedActionFitsViewport(compose, "source-mode-Device")
        val firstX = compose.onNodeWithTag("source-mode-Device").fetchSemanticsNode().positionInRoot.x
        for (tag in listOf("source-mode-Online", "source-mode-Torrent", "source-mode-Debrid", "source-back")) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocused(tag)
            assertFocusedActionFitsViewport(compose, tag)
        }
        if (fixture.name == "debrid") assertTrue("The narrow source viewport must scroll to reach Back",
            compose.onNodeWithTag("source-mode-Device").fetchSemanticsNode().positionInRoot.x < firstX)
        NativeScreenshotEvidence.capture("source-modes-${fixture.name}-back-focused")
    }

    private fun awaitFocused(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun assertFocusedSourceActionFitsViewport(tag: String) {
        compose.onNodeWithTag(tag).assertIsFocused().assertIsDisplayed()
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val viewport = generateSequence(node.parent) { it.parent }
            .first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }.boundsInRoot
        // Button semantics precede TV Material's 1.1x focus layer. Use the full
        // measured size/position so clipped semantics cannot hide an overflow.
        val dx = node.size.width * .05f
        val dy = node.size.height * .05f
        val visual = Rect(node.positionInRoot.x - dx, node.positionInRoot.y - dy,
            node.positionInRoot.x + node.size.width + dx, node.positionInRoot.y + node.size.height + dy)
        assertTrue("$tag scaled visual bounds $visual exceed source viewport $viewport",
            visual.left >= viewport.left - 1f && visual.right <= viewport.right + 1f &&
                visual.top >= viewport.top - 1f && visual.bottom <= viewport.bottom + 1f)
    }

    private fun awaitSelectedProvider(fixture: DownloadFixture) {
        val started = SystemClock.elapsedRealtime()
        fun remaining(): Long = (10_000 - (SystemClock.elapsedRealtime() - started)).also {
            check(it > 0) { "Provider startup exceeded its total 10000ms budget" }
        }
        var stage = "provider and settings HTTP responses"
        NativeUiStepWatchdog("source-provider-startup-${fixture.name}").use { steps ->
            try {
                steps.step("Wait for provider and settings response bodies") {
                    compose.waitUntil(remaining()) {
                        fixture.completedBodies.containsAll(listOf("/api/v1/extensions/list/anime-torrent-provider", "/api/v1/settings"))
                    }
                }
                stage = "selected provider before lazy scroll"
                steps.step("Record provider nodes before scrolling") {
                    fixture.recordLayout()
                    val selected = compose.onAllNodesWithText("✓ Fixture provider").fetchSemanticsNodes()
                    val unselected = compose.onAllNodesWithText("Fixture provider").fetchSemanticsNodes()
                    fixture.record("UI before scroll selected=${selected.map { it.boundsInRoot }} unselected=${unselected.map { it.boundsInRoot }}")
                }
                stage = "lazy scroll to selected provider"
                steps.step("Scroll source list to selected provider") {
                    remaining()
                    compose.onNodeWithTag("source-content").performScrollToNode(hasText("✓ Fixture provider"))
                }
                stage = "selected provider name and visibility"
                steps.step("Assert selected provider is visible") {
                    compose.waitUntil(remaining()) { compose.onAllNodesWithText("✓ Fixture provider").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("✓ Fixture provider").assertIsDisplayed()
                    remaining()
                    fixture.record("UI selected provider visible after ${SystemClock.elapsedRealtime() - started}ms")
                }
            } catch (failure: Throwable) {
                fixture.record("FAILED $stage after ${SystemClock.elapsedRealtime() - started}ms: ${failure.javaClass.simpleName}: ${failure.message}")
                fixture.recordLayout()
                steps.step("Capture provider startup failure screen") {
                    runCatching { NativeScreenshotEvidence.capture("source-provider-startup-${fixture.name}-failure") }
                        .onFailure { fixture.record("Screenshot unavailable: ${it.javaClass.simpleName}: ${it.message}") }
                }
                steps.step("Capture provider startup failure semantics") {
                    runCatching { compose.onRoot(useUnmergedTree = true).printToString() }
                        .onSuccess { fixture.record("UI semantic tree:\n$it") }
                        .onFailure { fixture.record("Semantic tree unavailable: ${it.javaClass.simpleName}: ${it.message}") }
                }
                throw failure
            }
        }
    }

    private fun fixture(name: String, test: (DownloadFixture) -> Unit) {
        val fixture = DownloadFixture(name)
        val server = MockWebServer().apply { dispatcher = fixture; start(InetAddress.getByName("127.0.0.1"), 0) }
        val httpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).eventListener(object : EventListener() {
                override fun callStart(call: Call) { fixture.record("CLIENT start ${call.request().method} ${call.request().url.encodedPath}") }
                override fun responseHeadersEnd(call: Call, response: Response) { fixture.record("CLIENT response ${response.code} ${call.request().url.encodedPath}") }
                override fun responseBodyEnd(call: Call, byteCount: Long) {
                    val path = call.request().url.encodedPath
                    fixture.record("CLIENT body complete $path bytes=$byteCount")
                    fixture.completedBodies.add(path)
                }
                override fun callFailed(call: Call, ioe: IOException) { fixture.record("CLIENT failure ${call.request().url.encodedPath}: ${ioe.javaClass.simpleName}: ${ioe.message}") }
            }).build()
        val api = SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString(), httpClient = httpClient)
        try {
            val repo = SeanimeRepository(api)
            val media = SeanimeJson.media(JSONObject("""{"id":21,"title":"Fixture anime"}"""))
            val episode = SeanimeJson.episode(JSONObject("""{"episodeNumber":3,"aniDBEpisode":"S2","progressNumber":0,"baseAnime":{"id":21}}"""))
            val providerObserver: ((String) -> Unit)? = if (name == "torrent") { event -> fixture.record("PROVIDER $event") } else null
            compose.setContent { SeanimeTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp)) {
                    // The callback-off flow also covers a narrower source panel, where the mode row must scroll.
                    Box(if (name == "debrid") Modifier.widthIn(max = 440.dp) else Modifier.fillMaxSize()) {
                        SourceScreen(media, episode, repo, { fixture.playRequests.incrementAndGet() }, {}, initialMode = "Torrent",
                            listState = fixture.listState, onProviderDiagnostic = providerObserver)
                    }
                }
            } }
            test(fixture)
        } finally { api.close(); server.shutdown() }
    }

    private class DownloadFixture(val name: String) : Dispatcher() {
        val downloads = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val playRequests = AtomicInteger()
        val completedBodies = CopyOnWriteArrayList<String>()
        val listState = LazyListState()
        fun recordLayout() = InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val layout = listState.layoutInfo
            record("LAYOUT totalItems=${layout.totalItemsCount} visible=${layout.visibleItemsInfo.map { "index=${it.index},key=${it.key},size=${it.size},offset=${it.offset}" }}")
        }
        private val started = SystemClock.elapsedRealtime()
        private val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "native-test-diagnostics")
            .apply { check(isDirectory || mkdirs()) }.resolve("source-provider-http-$name.txt").apply { writeText("") }
        @Synchronized fun record(message: String) {
            val line = "${SystemClock.elapsedRealtime() - started}ms $message"
            line.lineSequence().forEach { Log.i("NativeSourceStartup", it) }
            output.appendText("$line\n")
        }
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            record("SERVER request ${request.method} $path")
            if (path in setOf("/api/v1/torrent-client/download", "/api/v1/debrid/torrents")) {
                downloads.add(path to JSONObject(request.body.readUtf8()))
                return if (path == "/api/v1/torrent-client/download" && downloads.size == 1)
                    MockResponse().setResponseCode(500).setBody("""{"error":"Fixture download failure"}""")
                else MockResponse().setBody("""{"data":true}""")
            }
            val data: Any = when (path) {
                "/api/v1/extensions/list/anime-torrent-provider" -> JSONArray().put(JSONObject().put("id", "fixture").put("name", "Fixture provider"))
                "/api/v1/extensions/list/anime-entry-episode-tabs" -> JSONArray()
                "/api/v1/torrent/search" -> JSONObject("""{"torrents":[{"name":"Fixture release","infoHash":"fixture-hash","magnetLink":"magnet:?xt=fixture","seeders":3}]}""")
                "/api/v1/settings" -> JSONObject("""{"library":{"libraryPath":"/media/Anime","libraryPaths":["/storage/Extra"]}}""")
                "/api/v1/directory-selector" -> {
                    val input = JSONObject(request.body.readUtf8()).getString("input")
                    JSONObject().put("fullPath", input).put("exists", true).put("basePath", input.substringBeforeLast('/'))
                        .put("content", JSONArray().apply { if (input == "/media/Anime") put(JSONObject().put("folderName", "Fixture series").put("fullPath", "/media/Anime/Fixture series")) })
                }
                else -> JSONObject()
            }
            record("SERVER response prepared 200 $path")
            return MockResponse().setBody(JSONObject().put("data", data).toString())
        }
    }
}
