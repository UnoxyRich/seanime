package app.seanime.tv

import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.gomobile.mobile.Mobile
import app.seanime.tv.platform.NativePlatformActions
import app.seanime.tv.platform.NativePlaybackBus
import app.seanime.tv.platform.BundledEnglishProviders
import app.seanime.tv.ui.TvFeature
import app.seanime.tv.ui.awaitTvWindowFocus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native-host regressions. No JavaScript fixture or React UI participates in these checks. */
@RunWith(AndroidJUnit4::class)
class AndroidTvStartupTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun nativeComposeUiStartsAndRestartsTheRealServer() {
        repeat(2) {
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                awaitNativeReady()
                assertBundledProvidersLoaded(activityClient(scenario))
                scenario.onActivity { activity ->
                    assertTrue("The TV host must be a native ComponentActivity", activity is ComponentActivity)
                    assertTrue("The main Activity must contain a real ComposeView", containsView<ComposeView>(activity.window.decorView))
                    assertFalse("The main UI must never contain a WebView", containsView<WebView>(activity.window.decorView))
                }
                compose.onNodeWithTag("native-tv-root").assertIsDisplayed()
                compose.onNodeWithTag("nav-LIBRARY").assertHasClickAction()
                compose.onNodeWithTag("anime-search-submit").assertIsDisplayed()
                compose.onNodeWithTag("anime-search-field").assertDoesNotExist()
                awaitFocus("nav-LIBRARY")
                NativeScreenshotEvidence.capture("real-go-library-home-rail-focus")
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                awaitFocus("anime-search-submit")
                NativeScreenshotEvidence.capture("real-go-library-search-toolbar-focus")
            } finally {
                scenario.close()
                Mobile.stopServer()
            }
            assertTrue("The embedded Go server failed to stop", waitForServerStatus("stopped", 20_000))
        }
    }

    @Test
    fun dpadMovesBetweenNativeDestinationsAndBackOffersAnExit() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitNativeReady()
            selectAniListFromInitialRail()
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus("anime-search-submit")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus("anime-collection-options")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitFocus("collection-status")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            awaitFocus("collection-sort")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithText("Connect AniList").assertIsDisplayed().assertIsFocused()
            NativeScreenshotEvidence.capture("real-go-anilist-options-account-focus")

            // Close the actual options dialog before testing content → rail → Exit.
            key(KeyEvent.KEYCODE_BACK)
            awaitFocus("anime-collection-options")
            compose.onNodeWithText("Collection options").assertDoesNotExist()
            key(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("nav-ANILIST").assertIsFocused()
            key(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("exit-confirm").assertIsDisplayed()
            key(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("exit-confirm").assertDoesNotExist()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            compose.onNodeWithTag("native-tv-root").assertIsDisplayed()
        } finally {
            scenario.close()
            Mobile.stopServer()
        }
    }

    @Test
    fun nativeRouteAndTextSurviveRecreationAndBackgroundReturn() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitNativeReady()
            selectAniListFromInitialRail()
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus("anime-search-submit")
            openSearchFromFocusedToolbar()
            typeWithHardwareKeys("remote query")
            compose.onNodeWithTag("anime-search-field").assertTextContains("remote query")
            submitSearchWithHardwareEnter()
            compose.onNodeWithTag("anime-search-submit").assertTextContains("Search: remote query")
            val initialSession = activitySession(scenario)
            assertTrue("The running Go server must supply a signed native client identity",
                initialSession.clientId.isNotBlank() && !initialSession.identityProof.isNullOrBlank())
            scenario.recreate()
            assertEquals("Activity recreation must retain the running server", "ready", Mobile.serverStatus())
            awaitNativeReady()
            val recreatedSession = activitySession(scenario)
            assertTrue("Recreation changed the server's signed client ID", initialSession.clientId == recreatedSession.clientId)
            assertTrue("Recreation changed the server origin", initialSession.canonicalOrigin == recreatedSession.canonicalOrigin)
            assertTrue("Recreation lost the server authentication state", initialSession.serverToken == recreatedSession.serverToken)
            // Go renews each response proof with timestamped iat/exp claims.
            // Its bytes may change while the authenticated client stays the same.
            assertFalse("Recreation lost the server-issued proof", recreatedSession.identityProof.isNullOrBlank())
            assertLiveSignedSession(activityClient(scenario), initialSession)
            awaitFocus("nav-ANILIST")
            compose.onNodeWithTag("anime-search-submit").assertTextContains("Search: remote query")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus("anime-search-submit")
            openSearchFromFocusedToolbar()
            compose.onNodeWithTag("anime-search-field").assertTextContains("remote query")
            submitSearchWithHardwareEnter()

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitNativeReady()
            awaitFocus("anime-search-submit")
            compose.onNodeWithTag("anime-search-submit").assertTextContains("Search: remote query")
            openSearchFromFocusedToolbar()
            compose.onNodeWithTag("anime-search-field").assertTextContains("remote query")
            NativeScreenshotEvidence.capture("real-go-search-query-restored-after-background")
            submitSearchWithHardwareEnter()
            key(KeyEvent.KEYCODE_BACK)
            awaitFocus("nav-ANILIST")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("nav-MANGA").assertIsFocused()
            scenario.onActivity { activity ->
                assertFalse("Background return inserted a WebView", containsView<WebView>(activity.window.decorView))
            }
        } finally {
            scenario.close()
            Mobile.stopServer()
        }
    }

    @Test
    fun remoteLetterKeysEnterNativeSearchWithoutStealingCursorFocus() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val inputTrace = SearchInputTrace()
        var injectedKeys = 0
        fun record(stage: String) = inputTrace.record(stage, injectedKeys,
            compose.onAllNodesWithTag("anime-search-field").fetchSemanticsNodes())
        fun awaitInputWindow(stage: String) {
            record(stage)
            awaitFocus("anime-search-field")
            compose.onNodeWithTag("anime-search-field").awaitTvWindowFocus()
                .assertIsDisplayed().assertIsFocused()
            record(stage) // Replace this stage with the final pre-key observation.
        }
        try {
            awaitNativeReady()
            awaitFocus("nav-LIBRARY")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus("anime-search-submit")
            openSearchFromFocusedToolbar()
            assertEquals("A new Library search should start empty", "",
                compose.onNodeWithTag("anime-search-field").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            // Semantic focus can precede Android's dialog-window input ownership.
            // Send each real letter once; never replace text or retry typing.
            awaitInputWindow("before-a")
            injectedKeys++; key(KeyEvent.KEYCODE_A)
            record("after-a")
            awaitInputWindow("before-b")
            injectedKeys++; key(KeyEvent.KEYCODE_B)
            record("after-b")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("anime-search-field").fetchSemanticsNodes().singleOrNull()
                    ?.config?.getOrNull(SemanticsProperties.EditableText)?.text == "ab"
            }
            record("text-delivered")
            compose.onNodeWithTag("anime-search-field").assertTextContains("ab")
            injectedKeys++; key(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithTag("anime-search-field").awaitTvWindowFocus()
            record("after-left")
            compose.onNodeWithTag("anime-search-field").assertIsFocused()
            inputTrace.finish("passed", injectedKeys)
        } catch (failure: Throwable) {
            // Reuse the last observed state, without another potentially blocking
            // Compose query after the original failure. No raw editor text is saved.
            runCatching { inputTrace.finish("failed", injectedKeys) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            scenario.close()
            Mobile.stopServer()
        }
    }

    @Test
    fun everyMainDestinationHasRemoteContentAndBackRecovery() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitNativeReady()
            awaitFocus("nav-LIBRARY")
            TvFeature.entries.forEachIndexed { index, feature ->
                if (index > 0) key(KeyEvent.KEYCODE_DPAD_DOWN)
                val railTag = "nav-${feature.name}"
                awaitFocus(railTag)
                assertRailControlInsideSafeBounds(railTag)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitFocus(railTag)
                val enabledContentAction = hasClickAction() and isEnabled() and
                    hasAnyAncestor(hasTestTag("native-content"))
                compose.waitUntil(15_000) {
                    compose.onAllNodes(enabledContentAction).fetchSemanticsNodes().isNotEmpty()
                }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                val contentFocused = isFocused() and hasAnyAncestor(hasTestTag("native-content"))
                compose.waitUntil(15_000) {
                    compose.onAllNodes(contentFocused).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onAllNodes(contentFocused)[0].assertIsDisplayed()
                key(KeyEvent.KEYCODE_BACK)
                awaitFocus(railTag)
                assertRailControlInsideSafeBounds(railTag)
                if (feature == TvFeature.SETTINGS) NativeScreenshotEvidence.capture("real-go-settings-rail-safe-focus")
                if (feature == TvFeature.LOGS) NativeScreenshotEvidence.capture("real-go-logs-rail-safe-focus")
                key(KeyEvent.KEYCODE_BACK)
                compose.onNodeWithTag("exit-confirm").assertIsDisplayed()
                key(KeyEvent.KEYCODE_BACK)
                compose.onNodeWithTag("exit-confirm").assertDoesNotExist()
                awaitFocus(railTag)
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
            }
            // Exercise upward scrolling too, without a semantic scroll or focus jump.
            TvFeature.entries.dropLast(1).asReversed().forEach { feature ->
                key(KeyEvent.KEYCODE_DPAD_UP)
                awaitFocus("nav-${feature.name}")
                assertRailControlInsideSafeBounds("nav-${feature.name}")
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitFocus("nav-LIBRARY")
            compose.onNodeWithTag("anime-search-submit").assertIsDisplayed()
        } finally {
            scenario.close()
            Mobile.stopServer()
        }
    }

    @Test
    fun oauthBrowserDeclaresResizeForTheTvKeyboard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, AuthWebViewActivity::class.java), 0,
        )
        val adjustMode = info.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        assertEquals("OAuth input must remain above the TV keyboard", WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, adjustMode)
    }

    @Test
    fun updateInstallerRejectsApksOutsideTheManagedUpdateCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("android-tv-updates", android.content.Context.MODE_PRIVATE)
        val previous = preferences.getString("pending-install-path", null)
        val foreignApk = java.io.File.createTempFile("outside-update-cache-", ".apk", context.cacheDir)
            .apply { writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitNativeReady()
            var rejected = false
            scenario.onActivity { activity ->
                val actions = NativePlatformActions(activity, onError = { rejected = true })
                try { actions.installUpdate(foreignApk.absolutePath) } finally { actions.close() }
            }
            assertTrue("An APK outside the managed cache must be rejected", rejected)
            assertEquals("A rejected APK must not replace the pending installer path", previous,
                preferences.getString("pending-install-path", null))
            compose.onNodeWithTag("native-tv-root").assertIsDisplayed()
        } finally {
            scenario.close()
            Mobile.stopServer()
            foreignApk.delete()
        }
    }

    @Test
    fun networkPolicyKeepsTheEmbeddedServerReachableAndModernRemoteHttpBlocked() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("The legacy HTTP flag must be limited to pre-Nougat devices",
            android.os.Build.VERSION.SDK_INT < 24,
            context.resources.getBoolean(R.bool.allow_legacy_loopback_http))
        val policy = android.security.NetworkSecurityPolicy.getInstance()
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            assertTrue("The embedded Go server's canonical HTTP origin must be allowed",
                policy.isCleartextTrafficPermitted("127.0.0.1"))
            assertFalse("Remote cleartext must not be enabled by the legacy compatibility flag",
                policy.isCleartextTrafficPermitted("example.com"))
        } else {
            assertTrue("API23 ignores XML network policy and still needs the embedded HTTP server",
                policy.isCleartextTrafficPermitted)
        }
    }

    @Test
    fun callbackDestinationsStayOnTheCanonicalLoopbackOrigin() {
        assertEquals("http://127.0.0.1:43211/api/auth?code=sample#returned",
            NativePlaybackBus.localUrl("http://localhost:43211/api/auth?code=sample#returned"))
        listOf("https://127.0.0.1:43211/", "http://127.0.0.1:43212/", "http://localhost.example:43211/",
            "http://user@127.0.0.1:43211/", "file:///data/local/tmp/page.html").forEach {
            assertNull("Unexpected callback origin was accepted: $it", NativePlaybackBus.localUrl(it))
        }
    }

    @Test
    fun packageIsDiscoverableAsAnAndroidTvLauncherApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        val launchIntent = pm.getLeanbackLaunchIntentForPackage(context.packageName)
        assertNotNull("Missing Leanback launcher entry", launchIntent)
        assertEquals(MainActivity::class.java.name, launchIntent?.component?.className)
        val application = pm.getApplicationInfo(context.packageName, 0)
        assertTrue("Missing TV icon", application.icon != 0)
        assertTrue("Missing TV banner", application.banner != 0)
        val features = pm.getPackageInfo(context.packageName, PackageManager.GET_CONFIGURATIONS).reqFeatures.orEmpty()
        val leanback = features.firstOrNull { it.name == "android.software.leanback" }
        val touchscreen = features.firstOrNull { it.name == "android.hardware.touchscreen" }
        assertNotNull(leanback)
        assertTrue((leanback!!.flags and FeatureInfo.FLAG_REQUIRED) != 0)
        assertNotNull(touchscreen)
        assertFalse((touchscreen!!.flags and FeatureInfo.FLAG_REQUIRED) != 0)
    }

    private fun activitySession(scenario: ActivityScenario<MainActivity>) = activityClient(scenario).snapshotSession()

    private fun assertBundledProvidersLoaded(client: SeanimeApiClient) = runBlocking {
        // /status becomes ready before asynchronous extension loading. Observe
        // the passive inventory, without a provider search or update request.
        withTimeout(30_000) {
            while (true) {
                val inventory = client.request("POST", "/api/v1/extensions/all", JSONObject().put("withUpdates", false)) as JSONObject
                val loaded = inventory.optJSONArray("extensions")
                val byId = (0 until (loaded?.length() ?: 0)).map { loaded!!.getJSONObject(it) }.associateBy { it.getString("id") }
                if (BundledEnglishProviders.entries.all { it.id in byId }) {
                    BundledEnglishProviders.entries.forEach { expected ->
                        val actual = byId.getValue(expected.id)
                        assertEquals(expected.version, actual.getString("version"))
                        assertEquals("en", actual.getString("lang"))
                        assertEquals(expected.type, actual.getString("type"))
                        assertEquals(expected.manifestURI, actual.getString("manifestURI"))
                    }
                    return@withTimeout
                }
                delay(100)
            }
        }
    }

    private fun activityClient(scenario: ActivityScenario<MainActivity>): SeanimeApiClient {
        var client: SeanimeApiClient? = null
        scenario.onActivity { activity ->
            val field = MainActivity::class.java.getDeclaredField("api").apply { isAccessible = true }
            client = field.get(activity) as SeanimeApiClient
        }
        return requireNotNull(client)
    }

    private fun assertLiveSignedSession(client: SeanimeApiClient, original: SeanimeApiClient.SessionSnapshot) = runBlocking {
        withTimeout(30_000) {
            client.awaitEventsReady()
            assertFalse("The signed request has no identity proof", client.requestHeaders()["X-Seanime-Client-Id-Proof"].isNullOrBlank())
            val status = client.request("GET", "/api/v1/status") as JSONObject
            assertTrue("Signed status did not reach the running Go server", status.optString("version").isNotBlank() && status.optBoolean("serverReady"))
            val timestamp = SystemClock.elapsedRealtime()
            val reply = async(start = CoroutineStart.UNDISPATCHED) {
                client.events.first { it.type == "pong" && (it.payload as? JSONObject)?.optLong("timestamp") == timestamp }
            }
            assertTrue("The recreated client socket rejected its ping", client.sendEvent("ping", JSONObject().put("timestamp", timestamp)))
            reply.await()
            val current = client.snapshotSession()
            assertTrue("The signed round-trip changed client identity", current.clientId == original.clientId)
            assertTrue("The signed round-trip changed origin or authentication", current.canonicalOrigin == original.canonicalOrigin && current.serverToken == original.serverToken)
            assertFalse("The round-trip lost the renewed proof", current.identityProof.isNullOrBlank())
        }
    }

    private fun awaitNativeReady() {
        assertTrue("The embedded Go server did not become ready", waitForServerStatus("ready", 60_000))
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("nav-LIBRARY").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("setup-continue").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("setup-continue").fetchSemanticsNodes().isNotEmpty()) {
            // Setup is a remote-first TV control: drive its actual key path rather
            // than synthesized pointer activation.
            awaitFocus("setup-continue")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav-LIBRARY").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-tv-root").assertIsDisplayed()
    }

    private fun awaitFocus(tag: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Focused) == true
            }
        }
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsFocused()
    }

    private fun assertRailControlInsideSafeBounds(tag: String) {
        val control = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val safe = compose.onNodeWithTag("native-navigation").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("navigation-footer").fetchSemanticsNode().boundsInRoot
        assertTrue("$tag is clipped at the left safe edge", control.left >= safe.left - 1f)
        assertTrue("$tag is clipped at the top safe edge", control.top >= safe.top - 1f)
        assertTrue("$tag overlaps the fixed navigation footer", control.bottom <= footer.top + 1f)
        assertTrue("$tag is outside the safe viewport", control.right <= safe.right + 1f && control.bottom <= safe.bottom + 1f)
    }

    private fun selectAniListFromInitialRail() {
        awaitFocus("nav-LIBRARY")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocus("nav-ANILIST")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus("nav-ANILIST")
    }

    private fun openSearchFromFocusedToolbar() {
        compose.onNodeWithTag("anime-search-submit").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus("anime-search-field")
    }

    private fun typeWithHardwareKeys(value: String) {
        value.forEach { character ->
            key(when (character) {
                in 'a'..'z' -> KeyEvent.KEYCODE_A + (character - 'a')
                ' ' -> KeyEvent.KEYCODE_SPACE
                else -> error("Unsupported hardware-key fixture character")
            })
        }
    }

    private fun submitSearchWithHardwareEnter() {
        compose.onNodeWithTag("anime-search-field").assertIsFocused()
        // The production single-line editor declares ImeAction.Search. Exercise
        // its actual hardware Enter path instead of invoking a semantics action.
        key(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("anime-search-field").fetchSemanticsNodes().isEmpty()
        }
        awaitFocus("anime-search-submit")
    }

    private fun key(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private class SearchInputTrace {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val observations = JSONArray()
        private val data = JSONObject().put("schemaVersion", 1).put("scenario", "native-search-hardware-input")
            .put("startedAtMs", System.currentTimeMillis()).put("outcome", "running").put("observations", observations)
        private fun emptyObservation(stage: String, keys: Int) = JSONObject().put("stage", stage).put("keysSent", keys)
            .put("nodeCount", 0).put("hasEditableText", false).put("textLength", 0)
            .put("exactEmpty", false).put("exactA", false).put("exactAB", false)
            .put("semanticFocused", false).put("viewAttached", false).put("viewLaidOut", false)
            .put("windowFocused", false).put("imeVisible", "unknown")

        fun record(stage: String, keys: Int, nodes: List<SemanticsNode>) {
            val node = nodes.singleOrNull()
            val text = node?.config?.getOrNull(SemanticsProperties.EditableText)?.text
            val observation = emptyObservation(stage, keys).put("nodeCount", nodes.size.coerceAtMost(255))
                .put("hasEditableText", text != null).put("textLength", text?.length?.coerceAtMost(4096) ?: 0)
                .put("exactEmpty", text == "").put("exactA", text == "a").put("exactAB", text == "ab")
                .put("semanticFocused", node?.config?.getOrNull(SemanticsProperties.Focused) == true)
            val view = (node?.root as? ViewRootForTest)?.view
            instrumentation.runOnMainSync {
                observation.put("viewAttached", view?.isAttachedToWindow == true)
                    .put("viewLaidOut", view?.isLaidOut == true).put("windowFocused", view?.hasWindowFocus() == true)
                    .put("imeVisible", view?.let(ViewCompat::getRootWindowInsets)?.let {
                        if (it.isVisible(WindowInsetsCompat.Type.ime())) "visible" else "hidden"
                    } ?: "unknown")
            }
            val last = observations.length() - 1
            if (last >= 0 && observations.getJSONObject(last).getString("stage") == stage) observations.put(last, observation)
            else observations.put(observation)
            check(observations.length() <= 6)
            save()
        }

        fun finish(outcome: String, keys: Int) {
            if (outcome == "failed") {
                val last = observations.optJSONObject(observations.length() - 1)
                observations.put((last?.let { JSONObject(it.toString()) } ?: emptyObservation("failure", keys))
                    .put("stage", "failure").put("keysSent", keys))
            }
            data.put("outcome", outcome).put("completedAtMs", System.currentTimeMillis())
            save()
        }

        private fun save() {
            val directory = File(instrumentation.targetContext.cacheDir, "native-acceptance-diagnostics")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "native-search-hardware-input.json").writeText(data.toString())
        }
    }

    private fun waitForServerStatus(expected: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Mobile.serverStatus() == expected) return true
            SystemClock.sleep(50)
        }
        return Mobile.serverStatus() == expected
    }

    private inline fun <reified T : View> containsView(root: View): Boolean {
        val pending = java.util.ArrayDeque<View>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (view is T) return true
            if (view is ViewGroup) for (index in 0 until view.childCount) pending.add(view.getChildAt(index))
        }
        return false
    }
}
