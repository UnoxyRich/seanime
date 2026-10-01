package app.seanime.tv.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Exercises real Android preferences and the existing server request, without fetching the catalog. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NativeMarketplaceDefaultsTest {
    @Test fun newConfigurationUsesVerifiedHostedCatalogWithoutPersistingOrInstallingAnything() = verifySource(null, saved = false,
        expected = "https://raw.githubusercontent.com/Bas1874/Seanime-Marketplace/refs/heads/main/Marketplace/Main.json")

    @Test fun savedCustomCatalogKeepsItsExactAddressAndPreference() = verifySource(
        "https://example.org/my-catalog.json?branch=stable&lang=en", saved = true,
        expected = "https://example.org/my-catalog.json?branch=stable&lang=en")

    @Test fun explicitEmptySelectionStillUsesTheBuiltInCatalogWithoutAQueryOverride() = verifySource("", saved = true, expected = "")

    @Test fun explicitlySavedCommunityCatalogRemainsAnExplicitSelection() = verifySource(
        NativeDefaultMarketplaceUrl, saved = true, expected = NativeDefaultMarketplaceUrl)

    private fun verifySource(value: String?, saved: Boolean, expected: String) = runBlocking {
        val prefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("native-extension-marketplace", 0)
        prefs.edit().clear().commit()
        if (saved) prefs.edit().putString("source", value).commit()
        val before = prefs.all.toMap()
        val source = nativeMarketplaceInitialSource(prefs)
        assertEquals(expected, source)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":[]}"""))
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
                assertTrue(fetchNativeMarketplace(SeanimeRepository(api), source).isEmpty())
                val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("GET", request.method)
                assertEquals("/api/v1/extensions/marketplace", request.requestUrl!!.encodedPath)
                assertEquals(expected.takeIf(String::isNotBlank), request.requestUrl!!.queryParameter("marketplace"))
                assertEquals(if (expected.isBlank()) emptySet<String>() else setOf("marketplace"), request.requestUrl!!.queryParameterNames)
                assertEquals("Choosing a default loads metadata; it must not install extensions", 1, server.requestCount)
            }
        }
        assertEquals("Loading a default must not overwrite a user's saved selection", before, prefs.all)
        assertEquals(saved, prefs.contains("source"))
    }
}
