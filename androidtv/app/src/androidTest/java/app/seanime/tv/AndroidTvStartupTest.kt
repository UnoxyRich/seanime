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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.gomobile.mobile.Mobile
import app.seanime.tv.platform.NativePlatformActions
import app.seanime.tv.platform.NativePlaybackBus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
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
                scenario.onActivity { activity ->
                    assertTrue("The TV host must be a native ComponentActivity", activity is ComponentActivity)
                    assertTrue("The main Activity must contain a real ComposeView", containsView<ComposeView>(activity.window.decorView))
                    assertFalse("The main UI must never contain a WebView", containsView<WebView>(activity.window.decorView))
                }
                compose.onNodeWithTag("native-tv-root").assertIsDisplayed()
                compose.onNodeWithTag("nav-LIBRARY").assertHasClickAction()
                compose.onNodeWithTag("anime-search-field").assertExists()
                compose.onNodeWithTag("nav-LIBRARY").performSemanticsAction(SemanticsActions.RequestFocus)
                compose.onNodeWithTag("nav-LIBRARY").assertIsFocused()
                NativeScreenshotEvidence.capture("real-go-library-home-rail-focus")
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
            compose.onNodeWithTag("nav-LIBRARY").performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNodeWithTag("nav-LIBRARY").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("nav-ANILIST").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText("Connect AniList").assertIsDisplayed()

            // Back first restores the current navigation item, then opens the exit dialog.
            compose.onNodeWithTag("anime-search-submit").performSemanticsAction(SemanticsActions.RequestFocus)
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
            compose.onNodeWithTag("nav-ANILIST").performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNodeWithTag("nav-ANILIST").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("anime-search-field").performTextInput("remote query")
            compose.onNodeWithTag("anime-search-submit").performSemanticsAction(SemanticsActions.RequestFocus)
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
            compose.onNodeWithText("Connect AniList").assertIsDisplayed()
            compose.onNodeWithTag("anime-search-field").assertTextContains("remote query")

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitNativeReady()
            compose.onNodeWithText("Connect AniList").assertIsDisplayed()
            compose.onNodeWithTag("anime-search-field").assertTextContains("remote query")
            compose.onNodeWithTag("nav-ANILIST").performSemanticsAction(SemanticsActions.RequestFocus)
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
        try {
            awaitNativeReady()
            compose.onNodeWithTag("anime-search-field").performTextClearance()
            compose.onNodeWithTag("anime-search-field").performSemanticsAction(SemanticsActions.RequestFocus)
            key(KeyEvent.KEYCODE_A)
            key(KeyEvent.KEYCODE_B)
            compose.onNodeWithTag("anime-search-field").assertTextContains("ab")
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithTag("anime-search-field").assertIsFocused()
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
            compose.onNodeWithTag("setup-continue").performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNodeWithTag("setup-continue").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav-LIBRARY").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-tv-root").assertIsDisplayed()
    }

    private fun key(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
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
