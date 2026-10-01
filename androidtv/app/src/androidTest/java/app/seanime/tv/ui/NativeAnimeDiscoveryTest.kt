package app.seanime.tv.ui

import android.view.KeyEvent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.seanime.tv.NativeScreenshotEvidence
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
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Fixture-backed HTTP plus real DPAD activation and Back; no provider/account access required. */
@OptIn(ExperimentalTestApi::class)
class NativeAnimeDiscoveryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchDialogCancelPreservesQueryAndRemoteNavigationKeepsWholePostersVisible() = fixture { fixture ->
        openDiscovery()
        compose.onNodeWithTag("discovery-search-field").assertDoesNotExist()
        editSearch("Space")
        compose.waitUntil(10_000) { fixture.requests.lastOrNull()?.optString("search") == "Space" &&
            compose.onAllNodesWithTag("discovery-grid").fetchSemanticsNodes().isNotEmpty() }
        val requestsBeforeCancel = fixture.requests.size
        editSearch("Discard this draft", save = false)
        assertEquals("Cancel must not send a discovery request", requestsBeforeCancel, fixture.requests.size)
        compose.onNodeWithTag("discovery-search-submit").assertTextContains("Search: Space")
        compose.onNodeWithTag("discovery-search-field").assertDoesNotExist()

