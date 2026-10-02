package app.seanime.tv.ui

import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.ViewRootForTest
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
import org.junit.rules.RuleChain
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class NativeLibraryManagementTest {
    private val compose = createComposeRule()
    private val dpad = TvDpadInputRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(dpad).around(compose)

    @Test fun selectedFileActionCancelsThenRetainsFalseAckForExactRetry() = fixture(failBulkOnce = true) { fixture ->
        selectTwoFiles()
        scrollMain("library-selected-actions").performTvClick()
        chooseAction("lock")
        compose.onNodeWithTag("library-bulk-confirm-cancel").assertIsFocused().performTvClick()
        assertTrue(fixture.bulk.isEmpty())
        chooseAction("lock")
        compose.onNodeWithTag("library-bulk-apply").performTvClick()
        awaitTag("library-bulk-error")
        compose.onNodeWithTag("library-bulk-confirmation").assertExists()
        NativeScreenshotEvidence.capture("library-multi-file-confirm-retry")
        compose.onNodeWithTag("library-bulk-apply").performTvClick()
        awaitFocused("library-tools-refresh")
        assertEquals(2, fixture.bulk.size)
        fixture.bulk.forEach { body ->
            assertEquals(setOf("paths", "action"), body.keys().asSequence().toSet())
            assertEquals(setOf("/owned/one.mkv", "/owned/sub/two.mkv"), body.getJSONArray("paths").strings())
            assertEquals("lock", body.getString("action"))
        }
        compose.onNodeWithTag("library-selected-actions").assertDoesNotExist()
    }

    @Test fun folderActionsUseTypedAnimeAndExcludeUnindexedAndOtherFolderFiles() = fixture { fixture ->
        awaitTag("library-file-select-/owned/one.mkv")
        scrollMain("library-tab-Explorer").performTvClick()
        awaitTag("library-folder-actions-/owned")
        scrollMain("library-folder-actions-/empty").assertIsNotEnabled()
        scrollMain("library-folder-actions-/owned").performTvClick()
        chooseAction("match")
        awaitTag("media-picker-42")
        compose.onNodeWithTag("media-picker-42").performScrollTo().performTvClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("media-picker-42").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("media-picker-42").assertDoesNotExist()
        awaitFocused("library-bulk-confirm-cancel")
        compose.onNodeWithTag("library-bulk-confirm-cancel").assertIsFocused()
        compose.onNodeWithText("Anime: Owned anime fixture").assertIsDisplayed()
        NativeScreenshotEvidence.capture("library-folder-typed-match-preview")
        compose.onNodeWithTag("library-bulk-apply").performTvClick()
        awaitFocused("library-tools-refresh")
        val body = fixture.bulk.single()
        assertEquals(setOf("paths", "action", "mediaId"), body.keys().asSequence().toSet())
        assertEquals("match", body.getString("action")); assertEquals(42, body.getInt("mediaId"))
        assertEquals(setOf("/owned/one.mkv", "/owned/sub/two.mkv"), body.getJSONArray("paths").strings())
    }

    @Test fun renamePreviewCancelAndFailureRetainTheFilenameUntilServerAck() = fixture(failRenameOnce = true) { fixture ->
        awaitTag("library-file-rename-/owned/one.mkv")
        scrollMain("library-file-rename-/owned/one.mkv").performTvClick()
        awaitFocused("library-rename-edit")
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocused("library-rename-input")
        compose.enterTvTextAndDismissIme("library-rename-input", "Discarded filename.mkv", dpad)
        compose.onNodeWithTag("library-rename-input").assertTextContains("Discarded filename.mkv")
        assertTrue("Done must not submit a rename", fixture.renames.isEmpty())
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("text-entry-cancel").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("text-entry-dialog").fetchSemanticsNodes().isEmpty() }
        awaitFocused("library-rename-edit")
        compose.onNodeWithTag("library-rename-edit").assertTextContains("New filename: one.mkv")
        compose.onNodeWithTag("library-rename-preview").assertTextContains("Preview: /owned/one.mkv")
        assertTrue("Cancel must discard only the editor draft", fixture.renames.isEmpty())
        editRename("Renamed episode.mkv")
        assertTrue("Saving the filename draft must not submit a rename", fixture.renames.isEmpty())
        compose.onNodeWithTag("library-rename-preview").assertTextContains("Preview: /owned/Renamed episode.mkv")
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("library-rename-cancel").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(fixture.renames.isEmpty())
        awaitFocused("library-file-rename-/owned/one.mkv")
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        editRename("Renamed episode.mkv")
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("library-rename-cancel").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("library-rename-confirm").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitTag("library-rename-error")
        compose.onNodeWithTag("library-rename-preview").assertTextContains("Preview: /owned/Renamed episode.mkv")
        awaitFocused("library-rename-confirm")
        NativeScreenshotEvidence.capture("library-rename-retained-filename")
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocused("library-tools-refresh")
        assertEquals(2, fixture.renames.size)
        fixture.renames.forEach { body ->
            assertEquals(setOf("files"), body.keys().asSequence().toSet())
            val change = body.getJSONArray("files").getJSONObject(0)
            assertEquals(setOf("path", "newName"), change.keys().asSequence().toSet())
            assertEquals("/owned/one.mkv", change.getString("path")); assertEquals("Renamed episode.mkv", change.getString("newName"))
        }
    }

    @Test fun staleSelectionBlocksWholeActionAndBackClearsSelectionBeforeLeaving() = fixture { fixture ->
        selectTwoFiles()
        fixture.removeFile("/owned/sub/two.mkv")
        scrollMain("library-selected-actions").performTvClick()
        chooseAction("ignore")
        compose.onNodeWithTag("library-bulk-apply").performTvClick()
        awaitTag("library-bulk-error")
        assertTrue(fixture.bulk.isEmpty())
        compose.onNodeWithTag("library-bulk-confirm-cancel").performTvClick()
        // Dismissal restores the selected action after its window becomes active again.
        // Back then closes this action chooser without racing its pending return-focus request.
        awaitFocused("library-bulk-ignore")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        awaitFocused("library-selected-actions")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("library-selected-actions").assertDoesNotExist()
        awaitFocused("library-tools-refresh")
        assertEquals(0, fixture.closed.get())
    }

    private fun selectTwoFiles() {
        awaitTag("library-file-select-/owned/one.mkv")
        scrollMain("library-file-select-/owned/one.mkv").performTvClick()
        scrollMain("library-file-select-/owned/sub/two.mkv").performTvClick()
    }
    private fun chooseAction(action: String) {
        compose.onNodeWithTag("library-bulk-actions").performScrollToNode(hasTestTag("library-bulk-$action"))
        compose.onNodeWithTag("library-bulk-$action").performTvClick()
    }
    private fun editRename(name: String) {
        awaitFocused("library-rename-edit")
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocused("library-rename-input")
        compose.enterTvTextAndDismissIme("library-rename-input", name, dpad)
        compose.onNodeWithTag("library-rename-input").assertTextContains(name)
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("text-entry-cancel").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("text-entry-save").assertIsFocused().assertIsEnabled()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("text-entry-dialog").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("library-rename-edit").assertTextContains("New filename: $name")
        awaitFocused("library-rename-edit")
    }
    private fun remote(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
    }
    private fun scrollMain(tag: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }
    private fun awaitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitFocused(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes()
            .any { (it.root as ViewRootForTest).view.hasWindowFocus() }
    }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }.toSet()

    private fun fixture(failBulkOnce: Boolean = false, failRenameOnce: Boolean = false, block: (Fixture) -> Unit) {
        val fixture = Fixture(failBulkOnce, failRenameOnce)
        val server = MockWebServer().apply { dispatcher = fixture; start(InetAddress.getByName("127.0.0.1"), 0) }
        val api = SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        val repo = SeanimeRepository(api)
        var visible by mutableStateOf(true)
        try {
            compose.setContent { SeanimeTheme { NativeArtworkProvider(repo.client) {
                if (visible) LibraryTools(repo, {}, onClose = { fixture.closed.incrementAndGet(); visible = false })
            } } }
            block(fixture)
        } finally {
            compose.runOnIdle { visible = false }; compose.waitForIdle()
            api.close(); server.shutdown()
        }
    }

    private class Fixture(val failBulkOnce: Boolean, val failRenameOnce: Boolean) : Dispatcher() {
        val bulk = CopyOnWriteArrayList<JSONObject>()
        val renames = CopyOnWriteArrayList<JSONObject>()
        val closed = AtomicInteger()
        private val files = linkedMapOf("/owned/one.mkv" to file("/owned/one.mkv"), "/owned/sub/two.mkv" to file("/owned/sub/two.mkv"), "/other/three.mkv" to file("/other/three.mkv"))
        @Synchronized fun removeFile(path: String) { files.remove(path) }
        @Synchronized override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path == "/api/v1/library/local-files" && request.method == "GET" -> data(JSONArray(files.values.toList()))
            request.path == "/api/v1/library/local-files" && request.method == "PATCH" -> {
                val body = JSONObject(request.body.readUtf8()); bulk += body
                if (failBulkOnce && bulk.size == 1) data(false) else data(true)
            }
            request.path == "/api/v1/library/local-files/super-update" -> {
                val body = JSONObject(request.body.readUtf8()); renames += body
                if (failRenameOnce && renames.size == 1) MockResponse().setResponseCode(503).setBody("""{"error":"Owned rename failure"}""")
                else {
                    val change = body.getJSONArray("files").getJSONObject(0)
                    val old = change.getString("path"); val newName = change.getString("newName"); val target = old.substringBeforeLast('/') + "/" + newName
                    files.remove(old)?.let { files[target] = JSONObject(it.toString()).put("path", target).put("name", newName) }
                    data(true)
                }
            }
            request.path == "/api/v1/library/explorer/file-tree/refresh" -> data(true)
            request.path == "/api/v1/library/explorer/file-tree" -> data(jsonObject("root" to tree()))
            request.path == "/api/v1/library/collection" -> data(JSONArray().put(jsonObject("id" to 42, "title" to "Owned anime fixture")))
            else -> MockResponse().setResponseCode(404).setBody("""{"error":"Unexpected fixture request"}""")
        }
        private fun tree(): JSONObject {
            fun leaf(path: String) = jsonObject("path" to path, "name" to path.substringAfterLast('/'), "kind" to "file", "localFile" to files[path])
            val sub = jsonObject("path" to "/owned/sub", "name" to "sub", "kind" to "directory", "children" to JSONArray().apply { if (files.containsKey("/owned/sub/two.mkv")) put(leaf("/owned/sub/two.mkv")) })
            val owned = jsonObject("path" to "/owned", "name" to "owned", "kind" to "directory", "children" to JSONArray().apply {
                files.keys.filter { it.startsWith("/owned/") && '/' !in it.removePrefix("/owned/") }.forEach { put(leaf(it)) }
                put(sub); put(jsonObject("path" to "/owned/unindexed.mkv", "name" to "unindexed.mkv", "kind" to "file"))
            })
            return jsonObject("path" to ".", "kind" to "directory", "children" to JSONArray().put(owned)
                .put(jsonObject("path" to "/empty", "name" to "empty", "kind" to "directory", "children" to JSONArray())))
        }
        private fun file(path: String) = jsonObject("path" to path, "name" to path.substringAfterLast('/'), "mediaId" to 21, "locked" to false, "ignored" to false,
            "metadata" to jsonObject("episode" to 1, "aniDBEpisode" to "1", "type" to "main"))
        private fun data(value: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(jsonObject("data" to value).toString())
    }
}
