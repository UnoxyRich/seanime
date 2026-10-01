package app.seanime.tv.ui

import app.seanime.tv.data.DownloadItem
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExistingDebridDownloadTest {
    private val raw = JSONObject("""{"id":"debrid-21","name":"Fixture release","status":"downloaded","provider":"fixture","files":[{"id":4,"path":"special.mkv"}],"custom":"keep-me"}""")
    private fun item() = DownloadItem("debrid-21", "Fixture release", "downloaded", 1.0, "", "debrid", raw)

    @Test fun existingTransferUsesItsCompleteTorrentItemAndChosenDestination() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SeanimeApiClient(server.url("/").toString()).use { api ->
                server.enqueue(MockResponse().setBody("""{"data":true}"""))
                requestExistingDebridDownload(SeanimeRepository(api), item(), "/media/Anime/Fixture series")
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/api/v1/debrid/torrents/download", request.path)
                val body = JSONObject(request.body.readUtf8())
                assertEquals(setOf("torrentItem", "destination"), body.keys().asSequence().toSet())
                assertEquals(raw.toString(), body.getJSONObject("torrentItem").toString())
                assertEquals("/media/Anime/Fixture series", body.getString("destination"))
            }
        }
    }

    @Test fun falseMissingAndFailedResponsesCannotReportAnAcceptedDownload() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val repo = SeanimeRepository(api)
                listOf("false", "null", "{}").forEach { response ->
                    server.enqueue(MockResponse().setBody("{\"data\":$response}"))
                    val failure = runCatching { requestExistingDebridDownload(repo, item(), "/media/Anime") }.exceptionOrNull()
                    assertEquals("The server did not confirm the debrid download request", failure?.message)
                }
                server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"Fixture debrid failure"}"""))
                assertEquals("Fixture debrid failure", runCatching { requestExistingDebridDownload(repo, item(), "/media/Anime") }.exceptionOrNull()?.message)
                assertTrue(runCatching { requestExistingDebridDownload(repo, item(), "") }.isFailure)
                assertEquals(4, server.requestCount)
            }
        }
    }
}
