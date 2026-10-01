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
import androidx.compose.ui.semantics.SemanticsActions
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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded-response regressions on production routes.
 * Each gate is explicitly released in finally and times out after eight seconds.
 * Remote journeys use no focus assignment, semantic click, forced scrolling, or enlarged wait.
 * Explicitly labeled regressions inject platform fallback focus to model outgoing views.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34],
    qualifiers = "w960dp-h540dp-land-television-mdpi-notouch-nokeys-navexposed-dpad")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class NativeTvLoadingFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun detailLoadingHasVisibleBackAndLateDataCannotTakeRailFocus() = fixture(Delay.DETAIL_ENTRY) { backend ->
        openLibraryCard()
        awaitTag("detail-pending-back")
        awaitBlocked(backend)
        compose.onNodeWithTag("detail-pending-back").assertIsDisplayed().assertIsFocused()
        moveLeftTo("nav-LIBRARY")
        backend.gate.release()
        awaitTag("anime-detail-content")
        compose.onNodeWithTag("nav-LIBRARY").assertIsFocused()
        compose.onNodeWithText("Watch").assertIsNotFocused()
        capture("detail-late-data-keeps-rail")
    }

    @Test fun discoveryReturnKeepsVisibleBackAndRespectsRemoteRailChoice() = fixture(Delay.DISCOVERY_RETURN) { backend ->
        openDiscovery()
        key(compose.onNodeWithTag("discovery-back").assertIsFocused(), Key.DirectionDown)
        moveLeftTo("discovery-search-submit")
        key(compose.onNodeWithTag("discovery-search-submit").assertIsFocused(), Key.DirectionDown)
        key(compose.onNodeWithTag("discovery-media-1").assertIsFocused(), Key.DirectionCenter)
        awaitTag("anime-detail-content")
        activityBack()
        awaitBlocked(backend)
        compose.onNodeWithTag("discovery-back").assertIsFocused()
        moveLeftTo("nav-LIBRARY")
        backend.gate.release()
        awaitTag("discovery-media-1")
        compose.onNodeWithTag("nav-LIBRARY").assertIsFocused()
        compose.onNodeWithTag("discovery-media-1").assertIsNotFocused()
        capture("discovery-late-data-keeps-rail")
    }

    @Test fun malformedDetailShowsRetryAndBackReachableOnlyWithRemoteKeys() = fixture(Delay.DETAIL_MALFORMED) {
        openLibraryCard()
        awaitTag("detail-retry")
        compose.onNodeWithText("Watch").assertDoesNotExist()
        key(compose.onNodeWithTag("detail-retry").assertIsFocused(), Key.DirectionUp)
        compose.onNodeWithTag("detail-pending-back").assertIsFocused()
        key(compose.onNodeWithTag("detail-pending-back"), Key.DirectionDown)
        key(compose.onNodeWithTag("detail-retry").assertIsFocused(), Key.DirectionCenter)
        awaitTag("anime-detail-content")
        compose.onNodeWithText("Watch").assertIsFocused()
    }

    @Test fun malformedCollectionReturnKeepsArrowReachableRetryInsteadOfEmptySuccess() = fixture(Delay.COLLECTION_MALFORMED) {
        openDetail()
        activityBack()
        awaitText("Try again")
        compose.onNodeWithText("Your collection starts here").assertDoesNotExist()
        key(compose.onNodeWithTag("anime-search-submit").assertIsFocused(), Key.DirectionDown)
        key(compose.onNodeWithText("Try again").assertIsFocused(), Key.DirectionCenter)
        awaitTag("media-1")
        compose.onNodeWithTag("anime-search-submit").assertIsFocused()
    }

    @Test fun malformedDiscoveryKeepsArrowReachableRetryAndReturnsRealResponseCards() = fixture(Delay.DISCOVERY_MALFORMED) {
        openDiscovery(expectCards = false)
        awaitText("Try again")
        compose.onNodeWithText("No anime found").assertDoesNotExist()
        compose.onNodeWithTag("discovery-media-0").assertDoesNotExist()
        key(compose.onNodeWithTag("discovery-back").assertIsFocused(), Key.DirectionDown)
        moveLeftTo("discovery-search-submit")
        key(compose.onNodeWithTag("discovery-search-submit").assertIsFocused(), Key.DirectionDown)
        key(compose.onNodeWithText("Try again").assertIsFocused(), Key.DirectionCenter)
        awaitTag("discovery-media-1")
        compose.onNodeWithTag("discovery-search-submit").assertIsFocused()
    }

    @Test fun lateCollectionCompletionKeepsTheUsersBackToRailFocus() = fixture(Delay.COLLECTION_RETURN) { backend ->
        openDetail()
        activityBack()
        awaitBlocked(backend)
        activityBack()
        val rail = compose.onNodeWithTag("nav-LIBRARY").assertIsFocused()
        capture("collection-pending-rail-focused")
        backend.gate.release()
        awaitTag("media-1")
        rail.assertIsFocused()
        compose.onNodeWithTag("media-1").assertIsNotFocused()
        compose.onNodeWithText("Leave Seanime?").assertDoesNotExist()
    }

    @Test fun automaticRailFallbackDuringDetailReturnDoesNotCancelSavedCard() = fixture(Delay.COLLECTION_RETURN) { backend ->
        openDetail()
        activityBack()
        awaitBlocked(backend)
        // Model the Android window's automatic focus fallback while its detail view
        // disappears. This is a regression injection, not evidence of a remote input.
        compose.onNodeWithTag("nav-LIBRARY").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("nav-LIBRARY").assertIsFocused()
        backend.gate.release()
        awaitTag("media-1")
        compose.onNodeWithTag("media-1").assertIsFocused()
    }

    @Test fun aNewToolbarKeyDuringDetailReturnCancelsThePendingCardRestore() = fixture(Delay.COLLECTION_RETURN) { backend ->
        openDetail()
        activityBack()
        awaitBlocked(backend)
        // The window may still be assigning fallback focus after detail removal.
        // Establish the newer choice with actual keys, without assigning focus.
        repeat(4) {
            if (compose.onAllNodes(hasTestTag("anime-collection-options") and isFocused()).fetchSemanticsNodes().isEmpty())
                key(compose.onRoot(), Key.DirectionRight)
        }
        val options = compose.onNodeWithTag("anime-collection-options").assertIsFocused()
        backend.gate.release()
        awaitTag("media-1")
        options.assertIsFocused()
        compose.onNodeWithTag("media-1").assertIsNotFocused()
    }

    @Test fun sourcePreparationKeepsAVisibleRemoteTargetBeforeStatusReturns() = fixture(Delay.SOURCE_STATUS) { backend ->
        openDetail()
        key(compose.onNodeWithText("Watch").assertIsFocused(), Key.DirectionCenter)
        awaitTag("source-content")
        awaitBlocked(backend)
        val focused = compose.onAllNodes(isFocused() and hasClickAction() and
            hasAnyAncestor(hasTestTag("source-content"))).fetchSemanticsNodes()
        assertEquals("Source preparation must expose one focused actionable target before HTTP completes", 1, focused.size)
        compose.onNode(isFocused() and hasClickAction() and hasAnyAncestor(hasTestTag("source-content"))).assertIsDisplayed()
        capture("source-preparing-target-focused")
        backend.gate.release()
        awaitTag("source-provider-fixture")
    }

    @Test fun libraryToolsKeepsFilesFocusedWhileItsInitialReadIsPending() = fixture(Delay.TOOLS_FILES) { backend ->
        key(compose.onNodeWithTag("nav-LIBRARY").assertIsFocused(), Key.DirectionRight)
        key(compose.onNodeWithTag("anime-search-submit").assertIsFocused(), Key.DirectionRight)
        key(compose.onNodeWithTag("anime-collection-options").assertIsFocused(), Key.DirectionRight)
        key(compose.onNodeWithTag("anime-discover").assertIsFocused(), Key.DirectionRight)
        key(compose.onNodeWithText("Manage").assertIsFocused(), Key.DirectionCenter)
        awaitTag("library-tab-Files")
        awaitBlocked(backend)
        compose.onNodeWithTag("library-tab-Files").assertIsDisplayed().assertIsFocused()
        capture("library-tools-pending-files-focused")
        backend.gate.release()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("library-tools-refresh") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-tab-Files").assertIsFocused()
    }

    @Test fun settingsBackRestoresItsTemporarilyDisabledOriginDuringReload() = fixture(Delay.SETTINGS_CATEGORY) { backend ->
        var previous = TvFeature.LIBRARY
        for (feature in TvFeature.entries.drop(1).takeWhile { it.ordinal <= TvFeature.SETTINGS.ordinal }) {
            key(compose.onNodeWithTag("nav-${previous.name}").assertIsFocused(), Key.DirectionDown)
            compose.onNodeWithTag("nav-${feature.name}").assertIsFocused()
            previous = feature
        }
        val settings = compose.onNodeWithTag("nav-SETTINGS").assertIsFocused()
        key(settings, Key.DirectionCenter)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("settings-row-section:library") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        key(settings, Key.DirectionRight)
        key(compose.onNodeWithTag("settings-row-device").assertIsFocused(), Key.DirectionDown)
        key(compose.onNodeWithTag("settings-library-index").assertIsFocused(), Key.DirectionDown)
        val origin = compose.onNodeWithTag("settings-row-section:library").assertIsFocused()
        key(origin, Key.DirectionCenter)
        awaitTag("settings-row-field:autoPlayNextEpisode")
        awaitBlocked(backend)
        compose.onNodeWithTag("settings-row-field:autoPlayNextEpisode").assertIsNotEnabled().assertIsDisplayed().assertIsFocused()
        capture("settings-pending-disabled-field-focused")
        activityBack()
        origin.assertIsNotEnabled().assertIsDisplayed().assertIsFocused()
        capture("settings-pending-disabled-origin-focused")
        backend.gate.release()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("settings-row-section:library") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        origin.assertIsFocused()
    }

    @Test fun departingSettingsFallbackCannotOverwriteTheSavedDeviceRow() = fixture(Delay.SETTINGS_CATEGORY) { _ ->
        var previous = TvFeature.LIBRARY
        for (feature in TvFeature.entries.drop(1).takeWhile { it.ordinal <= TvFeature.SETTINGS.ordinal }) {
            key(compose.onNodeWithTag("nav-${previous.name}").assertIsFocused(), Key.DirectionDown)
            compose.onNodeWithTag("nav-${feature.name}").assertIsFocused()
            previous = feature
        }
        val settings = compose.onNodeWithTag("nav-SETTINGS").assertIsFocused()
        key(settings, Key.DirectionCenter)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("settings-row-section:library") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        key(settings, Key.DirectionRight)
        key(compose.onNodeWithTag("settings-row-device").assertIsFocused(), Key.DirectionCenter)
        val deviceRows = listOf("anime-folder", "manga-folder", "additional-anime-folder", "screenshot-folder",
            "torrent-folder", "folder-access", "anilist", "mal", "accounts", "update")
        deviceRows.forEachIndexed { index, id ->
            val row = compose.onNodeWithTag("settings-row-device:$id").assertIsFocused()
            if (index < deviceRows.lastIndex) key(row, Key.DirectionDown)
        }
        val lastRow = compose.onNodeWithTag("settings-row-device:update").assertIsFocused()
        val lastY = lastRow.fetchSemanticsNode().positionInRoot.y
        val fallback = requireNotNull(compose.onNodeWithTag("settings-row-device:accounts")
            .fetchSemanticsNode().config[SemanticsActions.RequestFocus].action)
        var accepted = false
        compose.runOnUiThread {
            val time = SystemClock.uptimeMillis()
            compose.activity.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
            compose.activity.dispatchKeyEvent(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
            // Explicit platform-fallback simulation, not remote input: Android can
            // focus an attached outgoing node after navigation but before disposal.
            accepted = fallback()
        }
        assertTrue("The real outgoing row must accept the simulated fallback before disposal", accepted)
        compose.waitForIdle()
        key(compose.onNodeWithTag("settings-row-device").assertIsFocused(), Key.DirectionCenter)
        lastRow.assertIsFocused().assertIsDisplayed()
        assertEquals("The saved Device row must retain its scroll position", lastY,
            lastRow.fetchSemanticsNode().positionInRoot.y, 1f)
    }

    private fun openDetail() {
        openLibraryCard()
        awaitTag("anime-detail-content")
        compose.onNodeWithText("Watch").assertIsFocused()
    }

    private fun openLibraryCard() {
        key(compose.onNodeWithTag("nav-LIBRARY").assertIsFocused(), Key.DirectionRight)
        key(compose.onNodeWithTag("anime-search-submit").assertIsFocused(), Key.DirectionDown)
        key(compose.onNodeWithTag("media-1").assertIsFocused(), Key.DirectionCenter)
    }

    private fun openDiscovery(expectCards: Boolean = true) {
        key(compose.onNodeWithTag("nav-LIBRARY").assertIsFocused(), Key.DirectionRight)
        key(compose.onNodeWithTag("anime-search-submit").assertIsFocused(), Key.DirectionRight)
        key(compose.onNodeWithTag("anime-collection-options").assertIsFocused(), Key.DirectionRight)
        key(compose.onNodeWithTag("anime-discover").assertIsFocused(), Key.DirectionCenter)
        awaitTag(if (expectCards) "discovery-media-1" else "discovery-back")
        compose.onNodeWithTag("discovery-back").assertIsFocused()
    }

    private fun moveLeftTo(tag: String) {
        repeat(12) {
            if (compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()) return
            key(compose.onNode(isFocused()), Key.DirectionLeft)
        }
        compose.onNodeWithTag(tag).assertIsFocused()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
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

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun awaitBlocked(backend: Backend) {
        compose.waitUntil(5_000) { backend.gate.started.get() }
        assertFalse("The HTTP response must remain pending during the focus assertions", backend.gate.released.get())
        assertFalse("The bounded response gate must not time out", backend.gate.timedOut.get())
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(250)
        compose.waitForIdle()
        val root = compose.onRoot()
        val view = (root.fetchSemanticsNode().root as ViewRootForTest).view
        val directory = File(System.getProperty("seanime.hostEvidenceDir") ?: "build/test-evidence/native-loading-focus")
        directory.mkdirs()
        compose.runOnUiThread {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        File(directory, "$name.semantics.txt").writeText(root.printToString())
    }

    @Suppress("DEPRECATION")
    private fun fixture(delay: Delay, test: (Backend) -> Unit) {
        assertEquals(Application::class.java, compose.activity.application.javaClass)
        compose.runOnUiThread {
            compose.activity.setTheme(R.style.AppTheme)
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            compose.activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        val backend = Backend(delay)
        MockWebServer().use { server ->
            server.dispatcher = backend
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
                val status = SeanimeJson.status(backend.status())
                compose.setContent { SeanimeTheme { SeanimeTvApp(SeanimeRepository(api), status, {}, {}, {}) } }
                awaitTag("media-1")
                val bounds = compose.onRoot().getUnclippedBoundsInRoot()
                assertEquals(960f, (bounds.right - bounds.left).value, .1f)
                assertEquals(540f, (bounds.bottom - bounds.top).value, .1f)
                try {
                    test(backend)
                    assertFalse("No response gate may time out", backend.gate.timedOut.get())
                } catch (failure: Throwable) {
                    println("Pending-response scenario: $delay; requests: ${backend.requests}")
                    runCatching {
                        val roots = compose.onAllNodes(isRoot())
                        repeat(roots.fetchSemanticsNodes().size) { index -> println(roots[index].printToString()) }
                    }.onFailure { diagnosticFailure -> failure.addSuppressed(diagnosticFailure) }
                    throw failure
                } finally {
                    backend.gate.release()
                }
            }
        }
    }

    private enum class Delay { COLLECTION_RETURN, SOURCE_STATUS, TOOLS_FILES, SETTINGS_CATEGORY,
        DETAIL_ENTRY, DISCOVERY_RETURN, DETAIL_MALFORMED, COLLECTION_MALFORMED, DISCOVERY_MALFORMED }

    private class Gate {
        val started = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        private val latch = CountDownLatch(1)
        fun block(): Boolean {
            started.set(true)
            val completed = latch.await(8, TimeUnit.SECONDS)
            if (!completed) timedOut.set(true)
            return completed
        }
        fun release() { released.set(true); latch.countDown() }
    }

    private class Backend(private val delay: Delay) : Dispatcher() {
        val gate = Gate()
        val requests = CopyOnWriteArrayList<String>()
        private val collectionReads = AtomicInteger()
        private val settingsReads = AtomicInteger()
        private val detailReads = AtomicInteger()
        private val discoveryReads = AtomicInteger()
        fun status() = JSONObject("""{"serverReady":true,"settings":{"library":{"enableOnlinestream":true,"defaultPlaybackSource":"onlinestream"}},"user":{"isSimulated":true}}""")
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            requests.add(path)
            val shouldBlock = when (path) {
                "/api/v1/library/collection" -> collectionReads.incrementAndGet() == 2 && delay == Delay.COLLECTION_RETURN
                "/api/v1/settings" -> settingsReads.incrementAndGet() == 2 && delay == Delay.SETTINGS_CATEGORY
                "/api/v1/status" -> delay == Delay.SOURCE_STATUS
                "/api/v1/library/local-files" -> delay == Delay.TOOLS_FILES
                "/api/v1/library/anime-entry/1" -> detailReads.incrementAndGet() == 1 && delay == Delay.DETAIL_ENTRY
                "/api/v1/anilist/list-anime" -> discoveryReads.incrementAndGet() == 2 && delay == Delay.DISCOVERY_RETURN
                else -> false
            }
            if (shouldBlock && !gate.block()) return MockResponse().setResponseCode(504).setBody("""{"error":"Fixture response gate timed out"}""")
            if (delay == Delay.COLLECTION_MALFORMED && path == "/api/v1/library/collection" && collectionReads.get() == 2)
                return MockResponse().setBody("""{"data":"bad"}""")
            if (delay == Delay.DETAIL_MALFORMED && path == "/api/v1/library/anime-entry/1" && detailReads.get() == 1)
                return MockResponse().setBody("""{"data":{}}""")
            if (delay == Delay.DISCOVERY_MALFORMED && path == "/api/v1/anilist/list-anime" && discoveryReads.get() == 1)
                return MockResponse().setBody("""{"data":{"Page":{"media":[{}]}}}""")
            val data: Any = when (path) {
                "/api/v1/library/collection" -> JSONObject("""{"lists":[{"status":"CURRENT","entries":[{"media":{"id":1,"title":"Fixture show","description":"A populated TV navigation fixture."},"listData":{"status":"CURRENT","progress":0}}]}]}""")
                "/api/v1/library/anime-entry/1" -> JSONObject("""{"media":{"id":1,"title":"Fixture show","description":"A populated TV navigation fixture."},"episodes":[{"episodeNumber":1,"aniDBEpisode":"1","progressNumber":1,"displayTitle":"First episode"}]}""")
                "/api/v1/anilist/list-anime" -> JSONObject("""{"Page":{"media":[{"id":1,"title":"Fixture show","description":"A populated TV navigation fixture."}],"pageInfo":{"currentPage":1,"hasNextPage":false}}}""")
                "/api/v1/status" -> status()
                "/api/v1/settings" -> JSONObject("""{"library":{"autoPlayNextEpisode":true,"defaultPlaybackSource":"onlinestream","enableOnlinestream":true,"libraryPaths":[]},"manga":{}}""")
                "/api/v1/library/local-files" -> JSONArray()
                "/api/v1/extensions/list/onlinestream-provider" -> JSONArray().put(JSONObject().put("id", "fixture").put("name", "Fixture provider"))
                else -> if (path.contains("extensions")) JSONArray() else JSONObject()
            }
            return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("data", data).toString())
        }
    }
}
