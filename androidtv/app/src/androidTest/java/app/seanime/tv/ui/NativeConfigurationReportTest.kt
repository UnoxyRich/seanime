package app.seanime.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NativeConfigurationReportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enabledExtensionShowsConfigurationErrorAndKeepsDraftThroughLoadAndSaveRetry() = fixture(TvFeature.EXTENSIONS) { fixture ->
        awaitText("Configuration needs attention: user config is missing")
        compose.onNodeWithTag("extension-configure-fixture").performScrollTo().performTvClick()
        awaitText("Fixture configuration unavailable")
        compose.onNodeWithTag("extension-config-save").assertIsNotEnabled()
        compose.onNodeWithTag("extension-config-retry").performTvClick()
        awaitText("Region")
        compose.onNodeWithText("Japan").performScrollTo().performTvClick()
        compose.onNodeWithTag("extension-config-save").performScrollTo().performTvClick()
        awaitText("The server did not confirm the configuration save")
        compose.onNodeWithText("Configuration saved").assertDoesNotExist()
        compose.onNodeWithText("✓ Japan").performScrollTo().assertExists()
        compose.onNodeWithTag("extension-config-save").performScrollTo().performTvClick()
        awaitText("Configuration saved")
        compose.onNodeWithText("Configuration needs attention: user config is missing").assertDoesNotExist()
        assertEquals(2, fixture.configSaves.size)
        fixture.configSaves.forEach { body ->
            assertEquals("fixture", body.getString("id"))
            assertEquals(3, body.getInt("version"))
            assertEquals("JP", body.getJSONObject("values").getString("region"))
        }
        compose.onNodeWithTag("extension-config-back").performScrollTo().performTvClick()
        awaitTag("extension-configure-fixture")
        compose.onNodeWithText("Configuration needs attention: user config is missing").assertDoesNotExist()
        NativeScreenshotEvidence.capture("native-extension-config-repaired")
    }

    @Test fun reportChoicePreservesDescriptionOnFailureAndExportsOnlyAfterAcknowledgement() = fixture(TvFeature.LOGS) { fixture ->
        awaitText("Fixture server log")
        compose.onNodeWithText("Create issue report").performTvClick()
        awaitFocused("report-cancel")
        compose.onNodeWithTag("report-cancel").performTvClick()
        assertTrue(fixture.reports.isEmpty())
        compose.onNodeWithText("Create issue report").performTvClick()
        compose.onNodeWithTag("report-content").performScrollToNode(hasTestTag("report-edit-description"))
        compose.onNodeWithTag("report-edit-description").performTvClick()
        awaitFocused("report-description-editor")
        compose.onNodeWithTag("report-description-editor").performTextReplacement("Scanner missed the selected series")
        compose.onNodeWithText("Save").performTvClick()
        awaitFocused("report-edit-description")
        compose.onNodeWithTag("report-prepare").performTvClick()
        compose.waitUntil(10_000) { fixture.reports.size == 1 && compose.onAllNodes(hasTestTag("report-prepare") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("report-content").performScrollToNode(hasText("The server did not confirm that the report was prepared"))
        compose.onNodeWithText("The server did not confirm that the report was prepared").assertIsDisplayed()
        compose.onNodeWithText("Export report").assertDoesNotExist()
        compose.onNodeWithTag("report-content").performScrollToNode(hasTestTag("report-library"))
        compose.onNodeWithTag("report-library").performTvClick()
        compose.onNodeWithTag("report-prepare").performTvClick()
        awaitText("Export report")
        assertEquals(2, fixture.reports.size)
        assertFalse(fixture.reports.first().getBoolean("isAnimeLibraryIssue"))
        assertTrue(fixture.reports.last().getBoolean("isAnimeLibraryIssue"))
        fixture.reports.forEach { assertEquals("Scanner missed the selected series", it.getString("description")) }
        assertEquals("Preparing an archive must not automatically export/share it", 0, fixture.platformRequests.get())
        NativeScreenshotEvidence.capture("native-library-issue-report-ready")
    }

    private fun awaitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitFocused(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty() }
    private fun fixture(feature: TvFeature, test: (Fixture) -> Unit) {
        val fixture = Fixture()
        MockWebServer().use { server ->
            server.dispatcher = fixture
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
                val repo = SeanimeRepository(api)
                compose.setContent { SeanimeTheme {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        FeatureScreen(feature, repo, {}, { fixture.platformRequests.incrementAndGet() })
                    }
                } }
                test(fixture)
            }
        }
    }

    private class Fixture : Dispatcher() {
        val configSaves = CopyOnWriteArrayList<JSONObject>()
        val reports = CopyOnWriteArrayList<JSONObject>()
        val platformRequests = AtomicInteger()
        private val failConfigLoad = AtomicBoolean(true)
        private val configValid = AtomicBoolean(false)
        override fun dispatch(request: RecordedRequest): MockResponse {
            val data: Any = when (request.path) {
                "/api/v1/extensions/all" -> JSONObject().put("extensions", JSONArray().put(JSONObject("""{"id":"fixture","name":"Provider fixture","type":"anime-torrent-provider","manifestURI":"builtin"}""")))
                    .put("invalidUserConfigExtensions", JSONArray().apply { if (!configValid.get()) put(JSONObject().put("id", "fixture").put("reason", "user config is missing")) })
                "/api/v1/extensions/user-config/fixture" -> {
                    if (failConfigLoad.getAndSet(false)) return MockResponse().setResponseCode(503).setBody("""{"error":"Fixture configuration unavailable"}""")
                    JSONObject("""{"userConfig":{"version":3,"requiresConfig":true,"fields":[{"name":"region","label":"Region","type":"select","default":"US","options":[{"value":"US","label":"United States"},{"value":"JP","label":"Japan"}]}]},"savedUserConfig":{"version":3,"values":{"region":"US"}}}""")
                }
                "/api/v1/extensions/user-config" -> {
                    configSaves.add(JSONObject(request.body.readUtf8()))
                    (configSaves.size > 1).also { configValid.set(it) }
                }
                "/api/v1/logs/latest" -> "Fixture server log"
                "/api/v1/report/issue" -> { reports.add(JSONObject(request.body.readUtf8())); reports.size > 1 }
                else -> JSONObject()
            }
            return MockResponse().setBody(JSONObject().put("data", data).toString())
        }
    }
}
