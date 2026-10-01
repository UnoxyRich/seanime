package app.seanime.tv.ui

import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import app.seanime.tv.data.jsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MangaReadProgressTest {
    @Test fun markReadFetchesCurrentEntryAndAdvancesUsingItsMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(entry(2))
            server.enqueue(MockResponse().setBody("""{"data":true}"""))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                assertEquals(MangaReadResult(3, true), markMangaChapterRead(SeanimeRepository(api), 42, "3.5", 0))
            }
            assertEquals("/api/v1/manga/entry/42", server.takeRequest().path)
            val mutation = server.takeRequest()
            assertEquals("POST", mutation.method)
            assertEquals("/api/v1/manga/update-progress", mutation.path)
            val body = JSONObject(mutation.body.readUtf8())
            assertEquals(3, body.getInt("chapterNumber"))
            assertEquals(24, body.getInt("totalChapters"))
            assertEquals(84, body.getInt("malId"))
        }
    }

    @Test fun rereadingNeverLowersMoreRecentServerProgress() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(entry(20))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                assertEquals(MangaReadResult(20, false), markMangaChapterRead(SeanimeRepository(api), 42, "1"))
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun failedOrMismatchedEntryCannotWriteGuessedProgress() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"Unavailable"}"""))
            server.enqueue(entry(0, id = 43))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                repeat(2) { assertTrue(runCatching { markMangaChapterRead(SeanimeRepository(api), 42, "3") }.isFailure) }
            }
            assertEquals(2, server.requestCount)
            repeat(2) { assertEquals("GET", server.takeRequest().method) }
        }
    }

    @Test fun automaticProgressNeedsTheRenderedEndOfAValidChapter() {
        for (number in listOf("", "NaN", "Infinity", "-1", "0", "0.5", "2147483648")) assertNull(mangaChapterProgress(number))
        assertEquals(3, mangaChapterProgress("3.5"))
        assertFalse(mangaEndPageRendered(0, 0, false, emptySet()))
        assertFalse(mangaEndPageRendered(0, 4, false, setOf(0)))
        assertFalse(mangaEndPageRendered(3, 4, false, emptySet()))
        assertFalse(mangaEndPageRendered(2, 4, true, setOf(2)))
        assertTrue(mangaEndPageRendered(2, 4, true, setOf(2, 3)))
        assertTrue(mangaEndPageRendered(3, 4, false, setOf(3)))
    }

    private fun entry(progress: Int, id: Int = 42) = MockResponse().setBody(jsonObject("data" to
        jsonObject("media" to jsonObject("id" to id, "idMal" to 84, "chapters" to 24),
            "listData" to jsonObject("progress" to progress))).toString())
}
