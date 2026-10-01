package app.seanime.tv.ui

import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeLibraryFileActionsTest {
    private fun file() = JSONObject("""{"path":"/owned/episode.mkv","name":"Episode","mediaId":21,"locked":false,"ignored":false,"metadata":{"episode":1,"aniDBEpisode":"1","type":"main","releaseGroup":"original"},"opaque":"keep"}""")

    @Test fun editRebasesOnlyChangedFieldsOntoCurrentMetadata() {
        val original = file()
        val current = file().put("locked", true).apply { getJSONObject("metadata").put("releaseGroup", "fresh").put("codec", "h264") }
        val edited = file().apply { getJSONObject("metadata").put("episode", 2).put("aniDBEpisode", "2") }
        val payload = nativeFileMatchPayload(original, current, edited)
        assertEquals(setOf("path", "metadata", "mediaId", "locked", "ignored"), payload.keys().asSequence().toSet())
        assertTrue(payload.getBoolean("locked"))
        assertEquals(2, payload.getJSONObject("metadata").getInt("episode"))
        assertEquals("fresh", payload.getJSONObject("metadata").getString("releaseGroup"))
        assertEquals("h264", payload.getJSONObject("metadata").getString("codec"))
        assertEquals(1, original.getJSONObject("metadata").getInt("episode"))
    }

    @Test fun matchMutationRequiresFreshIndexAndExactUpdatedFileResponse() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val original = file()
            val edited = file().put("locked", true)
            server.enqueue(data(JSONArray().put(original)))
            server.enqueue(data(JSONArray().put(edited)))
            server.enqueue(data(JSONArray()))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val repo = SeanimeRepository(api)
                saveNativeFileMatch(repo, original, edited)
                assertEquals("GET", server.takeRequest().method)
                val mutation = server.takeRequest()
                assertEquals("PATCH", mutation.method)
                assertEquals("/api/v1/library/local-file", mutation.path)
                assertTrue(JSONObject(mutation.body.readUtf8()).getBoolean("locked"))
                assertNotNull(runCatching { saveNativeFileMatch(repo, original, edited) }.exceptionOrNull())
                assertEquals("GET", server.takeRequest().method)
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun deletionSendsOnlyTheConfirmedIndexedPathAndRequiresTrue() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val selected = file()
            server.enqueue(data(JSONArray().put(selected)))
            server.enqueue(data(false))
            server.enqueue(data(JSONArray().put(selected)))
            server.enqueue(data(true))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val repo = SeanimeRepository(api)
                assertNotNull(runCatching { deleteNativeLibraryFile(repo, selected.getString("path")) }.exceptionOrNull())
                deleteNativeLibraryFile(repo, selected.getString("path"))
                repeat(2) {
                    assertEquals("GET", server.takeRequest().method)
                    val request = server.takeRequest()
                    assertEquals("DELETE", request.method)
                    assertEquals("/api/v1/library/local-files", request.path)
                    val body = JSONObject(request.body.readUtf8())
                    assertEquals(setOf("paths"), body.keys().asSequence().toSet())
                    assertEquals("[\"/owned/episode.mkv\"]", body.getJSONArray("paths").toString())
                }
            }
        }
    }

    @Test fun changedPathInvalidNumberAndInvalidTypeCannotCreateAMutation() {
        val original = file()
        assertNotNull(runCatching { nativeFileMatchPayload(original, file().put("path", "/other"), file()) }.exceptionOrNull())
        for (change in listOf("episode" to -1, "type" to "unknown")) {
            val edited = file().apply { getJSONObject("metadata").put(change.first, change.second) }
            assertNotNull(runCatching { nativeFileMatchPayload(original, original, edited) }.exceptionOrNull())
        }
    }

    private fun data(value: Any) = MockResponse().setBody(JSONObject().put("data", value).toString())
}
