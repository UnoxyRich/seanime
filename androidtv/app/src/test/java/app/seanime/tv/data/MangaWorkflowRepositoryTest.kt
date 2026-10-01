package app.seanime.tv.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MangaWorkflowRepositoryTest {
    @Test fun ordinaryBrowseKeepsCacheAndExplicitRefreshDeletesBeforeReading() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(chapters("cached"))
            server.enqueue(response("true"))
            server.enqueue(chapters("fresh"))
            SeanimeApiClient(server.url("/").toString()).use { client ->
                val repo = SeanimeRepository(client)
                assertEquals("cached", repo.mangaChapters(42, "source").single().id)
                assertEquals("fresh", repo.refreshMangaChapters(42, "source").single().id)
            }
            val ordinary = server.takeRequest()
            assertEquals("POST", ordinary.method)
            assertEquals("/api/v1/manga/chapters", ordinary.path)
            val clear = server.takeRequest()
            assertEquals("DELETE", clear.method)
            assertEquals("/api/v1/manga/entry/cache", clear.path)
            assertEquals(setOf("mediaId"), JSONObject(clear.body.readUtf8()).keys().asSequence().toSet())
            val fresh = server.takeRequest()
            assertEquals("/api/v1/manga/chapters", fresh.path)
            val payload = JSONObject(fresh.body.readUtf8())
            assertEquals(42, payload.getInt("mediaId"))
            assertEquals("source", payload.getString("provider"))
        }
    }

    @Test fun failedCacheClearDoesNotFetchAndPretendTheOldChaptersAreFresh() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"Cache unavailable"}"""))
            SeanimeApiClient(server.url("/").toString()).use { client ->
                assertTrue(runCatching { SeanimeRepository(client).refreshMangaChapters(42, "source") }.isFailure)
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun falseOrInvalidMutationAcknowledgementsCannotAdvanceTheWorkflow() = runBlocking {
        MockWebServer().use { server ->
            for (ack in listOf("false", "null", "{}")) repeat(3) { server.enqueue(response(ack)) }
            SeanimeApiClient(server.url("/").toString()).use { client ->
                val repo = SeanimeRepository(client)
                repeat(3) {
                    assertTrue(runCatching { repo.refreshMangaChapters(42, "source") }.isFailure)
                    assertTrue(runCatching { repo.setMangaMapping(42, "source", "edition") }.isFailure)
                    assertTrue(runCatching { repo.resetMangaMapping(42, "source") }.isFailure)
                }
            }
            assertEquals(9, server.requestCount)
            repeat(3) {
                assertEquals("/api/v1/manga/entry/cache", server.takeRequest().path)
                assertEquals("/api/v1/manga/manual-mapping", server.takeRequest().path)
                assertEquals("/api/v1/manga/remove-mapping", server.takeRequest().path)
            }
        }
    }

    @Test fun mappingReadAndResetUseTheExistingProviderScopedContract() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response("""{"mangaId":"wrong-edition"}"""))
            server.enqueue(response("true"))
            server.enqueue(response("""{"mangaId":null}"""))
            SeanimeApiClient(server.url("/").toString()).use { client ->
                val repo = SeanimeRepository(client)
                assertEquals("wrong-edition", repo.mangaMapping(42, "source"))
                repo.resetMangaMapping(42, "source")
                assertNull(repo.mangaMapping(42, "source"))
            }
            for (path in listOf("get-mapping", "remove-mapping", "get-mapping")) {
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/api/v1/manga/$path", request.path)
                val payload = JSONObject(request.body.readUtf8())
                assertEquals(setOf("mediaId", "provider"), payload.keys().asSequence().toSet())
                assertEquals(42, payload.getInt("mediaId"))
                assertEquals("source", payload.getString("provider"))
            }
        }
    }

    @Test fun sparseProviderPageIndicesKeepTheirDimensionsAfterSorting() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response("""{"isDownloaded":true,"pages":[{"index":20,"url":"wide.jpg"},{"index":4,"url":"portrait.jpg"},{"index":9,"url":"unknown.jpg"}],"pageDimensions":{"4":{"width":800,"height":1200},"20":{"width":1600,"height":1000},"9":{"width":0,"height":1000},"0":{"width":99,"height":1}}}"""))
            SeanimeApiClient(server.url("/").toString()).use { client ->
                val result = SeanimeRepository(client).mangaPageCollection(42, "chapter", "source", doublePage = true)
                assertEquals(listOf(4, 9, 20), result.pages.map { it.index })
                assertEquals(setOf(4, 20), result.dimensions.keys)
                assertFalse(result.dimensions.getValue(4).isWide)
                assertTrue(result.dimensions.getValue(20).isWide)
            }
            val request = server.takeRequest()
            assertEquals("/api/v1/manga/pages", request.path)
            val payload = JSONObject(request.body.readUtf8())
            assertEquals(setOf("mediaId", "chapterId", "provider", "doublePage"), payload.keys().asSequence().toSet())
            assertEquals(42, payload.getInt("mediaId"))
            assertEquals("chapter", payload.getString("chapterId"))
            assertEquals("source", payload.getString("provider"))
            assertTrue(payload.getBoolean("doublePage"))
        }
    }

    private fun response(data: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("{\"data\":$data}")
    private fun chapters(id: String) = response("""{"chapters":[{"id":"$id","chapter":"1"}]}""")
}
