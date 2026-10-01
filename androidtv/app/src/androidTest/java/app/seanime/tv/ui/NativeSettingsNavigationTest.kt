package app.seanime.tv.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
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

/** Tests the production Settings route, its real remote navigation and unchanged API payloads. */
class NativeSettingsNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun devicePageRetainsAllActionsAndRestoresItsLastRowAfterBack() = fixture { fixture ->
        awaitTag("settings-row-device")
        compose.onNodeWithTag("settings-library-index").assertExists()
        compose.onNodeWithTag("settings-row-device").performTvClick()
        awaitFocused("settings-row-device:anime-folder")
        pressRemote(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocused("settings-row-device:manga-folder")
        assertWholeRowVisible("settings-row-device:manga-folder")
        pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("storage:manga-local", fixture.platformActions.last())

        val actions = listOf(
            "anime-folder" to "storage:library-main", "manga-folder" to "storage:manga-local",
            "additional-anime-folder" to "storage:library-additional", "screenshot-folder" to "storage:screenshot",
            "torrent-folder" to "storage:torrent-stream", "folder-access" to "storage:manage",
            "anilist" to "oauth:anilist", "mal" to "oauth:mal", "accounts" to "accounts", "update" to "update",
        )
        actions.forEach { (id, action) ->
            val tag = "settings-row-device:$id"
            scrollTo(tag).performTvClick()
            assertEquals(action, fixture.platformActions.last())
        }
        assertEquals(actions.map { it.second }, fixture.platformActions.takeLast(actions.size))
        val lastPosition = compose.onNodeWithTag("settings-row-device:update").fetchSemanticsNode().positionInRoot.y
        pressRemote(KeyEvent.KEYCODE_BACK)
        awaitFocused("settings-row-device")
        pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocused("settings-row-device:update")
        assertWholeRowVisible("settings-row-device:update")
        assertEquals("Returning to Device & accounts must retain scroll", lastPosition,
            compose.onNodeWithTag("settings-row-device:update").fetchSemanticsNode().positionInRoot.y, 1f)
        NativeScreenshotEvidence.capture("settings-device-last-row-restored")
    }

    @Test fun settingRowsKeepChoiceAndBooleanPayloadsAndRestoreListDialogOpener() = fixture { fixture ->
        scrollTo("settings-row-section:library").performTvClick()
        awaitTag("settings-row-field:defaultPlaybackSource")
        compose.onAllNodesWithText("fixture-secret", substring = true).assertCountEquals(0)
        scrollTo("settings-row-field:apiToken").assertTextContains("Saved ••••••••")
        scrollTo("settings-row-field:defaultPlaybackSource").assertTextContains("Online streaming")
        scrollTo("settings-row-field:torrentProvider").assertTextContains("Saved provider unavailable")

        scrollTo("settings-row-field:autoPlayNextEpisode").performTvClick()
        compose.waitUntil(10_000) { fixture.patches.any { it.optString("path") == "library.autoPlayNextEpisode" && it.optBoolean("value") } }
        awaitFocused("settings-row-field:autoPlayNextEpisode")
        compose.onNodeWithTag("settings-row-field:autoPlayNextEpisode").assertTextContains("Enabled")
        scrollTo("settings-row-field:defaultPlaybackSource").performTvClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("✓ Online streaming") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        pressRemote(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithText("Local library").assertIsFocused()
        pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { fixture.patches.any { it.optString("path") == "library.defaultPlaybackSource" && it.optString("value") == "library" } }
        awaitFocused("settings-row-field:defaultPlaybackSource")

        scrollTo("settings-row-field:libraryPaths").assertTextContains("2 folders").performTvClick()
        compose.onNodeWithText("/media/anime-a").assertIsDisplayed()
        compose.onNodeWithText("/media/anime-b").assertIsDisplayed()
        val patchesBeforeCancel = fixture.patches.size
        compose.onNodeWithText("Cancel").performTvClick()
        awaitFocused("settings-row-field:libraryPaths")
        assertEquals(patchesBeforeCancel, fixture.patches.size)
        assertWholeRowVisible("settings-row-field:libraryPaths")
        pressRemote(KeyEvent.KEYCODE_BACK)
        awaitFocused("settings-row-section:library")
        pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocused("settings-row-field:libraryPaths")
        assertWholeRowVisible("settings-row-field:libraryPaths")
    }

    @Test fun allSettingsRestoresOffscreenCategoryAndKeepsSecondarySettingsEndpoint() = fixture { fixture ->
        val category = "settings-row-section:mediastream"
        scrollTo(category).performTvClick()
        awaitTag("settings-row-field:transcodeHwAccel")
        assertTrue(fixture.paths.contains("/api/v1/mediastream/settings"))
        compose.onNodeWithText("All settings").performTvClick()
        awaitFocused(category)
        assertWholeRowVisible(category)
        NativeScreenshotEvidence.capture("settings-category-row-restored")
    }

    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        awaitTag("settings-list")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag(tag))
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithTag(tag)
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitFocused(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun assertWholeRowVisible(tag: String) {
        val row = compose.onNodeWithTag(tag).assertIsFocused().fetchSemanticsNode()
        val list = compose.onNodeWithTag("settings-list").fetchSemanticsNode().boundsInRoot
        assertTrue("Focused settings row must be completely visible", row.positionInRoot.y >= list.top - 1f &&
            row.positionInRoot.y + row.size.height <= list.bottom + 1f)
    }

    private fun pressRemote(key: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        compose.waitForIdle()
    }

    private fun fixture(test: (SettingsFixture) -> Unit) {
        val fixture = SettingsFixture()
        val server = MockWebServer().apply { dispatcher = fixture; start(InetAddress.getByName("127.0.0.1"), 0) }
        val api = SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        val repo = SeanimeRepository(api)
        try {
            compose.setContent { SeanimeTheme {
                Box(Modifier.fillMaxSize().padding(24.dp)) {
                    FeatureScreen(TvFeature.SETTINGS, repo, {}, { fixture.platformActions.add(it) })
                }
            } }
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("settings-row-section:library") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            test(fixture)
        } finally { api.close(); server.shutdown() }
    }

    private class SettingsFixture : Dispatcher() {
        val platformActions = CopyOnWriteArrayList<String>()
        val patches = CopyOnWriteArrayList<JSONObject>()
        val paths = CopyOnWriteArrayList<String>()
        private val library = JSONObject().put("apiToken", "fixture-secret").put("autoPlayNextEpisode", false)
            .put("defaultPlaybackSource", "onlinestream").put("libraryPaths", JSONArray(listOf("/media/anime-a", "/media/anime-b")))
            .put("torrentProvider", "missing-provider-id")

        @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            paths.add(path)
            val data: Any = when {
                path == "/api/v1/settings/path" -> {
                    val patch = JSONObject(request.body.readUtf8()); patches.add(patch)
                    if (patch.optString("path").startsWith("library.")) library.put(patch.getString("path").substringAfter('.'), patch.get("value"))
                    true
                }
                path == "/api/v1/settings" -> JSONObject().put("library", JSONObject(library.toString())).put("manga", JSONObject())
                path == "/api/v1/mediastream/settings" -> JSONObject().put("transcodeHwAccel", "mediacodec")
                path.contains("extensions") -> JSONArray()
                else -> JSONObject()
            }
            return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("data", data).toString())
        }
    }
}
