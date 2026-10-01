package app.seanime.tv.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import app.seanime.tv.R
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import app.seanime.tv.data.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Fixture transport is test-only. Navigation uses arrow/center/Back, never assigned focus or scroll. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34],
    qualifiers = "w960dp-h540dp-land-television-mdpi-notouch-nokeys-navexposed-dpad")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class NativeAnimeMetadataNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun baseEntryNeverWaitsForMetadataAndInformationBackWorksDuringLoading() = fixture(delayed = true) { backend, _ ->
        assertEquals(0, backend.metadataRequests.get())
        openInformation()
        compose.waitUntil(5_000) { backend.metadataRequests.get() == 1 }
        compose.onNodeWithTag("anime-information-back").assertIsDisplayed().assertIsFocused()
        assertInsideContent("anime-information-back")
        compose.onNodeWithTag("anime-information-fact-Release status").assertExists()
        capture("metadata-pending-base-facts-back-focused")
        activityBack()
        compose.onNodeWithTag("anime-more-information").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText("Watch").assertExists()
        backend.release.countDown()
    }

    @Test fun failedSupplementRetriesWithoutLosingBackAndRelatedTitlesRestoreTheirExactOpener() = fixture(failFirst = true) { backend, screens ->
        openInformation()
        reach("anime-information-retry")
        capture("metadata-error-retry-focused")
        key(Key.DirectionCenter)
        compose.onNodeWithTag("anime-information-back").assertIsFocused()
        compose.waitUntil(5_000) { backend.metadataRequests.get() == 2 }
        val relatedTag = "anime-information-relation:anime:4294967297"
        reach(relatedTag)
        key(Key.DirectionCenter)
        awaitTag("anime-detail-content")
        compose.onNodeWithText("Watch").assertIsFocused()
        compose.runOnIdle { assertEquals("4294967297", screens.current.parameters["id"]) }
        activityBack()
        compose.onNodeWithTag(relatedTag).assertIsDisplayed().assertIsFocused()
        assertInsideContent(relatedTag)
        capture("metadata-related-opener-restored")
        compose.runOnIdle { assertEquals("1", screens.current.parameters["id"]) }
        activityBack()
        compose.onNodeWithTag("anime-more-information").assertIsFocused()
        assertEquals(1, backend.relatedRequests.get())
    }

    @Test fun longMetadataSynopsisCharactersAndExternalActionsFitTheProductionTvMargins() = fixture(rich = true) { _, _ ->
        openInformation()
        capture("metadata-shell-long-title-overview")
        reach("anime-information-synopsis")
        assertInsideContent("anime-information-synopsis")
        capture("metadata-shell-full-synopsis-focused")
        val firstLink = "anime-information-link-Open AniList"
        val secondLink = "anime-information-link-Open trailer"
        // A full-width synopsis can enter the nearest horizontal action. Both are
        // valid; move Left when required and prove the entire row is reachable.
        if (reach(firstLink, secondLink) == secondLink) key(Key.DirectionLeft)
        compose.onNodeWithTag(firstLink).assertIsFocused()
        assertInsideContent(firstLink)
        capture("metadata-shell-platform-links-focused")
        key(Key.DirectionRight)
        compose.onNodeWithTag(secondLink).assertIsFocused()
        assertInsideContent(secondLink)
        reach("anime-information-character-42")
        assertInsideContent("anime-information-character-42")
        capture("metadata-shell-studios-rankings-character-focused")
        reach("anime-information-recommendation:anime:4294967298")
        assertInsideContent("anime-information-recommendation:anime:4294967298")
        capture("metadata-shell-recommendation-focused")
    }

    private fun openInformation() {
        compose.onNodeWithText("Watch").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("anime-edit-list").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("anime-more-information").assertIsFocused()
        key(Key.DirectionCenter)
        awaitTag("anime-information-back")
    }

    private fun reach(vararg tags: String): String {
        val focusTrace = mutableListOf<String>()
        repeat(60) {
            tags.firstOrNull { tag -> compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty() }?.let { return it }
            focusTrace += "Down $it: " + compose.onNode(isFocused()).printToString().lineSequence()
                .filter { line -> "Tag:" in line || "Focused" in line || "Text =" in line }.joinToString(" | ").take(600)
            key(Key.DirectionDown)
        }
        val filename = "metadata-unreachable-" + tags.first().replace(Regex("[^A-Za-z0-9_-]"), "-")
        capture(filename)
        val directory = File(System.getProperty("seanime.hostEvidenceDir") ?: "build/test-evidence/native-host-metadata")
        File(directory, "$filename.focus-trace.txt").writeText(focusTrace.joinToString("\n"))
        throw AssertionError("Remote Down did not reach ${tags.joinToString()}")
    }

    private fun assertInsideContent(tag: String) {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
        val content = compose.onNodeWithTag("native-content").getUnclippedBoundsInRoot()
        assertTrue("$tag must stay inside the production content width", node.left >= content.left && node.right <= content.right)
        assertTrue("$tag must stay inside the production content height", node.top >= content.top && node.bottom <= content.bottom)
    }

    private fun key(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun activityBack() {
        compose.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            compose.activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
            compose.activity.dispatchKeyEvent(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        }
        compose.waitForIdle()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(250)
        compose.waitForIdle()
        val root = compose.onRoot()
        val view = (root.fetchSemanticsNode().root as ViewRootForTest).view
        val directory = File(System.getProperty("seanime.hostEvidenceDir") ?: "build/test-evidence/native-host-metadata")
        directory.mkdirs()
        compose.runOnUiThread {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        File(directory, "$name.semantics.txt").writeText(root.printToString())
    }

    private class Backend(val delayed: Boolean, val failFirst: Boolean, val rich: Boolean) : Dispatcher() {
        val metadataRequests = AtomicInteger()
        val relatedRequests = AtomicInteger()
        val release = CountDownLatch(1)
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            if (path == "/api/v1/anilist/media-details/1") {
                val number = metadataRequests.incrementAndGet()
                if (delayed) release.await(8, TimeUnit.SECONDS)
                if (failFirst && number == 1) return MockResponse().setResponseCode(503).setBody("""{"error":"Metadata fixture unavailable"}""")
                val relation = jsonObject("relationType" to "SEQUEL", "node" to media(4_294_967_297L))
                val metadata = jsonObject("id" to 1, "relations" to jsonObject("edges" to JSONArray().put(relation)))
                if (rich) {
                    metadata.put("studios", jsonObject("nodes" to JSONArray().put(jsonObject("name" to "A long animation studio fixture name"))))
                    metadata.put("rankings", JSONArray().put(jsonObject("rank" to 12, "type" to "RATED", "allTime" to true)))
                    metadata.put("characters", jsonObject("edges" to JSONArray().put(jsonObject("role" to "MAIN", "node" to
                        jsonObject("id" to 42, "name" to jsonObject("full" to "Character fixture with a deliberately long display name"))))))
                    metadata.put("recommendations", jsonObject("edges" to JSONArray().put(jsonObject("node" to
                        jsonObject("mediaRecommendation" to media(4_294_967_298L))))))
                }
                return response(metadata)
            }
            if (path.startsWith("/api/v1/library/anime-entry/")) {
                val id = path.substringAfterLast('/').toLong()
                if (id == 4_294_967_297L) relatedRequests.incrementAndGet()
                return response(jsonObject("mediaId" to id, "media" to media(id), "episodes" to JSONArray()))
            }
            return response(when {
                path == "/api/v1/settings" -> jsonObject("anilist" to jsonObject("hideAudienceScore" to true))
                path.contains("extensions") -> JSONArray()
                else -> jsonObject()
            })
        }
        private fun media(id: Long) = jsonObject("id" to id, "type" to "ANIME", "title" to jsonObject("userPreferred" to
            if (rich) "Metadata fixture $id with a long title that remains readable on a television" else "Metadata fixture $id"),
            "status" to "FINISHED", "format" to "TV", "description" to if (rich)
                (1..12).joinToString("<br><br>") { "Synopsis fixture paragraph $it. This long description must remain fully readable with only Up and Down on the television remote, including its final sentence." }
                else "A real response-shaped synopsis for the native metadata test.").apply {
                    if (rich) {
                        put("season", "FALL"); put("seasonYear", 2018); put("episodes", 13); put("duration", 24); put("meanScore", 82)
                        put("startDate", jsonObject("year" to 2018, "month" to 10)); put("genres", JSONArray(listOf("Drama", "Romance", "Slice of Life")))
                        put("siteUrl", "https://anilist.co/anime/$id"); put("trailer", jsonObject("id" to "fixture0123", "site" to "youtube"))
                    }
                }
        private fun response(data: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(jsonObject("data" to data).toString())
    }

    private fun fixture(delayed: Boolean = false, failFirst: Boolean = false, rich: Boolean = false, test: (Backend, NativePluginScreens) -> Unit) {
        compose.runOnUiThread {
            compose.activity.setTheme(R.style.AppTheme)
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            compose.activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        val backend = Backend(delayed, failFirst, rich)
        MockWebServer().use { server ->
            server.dispatcher = backend
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val screens = NativePluginScreens()
                val repo = SeanimeRepository(api)
                try {
                    compose.setContent { SeanimeTheme { NativeArtworkProvider(api) {
                        var railFocused by remember { mutableStateOf(false) }
                        val contentFocus = remember { FocusRequester() }
                        val railFocus = remember { FocusRequester() }
                        val railGranted = remember { mutableStateOf(true) }
                        CompositionLocalProvider(LocalNativePluginScreens provides screens, LocalNativeNavigationOwnsFocus provides railFocused) {
                            NativeTvScaffold(TvFeature.LIBRARY, "Server connected", railFocused, {}, { railFocused = it },
                                contentFocus, railFocus, railGranted) {
                                AnimeDetailScreen(1, repo, {}, {})
                            }
                        }
                    } } }
                    awaitTag("anime-detail-content")
                    test(backend, screens)
                } finally { backend.release.countDown() }
            }
        }
    }
}
