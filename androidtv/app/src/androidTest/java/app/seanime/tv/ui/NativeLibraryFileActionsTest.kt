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

class NativeLibraryFileActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun matchFailureKeepsEditedDraftAndRetryClosesOnlyAfterConfirmation() = fixture { fixture ->
        openFile("edit")
        compose.onNodeWithTag("file-match-number").performScrollTo().performTvClick()
        compose.onNodeWithTag("file-match-editor-number").assertIsFocused().performTextReplacement("9")
        compose.onNodeWithTag("file-match-editor-number").performImeAction()
        val editor = isDialog() and hasAnyDescendant(hasTestTag("file-match-editor-number"))
        compose.onNode(hasText("Save") and hasAnyAncestor(editor)).performTvClick()
        compose.onNodeWithTag("file-match-number").assertIsFocused().assertTextEquals("Episode number: 9")
        compose.onNodeWithTag("file-match-save").performTvClick()
        compose.waitUntil(10_000) { fixture.patches.size == 1 && compose.onAllNodesWithTag("file-match-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("file-match-number").performScrollTo().assertTextEquals("Episode number: 9")
        compose.onNodeWithTag("file-match-dialog").assertExists()
        NativeScreenshotEvidence.capture("file-match-retained-retry-draft")
        compose.onNodeWithTag("file-match-save").performTvClick()
        compose.waitUntil(10_000) { fixture.patches.size == 2 && compose.onAllNodesWithTag("file-match-dialog").fetchSemanticsNodes().isEmpty() }
        fixture.patches.forEach { assertEquals(9, it.getJSONObject("metadata").getInt("episode")); assertEquals("keep", it.getJSONObject("metadata").getString("opaque")) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("library-file-edit-${fixture.path}") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun deletionRequiresExplicitConfirmationRetainsFailureAndRestoresRefreshFocus() = fixture { fixture ->
        openFile("delete")
        compose.onNodeWithTag("file-delete-cancel").assertIsFocused().performTvClick()
        assertTrue(fixture.deletions.isEmpty())
        compose.onNodeWithTag("library-file-delete-${fixture.path}").assertIsFocused().performTvClick()
        compose.onNodeWithTag("file-delete-confirm").performTvClick()
        compose.waitUntil(10_000) { fixture.deletions.size == 1 && compose.onAllNodesWithTag("file-delete-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("file-delete-dialog").assertExists()
        compose.onNodeWithTag("file-delete-confirm").performTvClick()
        compose.waitUntil(10_000) { fixture.deletions.size == 2 && compose.onAllNodesWithTag("file-delete-dialog").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("library-tools-refresh") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("No matching files").assertIsDisplayed()
        fixture.deletions.forEach { assertEquals(JSONArray().put(fixture.path).toString(), it.getJSONArray("paths").toString()); assertEquals(1, it.length()) }
    }

    private fun openFile(action: String) {
        val tag = "library-file-$action-/owned/episode.mkv"
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).performScrollTo().performTvClick()
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        MockWebServer().use { server ->
            server.dispatcher = fixture; server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
                compose.setContent { SeanimeTheme {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { LibraryTools(SeanimeRepository(api), {}, onClose = {}) }
                } }
                test(fixture)
            }
        }
    }

    private class Fixture : Dispatcher() {
        val path = "/owned/episode.mkv"
        private val present = AtomicBoolean(true)
        private val current = JSONObject("""{"path":"/owned/episode.mkv","name":"Owned episode","mediaId":21,"locked":false,"ignored":false,"metadata":{"episode":1,"aniDBEpisode":"1","type":"main","opaque":"keep"}}""")
        val patches = CopyOnWriteArrayList<JSONObject>()
        val deletions = CopyOnWriteArrayList<JSONObject>()
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path == "/api/v1/library/local-files" && request.method == "GET" -> data(JSONArray().apply { if (present.get()) put(JSONObject(current.toString())) })
            request.path == "/api/v1/library/local-file" && request.method == "PATCH" -> {
                val value = JSONObject(request.body.readUtf8()); patches += value
                if (patches.size == 1) failure() else {
                    value.keys().forEach { current.put(it, value.opt(it)) }
                    data(JSONArray().put(current))
                }
            }
            request.path == "/api/v1/library/local-files" && request.method == "DELETE" -> {
                deletions += JSONObject(request.body.readUtf8())
                if (deletions.size == 1) failure() else { present.set(false); data(true) }
            }
            else -> data(JSONArray())
        }
        private fun data(value: Any) = MockResponse().setBody(JSONObject().put("data", value).toString())
        private fun failure() = MockResponse().setResponseCode(500).setBody("""{"error":"Fixture storage failure"}""")
    }
}
