package app.seanime.tv

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.seanime.tv.data.ApiException
import app.seanime.tv.data.Playlist
import app.seanime.tv.data.PlaylistEpisode
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeJson
import app.seanime.tv.data.SeanimeRepository
import app.seanime.tv.data.jsonObject
import app.seanime.tv.gomobile.mobile.Mobile
import app.seanime.tv.ui.settingChoices
import app.seanime.tv.ui.validateSettingNumber
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract integration against the actual embedded Go server and SQLite storage.
 * These tests never substitute MockWebServer, request a provider account, play a
 * synthetic episode, or delete anything except their own UUID-named fixture IDs.
 * Run serially with the other Activity/server lifecycle instrumentation tests.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNativeBackendFlowsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun nativePlaylistDialogCancelsThenCreatesInTheRealBackend() = withNativeServer { _, repo ->
        val name = fixtureName()
        val before = serverCall { repo.playlists() }
        try {
            awaitFocusedTag("nav-LIBRARY")
            repeat(4) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            awaitFocusedTag("nav-PLAYLISTS")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitForEnabledText("New playlist")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocusedText("New playlist")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            typePlaylistName(name)
            // A real TV IME owns key events while visible. Hide it before moving
            // through the app's footer, rather than assigning focus underneath it.
            hideKeyboardKeepingDialog()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            awaitFocusedTag("text-entry-cancel")
            NativeScreenshotEvidence.capture("real-go-playlist-cancel-focus")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitNameDialogClosed()
            awaitFocusedText("New playlist")
            assertEquals("Cancel must not create a playlist", playlistState(before),
                playlistState(serverCall { repo.playlists() }))

            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("text-entry-save").assertIsNotEnabled()
            typePlaylistName(name)
            hideKeyboardKeepingDialog()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            awaitFocusedTag("text-entry-cancel")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocusedTag("text-entry-save")
            compose.onNodeWithTag("text-entry-save").assertIsEnabled()
            NativeScreenshotEvidence.capture("real-go-playlist-save-focus")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(30_000) {
                serverCall { repo.playlists() }.any { it.name == name }
            }
            waitForEnabledText("New playlist")
            val saved = serverCall { repo.playlists() }.single { it.name == name }
            assertTrue("Refetched playlist must have its SQLite ID", saved.id > 0)
            assertFalse("Fixture must never reuse a preexisting playlist", before.any { it.id == saved.id })
            assertTrue(saved.episodes.isEmpty())
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            compose.onNodeWithText("Playlist saved").assertIsDisplayed()
            assertExistingPlaylistsUnchanged(before, serverCall { repo.playlists() })
        } finally {
            removeNamedFixtures(repo, name, before)
        }
    }

    @Test
    fun repositoryPlaylistCreateRenameReorderCompleteAndDeleteRoundTrips() = withNativeServer { _, repo ->
        val name = fixtureName()
        val before = serverCall { repo.playlists() }
        var fixtureId: Int? = null
        try {
            val episodes = (1..3).map(::syntheticEpisode)
            val createResponse = serverCall { repo.createPlaylist(name, episodes) }
            assertEquals(name, createResponse.name)
            assertEquals(3, createResponse.episodes.size)
            // The existing Go POST handler does not populate dbId in its response.
            // As the native UI does, refetch and resolve only our exact unique name.
            val created = serverCall { repo.playlists() }.single { it.name == name }
            assertTrue(created.id > 0)
            assertFalse(before.any { it.id == created.id })
            fixtureId = created.id
            assertEquals(listOf(1, 2, 3), created.episodes.map { it.episode?.number })
            assertEquals(listOf("online", "torrent", "localfile"), created.episodes.map { it.watchType })
            assertTrue("The Go isNakama field must survive native mapping", created.episodes[1].raw.getBoolean("isNakama"))

            val reordered = listOf(created.episodes[2], created.episodes[0].copy(completed = true), created.episodes[1])
            val renamed = "$name renamed"
            val updated = serverCall { repo.updatePlaylist(created.copy(name = renamed, episodes = reordered)) }
            assertEquals(created.id, updated.id)
            assertEquals(renamed, updated.name)

            // A separate native HTTP client reads back the server's stored row.
            SeanimeApiClient().use { reader ->
                assertTrue(reader.restoreSession(repo.client.snapshotSession()))
                val reloaded = serverCall { SeanimeRepository(reader).playlists() }.single { it.id == created.id }
                assertEquals(renamed, reloaded.name)
                assertEquals(listOf(3, 1, 2), reloaded.episodes.map { it.episode?.number })
                assertEquals(listOf(false, true, false), reloaded.episodes.map { it.completed })
                assertEquals(listOf("localfile", "online", "torrent"), reloaded.episodes.map { it.watchType })
                reordered.zip(reloaded.episodes).forEach { (expected, actual) ->
                    assertEquals("Reorder must preserve the full server episode payload", canonical(expected.episode?.raw), canonical(actual.episode?.raw))
                    assertEquals(expected.raw.getBoolean("isNakama"), actual.raw.getBoolean("isNakama"))
                }
            }

            val trimmed = serverCall { repo.updatePlaylist(updated.copy(episodes = updated.episodes.dropLast(1))) }
            assertEquals(listOf(3, 1), trimmed.episodes.map { it.episode?.number })
            assertEquals(true, serverCall { repo.deletePlaylist(created.id) })
            val remaining = serverCall { repo.playlists() }
            assertFalse("Deleted fixture must disappear from a fresh GET", remaining.any { it.id == created.id })
            assertEquals("CRUD must leave all other playlists unchanged", playlistState(before), playlistState(remaining))
        } finally {
            // Prefer the verified ID after rename; fallback only to our unique name.
            fixtureId?.let { id ->
                if (serverCall { repo.playlists() }.any { it.id == id }) serverCall { repo.deletePlaylist(id) }
            }
            removeNamedFixtures(repo, name, before)
        }
    }

    @Test
    fun typedSettingsPersistPreserveOtherFieldsAndRejectInvalidWritesAtomically() = withNativeServer { _, repo ->
        val evidence = SettingsPreservationObservation(System.currentTimeMillis())
        val evidenceFile = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "native-acceptance-diagnostics/native-settings-preservation.json")
        fun observe(phase: String, expected: JSONObject, actual: JSONObject) {
            evidence.record(phase, expected, actual, System.currentTimeMillis())
            check(evidenceFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            evidenceFile.writeText(evidence.toJson())
        }
        fun assertObservedSettingsEqual(phase: String, expected: JSONObject, actual: JSONObject) {
            observe(phase, expected, actual)
            assertSettingsEqual(expected, actual)
        }
        val original = serverCall { repo.settings() }
        val originalBoolean = original.getJSONObject("anilist").getBoolean("hideAudienceScore")
        val originalNumber = original.getJSONObject("library").get("scannerMatchingThreshold")
        val originalChoice = original.getJSONObject("library").getString("scannerMatchingAlgorithm")
        val expected = JSONObject(original.toString())
        // These passive presentation/scanner preferences neither connect accounts
        // nor change filesystem access, download destinations, or playback state.
        val newBoolean = !originalBoolean
        val newNumber = validateSettingNumber("scannerMatchingThreshold",
            if ((originalNumber as Number).toDouble() == 0.625) "0.375" else "0.625", integral = false)
        val newChoice = settingChoices("library", "scannerMatchingAlgorithm", originalChoice)
            .first { it.value != originalChoice && it.value.isNotBlank() }.value
        val changedPaths = mutableListOf<Pair<String, Any>>()
        try {
            // Register rollback before each request: even a lost HTTP response may
            // follow a successful server-side write.
            changedPaths += "anilist.hideAudienceScore" to originalBoolean
            serverCall { repo.patchSetting("anilist.hideAudienceScore", newBoolean) }
            expected.getJSONObject("anilist").put("hideAudienceScore", newBoolean)
            assertObservedSettingsEqual("after-boolean", expected, serverCall { repo.settings() })

            changedPaths += "library.scannerMatchingThreshold" to originalNumber
            serverCall { repo.patchSetting("library.scannerMatchingThreshold", newNumber) }
            expected.getJSONObject("library").put("scannerMatchingThreshold", newNumber)
            assertObservedSettingsEqual("after-number", expected, serverCall { repo.settings() })

            changedPaths += "library.scannerMatchingAlgorithm" to originalChoice
            serverCall { repo.patchSetting("library.scannerMatchingAlgorithm", newChoice) }
            expected.getJSONObject("library").put("scannerMatchingAlgorithm", newChoice)
            val reloaded = serverCall { repo.settings() }
            observe("after-choice", expected, reloaded)
            assertTrue("Boolean setting must not become a string", reloaded.getJSONObject("anilist").get("hideAudienceScore") is Boolean)
            assertTrue("Numeric setting must not become a string", reloaded.getJSONObject("library").get("scannerMatchingThreshold") is Number)
            assertEquals(newChoice, reloaded.getJSONObject("library").getString("scannerMatchingAlgorithm"))
            assertSettingsEqual(expected, reloaded)

            // Deserialization fails before UpsertSettings. Verify all previously
            // saved settings and timestamps remain byte-for-value unchanged.
            val beforeRejectedWrite = serverCall { repo.settings() }
            try {
                serverCall { repo.patchSetting("anilist.hideAudienceScore", "not-a-boolean") }
                fail("The real Go handler must reject a string for a boolean setting")
            } catch (failure: ApiException) {
                assertEquals("/api/v1/settings/path", failure.path)
                assertTrue("A rejected write must explain its error", failure.message.isNotBlank())
            }
            val afterRejectedWrite = serverCall { repo.settings() }
            observe("rejected-write", beforeRejectedWrite, afterRejectedWrite)
            assertTrue("Rejected writes must not mutate even one setting",
                canonical(beforeRejectedWrite) == canonical(afterRejectedWrite))

            SeanimeApiClient().use { reader ->
                assertTrue(reader.restoreSession(repo.client.snapshotSession()))
                assertObservedSettingsEqual("fresh-client", expected, serverCall { SeanimeRepository(reader).settings() })
            }
        } finally {
            var cleanupFailure: Throwable? = null
            changedPaths.asReversed().forEach { (path, value) ->
                try { serverCall { repo.patchSetting(path, value) } }
                catch (failure: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure?.addSuppressed(failure)
                }
            }
            cleanupFailure?.let { throw AssertionError("Unable to restore original settings", it) }
            assertObservedSettingsEqual("restored", original, serverCall { repo.settings() })
        }
    }

    private fun withNativeServer(block: (ActivityScenario<MainActivity>, SeanimeRepository) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val client = SeanimeApiClient()
        try {
            awaitNativeReady()
            var session: SeanimeApiClient.SessionSnapshot? = null
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("api").apply { isAccessible = true }
                session = (field.get(activity) as SeanimeApiClient).snapshotSession()
            }
            assertTrue(client.restoreSession(requireNotNull(session)))
            val repo = SeanimeRepository(client)
            val status = serverCall { repo.status() }
            assertTrue("Tests require the real running Go server", status.ready && status.version.isNotBlank())
            assertEquals("http://127.0.0.1:43211", client.baseUrl)
            assertTrue("The server must issue a signed native client identity",
                client.clientId.isNotBlank() && !client.snapshotSession().identityProof.isNullOrBlank())
            assertEquals("androidtv", client.requestHeaders()["X-Seanime-Client-Platform"])
            block(scenario, repo)
        } finally {
            client.close()
            scenario.close()
            Mobile.stopServer()
        }
    }

    private fun awaitNativeReady() {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (Mobile.serverStatus() != "ready" && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertEquals("Embedded Go server did not start", "ready", Mobile.serverStatus())
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("nav-LIBRARY").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("setup-continue").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("setup-continue").fetchSemanticsNodes().isNotEmpty()) {
            awaitFocusedTag("setup-continue")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav-LIBRARY").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-tv-root").assertIsDisplayed()
    }

    private fun key(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private fun awaitFocusedTag(tag: String) = awaitFocused(hasTestTag(tag))
    private fun awaitFocusedText(text: String) = awaitFocused(hasText(text))
    private fun awaitFocused(matcher: SemanticsMatcher) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Focused) == true
            }
        }
        compose.onNode(matcher).assertIsDisplayed().assertIsFocused()
    }

    private fun typePlaylistName(name: String) {
        awaitFocused(hasSetTextAction())
        InstrumentationRegistry.getInstrumentation().sendStringSync(name)
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertTextContains(name)
    }

    private fun hideKeyboardKeepingDialog() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val previousFlags = automation.serviceInfo.flags
        val editorView = (compose.onNode(hasSetTextAction()).fetchSemanticsNode().root as ViewRootForTest).view
        fun keyboardVisible(): Boolean {
            val windows = automation.windows
            return try {
                windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ||
                    ViewCompat.getRootWindowInsets(editorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            finally { windows.forEach { it.recycle() } }
        }
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            if (keyboardVisible()) {
                key(KeyEvent.KEYCODE_BACK)
                compose.waitUntil(10_000) { !keyboardVisible() }
            }
            // The editor's Down handler uses these insets, while the keyboard itself is a
            // separate accessibility window. Both must settle before sending the next key.
            compose.waitUntil(10_000) { editorView.hasWindowFocus() && !keyboardVisible() }
            compose.waitForIdle()
            compose.onNode(hasSetTextAction()).assertIsDisplayed().assertIsFocused()
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }

    private fun awaitNameDialogClosed() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    private fun waitForEnabledText(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun <T> serverCall(block: suspend () -> T): T = runBlocking { withTimeout(60_000) { block() } }

    private fun fixtureName() = "Native backend integration ${UUID.randomUUID()}"

    private fun syntheticEpisode(number: Int): PlaylistEpisode {
        val raw = jsonObject(
            "type" to "main", "episodeNumber" to number, "progressNumber" to number,
            "absoluteEpisodeNumber" to number, "aniDBEpisode" to number.toString(),
            "displayTitle" to "Fixture episode $number", "episodeTitle" to "Synthetic $number",
            "baseAnime" to jsonObject("id" to 1_900_000_001, "title" to jsonObject("romaji" to "Native integration fixture")),
            "episodeMetadata" to jsonObject("summary" to "Fixture metadata $number", "length" to 24),
            "localFile" to JSONObject.NULL, "fileMetadata" to JSONObject.NULL,
        )
        return PlaylistEpisode(SeanimeJson.episode(raw), watchType = listOf("online", "torrent", "localfile")[number - 1],
            raw = jsonObject("isNakama" to (number == 2)))
    }

    private fun removeNamedFixtures(repo: SeanimeRepository, name: String, before: List<Playlist>) {
        val baselineIds = before.map { it.id }.toSet()
        serverCall { repo.playlists() }.filter { it.name == name && it.id !in baselineIds }.forEach {
            require(it.id > 0)
            serverCall { repo.deletePlaylist(it.id) }
        }
        assertEquals("Fixture cleanup must leave preexisting playlists intact", playlistState(before),
            playlistState(serverCall { repo.playlists() }))
    }

    private fun assertExistingPlaylistsUnchanged(before: List<Playlist>, after: List<Playlist>) {
        val ids = before.map { it.id }.toSet()
        assertEquals(playlistState(before), playlistState(after.filter { it.id in ids }))
    }

    private fun playlistState(playlists: List<Playlist>): Map<Int, String> = playlists.associate { it.id to canonical(it.raw) }

    private fun assertSettingsEqual(expected: JSONObject, actual: JSONObject) {
        fun configuration(value: JSONObject) = JSONObject(value.toString()).apply {
            // Existing handlers reconstruct BaseModel on save. Its audit times
            // cannot be restored through the settings API; all settings and ID can.
            remove("createdAt"); remove("updatedAt")
        }
        // Do not print complete settings into instrumentation reports: existing
        // configurations may contain account or provider credentials.
        assertTrue("Saving one field must preserve every other configuration value",
            canonical(configuration(expected)) == canonical(configuration(actual)))
    }

    /** JSON key ordering and 0 versus 0.0 do not alter the API value. */
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted()
            .joinToString(prefix = "{", postfix = "}") { "${JSONObject.quote(it)}:${canonical(value.get(it))}" }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
        is Number -> java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
