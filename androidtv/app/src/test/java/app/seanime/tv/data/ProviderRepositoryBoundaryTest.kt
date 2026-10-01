package app.seanime.tv.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderRepositoryBoundaryTest {
    private fun response(value: Any) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("data", value).toString())
    private fun pages(url: String, downloaded: Boolean = false) = JSONObject().put("isDownloaded", downloaded)
        .put("pages", JSONArray().put(JSONObject().put("index", 0).put("url", url)))

    @Test fun externalMangaCannotClaimLocalMarkersApiPathsOrNonHttpResources() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SeanimeApiClient(server.url("/").toString(), "private-token").use { api ->
                val repo = SeanimeRepository(api)
                for (url in listOf("{{manga-local-assets}}/private/page.jpg", "/api/v1/status", "file:///private/page.jpg", "content://private/page", server.url("/api/v1/status").toString())) {
                    server.enqueue(response(pages(url)))
                    assertTrue(url, runCatching { repo.mangaPages(1, "chapter", "atsumaru") }.isFailure)
                }
                server.enqueue(response(pages("https://images.example/page.jpg")))
                val publicPage = repo.mangaPages(1, "chapter", "atsumaru").single()
                assertTrue(publicPage.providerResult)
                assertFalse(publicPage.headers.keys.any { it.startsWith("X-Seanime-", true) })
                server.enqueue(response(pages("{{manga-local-assets}}/series/page.jpg")))
                val local = repo.mangaPages(1, "chapter", "local-manga").single()
                assertFalse(local.providerResult)
                assertTrue(local.url.startsWith(api.baseUrl + "/api/v1/manga/local-page/"))
                assertEquals("private-token", local.headers["X-Seanime-Token"])
                server.enqueue(response(pages("series/page.jpg", true)))
                val downloaded = repo.mangaPages(1, "chapter", "atsumaru").single()
                assertFalse(downloaded.providerResult)
                assertTrue(downloaded.url.endsWith("/manga-downloads/series/page.jpg"))
            }
        }
    }

    @Test fun streamAndSubtitleResultsAreValidatedBeforeExposingPlayableSources() = runBlocking {
        MockWebServer().use { server ->
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val repo = SeanimeRepository(api)
                fun source(url: String, subtitle: String) = JSONObject().put("videoSources", JSONArray().put(JSONObject().put("url", url)
                    .put("subtitles", JSONArray().put(JSONObject().put("url", subtitle)))))
                server.enqueue(response(source("file:///private/video", "https://cdn.example/sub.vtt")))
                assertTrue(runCatching { repo.onlineSources(1, 1, "animeheaven") }.isFailure)
                server.enqueue(response(source("https://cdn.example/video.mp4", "content://private/subtitle")))
                assertTrue(runCatching { repo.onlineSources(1, 1, "anidb") }.isFailure)
                server.enqueue(response(source("https://cdn.example/video.mp4", "https://cdn.example/sub.vtt")))
                assertEquals("https://cdn.example/video.mp4", repo.onlineSources(1, 1, "anidb").single().url)
            }
        }
    }
}