        // Start from the dialog's restored opener, then use the actual remote path.
        pressRemote(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocused("discovery-filters")
        assertFocusedActionFitsViewport(compose, "discovery-filters")
        pressRemote(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitFocused("discovery-search-submit")
        assertFocusedActionFitsViewport(compose, "discovery-search-submit")
        pressRemote(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocused("discovery-media-1")
        assertWholePosterVisible("discovery-media-1")
        pressRemote(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocused("discovery-media-2")
        assertWholePosterVisible("discovery-media-2")
        pressRemote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitUntil(10_000) {
            compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("discovery-grid"))).fetchSemanticsNodes()
                .any { it.config[SemanticsProperties.TestTag] != "discovery-media-2" }
        }
        val nextRowCard = compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("discovery-grid")))
            .fetchSemanticsNode().config[SemanticsProperties.TestTag]
        assertWholePosterVisible(nextRowCard)
        NativeScreenshotEvidence.capture("discovery-compact-toolbar-dpad-card-focus")
    }

    @Test fun trendingPaginationAndDetailsRestoreThePageCardAndOpener() = fixture { fixture ->
        openDiscovery()
        compose.waitUntil(10_000) { fixture.requests.size == 1 }
        val first = fixture.requests.first()
        assertEquals("TRENDING_DESC", first.getJSONArray("sort").getString(0))
        assertFalse(first.has("search"))
        compose.onNodeWithTag("discovery-previous").assertIsNotEnabled()
        compose.onNodeWithTag("discovery-grid").performScrollToIndex(21)
        compose.onNodeWithTag("discovery-next").performTvClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Page 2 of 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("discovery-previous").assertIsFocused()
        compose.onNodeWithTag("discovery-next").assertIsNotEnabled()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("discovery-media-25").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("discovery-media-25").assertIsDisplayed()
        compose.onNodeWithTag("discovery-grid").performScrollToIndex(21)
        compose.onNodeWithTag("discovery-media-46").performTvClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Update list").fetchSemanticsNodes().isNotEmpty() }
        pressBack()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("discovery-media-46") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Page 2 of 2").assertExists()
        compose.waitUntil(10_000) {
            val card = compose.onNodeWithTag("discovery-media-46").fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag("discovery-grid").fetchSemanticsNode().boundsInRoot
            card.top >= viewport.top - 1f && card.bottom <= viewport.bottom + 1f
        }
        val cardBounds = compose.onNodeWithTag("discovery-media-46").fetchSemanticsNode().boundsInRoot
        val gridBounds = compose.onNodeWithTag("discovery-grid").fetchSemanticsNode().boundsInRoot
        assertTrue("Focused poster and title must fit the grid viewport", cardBounds.height <= gridBounds.height + 1f)
        assertWholePosterVisible("discovery-media-46")
        NativeScreenshotEvidence.capture("discovery-page-two-restored-card-focus")
        pressBack()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("anime-discover") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("anime-discover").assertIsFocused()
    }

    @Test fun effectiveFiltersApplyTogetherAndCancelDoesNotChangeTheRequest() = fixture { fixture ->
        NativeUiStepWatchdog("discovery-filter-steps").use { steps ->
            openDiscovery(steps)
            editSearch("", save = false, diagnostics = steps)
            steps.step("Move right to filters") { compose.onNodeWithTag("discovery-search-submit").performKeyInput { pressKey(Key.DirectionRight) } }
            steps.step("Assert filters focus") { compose.onNodeWithTag("discovery-filters").assertIsFocused() }
            steps.step("Open filters") { compose.onNodeWithTag("discovery-filters").performTvClick() }
            steps.step("Assert initial sort focus") { compose.onNodeWithTag("discovery-filter-sort").assertIsFocused() }
            steps.step("Move down to status") { compose.onNodeWithTag("discovery-filter-sort").performKeyInput { pressKey(Key.DirectionDown) } }
            steps.step("Assert status focus") { compose.onNodeWithTag("discovery-filter-status").assertIsFocused() }
            selectChoice("sort", "SCORE_DESC", diagnostics = steps)
            selectChoice("status", "RELEASING", multiple = true, diagnostics = steps)
            selectChoice("season", "SPRING", diagnostics = steps)
            enterFilter("year", "2026", steps, fromSeason = true)
            enterFilter("year", "2030", steps, save = false)
            steps.step("Assert canceled year edit preserved draft") {
                compose.onNodeWithTag("discovery-filter-year").assertTextContains("Release year: 2026")
            }
            selectChoice("format", "TV", diagnostics = steps)
            selectChoice("genres", "Action", multiple = true, diagnostics = steps)
            selectChoice("tags", "Space", multiple = true, diagnostics = steps)
            selectChoice("tags", "Time Travel", multiple = true, diagnostics = steps)
            enterFilter("custom-tags", "Space, Time Travel, Found Family", steps)
            enterFilter("score", "100", steps)
            val beforeInvalidApply = fixture.requests.size
            steps.step("Apply invalid score draft") { compose.onNodeWithTag("discovery-filter-apply").performTvClick() }
            steps.step("Assert score validation preserves draft without a request") {
                compose.onNodeWithText("Score must be between 0 and 99").assertIsDisplayed()
                assertEquals(beforeInvalidApply, fixture.requests.size)
            }
            enterFilter("score", "80", steps)
            val beforeApply = fixture.requests.size
            steps.step("Apply filters") { compose.onNodeWithTag("discovery-filter-apply").performTvClick() }
            steps.step("Wait for applied request and grid") {
                compose.waitUntil(10_000) { fixture.requests.size == beforeApply + 1 && compose.onAllNodesWithTag("discovery-grid").fetchSemanticsNodes().isNotEmpty() }
            }
            steps.step("Assert applied request fields") {
                val request = fixture.requests.last()
                assertEquals(1, request.getInt("page"))
                assertEquals("SCORE_DESC", request.getJSONArray("sort").getString(0))
                assertEquals("RELEASING", request.getJSONArray("status").getString(0))
                assertEquals("SPRING", request.getString("season"))
                assertEquals(2026, request.getInt("seasonYear"))
                assertEquals("TV", request.getString("format"))
                assertEquals("Action", request.getJSONArray("genres").getString(0))
                assertEquals("Time Travel", request.getJSONArray("tags").getString(1))
                assertEquals("Found Family", request.getJSONArray("tags").getString(2))
                assertEquals(80, request.getInt("averageScore_greater"))
            }
            steps.step("Assert focus after apply") { compose.onNodeWithTag("discovery-filters").assertIsFocused() }
            steps.step("Reopen filters for reset") { compose.onNodeWithTag("discovery-filters").performTvClick() }
            steps.step("Reset draft filters") { compose.onNodeWithTag("discovery-filter-reset").performTvClick() }
            pressBack(steps)
            steps.step("Assert focus after Back") { compose.onNodeWithTag("discovery-filters").assertIsFocused() }
            steps.step("Assert canceled draft sent no request") { assertEquals(beforeApply + 1, fixture.requests.size) }
            steps.step("Reopen preserved filters") { compose.onNodeWithTag("discovery-filters").performTvClick() }
            steps.step("Assert preserved sort") { compose.onNodeWithTag("discovery-filter-sort").assertTextContains("Sort: Highest rated") }
            steps.step("Capture advanced filters evidence") { NativeScreenshotEvidence.capture("discovery-native-advanced-filters") }
            steps.step("Cancel filters") { compose.onNodeWithTag("discovery-filter-cancel").performTvClick() }
            steps.step("Assert final filters focus") { compose.onNodeWithTag("discovery-filters").assertIsFocused() }
        }
    }

    @Test fun emptySearchAndRetryKeepRemoteControlsUsableAndBlankSearchReturnsTrending() = fixture { fixture ->
        openDiscovery()
        editSearch("missing")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("No anime found").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("discovery-search-submit").assertIsFocused()
        editSearch("fail")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Try again").performTvClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("discovery-grid").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("discovery-search-submit").assertIsFocused()
        editSearch("")
        compose.waitUntil(10_000) { fixture.requests.lastOrNull()?.has("search") == false && compose.onAllNodesWithText("Trending now").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("TRENDING_DESC", fixture.requests.last().getJSONArray("sort").getString(0))
        assertEquals(1, fixture.libraryRequests.get())
        compose.onNodeWithTag("discovery-search-submit").assertIsFocused()
    }

    private fun diagnosticStep(diagnostics: NativeUiStepWatchdog?, name: String, block: () -> Unit) {
        if (diagnostics == null) block() else diagnostics.step(name, block)
    }

    private fun openDiscovery(diagnostics: NativeUiStepWatchdog? = null) {
        diagnosticStep(diagnostics, "Wait for empty library") { compose.waitUntil(10_000) { compose.onAllNodesWithText("Your collection starts here").fetchSemanticsNodes().isNotEmpty() } }
        diagnosticStep(diagnostics, "Open discovery") { compose.onNodeWithTag("anime-discover").performTvClick() }
        diagnosticStep(diagnostics, "Wait for discovery grid") { compose.waitUntil(10_000) { compose.onAllNodesWithTag("discovery-grid").fetchSemanticsNodes().isNotEmpty() } }
    }

    private fun editSearch(value: String, save: Boolean = true, diagnostics: NativeUiStepWatchdog? = null) {
        diagnosticStep(diagnostics, "Open discovery search editor") { compose.onNodeWithTag("discovery-search-submit").performTvClick() }
        diagnosticStep(diagnostics, "Wait for discovery search editor focus") { awaitFocused("discovery-search-field") }
        diagnosticStep(diagnostics, "Enter discovery query") { compose.onNodeWithTag("discovery-search-field").performTextReplacement(value) }
        diagnosticStep(diagnostics, if (save) "Search from dialog" else "Cancel search draft") {
            compose.onNodeWithTag(if (save) "text-entry-save" else "text-entry-cancel").performTvClick()
        }
        diagnosticStep(diagnostics, "Restore discovery search opener") { awaitFocused("discovery-search-submit") }
    }

    private fun awaitFocused(tag: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes()
                .any { (it.root as ViewRootForTest).view.hasWindowFocus() }
        }
        compose.waitForIdle()
    }

    private fun assertWholePosterVisible(tag: String) {
        compose.onNodeWithTag(tag).assertIsFocused()
        val card = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val viewport = compose.onNodeWithTag("discovery-grid").fetchSemanticsNode().boundsInRoot
        // boundsInRoot can already be clipped. Compare the full unscaled card size,
        // including its fixed two-line title area, at the actual layout position.
        val origin = card.positionInRoot
        assertTrue("Focused poster/title must fit horizontally", origin.x >= viewport.left - 1f &&
            origin.x + card.size.width <= viewport.right + 1f)
        assertTrue("Focused poster/title must fit vertically", origin.y >= viewport.top - 1f &&
            origin.y + card.size.height <= viewport.bottom + 1f)
    }

    private fun pressRemote(key: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        compose.waitForIdle()
    }

    private fun selectChoice(field: String, value: String, multiple: Boolean = false, diagnostics: NativeUiStepWatchdog? = null) {
        diagnosticStep(diagnostics, "Scroll to $field filter") { compose.onNodeWithTag("discovery-filter-list").performScrollToNode(hasTestTag("discovery-filter-$field")) }
        diagnosticStep(diagnostics, "Open $field choices") { compose.onNodeWithTag("discovery-filter-$field").performTvClick() }
        diagnosticStep(diagnostics, "Scroll to $field choice") { compose.onNodeWithTag("discovery-choices").performScrollToNode(hasTestTag("discovery-choice-$value")) }
        diagnosticStep(diagnostics, "Select $field choice") { compose.onNodeWithTag("discovery-choice-$value").performTvClick() }
        if (multiple) diagnosticStep(diagnostics, "Close $field choices") { compose.onNodeWithTag("discovery-choice-close").performTvClick() }
        diagnosticStep(diagnostics, "Assert $field focus restored") { awaitFocused("discovery-filter-$field"); compose.onNodeWithTag("discovery-filter-$field").assertIsFocused() }
    }

    private fun enterFilter(field: String, value: String, diagnostics: NativeUiStepWatchdog? = null, fromSeason: Boolean = false, save: Boolean = true) {
        if (fromSeason) {
            diagnosticStep(diagnostics, "Assert season focus before year editor") { compose.onNodeWithTag("discovery-filter-season").assertIsFocused() }
            diagnosticStep(diagnostics, "Move Down from season to year control") {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            }
            diagnosticStep(diagnostics, "Assert year control focus") { compose.onNodeWithTag("discovery-filter-year").assertIsFocused() }
            diagnosticStep(diagnostics, "Open year editor with Center") {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            }
        } else {
            diagnosticStep(diagnostics, "Scroll to $field editor control") { compose.onNodeWithTag("discovery-filter-list").performScrollToNode(hasTestTag("discovery-filter-$field")) }
            diagnosticStep(diagnostics, "Open $field editor") { compose.onNodeWithTag("discovery-filter-$field").performTvClick() }
        }
        diagnosticStep(diagnostics, "Wait for $field editor initial focus") {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("discovery-editor-$field") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        }
        diagnosticStep(diagnostics, "Type $field editor value") {
            compose.onNodeWithTag("discovery-editor-$field").performTextReplacement(value)
            compose.onNodeWithTag("discovery-editor-$field").performImeAction()
        }
        if (save) diagnosticStep(diagnostics, "Save $field editor") { compose.onNodeWithText("Save").performTvClick() }
        else diagnosticStep(diagnostics, "Cancel $field editor") {
            compose.onNode(hasText("Cancel") and !hasTestTag("discovery-filter-cancel")).performTvClick()
        }
        diagnosticStep(diagnostics, "Assert $field editor opener focus restored") {
            awaitFocused("discovery-filter-$field")
        }
    }

    private fun pressBack(diagnostics: NativeUiStepWatchdog? = null) {
        diagnosticStep(diagnostics, "Send Back key") { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }
        diagnosticStep(diagnostics, "Wait for instrumentation idle after Back") { InstrumentationRegistry.getInstrumentation().waitForIdleSync() }
        diagnosticStep(diagnostics, "Wait for Compose idle after Back") { compose.waitForIdle() }
    }

    private fun fixture(test: (DiscoveryFixture) -> Unit) {
        val fixture = DiscoveryFixture()
        val server = MockWebServer().apply { dispatcher = fixture; start(InetAddress.getByName("127.0.0.1"), 0) }
        val api = SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        try {
            val status = SeanimeJson.status(JSONObject("""{"version":"fixture","serverReady":true,"settings":{"library":{}},"user":{"isSimulated":true}}"""))
            compose.setContent { SeanimeTheme { SeanimeTvApp(SeanimeRepository(api), status, {}, {}, {}) } }
            test(fixture)
        } finally { api.close(); server.shutdown() }
    }

    private class DiscoveryFixture : Dispatcher() {
        val requests = CopyOnWriteArrayList<JSONObject>()
        val libraryRequests = AtomicInteger()
        private val failOnce = AtomicBoolean(true)
        override fun dispatch(request: RecordedRequest): MockResponse {
            val data = when {
                request.path == "/api/v1/library/collection" -> { libraryRequests.incrementAndGet(); JSONObject().put("lists", JSONArray()) }
                request.path == "/api/v1/anilist/collection/raw/tags" -> JSONObject().put("1", JSONArray(listOf("Space", "Time Travel")))
                request.path == "/api/v1/anilist/list-anime" -> {
                    val body = JSONObject(request.body.readUtf8())
                    requests.add(body)
                    if (body.optString("search") == "fail" && failOnce.getAndSet(false)) return MockResponse().setResponseCode(503).setBody("""{"error":"Discovery fixture unavailable"}""")
                    val page = body.getInt("page")
                    val empty = body.optString("search") == "missing"
                    val media = JSONArray().apply { if (!empty) (1..24).forEach { put(JSONObject().put("id", (page - 1) * 24 + it)
                        .put("title", "Discovery ${(page - 1) * 24 + it}: Beyond the distant stars")) } }
                    JSONObject().put("Page", JSONObject().put("media", media).put("pageInfo", JSONObject()
                        .put("currentPage", page).put("hasNextPage", page == 1 && !empty).put("lastPage", if (empty) 1 else 2)))
                }
                request.path?.startsWith("/api/v1/library/anime-entry/") == true -> JSONObject().put("media", JSONObject()
                    .put("id", request.path!!.substringAfterLast('/').toInt()).put("title", "Discovery details")).put("episodes", JSONArray())
                else -> JSONObject()
            }
            return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("data", data).toString())
        }
    }
}
