package app.seanime.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
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
import java.util.concurrent.atomic.AtomicReference

class NativeTransferStatusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun liveMangaQueueRefreshBlocksCanceledWorkersAndRechecksBeforeClearingFailures() = fixture(TvFeature.DOWNLOADS) { f ->
        clearControl().assertIsNotEnabled()
        compose.onNodeWithTag("manga-queue-pause").performScrollTo().performTvClick()
        awaitText("Queue cancellation requested. Downloads may still be stopping.")
        clearControl().assertIsNotEnabled()
        assertEquals(1, f.writes.count { it == "POST /api/v1/manga/download-queue/stop" })
        assertTrue(f.writes.none { it.startsWith("DELETE") })

        f.chapterStatus.set("errored"); f.send("chapter-download-queue-updated")
        awaitEnabled("manga-queue-clear")
        clearControl().performTvClick()
        f.chapterStatus.set("downloading") // A later server change must invalidate the confirmation.
        compose.onNodeWithText("Confirm").performTvClick()
        awaitText("Clear is unavailable while chapters are downloading.")
        assertTrue(f.writes.none { it.startsWith("DELETE") })

        f.chapterStatus.set("errored"); f.send("chapter-download-queue-updated")
        awaitEnabled("manga-queue-clear")
        clearControl().performTvClick()
        compose.onNodeWithText("Confirm").performTvClick()
        awaitText("No queued chapters")
        assertEquals(1, f.writes.count { it == "DELETE /api/v1/manga/download-queue" })
        NativeScreenshotEvidence.capture("manga-queue-live-terminal-clear")
    }

    @Test fun metadataAcknowledgementAndEmptyQueueStayPendingUntilTheFinishEvent() = fixture(TvFeature.OFFLINE) { f ->
        awaitEnabled("offline-save-metadata")
        compose.onNodeWithTag("offline-save-metadata").performScrollTo().performTvClick()
        awaitStatus("Metadata request accepted.")
        assertEquals(1, f.writes.count { it == "POST /api/v1/local/local" })
        compose.onNodeWithText("Offline metadata refreshed").assertDoesNotExist()

        f.metadata.set(JSONObject("""{"animeTasks":{"21":{"mediaId":21,"title":"Fixture anime"}},"mangaTasks":{}}"""))
        f.send("sync-local-queue-state", f.metadata.get())
        awaitStatus("Saving metadata for 1 title.")
        compose.onNodeWithTag("offline-save-metadata").assertIsNotEnabled()
        compose.onNodeWithText("Anime · Fixture anime").assertExists()
        f.metadata.set(emptyMetadata()); f.send("sync-local-queue-state", f.metadata.get())
        awaitStatus("Metadata request accepted.")
        f.send("sync-local-finished")
        awaitStatus("The server finished metadata processing.")
        assertTrue(f.writes.none { it.contains("/local/anilist") })
        compose.onNodeWithTag("offline-metadata-status").performScrollTo().assertIsDisplayed()
        NativeScreenshotEvidence.capture("offline-metadata-server-finished")
    }

    @Test fun metadataStatusFailureAndRejectedRequestRemainRetryable() = fixture(TvFeature.OFFLINE) { f ->
        awaitEnabled("offline-save-metadata")
        f.rejectMetadata.set(true)
        compose.onNodeWithTag("offline-save-metadata").performScrollTo().performTvClick()
        compose.waitUntil(10_000) { f.writes.count { it == "POST /api/v1/local/local" } == 1 }
        awaitEnabled("offline-save-metadata")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("The server did not accept the metadata request"))
        compose.onNodeWithText("The server did not accept the metadata request").assertIsDisplayed()
        f.failQueueRead.set(true)
        compose.onNodeWithTag("offline-save-metadata").performScrollTo().performTvClick()
        awaitStatus("Metadata request accepted.")
        compose.onNodeWithTag("offline-refresh-sync").performScrollTo().assertTextContains("Retry sync status").performTvClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Retry sync status").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("offline-refresh-sync").assertTextContains("Refresh sync status")
        awaitStatus("Metadata request accepted.")
        assertEquals(2, f.writes.count { it == "POST /api/v1/local/local" })
    }

    private fun clearControl(): SemanticsNodeInteraction {
        compose.onNodeWithTag("manga-queue-actions").performScrollToNode(hasTestTag("manga-queue-clear"))
        return compose.onNodeWithTag("manga-queue-clear")
    }
    private fun awaitText(value: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitEnabled(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitStatus(prefix: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag("offline-metadata-status") and hasText(prefix, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun fixture(feature: TvFeature, body: (TransferFixture) -> Unit) {
        val f = TransferFixture()
        MockWebServer().use { server ->
            server.dispatcher = f; server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
                val repo = SeanimeRepository(api)
                compose.setContent { SeanimeTheme {
                    Box(Modifier.fillMaxSize().background(androidx.tv.material3.MaterialTheme.colorScheme.background)) {
                        FeatureScreen(feature, repo, {}, {})
                    }
                } }
                api.connectEvents()
                compose.waitUntil(10_000) { f.socket.get() != null && api.connected.value }
                if (feature == TvFeature.DOWNLOADS) awaitText("Chapter 1")
                body(f)
            }
        }
    }

    private class TransferFixture : Dispatcher() {
        val socket = AtomicReference<WebSocket?>()
        val chapterStatus = AtomicReference<String?>("downloading")
        val metadata = AtomicReference(emptyMetadata())
        val rejectMetadata = AtomicBoolean(false)
        val failQueueRead = AtomicBoolean(false)
        val writes = CopyOnWriteArrayList<String>()
        fun send(type: String, payload: Any = JSONObject.NULL) {
            check(socket.get()!!.send(JSONObject().put("type", type).put("payload", payload).toString()))
        }
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty().substringBefore('?')
            if (path == "/events") return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { socket.set(webSocket) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            })
            if (request.method != "GET") writes.add("${request.method} $path")
            val data: Any = when (path) {
                "/api/v1/status" -> JSONObject("""{"serverReady":true,"user":{"isSimulated":false},"settings":{"library":{}}}""")
                "/api/v1/manga/download-queue" -> {
                    if (request.method == "DELETE") { chapterStatus.set(null); true }
                    else JSONArray().apply { chapterStatus.get()?.let { put(JSONObject().put("mediaId", 21).put("provider", "fixture").put("chapterId", "1").put("chapterNumber", "1").put("status", it)) } }
                }
                "/api/v1/manga/download-queue/stop" -> { chapterStatus.set("not_started"); true }
                "/api/v1/local/track" -> JSONArray()
                "/api/v1/local/storage/size" -> "0 B"
                "/api/v1/local/updated" -> false
                "/api/v1/local/local" -> !rejectMetadata.getAndSet(false)
                "/api/v1/local/queue" -> {
                    if (failQueueRead.getAndSet(false)) return MockResponse().setResponseCode(503).setBody("""{"error":"Fixture queue unavailable"}""")
                    metadata.get()
                }
                else -> return MockResponse().setResponseCode(404).setBody("""{"error":"Unexpected fixture route"}""")
            }
            return MockResponse().setBody(JSONObject().put("data", data).toString())
        }
    }

    companion object {
        private fun emptyMetadata() = JSONObject("""{"animeTasks":{},"mangaTasks":{}}""")
    }
}
