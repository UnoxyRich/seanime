package app.seanime.tv.ui

import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
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

class NativeAutoDownloaderBatchTest {
    @get:Rule val compose = createComposeRule()
    private val largeId = 4_294_967_399L

    @Test fun batchReviewCancelSharedPreferencesAndPartialRetryUseExactPerTitlePayloads() = fixture { fixture ->
        fixture.rejectOnce[largeId] = 400
        openBatch()
        addTitle(42); addTitle(largeId)
        scrollMain("auto-batch-preferences").performTvClick()
        compose.onNodeWithText("✓ Rule enabled").performTvClick()
        compose.onNodeWithTag("auto-rule-save").performTvClick()
        editEntry(largeId, "title", "Owned alternate release")
        editEntry(largeId, "folder", "/library/Second")
        scrollMain("auto-batch-review").performTvClick()
        compose.onNodeWithTag("auto-batch-confirm-cancel").assertIsFocused().performTvClick()
        assertTrue(fixture.created.isEmpty())
        scrollMain("auto-batch-review").performTvClick()
        NativeScreenshotEvidence.capture("auto-batch-explicit-rule-preview")
        compose.onNodeWithTag("auto-batch-confirm").performTvClick()
        awaitBatchResult(fixture, 2)
        scrollMain("auto-batch-result-$largeId").assertTextContains("Owned create rejection")
        NativeScreenshotEvidence.capture("auto-batch-partial-creation-results")
        scrollMain("auto-batch-review").performTvClick()
        compose.onNodeWithTag("auto-batch-confirm").performTvClick()
        awaitBatchResult(fixture, 3)
        awaitFocused("auto-batch-close")
        assertEquals(listOf(42L, largeId, largeId), fixture.created.map { it.getJSONObject("rule").getLong("mediaId") })
        fixture.created.forEach { assertEquals(setOf("rule"), it.keys().asSequence().toSet()); assertFalse(it.getJSONObject("rule").getBoolean("enabled")) }
        fixture.created.filter { it.getJSONObject("rule").getLong("mediaId") == largeId }.forEach {
            assertEquals("Owned alternate release", it.getJSONObject("rule").getString("comparisonTitle"))
            assertEquals("/library/Second", it.getJSONObject("rule").getString("destination"))
        }
    }

