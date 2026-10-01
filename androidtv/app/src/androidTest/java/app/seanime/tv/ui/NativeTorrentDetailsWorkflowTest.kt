package app.seanime.tv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class NativeTorrentDetailsWorkflowTest {
    private val dpad = TvDpadInputRule()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(dpad).around(compose)

    @Test fun downloadsEntrySupportsExactTorrentActionsFileRetryTrackersAndLazyBackFocus() = fixture { state ->
        enabled("torrent-global-limits")
        scroll("torrent-details-owned-18"); click("torrent-details-owned-18")
        await("torrent-detail-back"); enabled("torrent-detail-refresh")
        click("torrent-detail-force"); message("Force start enabled.")
        click("torrent-detail-queue-up"); focused("torrent-detail-force"); message("Queue position updated to 1.")
        scroll("torrent-detail-sequential"); click("torrent-detail-sequential"); message("Sequential download enabled.")
        click("torrent-detail-recheck"); message("Recheck requested. Verification runs in the background; this does not confirm it has finished.")
        click("torrent-detail-reannounce"); message("Tracker reannounce requested. Peer discovery may take time.")
        scroll("torrent-detail-tab-files"); click("torrent-detail-tab-files")
        scroll("torrent-detail-file-18"); click("torrent-detail-file-18")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("✓ Normal") and isFocused()).fetchSemanticsNodes()
                .any { (it.root as ViewRootForTest).view.hasWindowFocus() }
        }
        pressRemote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("High").assertIsFocused()
        pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
        await("torrent-action-error")
        compose.onNodeWithTag("torrent-action-error").assertTextContains("Owned priority retry")
        focused("torrent-action-close")
        pressRemote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("torrent-action-retry").assertIsFocused()
        pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
        // The retry closes its error dialog before its HTTP mutation/readback
        // finishes, while the disabled opener can still report focus.
        compose.waitUntil(10_000) {
            state.posts.count { it.optString("action") == "set-file-priority" } == 2 &&
                compose.onAllNodes(hasTestTag("torrent-detail-file-18") and hasText("Priority: High") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        focused("torrent-detail-file-18")
        compose.onNodeWithTag("torrent-detail-file-18").assertTextContains("Priority: High")
        val changes = state.posts.size
        click("torrent-detail-file-18"); compose.onNodeWithText("Cancel").performTvClick()
        focused("torrent-detail-file-18"); assertEquals(changes, state.posts.size)
        NativeScreenshotEvidence.capture("native-torrent-file-priority-return")

        scroll("torrent-detail-tab-trackers"); click("torrent-detail-tab-trackers")
        click("torrent-detail-add-tracker"); focused("torrent-tracker-editor")
        compose.enterTvTextAndDismissIme("torrent-tracker-editor", "https://discarded.example/announce", dpad)
        finishTrackerEditor(save = false); assertEquals(changes, state.posts.size)
        click("torrent-detail-add-tracker"); focused("torrent-tracker-editor")
        compose.onNodeWithTag("torrent-tracker-editor").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.enterTvTextAndDismissIme("torrent-tracker-editor", "https://new.example/announce", dpad)
        finishTrackerEditor(save = true); message("Tracker added.")
        scroll("torrent-detail-tracker-1"); click("torrent-detail-tracker-1")
        compose.onNodeWithText("Cancel").performTvClick(); assertEquals(changes + 1, state.posts.size)
        click("torrent-detail-tracker-1"); compose.onNodeWithText("Confirm").performTvClick()
        focused("torrent-detail-add-tracker"); message("Tracker removed.")
        scroll("torrent-detail-tab-peers"); click("torrent-detail-tab-peers")
        compose.onNodeWithText("Owned peer client").assertExists()
        NativeScreenshotEvidence.capture("native-torrent-peers")
        back(); focused("torrent-details-owned-18")
        compose.onNodeWithTag("torrent-details-owned-18").assertIsDisplayed()
        NativeScreenshotEvidence.capture("native-torrent-downloads-row-return")
        assertTrue(state.posts.all { it.getString("hash") == "owned-18" })
        val priorities = state.posts.filter { it.getString("action") == "set-file-priority" }
        assertEquals(2, priorities.size)
        priorities.forEach { assertEquals(18, it.getInt("index")); assertEquals(2, it.getInt("priority")); assertEquals(4, it.length()) }
        state.posts.filter { it.getString("action") in setOf("add-tracker", "remove-tracker") }.also { trackers ->
            assertEquals(listOf("add-tracker", "remove-tracker"), trackers.map { it.getString("action") })
            trackers.forEach { assertEquals("https://new.example/announce", it.getString("tracker")); assertEquals(3, it.length()) }
        }
    }

    private fun finishTrackerEditor(save: Boolean) {
        // Return from the observed IME session before using real footer arrows.
        var stage = "footer navigation"
        var editorDismissed = false
        try {
            pressRemote(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("text-entry-cancel").assertIsFocused()
            if (save) {
                pressRemote(KeyEvent.KEYCODE_DPAD_RIGHT)
                compose.onNodeWithTag("text-entry-save").assertIsFocused().assertIsEnabled()
            }
            stage = "editor dismissal"
            pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) {
                editorDismissed = compose.onAllNodesWithTag("torrent-tracker-editor").fetchSemanticsNodes().isEmpty()
                if (editorDismissed) stage = "opener restoration"
                editorDismissed && compose.onAllNodes(hasTestTag("torrent-detail-add-tracker") and isFocused() and isEnabled()).fetchSemanticsNodes()
                    .any { (it.root as ViewRootForTest).view.hasWindowFocus() }
            }
        } catch (failure: Throwable) {
            runCatching { NativeScreenshotEvidence.capture("native-torrent-editor-return-failure") }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw AssertionError("Tracker editor ${if (save) "Save" else "Cancel"}: stage=$stage dismissed=$editorDismissed; expected enabled add-tracker focus in its active window", failure)
        }
    }

    @Test fun sessionLimitsPreserveDraftOnFailureCancelDoesNotSubmitAndReadbackIsNotInvented() = fixture { state ->
        try {
            await("torrent-global-limits"); click("torrent-global-limits")
            enabled("torrent-limits-download"); click("torrent-limits-download")
            saveSessionLimitDraft("torrent-limits-download", "2048")
            compose.onNodeWithTag("torrent-limits-download").assertTextEquals("Download: 2048 KB/s")
            assertTrue("Saving a numeric draft must not apply session limits", state.posts.isEmpty())
            click("torrent-limits-cancel"); focused("torrent-global-limits"); assertTrue(state.posts.isEmpty())
            click("torrent-global-limits"); enabled("torrent-limits-download")
            compose.onNodeWithTag("torrent-limits-download").assertTextEquals("Download: 1024 KB/s")
            click("torrent-limits-download")
            saveSessionLimitDraft("torrent-limits-download", "2048")
            pressRemote(KeyEvent.KEYCODE_DPAD_DOWN); focused("torrent-limits-upload")
            pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
            saveSessionLimitDraft("torrent-limits-upload", "0")
            assertTrue("Editing both draft limits must not submit before Apply", state.posts.isEmpty())
            click("torrent-limits-apply"); await("torrent-limits-error")
            compose.onNodeWithTag("torrent-limits-download").assertTextEquals("Download: 2048 KB/s")
            compose.onNodeWithTag("torrent-limits-upload").assertTextEquals("Upload: 0 KB/s")
            click("torrent-limits-apply"); await("torrent-limits-accepted")
            compose.onNodeWithTag("torrent-limits-accepted").assertTextEquals("Session limit request accepted. These values are not saved as startup defaults.")
            NativeScreenshotEvidence.capture("native-torrent-session-limits-accepted")
            click("torrent-limits-cancel"); focused("torrent-global-limits")
            assertEquals(2, state.posts.size)
            state.posts.forEach {
                assertEquals(setOf("action", "downloadLimit", "uploadLimit"), it.keys().asSequence().toSet())
                assertEquals("set-limits", it.getString("action")); assertEquals(2048, it.getInt("downloadLimit")); assertEquals(0, it.getInt("uploadLimit")); assertFalse(it.has("hash"))
            }
        } catch (failure: Throwable) {
            runCatching { NativeScreenshotEvidence.capture("native-torrent-session-limits-failure") }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun saveSessionLimitDraft(opener: String, value: String) {
        var stage = "editor focus"
        var editorDismissed = false
        try {
            focused("torrent-limit-editor")
            stage = "IME handoff"
            compose.enterTvTextAndDismissIme("torrent-limit-editor", value, dpad)
            compose.onNodeWithTag("torrent-limit-editor").assertTextContains(value)
            stage = "footer navigation"
            pressRemote(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("text-entry-cancel").assertIsFocused()
            pressRemote(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithTag("text-entry-save").assertIsFocused().assertIsEnabled()
            stage = "editor dismissal"
            pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
            // The parent dialog may retain semantic focus while this nested editor
            // still owns input. Require removal and active-window focus together,
            // within the original return deadline, before another remote arrow.
            compose.waitUntil(10_000) {
                editorDismissed = compose.onAllNodesWithTag("torrent-limit-editor").fetchSemanticsNodes().isEmpty()
                if (editorDismissed) stage = "opener restoration"
                editorDismissed && compose.onAllNodes(hasTestTag(opener) and isFocused() and isEnabled()).fetchSemanticsNodes()
                    .any { (it.root as ViewRootForTest).view.hasWindowFocus() }
            }
        } catch (failure: Throwable) {
            throw AssertionError("Session limit Save: stage=$stage dismissed=$editorDismissed; expected enabled $opener focus in its active window", failure)
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val state = Fixture()
        MockWebServer().use { server ->
            server.dispatcher = state; server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
                val repo = SeanimeRepository(api)
                compose.setContent { SeanimeTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    FeatureScreen(TvFeature.DOWNLOADS, repo, {}, {}, NativePluginDestination(TvFeature.DOWNLOADS, NativeScreenLocation("/torrent-list"), downloadTab = "torrent"))
                } } }
                block(state)
            }
        }
    }
    private fun await(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun enabled(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    private fun focused(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag(tag) and isFocused() and isEnabled()).fetchSemanticsNodes()
            .any { (it.root as ViewRootForTest).view.hasWindowFocus() }
    }
    private fun message(text: String) {
        // Result text is in the scrolling page header; don't assume a long file list keeps it composed.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun click(tag: String) {
        if (tag.startsWith("torrent-detail-")) scroll(tag)
        enabled(tag); compose.onNodeWithTag(tag).performTvClick()
    }
    private fun scroll(tag: String) { compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag)); compose.waitForIdle() }
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun pressRemote(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }

    private class Fixture : Dispatcher() {
        val posts = CopyOnWriteArrayList<JSONObject>()
        private val priorityFailure = AtomicBoolean(true)
        private val limitFailure = AtomicBoolean(true)
        private val torrent = JSONObject().put("hash", "owned-18").put("name", "Owned release 18").put("destination", "/owned/library")
            .put("queueIndex", 1).put("paused", false).put("forceStart", false).put("sequential", false).put("length", 24000).put("completed", 12000)
        private val files = JSONArray((0 until 24).map { JSONObject().put("index", it).put("path", "Episode ${it + 1}.mkv").put("length", 1000).put("completed", 500).put("priority", 1) })
        private val trackers = mutableListOf("udp://tracker.example:80/announce")
        override fun dispatch(request: RecordedRequest): MockResponse {
            val data: Any = when (request.requestUrl!!.encodedPath) {
                "/api/v1/settings" -> JSONObject().put("torrent", JSONObject().put("defaultTorrentClient", "seanime").put("seanimeDownloadLimit", 1024).put("seanimeUploadLimit", 64))
                "/api/v1/torrent-client/list" -> JSONArray((0 until 20).map { JSONObject().put("hash", "owned-$it").put("name", "Owned release $it").put("status", "downloading").put("contentPath", "/owned/library").put("progress", 0.5) })
                "/api/v1/torrent-client/details" -> {
                    check(request.requestUrl!!.queryParameter("hash") == "owned-18")
                    JSONObject().put("torrent", torrent).put("files", files).put("trackers", JSONArray(trackers))
                        .put("peers", JSONArray().put(JSONObject().put("address", "192.0.2.1:4567").put("client", "Owned peer client")))
                }
                "/api/v1/torrent-client/action" -> {
                    val body = JSONObject(request.body.readUtf8()); posts += body
                    when (body.getString("action")) {
                        "force-start" -> torrent.put("forceStart", body.getBoolean("value"))
                        "queue-up" -> torrent.put("queueIndex", 0)
                        "set-sequential" -> torrent.put("sequential", body.getBoolean("value"))
                        "set-file-priority" -> {
                            if (priorityFailure.getAndSet(false)) return MockResponse().setResponseCode(503).setBody("""{"error":"Owned priority retry"}""")
                            files.getJSONObject(body.getInt("index")).put("priority", body.getInt("priority"))
                        }
                        "add-tracker" -> trackers.add(body.getString("tracker"))
                        "remove-tracker" -> trackers.remove(body.getString("tracker"))
                        "set-limits" -> if (limitFailure.getAndSet(false)) return MockResponse().setResponseCode(503).setBody("""{"error":"Owned limits retry"}""")
                    }
                    true
                }
                else -> JSONObject()
            }
            return MockResponse().setBody(JSONObject().put("data", data).toString())
        }
    }
}
