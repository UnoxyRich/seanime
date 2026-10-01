package app.seanime.tv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
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

class NativeExistingDebridDownloadTest {
    @get:Rule val compose = createComposeRule()

    @Test fun remoteFolderPickerCancelsReturnsFocusAndRetriesAnExistingDebridTransfer() = fixture { fixture ->
        openPicker()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("debrid-download-cancel") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("debrid-download-cancel").performTvClick()
        awaitFocused("downloads-debrid-files-debrid-21")
        assertTrue(fixture.downloads.isEmpty())

        openPicker()
        awaitTag("debrid-download-root-1")
        compose.onNodeWithTag("debrid-download-root-1").performScrollTo().performTvClick()
        awaitText("/storage/Extra")
        compose.onNodeWithTag("debrid-download-folders").performScrollToNode(hasTestTag("debrid-download-child-Fixture series"))
        compose.onNodeWithTag("debrid-download-child-Fixture series").performTvClick()
        awaitText("/storage/Extra/Fixture series")
        awaitEnabled("debrid-download-confirm")
        compose.onNodeWithTag("debrid-download-confirm").performTvClick()
        compose.waitUntil(10_000) { fixture.downloads.size == 1 && compose.onAllNodes(hasTestTag("debrid-download-confirm") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("debrid-download-folders").performScrollToNode(hasText("Fixture debrid failure"))
        compose.onNodeWithText("Fixture debrid failure").assertIsDisplayed()
        compose.onNodeWithText("Debrid download requested").assertDoesNotExist()
        compose.onNodeWithTag("debrid-download-confirm").performTvClick()
        compose.waitUntil(10_000) { fixture.downloads.size == 2 && compose.onAllNodesWithTag("debrid-download-confirm").fetchSemanticsNodes().isEmpty() }
        awaitFocused("downloads-debrid-files-debrid-21")
        val body = fixture.downloads.last()
        assertEquals("/storage/Extra/Fixture series", body.getString("destination"))
        assertEquals(fixture.torrent.toString(), body.getJSONObject("torrentItem").toString())
        assertEquals(setOf("torrentItem", "destination"), body.keys().asSequence().toSet())
        assertFocusedActionFitsViewport(compose, "downloads-debrid-files-debrid-21", verifyPixels = true)
        NativeScreenshotEvidence.capture("existing-debrid-download-requested")
    }

    @Test fun hardwareBackCancelsAndSuccessfulRemovedTransferReturnsToRefresh() = fixture { fixture ->
        openPicker()
        awaitFocused("debrid-download-cancel")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitFocused("downloads-debrid-files-debrid-21")
        assertTrue(fixture.downloads.isEmpty())
        fixture.failFirst.set(false)
        fixture.removeAfterDownload.set(true)
        openPicker()
        awaitEnabled("debrid-download-confirm")
        compose.onNodeWithTag("debrid-download-confirm").performTvClick()
        compose.waitUntil(10_000) { fixture.downloads.size == 1 && compose.onAllNodesWithText("No active torrents").fetchSemanticsNodes().isNotEmpty() }
        awaitFocused("downloads-refresh")
        assertEquals("/media/Anime", fixture.downloads.single().getString("destination"))
    }

    private fun openPicker() {
        awaitTag("downloads-debrid-files-debrid-21")
        compose.onNodeWithTag("downloads-debrid-files-debrid-21").performScrollTo().performTvClick()
        awaitTag("debrid-download-confirm")
    }
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitFocused(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitEnabled(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }

    private fun fixture(test: (DebridFixture) -> Unit) {
        val fixture = DebridFixture()
        MockWebServer().use { server ->
            server.dispatcher = fixture
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
                val repo = SeanimeRepository(api)
                compose.setContent { SeanimeTheme {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        FeatureScreen(TvFeature.DOWNLOADS, repo, {}, {}, resolveNativePluginDestination("/debrid"))
                    }
                } }
                test(fixture)
            }
        }
    }

    private class DebridFixture : Dispatcher() {
        val torrent = JSONObject("""{"id":"debrid-21","name":"Fixture debrid release","status":"downloaded","completionPercentage":100,"files":[{"id":4,"path":"special.mkv"}],"custom":"keep-me"}""")
        val downloads = CopyOnWriteArrayList<JSONObject>()
        val failFirst = AtomicBoolean(true)
        val removeAfterDownload = AtomicBoolean(false)
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path == "/api/v1/debrid/torrents/download") {
                downloads.add(JSONObject(request.body.readUtf8()))
                return if (failFirst.getAndSet(false)) MockResponse().setResponseCode(500).setBody("""{"error":"Fixture debrid failure"}""")
                else MockResponse().setBody("""{"data":true}""")
            }
            val data: Any = when (request.path) {
                "/api/v1/debrid/torrents" -> JSONArray().apply { if (!removeAfterDownload.get() || downloads.isEmpty()) put(torrent) }
                "/api/v1/settings" -> JSONObject("""{"library":{"libraryPath":"/media/Anime","libraryPaths":["/storage/Extra"]}}""")
                "/api/v1/directory-selector" -> {
                    val input = JSONObject(request.body.readUtf8()).getString("input")
                    JSONObject().put("fullPath", input).put("exists", true).put("basePath", input.substringBeforeLast('/'))
                        .put("content", JSONArray().apply { if (input == "/storage/Extra") put(JSONObject().put("folderName", "Fixture series").put("fullPath", "$input/Fixture series")) })
                }
                else -> JSONObject()
            }
            return MockResponse().setBody(JSONObject().put("data", data).toString())
        }
    }
}
