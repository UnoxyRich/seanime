package app.seanime.tv.ui

import android.app.Application
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import app.seanime.tv.R
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeJson
import app.seanime.tv.data.SeanimeRepository
import app.seanime.tv.data.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Candidate host regressions; execution is owned by the build coordinator.
 *
 * Renders the full, unchanged application. Every navigation step uses real Android arrows/Center
 * through Compose's key dispatcher, or the Activity Back dispatch path. No focus request, click
 * semantics, or scroll semantics are used to make an otherwise unreachable route pass.
 * Text replacement supplies only the text for an already-focused editor; its save uses D-pad.
 *
 * Entry and return are independent tests: a missing entry handoff must not prevent observing a
 * separate Back/state regression. These host tests do not emulate a physical TV IME or decoder.
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
class NativeTvSecondaryRouteFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun manageEntryFocusesFilesWithoutAnotherRemoteKey() = fixture {
        enterLibraryToolbar()
        openManage()
        awaitEnabled(hasTestTag("library-tools-refresh"))

        // Opening a child route must hand focus to its first safe control automatically.
        compose.onNodeWithTag("library-tab-Files").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText("Leave Seanime?").assertDoesNotExist()
    }

    @Test fun manageBackRestoresExactOpenerAndPersonalQueryAndGrid() = fixture {
        enterLibraryToolbar()
        enterCollectionQuery("Kept")
        awaitTag("media-1001")
        compose.onNodeWithTag("media-9900").assertDoesNotExist()

        // Exercise real grid navigation before returning to its persistent toolbar. The snapshot
        // is the exact viewport at the moment Manage opens, after normal D-pad bring-into-view.
        moveUntilFocused(Key.DirectionDown, hasTestTag("media-1006"), 4)
        compose.onNodeWithTag("media-1006").assertIsDisplayed().assertIsFocused()
        moveUntilFocused(Key.DirectionUp, hasTestTag("anime-search-submit"), 4)
        focusManage()
        val before = gridSnapshot()
        key(Key.DirectionCenter)
        awaitEnabled(hasTestTag("library-tools-refresh"))

        activityBack()
        awaitTag("media-grid")
        awaitTag("media-1001")
        compose.onNodeWithTag("anime-search-submit").assertTextEquals("Search: Kept")
        compose.onNodeWithTag("media-9900").assertDoesNotExist()
        assertGridUnchanged(before, gridSnapshot())
        compose.onNodeWithText("Manage").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("library-tools-refresh").assertDoesNotExist()

        // Repeat from the restored opener to expose one-shot or stale return-focus tokens.
        key(Key.DirectionCenter)
        awaitEnabled(hasTestTag("library-tools-refresh"))
        activityBack()
        awaitTag("media-1001")
        compose.onNodeWithText("Manage").assertIsFocused()
        compose.onNodeWithTag("anime-search-submit").assertTextEquals("Search: Kept")
        assertGridUnchanged(before, gridSnapshot())
    }

    @Test fun mangaChapterRouteAutomaticallyFocusesAllManga() = fixture { backend ->
        openSecondManga()
        awaitEnabled(hasTestTag("manga-edit-list"))
        assertChapterLookup(backend, 7002L)

        compose.onNode(hasText("All manga") and hasClickAction()).assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("manga-collection-7002").assertDoesNotExist()
    }

    @Test fun mangaBackRestoresTheExactSecondTitleOpenerRepeatedly() = fixture { backend ->
        openSecondManga()
        awaitEnabled(hasTestTag("manga-edit-list"))
        assertChapterLookup(backend, 7002L)

        activityBack()
        awaitFocused(hasTestTag("manga-collection-7002"))
        compose.onNodeWithTag("manga-collection-7002").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("manga-edit-list").assertDoesNotExist()
        assertInsideContent("manga-collection-7002")

        key(Key.DirectionCenter)
        awaitEnabled(hasTestTag("manga-edit-list"))
        activityBack()
        awaitFocused(hasTestTag("manga-collection-7002"))
        compose.onNodeWithTag("manga-collection-7002").assertIsDisplayed().assertIsFocused()
        assertInsideContent("manga-collection-7002")
    }

    @Test fun autoDownloaderEntryAutomaticallyFocusesDownloads() = fixture {
        openAutoFromTorrents()
        awaitEnabled(hasText("Configure") and hasClickAction())

        compose.onNode(hasText("Downloads") and hasClickAction()).assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("downloads-refresh").assertDoesNotExist()
    }

    @Test fun autoDownloaderBackRestoresItsOpenerAndOriginalTorrentTab() = fixture { backend ->
        openAutoFromTorrents()
        awaitEnabled(hasText("Configure") and hasClickAction())
        val mangaQueueReads = backend.requests.count { it.path == "/api/v1/manga/download-queue" }

        activityBack()
        awaitEnabled(hasTestTag("downloads-refresh"))
        compose.onNodeWithText("✓ Torrents").assertIsDisplayed()
        compose.onNodeWithText("✓ Manga").assertDoesNotExist()
        compose.onNodeWithText("Auto downloader").assertIsDisplayed().assertIsFocused()
        assertEquals("Returning from Auto downloader must not switch to and reload the Manga tab",
            mangaQueueReads, backend.requests.count { it.path == "/api/v1/manga/download-queue" })

        key(Key.DirectionCenter)
        awaitEnabled(hasText("Configure") and hasClickAction())
        activityBack()
        awaitEnabled(hasTestTag("downloads-refresh"))
        compose.onNodeWithText("✓ Torrents").assertIsDisplayed()
        compose.onNodeWithText("Auto downloader").assertIsFocused()
    }

    private fun enterLibraryToolbar() {
        compose.onNodeWithTag("nav-LIBRARY").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("anime-search-submit").assertIsFocused()
    }

    private fun focusManage() {
        compose.onNodeWithTag("anime-search-submit").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("anime-collection-options").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("anime-discover").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithText("Manage").assertIsDisplayed().assertIsFocused()
    }

    private fun openManage() {
        focusManage()
        key(Key.DirectionCenter)
    }

    private fun enterCollectionQuery(value: String) {
        compose.onNodeWithTag("anime-search-submit").assertIsFocused()
        key(Key.DirectionCenter)
        awaitFocused(hasTestTag("anime-search-field"))
        compose.onNodeWithTag("anime-search-field").assertIsFocused().performTextReplacement(value)
        key(Key.DirectionDown)
        compose.onNodeWithTag("text-entry-cancel").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("text-entry-save").assertIsFocused()
        key(Key.DirectionCenter)
        awaitFocused(hasTestTag("anime-search-submit"))
        compose.onNodeWithTag("anime-search-submit").assertTextEquals("Search: $value")
    }

    private fun selectFeature(feature: TvFeature) {
        compose.onNodeWithTag("nav-LIBRARY").assertIsFocused()
        repeat(feature.ordinal) { ordinal ->
            compose.onNodeWithTag("nav-${TvFeature.entries[ordinal].name}").assertIsFocused()
            key(Key.DirectionDown)
        }
        compose.onNodeWithTag("nav-${feature.name}").assertIsDisplayed().assertIsFocused()
        key(Key.DirectionCenter)
    }

    private fun openSecondManga() {
        selectFeature(TvFeature.MANGA)
        awaitEnabled(hasText("✓ My collection") and hasClickAction())
        key(Key.DirectionRight)
        compose.onNodeWithText("✓ My collection").assertIsFocused()
        moveUntilFocused(Key.DirectionDown, hasTestTag("manga-collection-7002"), 6)
        compose.onNodeWithTag("manga-collection-7002").assertIsDisplayed().assertIsFocused()
        key(Key.DirectionCenter)
        awaitTag("manga-edit-list")
    }

    private fun openAutoFromTorrents() {
        selectFeature(TvFeature.DOWNLOADS)
        awaitText("No queued chapters")
        key(Key.DirectionRight)
        compose.onNodeWithText("✓ Manga").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithText("Torrents").assertIsFocused()
        key(Key.DirectionCenter)
        awaitText("No active torrents")
        compose.onNodeWithText("✓ Torrents").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithText("Debrid").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithText("Auto downloader").assertIsFocused()
        key(Key.DirectionCenter)
        awaitText("Automatic checks disabled")
    }

    private fun key(key: Key) {
        // Dialogs have their own window. The background window can still report a focused opener,
        // so send the remote event to the active dialog's focused descendant while it is open.
        // There is no fallback target or focus request when the active route loses focus.
        val target = if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty())
            isFocused() and hasAnyAncestor(isDialog()) else isFocused()
        compose.onNode(target).assertIsFocused().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun moveUntilFocused(direction: Key, target: SemanticsMatcher, maxPresses: Int) {
        repeat(maxPresses) {
            if (compose.onAllNodes(target and isFocused()).fetchSemanticsNodes().isNotEmpty()) return
            key(direction)
        }
        compose.onNode(target).assertIsDisplayed().assertIsFocused()
    }

    private fun activityBack() {
        compose.runOnUiThread {
            val time = SystemClock.uptimeMillis()
            compose.activity.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
            compose.activity.dispatchKeyEvent(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        }
        compose.waitForIdle()
    }

    private fun awaitTag(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitFocused(matcher: SemanticsMatcher) = compose.waitUntil(10_000) {
        compose.onAllNodes(matcher and isFocused()).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitEnabled(matcher: SemanticsMatcher) = compose.waitUntil(10_000) {
        compose.onAllNodes(matcher and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }

    private data class GridSnapshot(val scroll: Float, val bounds: Rect, val visibleCards: Map<String, Rect>)

    private fun gridSnapshot(): GridSnapshot {
        val grid = compose.onNodeWithTag("media-grid").fetchSemanticsNode()
        val bounds = grid.boundsInRoot
        val visible = compose.onAllNodes(hasAnyAncestor(hasTestTag("media-grid")))
            .fetchSemanticsNodes().mapNotNull { node ->
                val tag = if (node.config.contains(SemanticsProperties.TestTag)) node.config[SemanticsProperties.TestTag] else ""
                val card = node.boundsInRoot
                if (tag.startsWith("media-") && card.width > 0f && card.height > 0f &&
                    card.bottom > bounds.top && card.top < bounds.bottom) tag to card else null
            }.toMap()
        assertTrue("The grid snapshot must include actual visible media cards", visible.isNotEmpty())
        return GridSnapshot(grid.config[SemanticsProperties.VerticalScrollAxisRange].value(), bounds, visible)
    }

    private fun assertGridUnchanged(expected: GridSnapshot, actual: GridSnapshot) {
        assertEquals("Manage must preserve the collection's grid scroll offset", expected.scroll, actual.scroll, .01f)
        assertEquals("Manage must preserve the collection viewport", expected.bounds, actual.bounds)
        assertEquals("Manage must preserve the filtered cards and their positions", expected.visibleCards, actual.visibleCards)
    }

    private fun assertInsideContent(tag: String) {
        val bounds = compose.onNodeWithTag("native-content").fetchSemanticsNode().boundsInRoot
        val control = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue("The restored manga opener must fit inside the visible content viewport",
            control.left >= bounds.left - 1f && control.right <= bounds.right + 1f &&
                control.top >= bounds.top - 1f && control.bottom <= bounds.bottom + 1f)
    }

    private fun assertChapterLookup(backend: Backend, id: Long) {
        compose.waitUntil(10_000) {
            backend.requests.any { it.path == "/api/v1/manga/chapters" && it.body.optLong("mediaId") == id }
        }
        awaitEnabled(hasTestTag("manga-edit-list"))
        val lookup = backend.requests.last { it.path == "/api/v1/manga/chapters" }
        assertEquals(id, lookup.body.getLong("mediaId"))
        assertEquals("fixture-source", lookup.body.getString("provider"))
    }

    @Suppress("DEPRECATION")
    private fun fixture(test: (Backend) -> Unit) {
        assertEquals("The test must not launch the gomobile Application", Application::class.java,
            compose.activity.application.javaClass)
        compose.runOnUiThread {
            compose.activity.setTheme(R.style.AppTheme)
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            compose.activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        val backend = Backend()
        MockWebServer().use { server ->
            server.dispatcher = backend
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
                val repository = SeanimeRepository(api)
                val status = SeanimeJson.status(backend.status())
                compose.setContent {
                    SeanimeTheme { SeanimeTvApp(repository, status, {}, {}, {}) }
                }
                awaitTag("media-9900")
                compose.onNodeWithTag("nav-LIBRARY").assertIsFocused()
                val bounds = compose.onRoot().getUnclippedBoundsInRoot()
                assertEquals("Host width must match the TV preview", 960f, (bounds.right - bounds.left).value, .1f)
                assertEquals("Host height must match the TV preview", 540f, (bounds.bottom - bounds.top).value, .1f)
                try {
                    test(backend)
                    assertTrue("Unhandled fixture requests: ${backend.unexpected}", backend.unexpected.isEmpty())
                } catch (failure: Throwable) {
                    println("Secondary route failure. Requests: ${backend.requests.map { "${it.method} ${it.path}" }}")
                    println("Unhandled fixture requests: ${backend.unexpected}")
                    println("Original failure:\n${failure.stackTraceToString()}")
                    runCatching {
                        val roots = compose.onAllNodes(isRoot())
                        repeat(roots.fetchSemanticsNodes().size) { index ->
                            println("Semantics root $index at failure:\n" + roots[index].printToString())
                        }
                    }.onFailure { diagnosticFailure ->
                        failure.addSuppressed(diagnosticFailure)
                        println("Could not collect semantics: $diagnosticFailure")
                    }
                    throw failure
                }
            }
        }
    }

    private data class Request(val method: String, val path: String, val body: JSONObject)

    private class Backend : Dispatcher() {
        val requests = CopyOnWriteArrayList<Request>()
        val unexpected = CopyOnWriteArrayList<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            val body = request.body.readUtf8().let { if (it.isBlank()) JSONObject() else JSONObject(it) }
            val method = request.method.orEmpty()
            requests += Request(method, path, body)
            val value: Any = when ("$method $path") {
                "GET /api/v1/library/collection" -> collection(
                    listOf(9900L to "Excluded title") + (1L..8L).map { 1000L + it to "Kept title $it" })
                "GET /api/v1/manga/collection" -> collection(listOf(7001L to "First manga", 7002L to "Second manga"))
                "GET /api/v1/library/local-files",
                "GET /api/v1/manga/download-queue",
                "GET /api/v1/torrent-client/list",
                "GET /api/v1/auto-downloader/rules",
                "GET /api/v1/auto-downloader/profiles",
                "GET /api/v1/auto-downloader/items",
                "GET /api/v1/extensions/list/anime-torrent-provider" -> JSONArray()
                "GET /api/v1/status" -> status()
                "GET /api/v1/settings" -> jsonObject(
                    "library" to jsonObject("libraryPath" to "/fixture/library"),
                    "torrent" to jsonObject("defaultTorrentClient" to "qbittorrent"),
                    "manga" to jsonObject("defaultMangaProvider" to "fixture-source", "mangaAutoUpdateProgress" to false),
                    "autoDownloader" to jsonObject("enabled" to false, "interval" to 20))
                "GET /api/v1/manga/source-refresh" -> JSONObject.NULL
                "GET /api/v1/extensions/list/manga-provider" -> JSONArray().put(
                    jsonObject("id" to "fixture-source", "name" to "Fixture source"))
                "GET /api/v1/manga/preferences" -> JSONObject()
                "POST /api/v1/manga/get-mapping" -> jsonObject("mangaId" to JSONObject.NULL)
                "POST /api/v1/manga/chapters" -> jsonObject("chapters" to JSONArray().put(
                    jsonObject("id" to "chapter-${body.getLong("mediaId")}", "title" to "Fixture chapter",
                        "chapter" to "1", "provider" to "fixture-source")))
                else -> {
                    unexpected += "$method $path"
                    return MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
                        .setBody("""{"error":"Unexpected secondary-route fixture request"}""")
                }
            }
            return MockResponse().setHeader("Content-Type", "application/json")
                .setBody(jsonObject("data" to value).toString())
        }

        fun status() = jsonObject("version" to "host-fixture", "serverReady" to true, "isOffline" to false,
            "settings" to jsonObject("library" to JSONObject()), "user" to jsonObject("isSimulated" to true))

        private fun collection(titles: List<Pair<Long, String>>) = jsonObject("lists" to JSONArray().put(
            jsonObject("status" to "CURRENT", "entries" to JSONArray(titles.map { (id, title) ->
                jsonObject("media" to jsonObject("id" to id, "title" to title),
                    "listData" to jsonObject("status" to "CURRENT", "progress" to 1))
            }))))
    }
}
