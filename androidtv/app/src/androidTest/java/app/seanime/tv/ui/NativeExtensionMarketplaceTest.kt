package app.seanime.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class NativeExtensionMarketplaceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualExtensionEntryBrowsesFiltersReviewsCancelsAndRetriesInstallWithoutLosingManifest() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("native-extension-marketplace", 0)
        val previous = prefs.getString("source", null)
        prefs.edit().remove("source").commit()
        val installs = CopyOnWriteArrayList<JSONObject>()
        val installed = AtomicBoolean(false)
        val manifest = JSONObject().put("id", "owned-plugin").put("name", "Owned marketplace plugin").put("version", "1.0")
            .put("type", "plugin").put("lang", "en").put("author", "Fixture author").put("description", "Owned fixture metadata")
            .put("manifestURI", "https://example.org/owned-plugin.json")
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val data: Any = when (request.requestUrl!!.encodedPath) {
                        "/api/v1/extensions/all" -> JSONObject().put("extensions", JSONArray().apply { if (installed.get()) put(manifest) })
                        "/api/v1/extensions/marketplace" -> JSONArray((0 until 16).map { index ->
                            JSONObject().put("id", "filler-$index").put("name", "Catalog item ${index.toString().padStart(2, '0')}").put("version", "1.0")
                                .put("type", "plugin").put("lang", "en").put("manifestURI", "https://example.org/filler-$index.json")
                        }).put(manifest)
                        "/api/v1/extensions/external/fetch" -> manifest
                        "/api/v1/extensions/external/install" -> {
                            installs += JSONObject(request.body.readUtf8())
                            if (installs.size == 1) return MockResponse().setResponseCode(503).setBody("""{"error":"Owned install retry"}""")
                            installed.set(true); JSONObject().put("message", "Installed fixture")
                        }
                        else -> JSONObject()
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("data", data).toString())
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
                try {
                    val repo = SeanimeRepository(api)
                    compose.setContent { SeanimeTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        FeatureScreen(TvFeature.EXTENSIONS, repo, {}, {})
                    } } }
                    awaitTag("extensions-marketplace")
                    compose.onNodeWithTag("extensions-marketplace").performTvClick()
                    awaitTag("marketplace-review-filler-0")
                    compose.onNodeWithTag("marketplace-search").performTvClick()
                    compose.onNodeWithTag("marketplace-search-editor").performTextReplacement("No matching title")
                    compose.onNodeWithTag("text-entry-save").performTvClick()
                    awaitText("No matching extensions")
                    compose.onNodeWithTag("marketplace-search").performTvClick()
                    compose.onNodeWithTag("marketplace-search-editor").performTextReplacement("")
                    compose.onNodeWithTag("text-entry-save").performTvClick()
                    awaitTag("marketplace-review-filler-0")
                    compose.onNodeWithTag("marketplace-list").performScrollToNode(hasTestTag("marketplace-review-owned-plugin"))
                    compose.onNodeWithTag("marketplace-review-owned-plugin").performTvClick()
                    awaitFocused("marketplace-install-cancel")
                    compose.onNodeWithTag("marketplace-install-cancel").performTvClick()
                    awaitFocused("marketplace-review-owned-plugin")
                    assertTrue(installs.isEmpty())
                    compose.onNodeWithTag("marketplace-review-owned-plugin").performTvClick()
                    awaitTag("marketplace-install-confirm")
                    compose.onNodeWithTag("marketplace-install-confirm").performTvClick()
                    awaitText("Owned install retry")
                    NativeScreenshotEvidence.capture("native-marketplace-install-retry")
                    compose.onNodeWithTag("marketplace-install-confirm").performTvClick()
                    awaitText("Owned marketplace plugin 1.0 is installed. Review its configuration and permissions in Installed extensions.")
                    awaitFocused("marketplace-back")
                    assertEquals(2, installs.size)
                    installs.forEach { assertEquals("https://example.org/owned-plugin.json", it.getString("manifestUri")); assertEquals(1, it.length()) }
                    compose.onNodeWithTag("marketplace-install-confirm").assertDoesNotExist()
                    compose.onNodeWithTag("marketplace-list").performScrollToNode(hasTestTag("marketplace-review-owned-plugin"))
                    compose.onNodeWithTag("marketplace-review-owned-plugin").assertIsNotEnabled()
                    compose.onNodeWithTag("marketplace-list").performScrollToNode(hasTestTag("marketplace-back"))
                    compose.onNodeWithTag("marketplace-back").performTvClick()
                    awaitTag("extension-configure-owned-plugin")
                } finally {
                    if (previous == null) prefs.edit().remove("source").commit() else prefs.edit().putString("source", previous).commit()
                }
            }
        }
    }

    private fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitFocused(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty() }
}
