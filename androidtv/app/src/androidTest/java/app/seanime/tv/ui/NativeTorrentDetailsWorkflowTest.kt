package app.seanime.tv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class NativeTorrentDetailsWorkflowTest {
    @get:Rule val compose = createComposeRule()

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
        click("torrent-detail-add-tracker"); await("torrent-tracker-editor")
        compose.onNodeWithTag("torrent-tracker-editor").performTextReplacement("https://new.example/announce")
        click("text-entry-cancel"); focused("torrent-detail-add-tracker"); assertEquals(changes, state.posts.size)
        click("torrent-detail-add-tracker"); compose.onNodeWithTag("torrent-tracker-editor").performTextReplacement("https://new.example/announce")
        click("text-entry-save"); message("Tracker added.")
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
    }

    @Test fun sessionLimitsPreserveDraftOnFailureCancelDoesNotSubmitAndReadbackIsNotInvented() = fixture { state ->
        await("torrent-global-limits"); click("torrent-global-limits")
        enabled("torrent-limits-download"); click("torrent-limits-download")
        compose.onNodeWithTag("torrent-limit-editor").performTextReplacement("2048")
        compose.onNodeWithTag("torrent-limit-editor").performImeAction()
        click("text-entry-save"); focused("torrent-limits-download")
        click("torrent-limits-cancel"); focused("torrent-global-limits"); assertTrue(state.posts.isEmpty())
        click("torrent-global-limits"); enabled("torrent-limits-download"); click("torrent-limits-download")
        compose.onNodeWithTag("torrent-limit-editor").performTextReplacement("2048")
        compose.onNodeWithTag("torrent-limit-editor").performImeAction(); click("text-entry-save")
        focused("torrent-limits-download")
        pressRemote(KeyEvent.KEYCODE_DPAD_DOWN); focused("torrent-limits-upload")
        pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("torrent-limit-editor").performTextReplacement("0")
        compose.onNodeWithTag("torrent-limit-editor").performImeAction(); click("text-entry-save"); focused("torrent-limits-upload")
        click("torrent-limits-apply"); await("torrent-limits-error")
        compose.onNodeWithTag("torrent-limits-download").assertTextEquals("Download: 2048 KB/s")
        click("torrent-limits-apply"); await("torrent-limits-accepted")
        compose.onNodeWithTag("torrent-limits-accepted").assertTextEquals("Session limit request accepted. These values are not saved as startup defaults.")
        NativeScreenshotEvidence.capture("native-torrent-session-limits-accepted")
        click("torrent-limits-cancel"); focused("torrent-global-limits")
        assertEquals(2, state.posts.size)
        state.posts.forEach { assertEquals("set-limits", it.getString("action")); assertEquals(2048, it.getInt("downloadLimit")); assertEquals(0, it.getInt("uploadLimit")); assertFalse(it.has("hash")) }
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
