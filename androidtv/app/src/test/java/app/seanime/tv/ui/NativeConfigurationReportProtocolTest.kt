package app.seanime.tv.ui

import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeConfigurationReportProtocolTest {
    @Test fun invalidUserConfigurationKeepsTheLoadedExtensionEnabledAndItsReasonVisible() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SeanimeApiClient(server.url("/").toString()).use { api ->
                server.enqueue(MockResponse().setBody("""{"data":{"extensions":[{"id":"fixture","name":"Provider"}],"disabledExtensions":[{"id":"disabled","name":"Disabled provider"}],"invalidUserConfigExtensions":[{"id":"fixture","extension":{"id":"fixture"},"reason":"user config is missing"},{"id":"disabled","reason":"region is invalid"}]}}"""))
                val entries = SeanimeRepository(api).extensions()
                assertEquals(2, entries.size)
                assertEquals("user config is missing", entries.first().configurationError)
                assertFalse(entries.first().disabled)
                assertEquals("region is invalid", entries.last().configurationError)
                assertTrue(entries.last().disabled)
            }
        }
    }

    @Test fun reportCategoryAndDescriptionReachTheExistingEndpointWithExactAcknowledgement() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val repo = SeanimeRepository(api)
                for (library in listOf(false, true)) {
                    server.enqueue(MockResponse().setBody("""{"data":true}"""))
                    assertTrue(repo.saveIssueReport("Scanner missed the selected series", library))
                    val request = server.takeRequest()
                    assertEquals("/api/v1/report/issue", request.path)
                    val body = JSONObject(request.body.readUtf8())
                    assertEquals(library, body.getBoolean("isAnimeLibraryIssue"))
                    assertEquals("Scanner missed the selected series", body.getString("description"))
                    assertEquals(0, body.getJSONArray("screenshots").length())
                }
                server.enqueue(MockResponse().setBody("""{"data":false}"""))
                assertFalse(repo.saveIssueReport("Keep this draft", true))
            }
        }
    }
}
