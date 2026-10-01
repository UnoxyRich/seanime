package app.seanime.tv.ui

import app.seanime.tv.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeCustomSourcesTest {
    @Test fun goNullSliceIsEmptyButMissingOrWrongTypeIsNotASuccessfulEmptyPage() = runBlocking {
        val provider = SeanimeJson.extension(JSONObject().put("id", "owned").put("settings", JSONObject().put("supportsAnime", true)))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"media":null,"page":1,"totalPages":0}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"page":1,"totalPages":0}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"media":{},"page":1,"totalPages":0}}"""))
            SeanimeApiClient(server.url("/").toString()).use { client ->
                val repo = SeanimeRepository(client)
                assertTrue(loadNativeCustomSourcePage(repo, provider, "anime", "", 1).media.isEmpty())
                assertTrue(runCatching { loadNativeCustomSourcePage(repo, provider, "anime", "", 1) }.isFailure)
                assertTrue(runCatching { loadNativeCustomSourcePage(repo, provider, "anime", "", 1) }.isFailure)
            }
        }
    }
    @Test fun supportedTypesFollowTheProviderAndUnsupportedTypesSendNoRequest() = runBlocking {
        val provider = SeanimeJson.extension(JSONObject().put("id", "owned").put("settings", JSONObject().put("supportsManga", true)))
        assertEquals(listOf("manga"), nativeCustomSourceTypes(provider))
        MockWebServer().use { server ->
            SeanimeApiClient(server.url("/").toString()).use { client ->
                assertTrue(runCatching { loadNativeCustomSourcePage(SeanimeRepository(client), provider, "anime", "", 1) }.isFailure)
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun normalizedCustomIdentitiesAndQueryPaginationUseExistingExactContract() = runBlocking {
        val ids = listOf(2_147_483_648L, 4_294_967_297L, 9_007_199_254_740_991L)
        val provider = SeanimeJson.extension(JSONObject().put("id", "owned").put("settings", JSONObject().put("supportsAnime", true)))
        MockWebServer().use { server ->
            val rows = JSONArray(ids.map { JSONObject().put("id", it).put("title", JSONObject().put("english", "Title $it")) })
            server.enqueue(MockResponse().setBody(JSONObject().put("data", JSONObject().put("media", rows).put("totalPages", 5)).toString()))
            SeanimeApiClient(server.url("/").toString()).use { client ->
                val page = loadNativeCustomSourcePage(SeanimeRepository(client), provider, "anime", "Space", 3)
                assertEquals(ids, page.media.map { it.id })
                assertEquals(3, page.page); assertEquals(5, page.totalPages)
                val request = server.takeRequest()
                assertEquals("/api/v1/custom-source/provider/list/anime", request.path)
                val body = JSONObject(request.body.readUtf8())
                assertEquals("owned", body.getString("provider")); assertEquals("Space", body.getString("search"))
                assertEquals(3, body.getInt("page")); assertEquals(20, body.getInt("perPage")); assertEquals(4, body.length())
            }
        }
    }
}