    @Test fun uncertainCreationSurvivesClosingAndReopeningBatchWithoutAnotherPost() = fixture { fixture ->
        fixture.rejectOnce[42L] = 503
        NativeUiStepWatchdog("auto-batch-uncertain-reopen").use { steps ->
            steps.step("Open batch and select owned title") { openBatch(); addTitle(42) }
            steps.step("Open first creation review") { scrollMain("auto-batch-review").performTvClick() }
            steps.step("Submit creation and await uncertain result") {
                compose.onNodeWithTag("auto-batch-confirm").performTvClick()
                awaitBatchResult(fixture, 1)
            }
            steps.step("Close batch and restore its opener") {
                scrollMain("auto-batch-close").performTvClick()
                awaitFocused("auto-batch-open")
            }
            steps.step("Reopen retained uncertain batch") {
                compose.onNodeWithTag("auto-batch-open").performTvClick()
                awaitFocused("auto-batch-review")
            }
            steps.step("Open reconciliation review") {
                compose.onNodeWithTag("auto-batch-review").performTvClick()
                compose.onNodeWithTag("auto-batch-confirm-cancel").assertIsFocused()
            }
            steps.step("Reconcile and verify no repeated creation") {
                compose.onNodeWithTag("auto-batch-confirm").performTvClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("auto-batch-confirmation").fetchSemanticsNodes().isEmpty() }
                scrollMain("auto-batch-result-42").assertTextContains("The earlier request is still unconfirmed. Refresh results or inspect Rules before creating another rule.")
                assertEquals(1, fixture.created.size)
            }
        }
    }

    @Test fun finishedRuleCleanupCancelsThenRetriesOnlyExplicitFailedIds() = fixture(withRules = true) { fixture ->
        fixture.falseDeleteOnce += 1
        awaitTag("auto-cleanup-open"); scrollMain("auto-cleanup-open")
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("auto-cleanup-open") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("auto-cleanup-open").performTvClick()
        awaitTag("auto-cleanup-dialog")
        compose.onNodeWithTag("auto-cleanup-rule-3").assertDoesNotExist()
        awaitFocused("auto-cleanup-cancel")
        pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(fixture.deleted.isEmpty())
        awaitFocused("auto-cleanup-open")
        // TV controls retain focus while disabled. Wait for the close-triggered
        // refresh to enable this opener before sending its next remote action.
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("auto-cleanup-open") and isFocused() and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        pressRemote(KeyEvent.KEYCODE_DPAD_CENTER); awaitTag("auto-cleanup-dialog")
        awaitFocused("auto-cleanup-cancel")
        NativeScreenshotEvidence.capture("auto-finished-rule-cleanup-preview")
        pressRemote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("auto-cleanup-confirm").assertIsFocused().assertIsEnabled()
        pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(15_000) { compose.onAllNodesWithText("1 of 2 removals confirmed").fetchSemanticsNodes().isNotEmpty() && fixture.deleted.size == 2 }
        awaitFocused("auto-cleanup-confirm")
        pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(15_000) { compose.onAllNodesWithText("2 of 2 removals confirmed").fetchSemanticsNodes().isNotEmpty() }
        awaitFocused("auto-cleanup-cancel")
        assertEquals(listOf(1, 2, 1), fixture.deleted.toList())
        assertTrue(fixture.deleted.none { it == -1 })
        assertEquals(setOf(3), fixture.ruleIds())
    }

    private fun openBatch() { awaitTag("auto-batch-open"); scrollMain("auto-batch-open").performTvClick(); awaitFocused("auto-batch-add") }
    private fun addTitle(id: Long) {
        scrollMain("auto-batch-add").performTvClick(); awaitTag("media-picker-$id")
        compose.onNodeWithTag("media-picker-$id").performScrollTo().performTvClick(); awaitFocused("auto-batch-add")
    }
    private fun editEntry(id: Long, field: String, value: String) {
        scrollMain("auto-batch-$field-$id").performTvClick()
        compose.onNodeWithTag("auto-batch-editor-$field").assertIsFocused().performTextReplacement(value)
        compose.onNodeWithTag("auto-batch-editor-$field").performImeAction()
        compose.onNodeWithTag("text-entry-save").performTvClick()
        compose.onNodeWithTag("auto-batch-$field-$id").assertIsFocused()
    }
    private fun awaitBatchResult(fixture: Fixture, count: Int) = compose.waitUntil(15_000) {
        fixture.created.size == count && compose.onAllNodesWithTag("auto-batch-confirmation").fetchSemanticsNodes().isEmpty()
    }
    private fun scrollMain(tag: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }
    private fun awaitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitFocused(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodes(hasTestTag(tag) and isFocused() and isEnabled()).fetchSemanticsNodes()
            .any { (it.root as ViewRootForTest).view.hasWindowFocus() }
    }
    private fun pressRemote(key: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        compose.waitForIdle()
    }
    private fun fixture(withRules: Boolean = false, block: (Fixture) -> Unit) {
        val fixture = Fixture(withRules)
        val server = MockWebServer().apply { dispatcher = fixture; start(InetAddress.getByName("127.0.0.1"), 0) }
        val api = SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()); val repo = SeanimeRepository(api)
        var visible by mutableStateOf(true)
        var primaryFailure: Throwable? = null
        try {
            compose.setContent { SeanimeTheme { NativeArtworkProvider(api) {
                if (visible) AutoDownloaderScreen(repo) { visible = false }
            } } }
            block(fixture)
        } catch (failure: Throwable) {
            primaryFailure = failure
            // A failed measure can leave Compose's tree non-idle. Report the first
            // failure before cleanup touches that tree, rather than masking it with
            // a later "layout state is not idle" exception.
            Log.e("NativeAutoBatchTest", "First failure before fixture cleanup", failure)
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun clean(block: () -> Unit) {
                try { block() } catch (failure: Throwable) {
                    val first = primaryFailure ?: cleanupFailure
                    if (first == null) cleanupFailure = failure
                    else if (first !== failure) first.addSuppressed(failure)
                    Log.e("NativeAutoBatchTest", "Fixture cleanup failure", failure)
                }
            }
            clean { compose.runOnIdle { visible = false }; compose.waitForIdle() }
            clean { api.close() }
            clean { server.shutdown() }
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
    }

    private inner class Fixture(withRules: Boolean) : Dispatcher() {
        val created = CopyOnWriteArrayList<JSONObject>()
        val deleted = CopyOnWriteArrayList<Int>()
        val rejectOnce = mutableMapOf<Long, Int>()
        val falseDeleteOnce = mutableSetOf<Int>()
        private val rules = linkedMapOf<Int, AutoRule>()
        init { if (withRules) {
            rules[1] = rule(42).copy(id = 1); rules[2] = rule(largeId).copy(id = 2); rules[3] = rule(44).copy(id = 3)
        } }
        @Synchronized fun ruleIds() = rules.keys.toSet()
        @Synchronized override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path == "/api/v1/auto-downloader/rules" -> data(JSONArray(rules.values.map { it.payload() }))
            request.path == "/api/v1/auto-downloader/profiles" || request.path == "/api/v1/auto-downloader/items" || request.path == "/api/v1/extensions/list/anime-torrent-provider" -> data(JSONArray())
            request.path == "/api/v1/settings" -> data(jsonObject("autoDownloader" to jsonObject("enabled" to false, "interval" to 20), "library" to jsonObject("libraryPath" to "/library")))
            request.path == "/api/v1/status" -> data(jsonObject("isOffline" to false, "serverReady" to true))
            request.path == "/api/v1/library/collection" -> data(JSONArray().put(jsonObject("id" to 42L, "title" to "Owned first anime")).put(jsonObject("id" to largeId, "title" to "Owned second anime")))
            request.path == "/api/v1/auto-downloader/rule" && request.method == "POST" -> {
                val body = JSONObject(request.body.readUtf8()); created += body
                val wanted = AutoRule.from(body.getJSONObject("rule")); val failure = rejectOnce.remove(wanted.mediaId)
                if (failure != null) MockResponse().setResponseCode(failure).setBody("""{"error":"Owned create rejection"}""")
                else { val saved = wanted.copy(id = (rules.keys.maxOrNull() ?: 0) + 1); rules[saved.id] = saved; data(saved.payload()) }
            }
            request.path == "/api/v1/anilist/collection/raw" -> data(jsonObject("MediaListCollection" to jsonObject("lists" to JSONArray().put(jsonObject("entries" to
                JSONArray().put(jsonObject("media" to jsonObject("id" to 42L, "status" to "FINISHED"))).put(jsonObject("media" to jsonObject("id" to largeId, "status" to "FINISHED")))
                    .put(jsonObject("media" to jsonObject("id" to 44L, "status" to "RELEASING"))))))))
            request.path.orEmpty().startsWith("/api/v1/auto-downloader/rule/") && request.method == "DELETE" -> {
                val id = request.path!!.substringAfterLast('/').toInt(); deleted += id
                if (falseDeleteOnce.remove(id)) data(false) else { rules.remove(id); data(true) }
            }
            else -> MockResponse().setResponseCode(404)
        }
        private fun rule(id: Long) = AutoRule(mediaId = id, title = "Owned $id", destination = "/library/$id")
        private fun data(value: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(jsonObject("data" to value).toString())
    }
}
