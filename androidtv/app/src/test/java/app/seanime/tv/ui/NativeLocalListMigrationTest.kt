package app.seanime.tv.ui

import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NativeLocalListMigrationTest {
    @Test fun confirmationNamesTheAccountAndDoesNotUploadUntilChosen() {
        var count = 0
        val prompt = nativeLocalListMigrationPrompt("Fixture account") { count++ }
        assertTrue(prompt.message.contains("Fixture account"))
        assertEquals("Cancel", prompt.dismissLabel)
        assertEquals(0, count)
        prompt.actions.single().invoke()
        assertEquals(1, count)
    }

    @Test fun migrationRechecksAccountAndUsesTheSimulatedCollectionEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(status())
            server.enqueue(MockResponse().setBody("""{"data":true}"""))
            SeanimeApiClient(server.url("/").toString()).use { api -> uploadNativeLocalList(SeanimeRepository(api), "Fixture account") }
            assertEquals("/api/v1/status", server.takeRequest().path)
            val upload = server.takeRequest()
            assertEquals("POST", upload.method)
            assertEquals("/api/v1/local/sync-simulated-to-anilist", upload.path)
        }
    }

    @Test fun offlineSimulatedChangedAccountAndFalseAcknowledgementDoNotLookSuccessful() = runBlocking {
        for (response in listOf(status(offline = true), status(simulated = true), status(name = "Other account"))) {
            MockWebServer().use { server ->
                server.start(); server.enqueue(response)
                SeanimeApiClient(server.url("/").toString()).use { api ->
                    assertNotNull(runCatching { uploadNativeLocalList(SeanimeRepository(api), "Fixture account") }.exceptionOrNull())
                }
                assertEquals(1, server.requestCount)
            }
        }
        MockWebServer().use { server ->
            server.start(); server.enqueue(status()); server.enqueue(MockResponse().setBody("""{"data":false}"""))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                assertNotNull(runCatching { uploadNativeLocalList(SeanimeRepository(api), "Fixture account") }.exceptionOrNull())
            }
        }
    }

    private fun status(offline: Boolean = false, simulated: Boolean = false, name: String = "Fixture account") =
        MockResponse().setBody("""{"data":{"isOffline":$offline,"user":{"isSimulated":$simulated,"viewer":{"name":"$name"}},"settings":{}}}""")
}
