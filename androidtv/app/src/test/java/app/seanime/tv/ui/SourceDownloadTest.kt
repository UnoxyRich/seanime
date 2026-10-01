package app.seanime.tv.ui

import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeJson
import app.seanime.tv.data.SeanimeRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SourceDownloadTest {
    @Test fun configuredFolderChoicesHaveReadableLabelsAndKeepExactPaths() {
        val folders = nativeDownloadFolders(JSONObject("""{"library":{"libraryPath":"/media/Anime","libraryPaths":["/media/Anime","/storage/Extra",""]}}"""))
        assertEquals(listOf("/media/Anime", "/storage/Extra"), folders.map { it.path })
        assertEquals(listOf("Main library · Anime", "Library folder · Extra"), folders.map { it.label })
        assertTrue(nativeDownloadFolders(JSONObject()).isEmpty())
    }

    @Test fun downloadRequestsPreserveTheSelectedReleaseMediaAndDestination() = runBlocking {
        val server = MockWebServer().apply { start() }
        val api = SeanimeApiClient(server.url("/").toString())
        try {
            val repo = SeanimeRepository(api)
            val media = SeanimeJson.media(JSONObject("""{"id":21,"title":"Fixture","custom":"media-value"}"""))
            val torrent = SeanimeJson.torrent(JSONObject("""{"name":"Fixture release","infoHash":"hash","magnetLink":"magnet:?xt=fixture","provider":"fixture-provider","custom":"torrent-value"}"""))
            server.enqueue(MockResponse().setBody("""{"data":true}"""))
            repo.downloadTorrents(listOf(torrent), "/media/Anime/Fixture", media)
            val local = server.takeRequest()
            assertEquals("/api/v1/torrent-client/download", local.path)
            val body = JSONObject(local.body.readUtf8())
            assertEquals("/media/Anime/Fixture", body.getString("destination"))
            assertEquals(21, body.getJSONObject("media").getInt("id"))
            assertEquals("media-value", body.getJSONObject("media").getString("custom"))
            assertEquals("torrent-value", body.getJSONArray("torrents").getJSONObject(0).getString("custom"))
            assertFalse(body.getJSONObject("smartSelect").getBoolean("enabled"))
            assertEquals(0, body.getJSONObject("smartSelect").getJSONArray("missingEpisodeNumbers").length())
            server.enqueue(MockResponse().setBody("""{"data":true}"""))
            repo.addDebridTorrents(listOf(torrent), "/storage/Extra", media)
            val debrid = server.takeRequest()
            assertEquals("/api/v1/debrid/torrents", debrid.path)
            val debridBody = JSONObject(debrid.body.readUtf8())
            assertEquals("/storage/Extra", debridBody.getString("destination"))
            assertEquals(body.getJSONObject("media").toString(), debridBody.getJSONObject("media").toString())
            assertEquals(body.getJSONArray("torrents").toString(), debridBody.getJSONArray("torrents").toString())
            assertFalse(debridBody.has("smartSelect"))
        } finally { api.close(); server.shutdown() }
    }

    @Test fun falseOrMissingConfirmationCannotBecomeDownloadSuccess() = runBlocking {
        val server = MockWebServer().apply { start() }
        val api = SeanimeApiClient(server.url("/").toString())
        try {
            val repo = SeanimeRepository(api)
            val media = SeanimeJson.media(JSONObject("""{"id":21,"title":"Fixture"}"""))
            val torrent = SeanimeJson.torrent(JSONObject("""{"name":"Fixture","infoHash":"hash"}"""))
            server.enqueue(MockResponse().setBody("""{"data":false}"""))
            assertTrue(runCatching { repo.downloadTorrents(listOf(torrent), "/media", media) }.isFailure)
            server.enqueue(MockResponse().setBody("""{"data":{}}"""))
            assertTrue(runCatching { repo.addDebridTorrents(listOf(torrent), "/media", media) }.isFailure)
        } finally { api.close(); server.shutdown() }
    }
}
