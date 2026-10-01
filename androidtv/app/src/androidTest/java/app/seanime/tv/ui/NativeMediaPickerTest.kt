package app.seanime.tv.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewRootForTest
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
import java.util.concurrent.atomic.AtomicReference

/** Real HTTP-backed Compose workflows; remote actions select titles without typing database IDs. */
class NativeMediaPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun catalogSearchSelectsTheReturnedTitleAndCancelDoesNotSelect() {
        val searchBody = AtomicReference<JSONObject>()
        val selected = AtomicReference<Long>()
        withServer({ request ->
            if (request.path == "/api/v1/anilist/list-anime") {
                searchBody.set(JSONObject(request.body.readUtf8())); JSONArray().put(media(42))
            } else JSONArray()
        }) { repo ->
            val open = mutableStateOf(true)
            compose.setContent { SeanimeTheme { NativeArtworkProvider(repo.client) {
                if (open.value) NativeMediaPickerDialog(repo, "Choose anime", onDismiss = { open.value = false }) { selected.set(it.id) }
            } } }
            awaitTag("media-picker-collection")
            compose.onNodeWithTag("media-picker-cancel").performTvClick()
            compose.runOnIdle { assertNull(selected.get()); open.value = true }
            awaitFocused("media-picker-collection")
            compose.onNodeWithTag("media-picker-query").performTextInput("Fixture title")
            compose.onNodeWithTag("media-picker-query").performImeAction()
            awaitTag("media-picker-42")
            compose.onNodeWithTag("media-picker-search").assertIsFocused()
            NativeScreenshotEvidence.capture("native-title-picker-search-focus")
            compose.onNodeWithTag("media-picker-42").performTvClick()
            compose.runOnIdle {
                assertEquals(42L, selected.get()); assertFalse(open.value)
                assertEquals("Fixture title", searchBody.get().getString("search"))
                assertEquals("SEARCH_MATCH", searchBody.get().getJSONArray("sort").getString(0))
            }
        }
    }

    @Test fun offlineTrackingUsesSelectedMediaAndExactExistingPayload() {
        val submitted = AtomicReference<JSONObject>()
        withServer({ request -> when (request.path) {
            "/api/v1/local/track" -> if (request.method == "POST") { submitted.set(JSONObject(request.body.readUtf8())); true } else JSONArray()
            "/api/v1/local/storage/size" -> "0 B"
            "/api/v1/local/updated" -> false
            "/api/v1/local/queue" -> JSONObject().put("animeTasks", JSONObject()).put("mangaTasks", JSONObject())
            "/api/v1/status" -> JSONObject().put("isOffline", false)
            "/api/v1/library/collection" -> JSONArray().put(media(42))
            else -> error("Unexpected fixture route: ${request.method} ${request.path}")
        } }) { repo ->
            compose.setContent { SeanimeTheme { NativeArtworkProvider(repo.client) { FeatureScreen(TvFeature.OFFLINE, repo, {}, {}) } } }
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Track anime"))
            compose.waitUntil(30_000) { compose.onAllNodes(hasText("Track anime") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Track anime").performTvClick()
            awaitFocused("media-picker-collection")
            awaitTag("media-picker-42")
            compose.onNodeWithTag("media-picker-42").performTvClick()
            compose.waitUntil(30_000) { submitted.get() != null }
            val payload = submitted.get()
            assertEquals(setOf("media"), payload.keys().asSequence().toSet())
            val selected = payload.getJSONArray("media").getJSONObject(0)
            assertEquals(42, selected.getInt("mediaId")); assertEquals("anime", selected.getString("type"))
            compose.onNodeWithTag("media-picker-query").assertDoesNotExist()
        }
    }

    @Test fun playlistTitleSelectionAppendsOnceAndPreservesEpisodePayload() {
        val first = episode(1)
        val second = episode(2)
        val state = AtomicReference(JSONObject().put("dbId", 7).put("name", "Fixture queue").put("episodes", JSONArray().put(first)))
        val patch = AtomicReference<JSONObject>()
        withServer({ request -> when (request.path) {
            "/api/v1/playlists" -> JSONArray().put(state.get())
            "/api/v1/library/collection" -> JSONArray().put(media(42))
            "/api/v1/playlist/episodes/42" -> JSONArray().put(first).put(second)
            "/api/v1/playlist" -> JSONObject(request.body.readUtf8()).also { patch.set(it); state.set(it) }
            else -> error("Unexpected fixture route: ${request.method} ${request.path}")
        } }) { repo ->
            compose.setContent { SeanimeTheme { NativeArtworkProvider(repo.client) { FeatureScreen(TvFeature.PLAYLISTS, repo, {}, {}) } } }
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Open").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Open").performTvClick()
            compose.onNodeWithText("Add anime").performTvClick()
            awaitFocused("media-picker-collection")
            awaitTag("media-picker-42")
            compose.onNodeWithTag("media-picker-42").performTvClick()
            compose.waitUntil(30_000) { patch.get() != null }
            val value = patch.get()
            assertEquals(7, value.getInt("dbId"))
            val episodes = value.getJSONArray("episodes")
            assertEquals(2, episodes.length())
            assertEquals("opaque-fixture", episodes.getJSONObject(1).getJSONObject("episode").getString("providerMetadata"))
            assertEquals("localfile", episodes.getJSONObject(1).getString("watchType"))
            assertEquals("/fixture/2.mkv", episodes.getJSONObject(1).getJSONObject("episode").getJSONObject("localFile").getString("path"))
        }
    }

    @Test fun hiddenTitlesCancelPreservesOriginalAndChosenTitlesSaveNumericIds() {
        val original = JSONArray().put(42)
        val saved = AtomicReference<JSONArray>()
        withServer({ JSONArray().put(media(42)).put(media(43)) }) { repo ->
            val open = mutableStateOf(true)
            compose.setContent { SeanimeTheme { NativeArtworkProvider(repo.client) {
                if (open.value) HiddenTitlesDialog(repo, original, { open.value = false }) { saved.set(it) }
            } } }
            compose.onNodeWithText("Remove").performTvClick()
            compose.onNodeWithText("Cancel").performTvClick()
            compose.runOnIdle {
                assertNull(saved.get()); assertEquals(42, original.getInt(0)); open.value = true
            }
            compose.onNodeWithText("Add title").performTvClick()
            awaitFocused("media-picker-collection")
            awaitTag("media-picker-43")
            compose.onNodeWithTag("media-picker-43").performTvClick()
            awaitFocused("hidden-titles-add")
            compose.onNodeWithText("Save list").performTvClick()
            compose.runOnIdle {
                assertEquals(2, saved.get().length()); assertEquals(43, saved.get().getInt(1))
                assertTrue(saved.get().get(1) is Number); assertEquals(1, original.length())
            }
        }
    }

    private fun media(id: Int) = JSONObject().put("id", id).put("title", "Fixture title $id")
    private fun episode(number: Int) = JSONObject().put("watchType", "localfile").put("isCompleted", false)
        .put("episode", JSONObject().put("baseAnime", media(42)).put("episodeNumber", number).put("providerMetadata", "opaque-fixture")
            .put("localFile", JSONObject().put("path", "/fixture/$number.mkv")))
    private fun awaitTag(tag: String) = compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitFocused(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes()
            .any { (it.root as ViewRootForTest).view.hasWindowFocus() }
    }
    private fun withServer(respond: (RecordedRequest) -> Any, block: (SeanimeRepository) -> Unit) {
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("data", respond(request)).toString()) }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
            try { block(SeanimeRepository(api)) } finally { server.shutdown() }
        }
    }
}
