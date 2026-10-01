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

class NativeExtensionMarketplaceTest {
    @Test fun installedReadbackMustMatchTheReviewedSourceAsWellAsIdAndVersion() {
        val reviewed = SeanimeJson.extension(JSONObject().put("id", "plugin").put("version", "1").put("type", "plugin").put("manifestURI", "https://example.org/reviewed.json"))
        assertTrue(nativeMarketplaceInstalledMatches(reviewed.copy(), reviewed))
        assertFalse(nativeMarketplaceInstalledMatches(reviewed.copy(manifestUrl = "https://example.org/other.json"), reviewed))
        assertFalse(nativeMarketplaceInstalledMatches(reviewed.copy(version = "2"), reviewed))
        assertFalse(nativeMarketplaceInstalledMatches(reviewed.copy(type = "custom-source"), reviewed))
        assertFalse(nativeMarketplaceInstalledMatches(reviewed.copy(manifestUrl = ""), reviewed.copy(manifestUrl = "")))
    }
    @Test fun repositoryAddressAllowsDefaultAndPublicHttpButRejectsEmbeddedCredentialsAndFragments() {
        assertEquals("", validateNativeMarketplaceUrl("  "))
        assertEquals("https://example.org/catalog.json?branch=stable", validateNativeMarketplaceUrl(" https://example.org/catalog.json?branch=stable "))
        listOf("file:///tmp/catalog.json", "https://user:password@example.org/list", "https://example.org/list#part", "not a URL", "https://example.org/\npath").forEach {
            assertTrue(it, runCatching { validateNativeMarketplaceUrl(it) }.isFailure)
        }
    }

    @Test fun filtersCombineTypeLanguageAndHumanTextWithStableNameOrdering() {
        fun entry(id: String, name: String, type: String, lang: String) = SeanimeJson.extension(JSONObject().put("id", id).put("name", name)
            .put("description", "Owned test provider").put("type", type).put("lang", lang))
        val entries = listOf(entry("a", "Zulu", "plugin", "EN"), entry("b", "Alpha", "manga-provider", "ja"), entry("c", "Beta", "plugin", "en"))
        assertEquals(listOf("c", "a"), filterNativeMarketplace(entries, "owned", "plugin", "en").map { it.id })
        assertEquals(listOf("b"), filterNativeMarketplace(entries, "Alpha", "", "").map { it.id })
        assertTrue(filterNativeMarketplace(entries, "unknown", "", "").isEmpty())
    }

    @Test fun repositoryUrlIsAQueryValueAndInvalidResponseIsNotAnEmptyMarketplace() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"one","name":"One","manifestURI":"https://example.org/one.json"}]}"""))
            server.enqueue(MockResponse().setBody("""{"data":true}"""))
            SeanimeApiClient(server.url("/").toString()).use { client ->
                val rows = fetchNativeMarketplace(SeanimeRepository(client), "https://example.org/catalog?branch=stable&kind=tv")
                assertEquals("one", rows.single().id)
                val request = server.takeRequest()
                assertEquals("/api/v1/extensions/marketplace", request.requestUrl!!.encodedPath)
                assertEquals("https://example.org/catalog?branch=stable&kind=tv", request.requestUrl!!.queryParameter("marketplace"))
                assertTrue(runCatching { fetchNativeMarketplace(SeanimeRepository(client), "") }.isFailure)
                assertNull(server.takeRequest().requestUrl!!.queryParameter("marketplace"))
            }
        }
    }
}
