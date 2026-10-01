package app.seanime.tv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.data.*
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

class NativeListEntryDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadsCurrentRatingAndCancelPreservesTheBackend() = fixture { fixture ->
        awaitEditor()
        compose.onNodeWithTag("list-entry-status").assertIsFocused()
        assertFocusedActionFitsVerticalViewport(compose, hasTestTag("list-entry-status"))
        compose.onNodeWithTag("list-entry-score").assertTextContains("Rating (0–10): 9.5")
        compose.onNodeWithTag("list-entry-progress").assertTextContains("Episodes watched: 5")
        NativeScreenshotEvidence.capture("list-entry-main-status-focus")
        compose.onNodeWithTag("list-entry-cancel").performTvClick()
        compose.onNodeWithTag("list-entry-dialog").assertDoesNotExist()
        assertTrue(fixture.writes.isEmpty())
        assertEquals(1, fixture.cancelled.get())
    }

    @Test fun statusEditOmitsTheExistingScoreProgressAndDates() = fixture { fixture ->
        awaitEditor()
        compose.onNodeWithTag("list-entry-status").performTvClick()
        compose.onNodeWithText("Paused").performTvClick()
        compose.onNodeWithTag("list-entry-status").assertIsFocused()
        compose.onNodeWithTag("list-entry-save").performTvClick()
        awaitClosed()
        val body = fixture.writes.single().second
        assertEquals(setOf("mediaId", "type", "status"), body.keys().asSequence().toSet())
        assertEquals("PAUSED", body.getString("status"))
        assertEquals("anime", body.getString("type"))
    }

    @Test fun cancellingNumericEditorsRestoresEachOpenerAndUnchangedSaveWritesNothing() = fixture { fixture ->
        awaitEditor()
        editNumber("progress", "8", save = false)
        compose.onNodeWithTag("list-entry-progress").assertTextContains("Episodes watched: 5")
        editNumber("score", "7.5", save = false)
        compose.onNodeWithTag("list-entry-score").assertTextContains("Rating (0–10): 9.5")
        compose.onNodeWithTag("list-entry-save").performTvClick()
        awaitClosed()
        assertTrue(fixture.writes.isEmpty())
        assertEquals(1, fixture.cancelled.get())
    }

    @Test fun numericEditorSavesOnlyDraftUntilTheMainEditorIsSavedOrCancelled() = fixture { fixture ->
        awaitEditor()
        editNumber("progress", "8")
        editNumber("score", "8.5")
        compose.onNodeWithTag("list-entry-progress").assertTextContains("Episodes watched: 8")
        compose.onNodeWithTag("list-entry-score").assertTextContains("Rating (0–10): 8.5")
        assertTrue(fixture.writes.isEmpty())
        compose.onNodeWithTag("list-entry-cancel").performTvClick()
        awaitClosed()
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun numericEditorCommitsExactProgressAndRatingWithUntouchedFieldsOmitted() = fixture { fixture ->
        awaitEditor()
        editNumber("progress", "8")
        editNumber("score", "8.5")
        assertTrue(fixture.writes.isEmpty())
        compose.onNodeWithTag("list-entry-save").performTvClick()
        awaitClosed()
        val body = fixture.writes.single().second
        assertEquals(setOf("mediaId", "type", "progress", "score"), body.keys().asSequence().toSet())
        assertEquals(8, body.getInt("progress"))
        assertEquals(85, body.getInt("score"))
    }

    @Test fun failedMangaRatingSaveRetainsTheExactDraftForRetry() = fixture(manga = true, failSaveOnce = true) { fixture ->
        awaitEditor()
        editNumber("score", "7.5", evidenceName = "list-entry-rating-editor-field-focus")
        compose.onNodeWithTag("list-entry-save").performTvClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture save failed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("list-entry-dialog").assertIsDisplayed()
        compose.onNodeWithTag("list-entry-score").assertTextContains("Rating (0–10): 7.5")
        assertTrue(fixture.changed.isEmpty())
        compose.onNodeWithTag("list-entry-save").performTvClick()
        awaitClosed()
        assertEquals(2, fixture.writes.size)
        fixture.writes.forEach { (method, body) ->
            assertEquals("POST", method)
            assertEquals(setOf("mediaId", "type", "score"), body.keys().asSequence().toSet())
            assertEquals(75, body.getInt("score")); assertEquals("manga", body.getString("type"))
        }
    }

    @Test fun mangaRemovalRequiresConfirmationAndUsesOnlyTheSelectedTitle() = fixture(manga = true) { fixture ->
        awaitEditor()
        compose.onNodeWithTag("list-entry-remove").performScrollTo().performTvClick()
        awaitFocused("list-entry-cancel-remove")
        compose.onNodeWithTag("list-entry-cancel-remove").assertIsFocused().performTvClick()
        awaitFocused("list-entry-status")
        compose.onNodeWithTag("list-entry-status").assertIsFocused()
        assertTrue(fixture.writes.isEmpty())
        compose.onNodeWithTag("list-entry-remove").performScrollTo().performTvClick()
        compose.onNodeWithTag("list-entry-confirm-remove").performTvClick()
        awaitClosed()
        val (method, body) = fixture.writes.single()
        assertEquals("DELETE", method)
        assertEquals(setOf("mediaId", "type"), body.keys().asSequence().toSet())
        assertEquals(42, body.getInt("mediaId")); assertEquals("manga", body.getString("type"))
        assertEquals(listOf(true), fixture.changed.toList())
        assertEquals(2, fixture.entryReads.get())
    }

    @Test fun changingToOfflineBeforeConfirmationBlocksRemovalAndKeepsDraft() = fixture { fixture ->
        awaitEditor()
        fixture.offline.set(true)
        compose.onNodeWithTag("list-entry-remove").performScrollTo().performTvClick()
        compose.onNodeWithTag("list-entry-confirm-remove").performTvClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Go online before removing a list entry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("list-entry-dialog").assertIsDisplayed()
        compose.onNodeWithTag("list-entry-score").assertTextContains("Rating (0–10): 9.5")
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun offlineEditorDisablesRemovalButLeavesOrdinaryEditsAvailable() = fixture(offline = true) { _ ->
        awaitEditor()
        compose.onNodeWithTag("list-entry-remove").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("list-entry-save").assertIsEnabled()
        compose.onNodeWithTag("list-entry-cancel").performTvClick()
    }

    @Test fun addingAnUnlistedTitleUsesPlanningWithoutADeleteAction() = fixture(existing = false) { fixture ->
        awaitEditor()
        compose.onNodeWithTag("list-entry-remove").assertDoesNotExist()
        compose.onNodeWithTag("list-entry-save").assertTextContains("Add to list").performTvClick()
        awaitClosed()
        val body = fixture.writes.single().second
        assertEquals(setOf("mediaId", "type", "status"), body.keys().asSequence().toSet())
        assertEquals("PLANNING", body.getString("status"))
    }

    @Test fun rawPartialDatesSurviveCancelledDateDraftAndNoOpSave() = fixture(
        startedAt = NativeListDate(2024), completedAt = NativeListDate(null, 3, 8)) { fixture ->
        awaitEditor()
        compose.onNodeWithTag("list-entry-startedAt").assertTextContains("Start date: 2024-??-??")
        compose.onNodeWithTag("list-entry-completedAt").assertTextContains("Completion date: ????-03-08")
        openDate("startedAt", evidenceName = "list-entry-partial-date-year-focus")
        editDateYear("startedAt", "2025")
        compose.onNodeWithTag("list-entry-date-startedAt-cancel").performTvClick()
        compose.onNodeWithTag("list-entry-startedAt").assertIsFocused().assertTextContains("Start date: 2024-??-??")
        compose.onNodeWithTag("list-entry-save").performTvClick()
        awaitClosed()
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun fullCalendarDateValidatesAndFailedSaveKeepsTheDateForRetry() = fixture(failSaveOnce = true) { fixture ->
        awaitEditor()
        openDate("startedAt")
        editDateYear("startedAt", "2023")
        chooseDatePart("startedAt", "month", 2)
        chooseDatePart("startedAt", "day", 29)
        compose.onNodeWithTag("list-entry-date-startedAt-apply").performTvClick()
        compose.onNodeWithTag("list-entry-date-startedAt-error").assertTextContains("Choose a valid calendar date")
        editDateYear("startedAt", "2024")
        compose.onNodeWithTag("list-entry-date-startedAt-apply").performTvClick()
        compose.onNodeWithTag("list-entry-startedAt").assertIsFocused().assertTextContains("Start date: 2024-02-29")
        assertTrue(fixture.writes.isEmpty())
        compose.onNodeWithTag("list-entry-save").performTvClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture save failed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("list-entry-startedAt").assertTextContains("Start date: 2024-02-29")
        compose.onNodeWithTag("list-entry-save").performTvClick()
        awaitClosed()
        assertEquals(2, fixture.writes.size)
        fixture.writes.forEach { (_, body) ->
            assertEquals(setOf("mediaId", "type", "startedAt"), body.keys().asSequence().toSet())
            assertEquals(2024, body.getJSONObject("startedAt").getInt("year"))
            assertEquals(2, body.getJSONObject("startedAt").getInt("month"))
            assertEquals(29, body.getJSONObject("startedAt").getInt("day"))
        }
    }

    @Test fun simulatedMangaCanExplicitlyClearADateAndOneComponent() = fixture(manga = true, simulated = true) { fixture ->
        awaitEditor()
        openDate("startedAt")
        compose.onNodeWithTag("list-entry-date-startedAt-clear").performScrollTo().performTvClick()
        compose.onNodeWithTag("list-entry-date-startedAt-apply").performTvClick()
        compose.onNodeWithTag("list-entry-startedAt").assertIsFocused().assertTextContains("Start date: Not set")
        openDate("completedAt")
        chooseDatePart("completedAt", "month", null)
        compose.onNodeWithTag("list-entry-date-completedAt-apply").performTvClick()
        compose.onNodeWithTag("list-entry-completedAt").assertIsFocused().assertTextContains("Completion date: 2025-??-03")
        compose.onNodeWithTag("list-entry-save").performTvClick()
        awaitClosed()
        val body = fixture.writes.single().second
        assertEquals(setOf("mediaId", "type", "startedAt", "completedAt"), body.keys().asSequence().toSet())
        assertEquals("manga", body.getString("type"))
        val start = body.getJSONObject("startedAt")
        assertTrue(start.isNull("year") && start.isNull("month") && start.isNull("day"))
        val end = body.getJSONObject("completedAt")
        assertEquals(2025, end.getInt("year")); assertTrue(end.isNull("month")); assertEquals(3, end.getInt("day"))
    }

    @Test fun livePartialDateRequiresFullReplacementAndDoesNotExposeClear() = fixture(startedAt = NativeListDate(2024)) { fixture ->
        awaitEditor()
        openDate("startedAt")
        compose.onNodeWithTag("list-entry-date-startedAt-clear").assertDoesNotExist()
        editDateYear("startedAt", "2025")
        compose.onNodeWithTag("list-entry-date-startedAt-apply").performTvClick()
        compose.onNodeWithTag("list-entry-date-startedAt-error").assertTextContains(
            "AniList date changes need a complete year, month and day. Keep the existing date unchanged or choose a full date.")
        compose.onNodeWithTag("list-entry-date-startedAt-year").assertTextContains("Year: 2025")
        compose.onNodeWithTag("list-entry-date-startedAt-cancel").performTvClick()
        compose.onNodeWithTag("list-entry-startedAt").assertIsFocused().assertTextContains("Start date: 2024-??-??")
        compose.onNodeWithTag("list-entry-cancel").performTvClick()
        awaitClosed()
        assertTrue(fixture.writes.isEmpty())
    }

    private fun awaitEditor() = compose.waitUntil(10_000) { compose.onAllNodesWithTag("list-entry-status").fetchSemanticsNodes().isNotEmpty() }
    private fun awaitClosed() = compose.waitUntil(10_000) { compose.onAllNodesWithTag("list-entry-dialog").fetchSemanticsNodes().isEmpty() }
    private fun awaitFocused(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes()
            .any { (it.root as ViewRootForTest).view.hasWindowFocus() }
    }
    private fun editNumber(field: String, value: String, save: Boolean = true, evidenceName: String? = null) {
        compose.onNodeWithTag("list-entry-$field").performScrollTo().performTvClick()
        val input = hasTestTag("list-entry-editor-$field")
        compose.onNode(input).assertIsFocused()
        evidenceName?.let(NativeScreenshotEvidence::capture)
        compose.onNode(input).performTextReplacement(value)
        compose.onNode(input).performImeAction()
        // The list editor and numeric editor are separate Dialog windows. Match the exact
        // window containing this field, so the underlying Save/Cancel controls cannot win.
        compose.onNode(hasText(if (save) "Save" else "Cancel") and hasAnyAncestor(isDialog() and hasAnyDescendant(input))).performTvClick()
        compose.onNode(input).assertDoesNotExist()
        awaitFocused("list-entry-$field")
        compose.onNodeWithTag("list-entry-$field").assertIsFocused()
    }
    private fun openDate(field: String, evidenceName: String? = null) {
        compose.onNodeWithTag("list-entry-$field").performScrollTo().performTvClick()
        compose.onNodeWithTag("list-entry-date-$field-year").assertIsFocused()
        assertFocusedActionFitsVerticalViewport(compose, hasTestTag("list-entry-date-$field-year"))
        evidenceName?.let(NativeScreenshotEvidence::capture)
    }
    private fun editDateYear(field: String, value: String) {
        compose.onNodeWithTag("list-entry-date-$field-year").performScrollTo().performTvClick()
        val input = hasTestTag("list-entry-date-$field-editor-year")
        compose.onNode(input).assertIsFocused().performTextReplacement(value)
        compose.onNode(input).performImeAction()
        compose.onNode(hasText("Save") and hasAnyAncestor(isDialog() and hasAnyDescendant(input))).performTvClick()
        compose.onNode(input).assertDoesNotExist()
        compose.onNodeWithTag("list-entry-date-$field-year").assertIsFocused()
    }
    private fun chooseDatePart(field: String, component: String, value: Int?) {
        compose.onNodeWithTag("list-entry-date-$field-$component").performScrollTo().performTvClick()
        val title = component.replaceFirstChar(Char::uppercase)
        val choice = if (value == null) "Not set" else "$title $value"
        val target = hasText(choice) or hasText("✓ $choice")
        val window = isDialog() and hasAnyDescendant(hasText(title))
        compose.onNode(hasScrollToNodeAction() and hasAnyAncestor(window)).performScrollToNode(target)
        compose.onNode(target and hasAnyAncestor(window)).performTvClick()
        compose.onNodeWithTag("list-entry-date-$field-$component").assertIsFocused()
    }
    private fun fixture(manga: Boolean = false, existing: Boolean = true, offline: Boolean = false, failSaveOnce: Boolean = false,
        simulated: Boolean = false, startedAt: NativeListDate = NativeListDate(2024, 1, 2), completedAt: NativeListDate = NativeListDate(2025, 2, 3),
        test: (EditorFixture) -> Unit) {
        val fixture = EditorFixture(manga, existing, offline, failSaveOnce, simulated, startedAt, completedAt)
        val server = MockWebServer().apply { dispatcher = fixture; start(InetAddress.getByName("127.0.0.1"), 0) }
        val api = SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString())
        val repo = SeanimeRepository(api)
        var visible by mutableStateOf(true)
        try {
            compose.setContent { SeanimeTheme {
                if (visible) NativeListEntryDialog(repo, MediaCard(42, "Fixture title", progress = 1, isManga = manga),
                    onDismiss = { fixture.cancelled.incrementAndGet(); visible = false },
                    onChanged = { fixture.changed += it; visible = false })
            } }
            test(fixture)
        } finally {
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            api.close(); server.shutdown()
        }
    }

    private class EditorFixture(val manga: Boolean, val existing: Boolean, initialOffline: Boolean, val failSaveOnce: Boolean,
        val simulated: Boolean, val startedAt: NativeListDate, val completedAt: NativeListDate) : Dispatcher() {
        val offline = AtomicBoolean(initialOffline)
        val writes = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val entryReads = AtomicInteger()
        val cancelled = AtomicInteger()
        val changed = CopyOnWriteArrayList<Boolean>()
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/api/v1/status" -> envelope(jsonObject("isOffline" to offline.get(), "user" to jsonObject("isSimulated" to simulated)))
            if (manga) "/api/v1/manga/entry/42" else "/api/v1/library/anime-entry/42" -> {
                entryReads.incrementAndGet()
                envelope(jsonObject("media" to jsonObject("id" to 42, "title" to "Fixture title", (if (manga) "chapters" else "episodes") to 24)).apply {
                    if (existing) put("listData", jsonObject("status" to "CURRENT", "progress" to 5, "score" to 95,
                        "startedAt" to "2024-01-02T00:00:00Z", "completedAt" to "2025-02-03T00:00:00Z", "repeat" to 3))
                })
            }
            if (manga) "/api/v1/manga/anilist/collection/raw" else "/api/v1/anilist/collection/raw" -> envelope(
                jsonObject("MediaListCollection" to jsonObject("lists" to JSONArray().put(jsonObject("entries" to JSONArray().apply {
                    put(jsonObject("media" to jsonObject("id" to 7), "startedAt" to NativeListDate(1999, 1, 1).payload()))
                    if (existing) put(jsonObject("media" to jsonObject("id" to 42), "status" to "CURRENT", "progress" to 5, "score" to 95,
                        "startedAt" to startedAt.payload(), "completedAt" to completedAt.payload(), "repeat" to 3))
                })))))
            "/api/v1/anilist/list-entry" -> {
                writes += request.method.orEmpty() to JSONObject(request.body.readUtf8())
                if (failSaveOnce && writes.size == 1) MockResponse().setResponseCode(503).setBody("""{"error":"Fixture save failed"}""") else envelope(true)
            }
            else -> MockResponse().setResponseCode(404)
        }
        private fun envelope(value: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(jsonObject("data" to value).toString())
    }
}
