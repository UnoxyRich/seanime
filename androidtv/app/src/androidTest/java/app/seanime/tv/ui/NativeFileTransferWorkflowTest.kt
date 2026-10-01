package app.seanime.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.seanime.tv.data.*
import app.seanime.tv.platform.NativeLibraryFiles
import app.seanime.tv.platform.NativeOwnedFile
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

class NativeFileTransferWorkflowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsBackupExportsExactBytesAndImportsOnlyAfterPreviewAndConfirmation() = fixture(TvFeature.SETTINGS) { fixture ->
        scroll("settings-library-index"); click("settings-library-index")
        await("metadata-export"); click("metadata-export")
        await("native-export-prepare"); click("native-export-prepare")
        await("native-export-error")
        compose.onNodeWithTag("native-export-error").assertTextEquals("Fixture export failure")
        assertTrue(fixture.saved.isEmpty())
        click("native-export-prepare"); await("native-export-save"); click("native-export-save")
        compose.waitUntil(10_000) { fixture.saved.size == 1 }
        assertEquals("application/json", fixture.saved.single().mimeType)
        assertArrayEquals(fixture.index.toString().toByteArray(), fixture.saved.single().bytes)
        click("native-export-close")
        scroll("metadata-root-0"); click("metadata-root-0")
        await("metadata-file-backup.json")
        scroll("metadata-file-backup.json"); click("metadata-file-backup.json")
        scroll("metadata-import-review"); click("metadata-import-review")
        compose.onAllNodesWithText("Cancel").onLast().performTvClick()
        assertTrue(fixture.imports.isEmpty())
        click("metadata-import-review"); compose.onNodeWithText("Confirm").performTvClick()
        compose.waitUntil(10_000) { fixture.imports.size == 1 }
        assertEquals("/androidtv/fixture/backup.json", fixture.imports.single().getString("dataFilePath"))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Library index imported and verified. Refresh the Library to see the new matches.").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun logsProfileExportReachesBinaryEndpointAndPlatformSaveBoundary() = fixture(TvFeature.LOGS) { fixture ->
        scroll("profile-export-heap"); click("profile-export-heap")
        await("native-export-prepare"); click("native-export-prepare")
        await("native-export-save"); click("native-export-save")
        compose.waitUntil(10_000) { fixture.saved.size == 1 }
        assertTrue(fixture.paths.contains("/api/v1/memory/profile?heap=true"))
        assertEquals("seanime-heap.pprof", fixture.saved.single().filename)
        assertEquals("application/octet-stream", fixture.saved.single().mimeType)
        assertEquals("fixture profile", fixture.saved.single().bytes.toString(Charsets.UTF_8))
    }

    @Test fun torrentRenamePreservesDraftOnFailureAndMoveRequiresReview() = fixture(TvFeature.DOWNLOADS) { fixture ->
        await("torrent-rename-hash-21"); click("torrent-rename-hash-21")
        await("torrent-name-editor")
        compose.onNodeWithTag("torrent-name-editor").performTextReplacement("New display name")
        click("text-entry-save"); await("torrent-rename-confirm")
        assertTrue(fixture.changes.isEmpty())
        click("torrent-rename-confirm"); await("torrent-rename-error")
        compose.onNodeWithText("Change Fixture release to New display name? File names stay unchanged.").assertExists()
        click("torrent-rename-confirm")
        compose.waitUntil(10_000) { fixture.changes.size == 2 && compose.onAllNodesWithTag("torrent-rename-confirm").fetchSemanticsNodes().isEmpty() }
        assertEquals("New display name", fixture.changes.last().getString("name"))
        click("torrent-move-hash-21"); await("torrent-move-confirm")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("torrent-move-confirm") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        click("torrent-move-confirm"); compose.onAllNodesWithText("Cancel").onLast().performTvClick()
        assertEquals(2, fixture.changes.size)
        click("torrent-move-confirm"); compose.onNodeWithText("Confirm").performTvClick()
        compose.waitUntil(10_000) { fixture.changes.size == 3 && compose.onAllNodesWithTag("torrent-move-confirm").fetchSemanticsNodes().isEmpty() }
        assertEquals("move-storage", fixture.changes.last().getString("action"))
        assertEquals("/androidtv/fixture", fixture.changes.last().getString("dir"))
    }

    private fun await(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun click(tag: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes()
                .any { (it.root as ViewRootForTest).view.hasWindowFocus() }
        }
        compose.onNodeWithTag(tag).performTvClick()
    }
    private fun scroll(tag: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(tag))
        await(tag)
    }
    private fun fixture(feature: TvFeature, test: (Fixture) -> Unit) {
        val fixture = Fixture()
        MockWebServer().use { server ->
            server.dispatcher = fixture; server.start(InetAddress.getByName("127.0.0.1"), 0)
            // Match production's permitted loopback origin. "localhost" is intentionally
            // not a cleartext exception in Android's network security configuration.
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
                val repo = SeanimeRepository(api)
                compose.setContent { SeanimeTheme { CompositionLocalProvider(LocalNativeLibraryFiles provides fixture) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        FeatureScreen(feature, repo, {}, {}, if (feature == TvFeature.DOWNLOADS) resolveNativePluginDestination("/torrent-list") else null)
                    }
                } } }
                test(fixture)
            }
        }
    }
    private class Fixture : Dispatcher(), NativeLibraryFiles {
        val index = JSONArray("""[{"path":"/androidtv/fixture/Episode.mkv","mediaId":21,"locked":true}]""")
        val saved = CopyOnWriteArrayList<NativeExportFile>()
        val imports = CopyOnWriteArrayList<JSONObject>()
        val changes = CopyOnWriteArrayList<JSONObject>()
        val paths = CopyOnWriteArrayList<String>()
        val failExport = AtomicBoolean(true)
        val failRename = AtomicBoolean(true)
        @Volatile var torrentName = "Fixture release"
        override suspend fun roots() = listOf(NativeOwnedFile("/androidtv/fixture", "Fixture storage", true))
        override suspend fun list(path: String) = listOf(NativeOwnedFile("/androidtv/fixture/backup.json", "backup.json", false))
        override suspend fun read(path: String) = index.toString().toByteArray()
        override fun save(file: NativeExportFile) { saved.add(file) }
        override fun dispatch(request: RecordedRequest): MockResponse {
            paths.add(request.path.orEmpty())
            val data: Any = when (request.path) {
                "/api/v1/settings" -> JSONObject("""{"library":{"libraryPath":"/androidtv/fixture"},"torrent":{"defaultTorrentClient":"seanime"}}""")
                "/api/v1/library/local-files" -> if (imports.isEmpty()) JSONArray() else index
                "/api/v1/library/local-files/dump" -> return if (failExport.getAndSet(false)) MockResponse().setResponseCode(503).setBody("""{"error":"Fixture export failure"}""")
                    else MockResponse().setBody(index.toString())
                "/api/v1/library/local-files/import" -> { imports.add(JSONObject(request.body.readUtf8())); true }
                "/api/v1/logs/latest" -> "Fixture server log"
                "/api/v1/memory/profile?heap=true" -> return MockResponse().setBody("fixture profile")
                "/api/v1/torrent-client/list" -> JSONArray().put(JSONObject().put("hash", "hash-21").put("name", torrentName).put("status", "paused").put("contentPath", "/old").put("progress", 0.5))
                "/api/v1/directory-selector" -> JSONObject().put("exists", true).put("fullPath", JSONObject(request.body.readUtf8()).getString("input")).put("content", JSONArray())
                "/api/v1/torrent-client/action" -> {
                    val body = JSONObject(request.body.readUtf8()); changes.add(body)
                    if (body.optString("action") == "rename") {
                        if (failRename.getAndSet(false)) return MockResponse().setResponseCode(503).setBody("""{"error":"Fixture rename failure"}""")
                        torrentName = body.getString("name")
                    }
                    true
                }
                else -> JSONObject()
            }
            return MockResponse().setBody(JSONObject().put("data", data).toString())
        }
    }
}
