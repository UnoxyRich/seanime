package app.seanime.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.KeyEvent
import android.view.inspector.WindowInspector
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.data.MangaChapter
import app.seanime.tv.data.MediaCard
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real HTTP image decode and remote input using owned pages through the downloaded-manga
 * response contract. This checks trusted server image authentication, not external-provider
 * transport. Fixtures never open or reset the embedded Go database.
 */
@OptIn(ExperimentalTestApi::class)
class NativeMangaReaderTest {
    @get:Rule val compose = createComposeRule()

    @Test @SdkSuppress(minSdkVersion = 29)
    fun decodedPagesSupportRemoteDirectionSpreadsZoomJumpProgressAndBack() = fixture { fixture ->
        waitForPage(1)
        compose.onNodeWithTag("manga-page").assertIsFocused()
        waitForFirstPagePixels()
        val imageRequest = fixture.images.first()
        assertEquals(fixture.api.snapshotSession().canonicalOrigin, imageRequest.getHeader("Origin"))
        assertEquals("reader-fixture-token", imageRequest.getHeader("X-Seanime-Token"))

        compose.onNodeWithTag("manga-page").performKeyInput { pressKey(Key.DirectionRight) }
        waitForPage(2)
        compose.onNodeWithTag("manga-page").performKeyInput { pressKey(Key.DirectionLeft) }
        waitForPage(1)
        compose.onNodeWithTag("manga-page").performKeyInput { pressKey(Key.DirectionDown) }
        assertReaderControlFocused()

        activateControl("manga-direction")
        compose.onNodeWithTag("manga-direction").assertTextContains("Right to left")
        compose.onNodeWithTag("manga-direction").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("manga-page").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        waitForPage(2)
        compose.onNodeWithTag("manga-page").performKeyInput { pressKey(Key.DirectionRight) }
        waitForPage(1)

        activateControl("manga-spread")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("manga-spread") and hasText("Two pages")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("manga-spread").assertTextContains("Two pages")
        val readerPreferences = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("native_manga_reader", Context.MODE_PRIVATE)
        compose.waitUntil(10_000) { readerPreferences.getBoolean("media:904201:rtl", false) && readerPreferences.getBoolean("media:904201:double", false) }
        assertFalse("Title changes must not overwrite global migration defaults", readerPreferences.getBoolean("rtl", true))
        assertFalse("Title changes must not overwrite global migration defaults", readerPreferences.getBoolean("double", true))
        val first = compose.onNodeWithContentDescription("Page 1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithContentDescription("Page 2").fetchSemanticsNode().boundsInRoot
        assertTrue("RTL spread puts the next page on the left", second.center.x < first.center.x)
        NativeScreenshotEvidence.capture("manga-reader-rtl-two-page-fixture")
        compose.onNodeWithTag("manga-spread").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("manga-page").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        waitForPage(3)

        activateControl("manga-zoom")
        compose.onNodeWithTag("manga-page").assertIsFocused()
        compose.onNodeWithTag("manga-page-status").assertTextContains("D-pad pans", substring = true)
        compose.onNodeWithTag("manga-page").performKeyInput { pressKey(Key.DirectionRight) }
        waitForPage(3)
        compose.onNodeWithTag("manga-page").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("manga-page-status").assertTextContains("Focus the page", substring = true)
        compose.onNodeWithTag("manga-page").performKeyInput { pressKey(Key.DirectionDown) }
        assertReaderControlFocused()

        activateControl("manga-jump")
        awaitFocused(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement("2")
        compose.waitUntil(10_000) { imeVisible() }
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(10_000) { !imeVisible() }
        awaitFocused(hasSetTextAction())
        compose.onNode(hasSetTextAction()).assertIsFocused()
        waitForPage(3)
        pressRemote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("text-entry-cancel").assertIsFocused()
        pressRemote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("text-entry-save").assertIsFocused()
        pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        waitForPage(2)
        awaitFocused(hasTestTag("manga-page"))
        compose.onNodeWithTag("manga-page").assertIsFocused()
        activateControl("manga-jump")
        awaitFocused(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement("4")
        compose.waitUntil(10_000) { imeVisible() }
        pressBack()
        compose.waitUntil(10_000) { !imeVisible() }
        // The first physical Back belongs to the keyboard. The native jump
        // dialog must still own input; underlying page semantics are insufficient.
        // The full-screen reader is itself a Dialog, so a Dialog ancestor alone
        // does not distinguish this editor title from its underlying opener.
        compose.onNode(hasText("Go to page") and !hasTestTag("manga-jump")).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertTrue(fixture.progress.isEmpty())
        pressBack()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        awaitFocused(hasTestTag("manga-page"))
        compose.onNodeWithTag("manga-page").assertIsFocused()
        waitForPage(2)
        assertEquals(0, fixture.closed.get())

        activateControl("manga-mark-read")
        compose.waitUntil(10_000) { fixture.progress.isNotEmpty() }
        val progress = fixture.progress.single()
        assertEquals(904201, progress.getInt("mediaId"))
        assertEquals(3, progress.getInt("chapterNumber"))
        assertEquals(12, progress.getInt("totalChapters"))
        assertEquals(76543, progress.getInt("malId"))
        pressBack()
        compose.onNodeWithTag("manga-page").assertDoesNotExist()
        assertEquals(1, fixture.closed.get())
    }

    @Test fun pageListFailureCanBeRetriedWithTheRemoteAndLoadsRealImages() = fixture(failPagesOnce = true) { fixture ->
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("manga-retry-pages").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Fixture chapter temporarily unavailable").assertIsDisplayed()
        compose.onNodeWithTag("manga-retry-pages").performTvClick()
        waitForPage(1)
        compose.onNodeWithTag("manga-page").assertIsFocused()
        waitForFirstPagePixels()
        assertEquals(2, fixture.pages.size)
        for (request in fixture.pages) {
            assertEquals(904201, request.getInt("mediaId"))
            assertEquals(fixture.chapter.id, request.getString("chapterId"))
            assertEquals("native-owned", request.getString("provider"))
        }
    }

    @Test fun failedImageRetriesWithoutRefetchingTheChapterOrChangingItsPage() = fixture(failImageOnce = true) { fixture ->
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("manga-retry-image-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Page 1 couldn't load").assertIsDisplayed()
        compose.onNodeWithTag("manga-retry-image-0").performTvClick()
        waitForFirstPagePixels()
        compose.onNodeWithTag("manga-retry-image-0").assertDoesNotExist()
        waitForPage(1)
        assertEquals(1, fixture.pages.size)
        assertEquals(2, fixture.images.count { it.path == "/manga-downloads/owned-reader/page-1.png" })
    }

    @Test fun automaticProgressWaitsForTheRenderedLastPageAndOnlyRunsOnce() = fixture(autoProgress = true) { fixture ->
        waitForPage(1)
        waitForFirstPagePixels()
        assertTrue(fixture.progress.isEmpty())
        repeat(3) { compose.onNodeWithTag("manga-page").performKeyInput { pressKey(Key.DirectionRight) } }
        waitForPage(4)
        compose.waitUntil(10_000) { fixture.progress.size == 1 }
        compose.onNodeWithTag("manga-page").performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionRight) }
        waitForPage(4)
        compose.waitForIdle()
        assertEquals(1, fixture.progress.size)
        assertEquals(3, fixture.progress.single().getInt("chapterNumber"))
    }

    @Test fun disabledAutomaticProgressLeavesTheLastPageUnmarked() = fixture { fixture ->
        waitForPage(1)
        repeat(3) { compose.onNodeWithTag("manga-page").performKeyInput { pressKey(Key.DirectionRight) } }
        waitForPage(4)
        compose.waitUntil(10_000) { fixture.images.any { it.path == "/manga-downloads/owned-reader/page-4.png" } }
        compose.waitForIdle()
        assertTrue(fixture.progress.isEmpty())
        assertEquals(0, fixture.entryReads.get())
    }

    @Test fun markingAnEarlierChapterReadPreservesNewerServerProgress() = fixture { fixture ->
        waitForPage(1)
        fixture.serverProgress.set(20)
        activateControl("manga-mark-read")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Chapter already marked read").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, fixture.entryReads.get())
        assertTrue(fixture.progress.isEmpty())
        assertEquals(20, fixture.serverProgress.get())
    }

    @Test fun failedFinalImageDoesNotAutoMarkUntilItsRetryRenders() = fixture(autoProgress = true, failLastImageOnce = true) { fixture ->
        waitForPage(1)
        repeat(3) { compose.onNodeWithTag("manga-page").performKeyInput { pressKey(Key.DirectionRight) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("manga-retry-image-3").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(fixture.progress.isEmpty())
        compose.onNodeWithTag("manga-retry-image-3").performTvClick()
        compose.waitUntil(10_000) { fixture.progress.size == 1 }
        assertEquals(3, fixture.serverProgress.get())
    }

    @Test fun portraitWideAndCoverSpreadsKeepPixelsPageAnchorAndFinalProgress() = fixture(
        autoProgress = true, failLastImageOnce = true, mixedWide = true) { fixture ->
        waitForPage(1)
        waitForFirstPagePixels()
        activateControl("manga-spread")
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Page 2").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(fixture.pages.first().getBoolean("doublePage"))
        assertTrue(fixture.pages.last().getBoolean("doublePage"))
        val paired = compose.onNodeWithContentDescription("Page 1").fetchSemanticsNode().boundsInRoot
        assertTrue(paired.width < compose.onNodeWithTag("manga-page").fetchSemanticsNode().boundsInRoot.width * .6f)
        activateControl("manga-cover")
        waitForPage(1)
        compose.onNodeWithContentDescription("Page 2").assertDoesNotExist()
        compose.onNodeWithTag("manga-cover").assertTextContains("Cover alone")
        compose.onNodeWithTag("manga-cover").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("manga-page").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        waitForPage(2)
        compose.onNodeWithContentDescription("Page 1").assertDoesNotExist()
        compose.onNodeWithTag("manga-page").performKeyInput { pressKey(Key.DirectionRight) }
        waitForPage(3)
        compose.onNodeWithContentDescription("Page 4").assertDoesNotExist()
        val wide = compose.onNodeWithContentDescription("Page 3").fetchSemanticsNode().boundsInRoot
        assertTrue("Landscape image occupies the full spread", wide.width > paired.width * 1.8f)
        compose.waitUntil(10_000) { fixture.images.any { it.path == "/manga-downloads/owned-reader/page-3.png" } }
        assertTrue(fixture.progress.isEmpty())
        NativeScreenshotEvidence.capture("manga-reader-wide-page-cover-fixture")
        compose.onNodeWithTag("manga-page").performKeyInput { pressKey(Key.DirectionRight) }
        waitForPage(4)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("manga-retry-image-3").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(fixture.progress.isEmpty())
        compose.onNodeWithTag("manga-retry-image-3").performTvClick()
        compose.waitUntil(10_000) { fixture.progress.size == 1 }
        activateControl("manga-cover")
        waitForPage(4)
        compose.waitForIdle()
        assertEquals(1, fixture.progress.size)
        assertEquals(2, fixture.pages.size)
        pressBack()
        assertEquals(1, fixture.closed.get())
    }

    private fun activateControl(tag: String) {
        compose.onNodeWithTag("manga-controls").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performTvClick()
    }

    private fun pressRemote(key: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        compose.waitForIdle()
    }

    private fun awaitFocused(matcher: SemanticsMatcher) = compose.waitUntil(10_000) {
        compose.onAllNodes(matcher and isFocused()).fetchSemanticsNodes()
            .any { (it.root as ViewRootForTest).view.hasWindowFocus() }
    }

    @RequiresApi(29)
    private fun imeVisible(): Boolean {
        var visible = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            visible = WindowInspector.getGlobalWindowViews().any { view ->
                ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        return visible
    }

    private fun waitForPage(number: Int) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("manga-page-status") and hasText("Page $number of 4", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertReaderControlFocused() {
        compose.onNodeWithTag("manga-page").assertIsNotFocused()
        val controls = compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("manga-controls")))
        controls.assertCountEquals(1)
        val focused = controls.fetchSemanticsNodes().single().boundsInRoot
        val reader = compose.onNodeWithTag("manga-reader").fetchSemanticsNode().boundsInRoot
        val safeInset = 28 * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("Focused reader control must remain above the full lower safe inset", focused.bottom <= reader.bottom - safeInset + 1)
        assertTrue("Focused reader control must remain inside the reader", focused.left >= reader.left && focused.right <= reader.right)
    }

    private fun waitForFirstPagePixels() {
        compose.waitUntil(15_000) {
            val bounds = compose.onAllNodesWithContentDescription("Page 1").fetchSemanticsNodes().firstOrNull()?.boundsInRoot ?: return@waitUntil false
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return@waitUntil false
            try {
                val pixel = screenshot.getPixel(bounds.center.x.toInt().coerceIn(0, screenshot.width - 1), bounds.center.y.toInt().coerceIn(0, screenshot.height - 1))
                Color.green(pixel) > 160 && Color.red(pixel) in 20..100 && Color.blue(pixel) in 80..160
            } finally { screenshot.recycle() }
        }
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.waitForIdle()
    }

    private fun fixture(failPagesOnce: Boolean = false, failImageOnce: Boolean = false,
        autoProgress: Boolean = false, failLastImageOnce: Boolean = false, mixedWide: Boolean = false, test: (ReaderFixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("native_manga_reader", Context.MODE_PRIVATE)
        val originalRtl = preferences.all["rtl"] as? Boolean
        val originalDouble = preferences.all["double"] as? Boolean
        val originalCover = preferences.all["coverAlone"] as? Boolean
        val titleSettings = listOf("media:904201:rtl", "media:904201:double", "media:904201:coverAlone")
        val originalTitleSettings = titleSettings.associateWith { preferences.all[it] as? Boolean }
        val chapter = MangaChapter("native-reader-${System.nanoTime()}", "Owned chapter 3", "3", "native-owned", JSONObject())
        val resumeKey = "904201:${chapter.provider}:${chapter.id}"
        preferences.edit().putBoolean("rtl", false).putBoolean("double", false).putBoolean("coverAlone", false).apply {
            titleSettings.forEach { putBoolean(it, false) }
        }.commit()
        var showing by mutableStateOf(true)
        val fixture = ReaderFixture(chapter, failPagesOnce, failImageOnce, autoProgress, failLastImageOnce, mixedWide)
        try {
            fixture.server.start(InetAddress.getByName("127.0.0.1"), 0)
            fixture.api = SeanimeApiClient(fixture.server.url("/").newBuilder().host("127.0.0.1").build().toString(), "reader-fixture-token")
            val media = MediaCard(904201, "Owned manga fixture", totalEpisodes = 12, isManga = true, raw = JSONObject().put("idMal", 76543))
            compose.setContent { SeanimeTheme {
                if (showing) MangaReader(SeanimeRepository(fixture.api), media, chapter, null,
                    onClose = { fixture.closed.incrementAndGet(); showing = false }, onNextChapter = {})
            } }
            test(fixture)
        } finally {
            compose.runOnIdle { showing = false }
            compose.waitForIdle()
            fixture.close()
            preferences.edit().apply {
                if (originalRtl == null) remove("rtl") else putBoolean("rtl", originalRtl)
                if (originalDouble == null) remove("double") else putBoolean("double", originalDouble)
                if (originalCover == null) remove("coverAlone") else putBoolean("coverAlone", originalCover)
                originalTitleSettings.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) }
                remove(resumeKey)
            }.commit()
        }
    }

    private class ReaderFixture(val chapter: MangaChapter, failPagesOnce: Boolean, failImageOnce: Boolean,
        autoProgress: Boolean, failLastImageOnce: Boolean, mixedWide: Boolean) {
        lateinit var api: SeanimeApiClient
        val pages = CopyOnWriteArrayList<JSONObject>()
        val progress = CopyOnWriteArrayList<JSONObject>()
        val images = CopyOnWriteArrayList<RecordedRequest>()
        val closed = AtomicInteger()
        val serverProgress = AtomicInteger()
        val entryReads = AtomicInteger()
        private val finalImageRequests = AtomicInteger()
        private val png = pngFixture()
        private val widePng = pngFixture(wide = true)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/v1/settings" -> MockResponse().setBody(JSONObject().put("data", JSONObject().put("manga", JSONObject().put("mangaAutoUpdateProgress", autoProgress))).toString())
                    "/api/v1/manga/entry/904201" -> {
                        entryReads.incrementAndGet()
                        MockResponse().setBody(JSONObject().put("data", JSONObject()
                            .put("media", JSONObject().put("id", 904201).put("chapters", 12).put("idMal", 76543))
                            .put("listData", JSONObject().put("progress", serverProgress.get()))).toString())
                    }
                    "/api/v1/manga/pages" -> {
                        pages += JSONObject(request.body.readUtf8())
                        if (failPagesOnce && pages.size == 1) MockResponse().setResponseCode(503).setBody("""{"error":"Fixture chapter temporarily unavailable"}""")
                        else MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("data", JSONObject().put("isDownloaded", true)
                            .put("pages", JSONArray().apply { (1..4).forEach { put(JSONObject().put("index", if (mixedWide) it * 10 else it - 1).put("url", "owned-reader/page-$it.png")) } })
                            .put("pageDimensions", if (pages.last().optBoolean("doublePage")) JSONObject().apply { (1..4).forEach {
                                put((if (mixedWide) it * 10 else it - 1).toString(), JSONObject().put("width", if (mixedWide && it == 3) 360 else 180).put("height", 260))
                            } } else JSONObject.NULL)).toString())
                    }
                    "/api/v1/manga/update-progress" -> {
                        val payload = JSONObject(request.body.readUtf8())
                        serverProgress.set(payload.getInt("chapterNumber"))
                        progress += payload
                        MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":true}""")
                    }
                    else -> if (request.path?.startsWith("/manga-downloads/owned-reader/page-") == true) {
                        images += request
                        if ((failImageOnce && images.size == 1) ||
                            (request.path == "/manga-downloads/owned-reader/page-4.png" && failLastImageOnce && finalImageRequests.incrementAndGet() == 1)) MockResponse().setResponseCode(404)
                        else MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(if (mixedWide && request.path == "/manga-downloads/owned-reader/page-3.png") widePng else png))
                    } else MockResponse().setResponseCode(404)
                }
            }
        }
        fun close() { if (::api.isInitialized) api.close(); server.shutdown() }

        companion object {
            private fun pngFixture(wide: Boolean = false): ByteArray {
                val bitmap = Bitmap.createBitmap(if (wide) 360 else 180, 260, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(60, 188, 125)) }
                return try { ByteArrayOutputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray() } }
                finally { bitmap.recycle() }
            }
        }
    }
}
