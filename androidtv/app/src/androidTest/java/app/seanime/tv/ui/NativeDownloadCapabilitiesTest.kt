package app.seanime.tv.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NativeDownloadCapabilitiesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun clientSpecificActionsRefreshAndRecheckBeforeSendingAnyBulkMutation() {
        val provider = AtomicReference("qbittorrent")
        val mutations = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val data = when (request.path) {
                        "/api/v1/settings" -> """{"torrent":{"defaultTorrentClient":"${provider.get()}"}}"""
                        "/api/v1/torrent-client/action" -> { mutations.incrementAndGet(); "true" }
                        else -> "[]"
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"data\":$data}")
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
                val repo = SeanimeRepository(api)
                compose.setContent { SeanimeTheme { FeatureScreen(TvFeature.DOWNLOADS, repo, {}, {}) } }
                compose.onNodeWithText("Torrents").performTvClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("No active torrents").fetchSemanticsNodes().isNotEmpty() }
                listOf("Add magnet", "Pause all", "Resume all").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
                provider.set("seanime")
                compose.onNodeWithText("Refresh").performTvClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Pause all").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Add magnet").assertExists()
                // Another client can change settings while this screen is open.
                provider.set("transmission")
                compose.onNodeWithText("Pause all").performTvClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("This action needs the built-in Seanime torrent client. Refresh to see the current client.").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Pause all").assertDoesNotExist()
                assertEquals("A stale capability must never send a bulk action to the new client", 0, mutations.get())
            }
        }
    }
}
