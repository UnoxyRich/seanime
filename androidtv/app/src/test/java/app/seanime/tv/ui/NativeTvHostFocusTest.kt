package app.seanime.tv.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import app.seanime.tv.R
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeJson
import app.seanime.tv.data.SeanimeRepository
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.net.InetAddress
import java.io.File

/**
 * Prepared host-only regression; not compiled or executed yet.
 *
 * The production composables and their BackHandler run unchanged. ComponentActivity is supplied
 * by the existing ui-test-manifest dependency. Plain Application prevents SeanimeTvApplication
 * from starting gomobile. A loopback fixture supplies only empty collection/plugin responses.
 *
 * Arrow/Center input uses Compose's real Android KeyEvent dispatcher. Back is sent through the
 * Activity dispatch path because performKeyInput targets a Compose View directly and therefore
 * is not a valid substitute for the Activity's system-back callback.
 *
 * This does not test the physical Android TV IME, Android window-manager lifecycle, or a decoder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [34],
    qualifiers = "w960dp-h540dp-land-television-mdpi-notouch-nokeys-navexposed-dpad",
)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class NativeTvHostFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun expandedRailReturnsToLastContentControlWithoutReflow() = fixture {
        val rail = compose.onNodeWithTag("nav-LIBRARY")
        rail.assertIsFocused()
        val contentBounds = compose.onNodeWithTag("native-content").fetchSemanticsNode().boundsInRoot
        val expandedWidth = compose.onNodeWithTag("navigation-rail").fetchSemanticsNode().boundsInRoot.width
        captureFocusedView("rail-expanded-library")

        key(rail, Key.DirectionRight)
        val search = compose.onNodeWithTag("anime-search-submit")
        search.assertIsFocused()
        key(search, Key.DirectionRight)
        val options = compose.onNodeWithTag("anime-collection-options")
        options.assertIsFocused()
        key(options, Key.DirectionRight)
        val discover = compose.onNodeWithTag("anime-discover")
        discover.assertIsFocused()
        key(discover, Key.DirectionRight)
        val lastControl = compose.onNodeWithText("Manage")
        lastControl.assertIsDisplayed().assertIsFocused()
        captureFocusedView("library-toolbar-manage-focused")

        val collapsedWidth = compose.onNodeWithTag("navigation-rail").fetchSemanticsNode().boundsInRoot.width
        assertTrue("The rail should collapse after focus enters content", collapsedWidth < expandedWidth / 2f)
        assertEquals("Expanding the rail must not move or resize content", contentBounds,
            compose.onNodeWithTag("native-content").fetchSemanticsNode().boundsInRoot)

        activityBack()
        rail.assertIsDisplayed().assertIsFocused()
        assertEquals(expandedWidth,
            compose.onNodeWithTag("navigation-rail").fetchSemanticsNode().boundsInRoot.width, .1f)
        compose.onNodeWithText("Leave Seanime?").assertDoesNotExist()
        key(rail, Key.DirectionRight)
        if (lastControl.fetchSemanticsNode().config[SemanticsProperties.Focused] != true) {
            println("Focus after returning from the rail:\n" + compose.onRoot().printToString())
        }
        lastControl.assertIsFocused()
        captureFocusedView("library-toolbar-restored-manage")
        assertEquals(contentBounds, compose.onNodeWithTag("native-content").fetchSemanticsNode().boundsInRoot)

        // Ordinary directional exit must also preserve the last target, without a Back callback.
        key(lastControl, Key.DirectionLeft)
        discover.assertIsFocused()
        key(discover, Key.DirectionLeft)
        options.assertIsFocused()
        key(options, Key.DirectionLeft)
        search.assertIsFocused()
        key(search, Key.DirectionLeft)
        rail.assertIsFocused()
        key(rail, Key.DirectionRight)
        search.assertIsFocused()
        assertEquals(contentBounds, compose.onNodeWithTag("native-content").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun searchCancelAndSaveRestoreTheirOpenerWithOnlyRemoteNavigation() = fixture {
        key(compose.onNodeWithTag("nav-LIBRARY").assertIsFocused(), Key.DirectionRight)
        val opener = compose.onNodeWithTag("anime-search-submit")
        opener.assertIsFocused().assertTextEquals("Search")
        key(opener, Key.DirectionCenter)
        var field = compose.onNodeWithTag("anime-search-field")
        field.assertIsFocused().performTextReplacement("Discarded query")
        // Robolectric has no physical IME window. This verifies the editor's IME-hidden D-pad path.
        key(field, Key.DirectionDown)
        val cancel = compose.onNodeWithTag("text-entry-cancel")
        cancel.assertIsFocused()
        key(cancel, Key.DirectionCenter)
        compose.onNodeWithTag("anime-search-field").assertDoesNotExist()
        opener.assertIsFocused().assertTextEquals("Search")

        key(opener, Key.DirectionCenter)
        field = compose.onNodeWithTag("anime-search-field")
        field.assertIsFocused()
        assertEquals("Cancel must discard the editor draft", "",
            field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        field.performTextReplacement("Frieren")
        key(field, Key.DirectionDown)
        compose.onNodeWithTag("text-entry-cancel").assertIsFocused()
        key(compose.onNodeWithTag("text-entry-cancel"), Key.DirectionRight)
        val submit = compose.onNodeWithTag("text-entry-save")
        submit.assertIsFocused().assertTextEquals("Search")
        captureFocusedView("collection-search-submit-focused", compose.onNodeWithTag("text-entry-dialog"))
        key(submit, Key.DirectionCenter)
        compose.onNodeWithTag("anime-search-field").assertDoesNotExist()
        opener.assertIsFocused().assertTextEquals("Search: Frieren")

        // Returning through the rail must keep the search opener as the last content target.
        activityBack()
        compose.onNodeWithTag("nav-LIBRARY").assertIsFocused()
        key(compose.onNodeWithTag("nav-LIBRARY"), Key.DirectionRight)
        opener.assertIsFocused().assertTextEquals("Search: Frieren")
    }

    @Test fun searchImeCallbackCommitsAndRestoresOpenerWithoutSimulatingAnImeWindow() = fixture {
        key(compose.onNodeWithTag("nav-LIBRARY").assertIsFocused(), Key.DirectionRight)
        val opener = compose.onNodeWithTag("anime-search-submit").assertIsFocused()
        key(opener, Key.DirectionCenter)
        val field = compose.onNodeWithTag("anime-search-field").assertIsFocused()
        field.performTextReplacement("Frieren")
        field.performImeAction()
        compose.onNodeWithTag("anime-search-field").assertDoesNotExist()
        opener.assertIsFocused().assertTextEquals("Search: Frieren")
    }

    @Test fun populatedLibraryDetailsAndSourceKeepAnActualRemoteFocusTarget() = fixture(populated = true) {
        key(compose.onNodeWithTag("nav-LIBRARY").assertIsFocused(), Key.DirectionRight)
        key(compose.onNodeWithTag("anime-search-submit").assertIsFocused(), Key.DirectionDown)
        val card = compose.onNodeWithTag("media-1").assertIsFocused()
        key(card, Key.DirectionCenter)
        awaitTag("anime-detail-content")
        val watch = compose.onNodeWithText("Watch")
        if (watch.fetchSemanticsNode().config[SemanticsProperties.Focused] != true) {
            println("Detail entry focus after card activation:\n" + compose.onRoot().printToString())
        }
        watch.assertIsFocused()
        captureFocusedView("library-detail-watch-focused")
        key(watch, Key.DirectionCenter)
        awaitTag("source-provider-fixture")
        val sourceFocus = compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("source-content"))).fetchSemanticsNodes()
        if (sourceFocus.size != 1) println("Source entry focus after Watch:\n" + compose.onRoot().printToString())
        assertEquals("Source selection needs an actual focused remote target without another keypress", 1, sourceFocus.size)
        captureFocusedView("source-entry-focused")
        activityBack()
        watch.assertIsFocused()
        activityBack()
        // The restored collection reloads asynchronously; assert its exact focus once the card exists.
        awaitTag("media-1")
        compose.onNodeWithTag("media-1").assertIsFocused()
        captureFocusedView("library-card-restored-after-source")
    }

    @Test fun settingsDeviceActionsAndCategoryRestoreTheirRemoteOpeners() = settingsRoute()

    @Test fun rapidDestinationThenRightEntersNewContentAndBackRestoresTheSelectedRail() = fixture {
        moveRailTo(TvFeature.LOGS)
        compose.onNodeWithTag("nav-LOGS").performKeyInput {
            pressKey(Key.DirectionCenter)
            pressKey(Key.DirectionRight)
        }
        awaitContentFocus()
        compose.onNodeWithText("Logs & diagnostics").assertIsDisplayed()
        compose.onNodeWithTag("nav-LOGS").assertIsNotFocused()
        compose.onNodeWithTag("navigation-rail").performScrollToIndex(0)
        activityBack()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("nav-LOGS") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("nav-LOGS").assertIsDisplayed().assertIsFocused()
        assertWholeControlInside("nav-LOGS", "navigation-rail")
        compose.onNodeWithText("Leave Seanime?").assertDoesNotExist()
    }

    @Test fun newerRailKeyCancelsContentEntryDuringDestinationChange() = fixture {
        moveRailTo(TvFeature.SETTINGS)
        activityKeys(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP)
        awaitTag("settings-row-device")
        println("Focused controls after rapid rail cancellation: " + compose.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.config })
        compose.onNodeWithTag("nav-NAKAMA").assertIsFocused()
        compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("native-content"))).assertCountEquals(0)
    }

    @Test fun backCancelsContentEntryBeforeTheNewDestinationIsPlaced() = fixture {
        moveRailTo(TvFeature.LOGS)
        activityKeys(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Logs & diagnostics").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Leave Seanime?").assertDoesNotExist()
        compose.onNodeWithTag("nav-LOGS").assertIsFocused()
        compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("native-content"))).assertCountEquals(0)
    }

    private fun moveRailTo(target: TvFeature) {
        for (feature in TvFeature.entries.take(target.ordinal)) {
            key(compose.onNodeWithTag("nav-${feature.name}").assertIsFocused(), Key.DirectionDown)
        }
        compose.onNodeWithTag("nav-${target.name}").assertIsFocused()
    }

    private fun awaitContentFocus() = compose.waitUntil(10_000) {
        compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("native-content"))).fetchSemanticsNodes().isNotEmpty()
    }

    private fun activityKeys(vararg keys: Int) {
        compose.runOnUiThread {
            val time = SystemClock.uptimeMillis()
            for (code in keys) {
                compose.activity.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
                compose.activity.dispatchKeyEvent(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, code, 0))
            }
        }
        compose.waitForIdle()
    }

    @Test @Config(fontScale = 1.3f)
    fun settingsLargeFontKeepsFocusedRowsAndArrowsFullyVisible() = settingsRoute()

    private fun settingsRoute() {
        val actions = mutableListOf<String>()
        fixture(onPlatformAction = { actions.add(it) }) {
            var previous = TvFeature.LIBRARY
            compose.onNodeWithTag("nav-${previous.name}").assertIsFocused()
            for (feature in TvFeature.entries.drop(1).takeWhile { it.ordinal <= TvFeature.SETTINGS.ordinal }) {
                key(compose.onNodeWithTag("nav-${previous.name}"), Key.DirectionDown)
                compose.onNodeWithTag("nav-${feature.name}").assertIsFocused().assertIsDisplayed()
                assertWholeControlInside("nav-${feature.name}", "navigation-rail")
                previous = feature
            }
            val settings = compose.onNodeWithTag("nav-SETTINGS").assertIsFocused()
            key(settings, Key.DirectionCenter)
            awaitTag("settings-row-device")
            key(settings, Key.DirectionRight)
            // Enter selects the first meaningful settings row, as verified by the real focus tree.
            val device = compose.onNodeWithTag("settings-row-device").assertIsFocused()
            key(device, Key.DirectionCenter)
            val deviceRows = listOf("anime-folder", "manga-folder", "additional-anime-folder", "screenshot-folder", "torrent-folder",
                "folder-access", "anilist", "mal", "accounts", "update")
            awaitTag("settings-row-device:anime-folder")
            for ((index, id) in deviceRows.withIndex()) {
                val row = compose.onNodeWithTag("settings-row-device:$id").assertIsFocused().assertIsDisplayed()
                assertWholeControlInside("settings-row-device:$id", "settings-list")
                if (index < deviceRows.lastIndex) key(row, Key.DirectionDown)
            }
            val update = compose.onNodeWithTag("settings-row-device:update").assertIsFocused()
            captureFocusedView("settings-update-focused")
            key(update, Key.DirectionCenter)
            assertEquals(listOf("update"), actions)
            activityBack()
            device.assertIsFocused()
            key(device, Key.DirectionDown)
            val index = compose.onNodeWithTag("settings-library-index").assertIsFocused()
            key(index, Key.DirectionDown)
            val library = compose.onNodeWithTag("settings-row-section:library").assertIsFocused()
            key(library, Key.DirectionCenter)
            awaitTag("settings-row-field:autoPlayNextEpisode")
            val firstField = compose.onNodeWithTag("settings-row-field:autoPlayNextEpisode")
            if (firstField.fetchSemanticsNode().config[SemanticsProperties.Focused] != true) {
                println("Settings category entry before reload settles:\n" + compose.onRoot().printToString())
            }
            firstField.assertIsFocused()
            activityBack()
            library.assertIsFocused()
            captureFocusedView("settings-library-category-restored")
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun assertWholeControlInside(tag: String, viewportTag: String) {
        val control = compose.onNodeWithTag(tag).assertIsFocused().fetchSemanticsNode()
        val viewport = compose.onNodeWithTag(viewportTag).fetchSemanticsNode().boundsInRoot
        assertTrue("$tag must stay fully inside $viewportTag",
            control.positionInRoot.x >= viewport.left - 1f && control.positionInRoot.y >= viewport.top - 1f &&
                control.positionInRoot.x + control.size.width <= viewport.right + 1f &&
                control.positionInRoot.y + control.size.height <= viewport.bottom + 1f)
        if (tag.startsWith("settings-row-")) {
            val arrow = compose.onNode(hasText("›") and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue("$tag arrow must remain inside the complete focused row",
                arrow.left >= control.positionInRoot.x && arrow.right <= control.positionInRoot.x + control.size.width &&
                    arrow.top >= control.positionInRoot.y && arrow.bottom <= control.positionInRoot.y + control.size.height)
        }
    }

    private fun captureFocusedView(name: String, node: SemanticsNodeInteraction = compose.onRoot()) {
        // Draw the actual Compose Android view after real key-driven focus and focus animations settle.
        // This is a Robolectric native-graphics view capture, not a device screenshot or a styled preview.
        compose.mainClock.advanceTimeBy(250)
        compose.waitForIdle()
        val view = (node.fetchSemanticsNode().root as ViewRootForTest).view
        val directory = File(System.getProperty("seanime.hostEvidenceDir") ?: "build/test-evidence/native-host-focus")
        val filename = name + if (compose.density.fontScale != 1f) "-font${(compose.density.fontScale * 100).toInt()}" else ""
        directory.mkdirs()
        compose.runOnUiThread {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "$filename.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        File(directory, "$filename.semantics.txt").writeText(node.printToString())
    }

    private fun key(node: SemanticsNodeInteraction, key: Key) {
        node.performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun activityBack() {
        compose.runOnUiThread {
            val time = SystemClock.uptimeMillis()
            compose.activity.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
            compose.activity.dispatchKeyEvent(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        }
        compose.waitForIdle()
    }

    @Suppress("DEPRECATION")
    private fun fixture(populated: Boolean = false, onPlatformAction: (String) -> Unit = {}, test: () -> Unit) {
        assertEquals("The test must not launch the gomobile Application", Application::class.java,
            compose.activity.application.javaClass)
        compose.runOnUiThread {
            compose.activity.setTheme(R.style.AppTheme)
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            compose.activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val data: Any = when (request.path) {
                        "/api/v1/library/collection" -> if (populated) JSONObject("""{"lists":[{"status":"CURRENT","entries":[{"media":{"id":1,"title":"Fixture show","description":"A populated TV navigation fixture."},"listData":{"status":"CURRENT","progress":0}}]}]}""")
                            else JSONObject().put("lists", JSONArray())
                        "/api/v1/library/anime-entry/1" -> JSONObject("""{"media":{"id":1,"title":"Fixture show","description":"A populated TV navigation fixture."},"episodes":[{"episodeNumber":1,"aniDBEpisode":"1","progressNumber":1,"displayTitle":"First episode"}]}""")
                        "/api/v1/status" -> JSONObject("""{"serverReady":true,"settings":{"library":{"enableOnlinestream":true,"defaultPlaybackSource":"onlinestream"}},"user":{"isSimulated":true}}""")
                        "/api/v1/settings" -> JSONObject("""{"library":{"autoPlayNextEpisode":true,"defaultPlaybackSource":"onlinestream","enableOnlinestream":true,"libraryPaths":[]},"manga":{}}""")
                        "/api/v1/extensions/list/onlinestream-provider" -> JSONArray().put(JSONObject().put("id", "fixture").put("name", "Fixture provider"))
                        else -> if (request.path.orEmpty().contains("extensions")) JSONArray() else JSONObject().put("lists", JSONArray()).put("Page", JSONObject().put("media", JSONArray()))
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("data", data).toString())
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
                val status = SeanimeJson.status(JSONObject("""{"version":"host-fixture","serverReady":true,"settings":{"library":{}},"user":{"isSimulated":true}}"""))
                compose.setContent { SeanimeTheme { SeanimeTvApp(SeanimeRepository(api), status, {}, onPlatformAction, {}) } }
                compose.waitUntil(10_000) {
                    if (populated) compose.onAllNodesWithTag("media-1").fetchSemanticsNodes().isNotEmpty()
                    else compose.onAllNodesWithText("Your collection starts here").fetchSemanticsNodes().isNotEmpty()
                }
                val bounds = compose.onRoot().getUnclippedBoundsInRoot()
                assertEquals("Host width must match the TV preview", 960f, (bounds.right - bounds.left).value, .1f)
                assertEquals("Host height must match the TV preview", 540f, (bounds.bottom - bounds.top).value, .1f)
                test()
            }
        }
    }
}
