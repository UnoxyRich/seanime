package app.seanime.tv

import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import app.seanime.tv.gomobile.mobile.Mobile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidTvStartupTest {
    @Test
    fun remoteBackDismissesWebUiThenReturnsToTheFirstHistoryEntryAndExits() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            assertTrue("Seanime server did not start", waitForServerStatus("ready", 60_000))
            waitUntil("embedded UI for Back navigation", 30_000) {
                evaluateJavascript(scenario,
                    "document.readyState === 'complete' && window.AndroidTV?.serverStatus() === 'ready'") == "true"
            }
            // Use a separate document so first-run React redirects cannot replace
            // the history fixture while the native Back callbacks are in flight.
            scenario.onActivity {
                val view = requireNotNull(findWebView(it.window.decorView))
                val originalClient = view.webViewClient
                view.webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldInterceptRequest(
                        webView: WebView?, request: android.webkit.WebResourceRequest?,
                    ): android.webkit.WebResourceResponse? {
                        if (request?.url?.path == "/_tv-back-test") {
                            val html = "<!doctype html><title>TV Back fixture</title><main id='tv-back-fixture'></main>"
                            return android.webkit.WebResourceResponse("text/html", "UTF-8",
                                java.io.ByteArrayInputStream(html.toByteArray()))
                        }
                        return originalClient.shouldInterceptRequest(webView, request)
                    }
                }
                // A real URL load participates in native WebView history, unlike
                // loadDataWithBaseURL's synthetic document on older WebViews.
                view.loadUrl("http://127.0.0.1:43211/_tv-back-test")
            }
            waitUntil("isolated Back navigation document", 10_000) {
                evaluateJavascript(scenario,
                    "document.readyState === 'complete' && document.title === 'TV Back fixture'") == "true"
            }
            scenario.onActivity { findWebView(it.window.decorView)?.clearHistory() }
            assertEquals("true", evaluateJavascript(scenario,
                """(() => {
                    document.body.innerHTML = '<main id="tv-back-fixture"></main>';
                    const navigate = document.createElement('button');
                    navigate.textContent = 'Open route';
                    navigate.style.cssText = 'position:fixed;left:64px;top:64px;width:200px;height:80px';
                    navigate.addEventListener('click', () => {
                        history.replaceState({}, '', '/#tv-back-root');
                        history.pushState({}, '', '/#tv-back-first');
                        history.pushState({}, '', '/#tv-back-second');
                        navigate.remove();
                        const menu = document.createElement('div');
                        menu.setAttribute('role', 'menu');
                        menu.style.cssText = 'position:fixed;left:64px;top:64px;width:200px;height:80px';
                        const item = document.createElement('button');
                        item.textContent = 'Dismiss menu';
                        item.addEventListener('keydown', event => {
                            if (event.key === 'Escape') {
                                event.preventDefault();
                                window.__tvBackMenuDismissed = true;
                                menu.remove();
                            }
                        });
                        menu.appendChild(item);
                        document.body.appendChild(menu);
                        item.focus();
                    });
                    document.body.appendChild(navigate);
                    navigate.focus();
                    return document.activeElement === navigate;
                })()""".trimIndent()))

            // Chromium skips script-created history entries without a user
            // gesture. Create the fixture routes using the same trusted remote
            // activation as the app's links instead of evaluateJavascript.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitUntil("native history to receive same-document navigation", 5_000) {
                var canGoBack = false
                scenario.onActivity { canGoBack = findWebView(it.window.decorView)?.canGoBack() == true }
                canGoBack
            }

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntil("focused menu to consume Back", 5_000) {
                evaluateJavascript(scenario,
                    "window.__tvBackMenuDismissed === true && location.hash === '#tv-back-second'") == "true"
            }
            assertEquals("true", evaluateJavascript(scenario,
                """(() => {
                    window.__tvBackReader = event => {
                        if (event.key === 'Escape') {
                            event.preventDefault();
                            window.__tvBackReaderDismissed = true;
                            document.removeEventListener('keydown', window.__tvBackReader, true);
                        }
                    };
                    document.addEventListener('keydown', window.__tvBackReader, true);
                    return true;
                })()""".trimIndent()))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntil("reader Escape handler to consume Back", 5_000) {
                evaluateJavascript(scenario,
                    "window.__tvBackReaderDismissed === true && location.hash === '#tv-back-second'") == "true"
            }
            assertEquals("true", evaluateJavascript(scenario,
                """(() => {
                    const closedDialog = document.createElement('div');
                    closedDialog.setAttribute('role', 'dialog');
                    closedDialog.hidden = true;
                    document.body.appendChild(closedDialog);
                    return true;
                })()""".trimIndent()))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntil("Back to the previous same-document route", 5_000) {
                evaluateJavascript(scenario, "location.hash === '#tv-back-first'") == "true"
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntil("Back to the initial route", 5_000) {
                evaluateJavascript(scenario,
                    "location.hash === '#tv-back-root' && history.length > 1") == "true"
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntil("Back to exit at the oldest history entry", 10_000) {
                scenario.state == Lifecycle.State.DESTROYED
            }
        } finally {
            scenario.close()
        }
        assertTrue("Seanime server did not stop after Back exit", waitForServerStatus("stopped", 20_000))
    }

    @Test
    fun oauthWebViewResizesForTheTvKeyboard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch<AuthWebViewActivity>(
            AuthWebViewActivity.intent(context, Uri.parse("http://127.0.0.1:1/oauth-input-fixture")),
        )
        try {
            scenario.onActivity { activity ->
                val adjustMode = activity.window.attributes.softInputMode and
                    WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
                assertEquals(
                    "OAuth text fields must stay visible above the TV keyboard",
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    adjustMode,
                )
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun updateInstallerRestrictsApkPathsAndRequestsUnknownSourceConsent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Unknown-source consent flow requires Android 8 or newer", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        assumeFalse("Test expects update installs to require user consent", context.packageManager.canRequestPackageInstalls())

        val preferences = context.getSharedPreferences("android-tv-updates", android.content.Context.MODE_PRIVATE)
        val previousPendingPath = preferences.getString("pending-install-path", null)
        val updateDirectory = File(context.filesDir, "seanime/updates").apply { mkdirs() }
        val validCachedApk = File(updateDirectory, "consent-flow.apk").apply { writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) }
        val externalApk = File(context.cacheDir, "untrusted-update.apk").apply { writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue("Seanime server did not start", waitForServerStatus("ready", 60_000))
            scenario.onActivity { it.installUpdate(externalApk.absolutePath) }
            assertEquals("Update installer accepted an APK outside its cache", previousPendingPath,
                preferences.getString("pending-install-path", null))

            scenario.onActivity { it.installUpdate(validCachedApk.absolutePath) }
            assertEquals(validCachedApk.canonicalPath, preferences.getString("pending-install-path", null))
            waitUntil("Android's install-source settings to take focus", 10_000) {
                !hasWindowFocus(scenario)
            }
            pressBackFromSystemUi()
            waitUntil("main activity to regain focus after install-source settings", 10_000) {
                hasWindowFocus(scenario)
            }
            assertEquals("Pending update should remain available until install permission is granted", validCachedApk.canonicalPath,
                preferences.getString("pending-install-path", null))
        } finally {
            if (!hasWindowFocus(scenario)) {
                pressBackFromSystemUi()
            }
            scenario.close()
            Mobile.stopServer()
            preferences.edit().putString("pending-install-path", previousPendingPath).commit()
            validCachedApk.delete()
            updateDirectory.delete()
            externalApk.delete()
        }
        assertTrue("server did not stop after update-installer test", waitForServerStatus("stopped", 20_000))
    }

    @Test
    fun mainWebViewKeepsRemoteTextEntryFocused() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            assertTrue("Seanime server did not start", waitForServerStatus("ready", 60_000))
            waitUntil("embedded UI to load for text entry", 30_000) {
                evaluateJavascript(scenario, "document.readyState === 'complete' && document.documentElement.classList.contains('android-tv')") == "true"
            }
            assertEquals("TV text input fixture did not focus", "true", evaluateJavascript(scenario,
                "(() => { document.body.innerHTML = '<main><input id=tv-search aria-label=Search><button id=tv-search-result>Result</button></main>'; const input=document.getElementById('tv-search'); input.focus(); return document.activeElement === input; })()"))

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_A)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_B)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)

            assertEquals(
                "D-pad navigation moved focus out of the text field or lost typed input",
                "true",
                evaluateJavascript(scenario,
                    "document.activeElement?.id === 'tv-search' && document.getElementById('tv-search').value === 'ab'"),
            )
        } finally {
            scenario.close()
        }
        assertTrue("Seanime server did not stop after text-entry test", waitForServerStatus("stopped", 20_000))
    }

    @Test
    fun packageIsDiscoverableAsAnAndroidTvLauncherApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        val launchIntent = packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
        assertNotNull("Seanime TV is missing its Leanback launcher entry", launchIntent)
        assertEquals(MainActivity::class.java.name, launchIntent?.component?.className)

        val applicationInfo = packageManager.getApplicationInfo(context.packageName, 0)
        assertTrue("Android TV app icon is missing", applicationInfo.icon != 0)
        assertTrue("Android TV launcher banner is missing", applicationInfo.banner != 0)

        val features = packageManager.getPackageInfo(context.packageName, PackageManager.GET_CONFIGURATIONS).reqFeatures.orEmpty()
        val leanback = features.firstOrNull { it.name == "android.software.leanback" }
        val touchscreen = features.firstOrNull { it.name == "android.hardware.touchscreen" }
        assertNotNull("Leanback must be required for this TV-only app", leanback)
        assertTrue("Leanback must be required", (leanback!!.flags and FeatureInfo.FLAG_REQUIRED) != 0)
        assertNotNull("Touchscreen support must be declared optional", touchscreen)
        assertFalse("Touchscreen must remain optional", (touchscreen!!.flags and FeatureInfo.FLAG_REQUIRED) != 0)
    }

    @Test
    fun bridgeBootstrapOnlyAcceptsTheLocalMainFrame() {
        val local = Uri.parse("http://127.0.0.1:43211")
        assertTrue(MainActivity.canBootstrapBridge(local, true, "seanime-tv-bootstrap-v1"))
        assertFalse(MainActivity.canBootstrapBridge(local, false, "seanime-tv-bootstrap-v1"))
        assertFalse(MainActivity.canBootstrapBridge(local, true, "unrelated-message"))
        assertFalse(MainActivity.canBootstrapBridge(local, true, null))
        for (origin in listOf("null", "http://localhost:43211", "https://127.0.0.1:43211", "http://127.0.0.1:43212", "http://user@127.0.0.1:43211", "https://example.com")) {
            assertFalse(MainActivity.canBootstrapBridge(Uri.parse(origin), true, "seanime-tv-bootstrap-v1"))
        }
    }

    @Test
    fun callbackDestinationsUseTheLocalBridgeOrigin() {
        assertEquals(
            "http://127.0.0.1:43211/api/auth?code=sample#returned",
            MainActivity.localPageUrl("http://localhost:43211/api/auth?code=sample#returned"),
        )
        assertNull(MainActivity.localPageUrl("https://127.0.0.1:43211/"))
        assertNull(MainActivity.localPageUrl("http://127.0.0.1:43212/"))
        assertNull(MainActivity.localPageUrl("http://localhost.example:43211/"))
        assertNull(MainActivity.localPageUrl("http://user@127.0.0.1:43211/"))
        assertNull(MainActivity.localPageUrl("file:///data/local/tmp/page.html"))
    }

    @Test
    fun embeddedUiBridgeAndServerLifecycleWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        try {
            assertTrue(
                "Seanime server failed: ${Mobile.serverStatus()} (${Mobile.serverError()})",
                waitForServerStatus("ready", 60_000),
            )
            waitUntil("embedded Seanime UI and authenticated bridge", 30_000) {
                evaluateJavascript(
                    scenario,
                        "document.readyState === 'complete' && document.title === 'Seanime' && " +
                        "document.getElementById('root') !== null && " +
                        "Object.hasOwn({ own: true }, 'own') && !Object.hasOwn(Object.create({ inherited: true }), 'inherited') && " +
                        "(() => { const viewport = getComputedStyle(document.documentElement).getPropertyValue('--viewport-height').trim(); " +
                        "const probe = document.createElement('div'); probe.className = 'h-[calc(var(--viewport-height)_-_3rem)]'; " +
                        "document.body.appendChild(probe); const height = parseFloat(getComputedStyle(probe).height); probe.remove(); " +
                        "return !CSS.supports('height', '100dvh') && viewport === '100vh' && height > 0; })() && " +
                        "(() => { const values = [3, 1, 2]; const deferred = Promise.withResolvers(); deferred.resolve(42); return " +
                        "values.at(-1) === 2 && values.findLast(value => value % 2 === 1) === 1 && " +
                        "values.findLastIndex(value => value % 2 === 1) === 1 && values.toSorted((a, b) => a - b).join(',') === '1,2,3' && " +
                        "values.toReversed().join(',') === '2,1,3' && values.toSpliced(1, 1, 4).join(',') === '3,4,2' && " +
                        "values.join(',') === '3,1,2' && deferred.promise instanceof Promise && typeof deferred.resolve === 'function'; })() && " +
                        "window.AndroidTV?.serverStatus() === 'ready' && " +
                        "window.AndroidTVNativeBridge?.serverStatus('invalid-token') === ''",
                    ) == "true"
            }

            assertEquals(
                "could not set up the native activity focus target",
                "true",
                evaluateJavascript(scenario, nativeDpadFixture()),
            )
            assertEquals(
                "native Android TV storage bridge was unavailable",
                "true",
                evaluateJavascript(
                    scenario,
                    """(() => {
                        if (!window.AndroidTV?.requestMediaFolder) return false;
                        window.AndroidTV.requestMediaFolder('library-main');
                        return true;
                    })()""",
                ),
            )
            waitUntil("native storage UI to take window focus", 10_000) { !hasWindowFocus(scenario) }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntil("WebView activity to regain window focus", 10_000) { hasWindowFocus(scenario) }
            waitUntil("WebView to restore its focus target after native UI", 5_000) {
                evaluateJavascript(scenario, "document.activeElement?.id === 'tv-native-focus-start'") == "true"
            }
            val restoredFocusStyle = evaluateJavascript(
                scenario,
                "JSON.stringify({id:document.activeElement?.id,marker:document.activeElement?.getAttribute('data-android-tv-focus-restored'),outline:getComputedStyle(document.activeElement).outlineStyle,outlineWidth:getComputedStyle(document.activeElement).outlineWidth,shadow:getComputedStyle(document.activeElement).boxShadow})",
            )
            assertEquals(
                "restored remote focus should keep a visible ring: $restoredFocusStyle",
                "true",
                evaluateJavascript(
                    scenario,
                    "document.activeElement?.getAttribute('data-android-tv-focus-restored') === 'true' && getComputedStyle(document.activeElement).outlineStyle === 'solid' && getComputedStyle(document.activeElement).outlineWidth === '3px'",
                ),
            )

            val missingPlayerSource = File(context.cacheDir, "focus-return-${System.nanoTime()}.mp4")
            scenario.onActivity { activity ->
                activity.startActivity(
                    NativePlayerActivity.intent(activity, Uri.fromFile(missingPlayerSource), "Focus return", "[]", 0, "{}"),
                )
            }
            waitUntil("native player to take window focus", 10_000) {
                NativePlayerActivity.isVisible() && !hasWindowFocus(scenario)
            }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntil("main WebView to regain focus after native player", 10_000) {
                hasWindowFocus(scenario) && !NativePlayerActivity.isVisible()
            }
            waitUntil("WebView to restore visible focus after native player", 5_000) {
                evaluateJavascript(
                    scenario,
                    "document.activeElement?.id === 'tv-native-focus-start' && document.activeElement?.getAttribute('data-android-tv-focus-restored') === 'true' && getComputedStyle(document.activeElement).outlineStyle === 'solid' && getComputedStyle(document.activeElement).outlineWidth === '3px'",
                ) == "true"
            }

            startSandboxedFrameBridgeProbe(scenario)
            waitUntil("sandboxed iframe bridge isolation", 10_000) {
                evaluateJavascript(scenario, "typeof window.__seanimeTVFrameBridgeProbe === 'string'") == "true"
            }
            assertEquals(
                "sandboxed iframe could read the native bridge token",
                "true",
                evaluateJavascript(scenario, "JSON.parse(window.__seanimeTVFrameBridgeProbe).token === 'blocked'"),
            )
            assertEquals(
                "sandboxed iframe could invoke a privileged native method",
                "true",
                evaluateJavascript(
                    scenario,
                    "(() => { const status = JSON.parse(window.__seanimeTVFrameBridgeProbe).nativeStatus; return status === '' || status === 'missing'; })()",
                ),
            )
            assertEquals("sandboxed iframe received the bridge bootstrap channel", "true",
                evaluateJavascript(scenario, "JSON.parse(window.__seanimeTVFrameBridgeProbe).bootstrap === 'undefined'"))

            assertTrue(
                "Android TV arrow keys did not move focus spatially",
                evaluateJavascript(scenario, dpadNavigationProbe()) == "true",
            )
            assertTrue(
                "Android TV focus fixture did not initialize",
                evaluateJavascript(scenario, nativeDpadFixture()) == "true",
            )
            scenario.onActivity { activity -> findWebView(activity.window.decorView)?.requestFocus() }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitUntil("native DPAD_RIGHT focus movement", 3_000) {
                evaluateJavascript(scenario, "document.activeElement?.id === 'tv-native-focus-right'") == "true"
            }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            waitUntil("native DPAD_DOWN focus movement", 3_000) {
                evaluateJavascript(scenario, "document.activeElement?.id === 'tv-native-focus-down'") == "true"
            }

            assertEquals("true", evaluateJavascript(scenario,
                "history.pushState({}, '', '/#androidtv-restored-page'); true"))
            scenario.recreate()
            waitUntil("WebView route after activity recreation", 30_000) {
                evaluateJavascript(scenario,
                    "document.readyState === 'complete' && location.hash === '#androidtv-restored-page' && window.AndroidTV?.serverStatus() === 'ready'") == "true"
            }
            scenario.onActivity { activity ->
                activity.startActivity(MainActivity.localPageIntent(activity, "http://localhost:43211/#androidtv-callback"))
            }
            waitUntil("callback intent delivery to the existing activity", 30_000) {
                evaluateJavascript(scenario,
                    "location.hostname === '127.0.0.1' && location.hash === '#androidtv-callback' && window.AndroidTV?.serverStatus() === 'ready'") == "true"
            }
            assertEquals("true", evaluateJavascript(scenario,
                "history.replaceState({}, '', '/#androidtv-after-callback'); true"))
            scenario.recreate()
            waitUntil("callback is not replayed during recreation", 30_000) {
                evaluateJavascript(scenario,
                    "document.readyState === 'complete' && location.hash === '#androidtv-after-callback' && window.AndroidTV?.serverStatus() === 'ready'") == "true"
            }
        } finally {
            scenario.close()
        }

        try {
            assertTrue("server did not stop with its activity", waitForServerStatus("stopped", 20_000))

            Mobile.startServer(
                File(context.filesDir, "seanime/data").absolutePath,
                File(context.cacheDir, "seanime").absolutePath,
                43211,
            )
            assertTrue(
                "server did not restart: ${Mobile.serverStatus()} (${Mobile.serverError()})",
                Mobile.waitForServer(60_000),
            )
        } finally {
            Mobile.stopServer()
        }
        assertTrue("server cleanup did not finish", waitForServerStatus("stopped", 20_000))
    }

    private fun startSandboxedFrameBridgeProbe(scenario: ActivityScenario<MainActivity>) {
        val result = evaluateJavascript(
            scenario,
            """(() => {
                window.__seanimeTVFrameBridgeProbe = null;
                const frame = document.createElement('iframe');
                frame.setAttribute('sandbox', 'allow-scripts');
                const onMessage = (event) => {
                    if (event.source !== frame.contentWindow) return;
                    window.removeEventListener('message', onMessage);
                    const detail = event.data || {};
                    window.__seanimeTVFrameBridgeProbe = JSON.stringify(detail);
                    frame.remove();
                };
                window.addEventListener('message', onMessage);
                const lessThan = String.fromCharCode(60);
                const probe = '(function(){let token="readable";try{void parent.__seanimeAndroidTVBridgeToken}catch(e){token="blocked"}let nativeStatus="missing";try{const bridge=window.AndroidTVNativeBridge;if(bridge)nativeStatus=bridge.serverStatus("untrusted-frame")}catch(e){nativeStatus="blocked"}parent.postMessage({token:token,nativeStatus:nativeStatus,bootstrap:typeof window.AndroidTVBootstrap},"*")})()';
                frame.srcdoc = lessThan + 'script>' + probe + lessThan + '/script>';
                document.body.appendChild(frame);
                return 'started';
            })()""",
        )
        assertEquals("sandboxed frame probe did not start", "\"started\"", result)
    }

    private fun dpadNavigationProbe(): String =
        """(() => {
            if (!document.documentElement.classList.contains('android-tv')) return false;
            document.body.innerHTML = '<main><button id="tv-focus-start">Start</button><button id="tv-focus-right">Right</button><button id="tv-focus-down">Down</button></main>';
            const positions = {
                'tv-focus-start': 'left:64px;top:64px',
                'tv-focus-right': 'left:224px;top:64px',
                'tv-focus-down': 'left:224px;top:160px',
            };
            for (const [id, position] of Object.entries(positions)) {
                const button = document.getElementById(id);
                button.style.cssText = 'position:fixed;width:120px;height:64px;' + position;
            }
            document.getElementById('tv-focus-start').focus();
            document.dispatchEvent(new KeyboardEvent('keydown', {key:'ArrowRight',bubbles:true,cancelable:true}));
            const movedRight = document.activeElement?.id === 'tv-focus-right';
            document.dispatchEvent(new KeyboardEvent('keydown', {key:'ArrowDown',bubbles:true,cancelable:true}));
            const movedDown = document.activeElement?.id === 'tv-focus-down';

            const slider = document.createElement('div');
            slider.id = 'tv-focus-slider';
            slider.setAttribute('role', 'slider');
            slider.setAttribute('aria-orientation', 'horizontal');
            slider.tabIndex = 0;
            slider.style.cssText = 'position:fixed;width:120px;height:48px;left:224px;top:250px';
            document.body.appendChild(slider);
            let sliderReceivedArrow = false;
            slider.addEventListener('keydown', event => { sliderReceivedArrow = event.key === 'ArrowRight'; });
            slider.focus();
            const sliderArrow = new KeyboardEvent('keydown', {key:'ArrowRight',bubbles:true,cancelable:true});
            slider.dispatchEvent(sliderArrow);
            const sliderKeptItsArrow = document.activeElement?.id === 'tv-focus-slider' && sliderReceivedArrow && !sliderArrow.defaultPrevented;
            document.dispatchEvent(new KeyboardEvent('keydown', {key:'ArrowUp',bubbles:true,cancelable:true}));
            const couldLeaveSliderVertically = document.activeElement?.id === 'tv-focus-down';

            const menu = document.createElement('div');
            menu.setAttribute('role', 'menu');
            const menuItem = document.createElement('button');
            menuItem.id = 'tv-focus-menu-item';
            menuItem.setAttribute('role', 'menuitem');
            menu.appendChild(menuItem);
            document.body.appendChild(menu);
            let menuReceivedArrow = false;
            menuItem.addEventListener('keydown', event => { menuReceivedArrow = event.key === 'ArrowDown'; });
            menuItem.focus();
            const menuArrow = new KeyboardEvent('keydown', {key:'ArrowDown',bubbles:true,cancelable:true});
            menuItem.dispatchEvent(menuArrow);

            const dialog = document.createElement('div');
            dialog.setAttribute('role', 'dialog');
            dialog.setAttribute('aria-modal', 'true');
            dialog.style.cssText = 'position:fixed;width:160px;height:88px;left:480px;top:250px';
            const dialogButton = document.createElement('button');
            dialogButton.id = 'tv-focus-dialog-button';
            dialog.appendChild(dialogButton);
            document.body.appendChild(dialog);
            dialogButton.focus();
            document.dispatchEvent(new KeyboardEvent('keydown', {key:'ArrowLeft',bubbles:true,cancelable:true}));
            const focusStayedInDialog = document.activeElement?.id === 'tv-focus-dialog-button';

            return movedRight && movedDown && sliderKeptItsArrow && couldLeaveSliderVertically &&
                menuReceivedArrow && !menuArrow.defaultPrevented && focusStayedInDialog;
        })()"""

    private fun nativeDpadFixture(): String =
        """(() => {
            if (!document.documentElement.classList.contains('android-tv')) return false;
            document.body.innerHTML = '<main><button id="tv-native-focus-start">Start</button><button id="tv-native-focus-right">Right</button><button id="tv-native-focus-down">Down</button></main>';
            for (const [id, position] of Object.entries({
                'tv-native-focus-start': 'left:64px;top:64px',
                'tv-native-focus-right': 'left:224px;top:64px',
                'tv-native-focus-down': 'left:224px;top:160px',
            })) {
                document.getElementById(id).style.cssText = 'position:fixed;width:120px;height:64px;' + position;
            }
            document.getElementById('tv-native-focus-start').focus();
            return document.activeElement?.id === 'tv-native-focus-start';
        })()"""

    private fun evaluateJavascript(scenario: ActivityScenario<MainActivity>, script: String): String? {
        val result = AtomicReference<String?>()
        val completed = CountDownLatch(1)
        scenario.onActivity { activity ->
            val webView = findWebView(activity.window.decorView)
            if (webView == null) {
                result.set(null)
                completed.countDown()
            } else {
                webView.evaluateJavascript(script) { value ->
                    result.set(value)
                    completed.countDown()
                }
            }
        }
        assertTrue("WebView script evaluation timed out", completed.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    private fun hasWindowFocus(scenario: ActivityScenario<MainActivity>): Boolean {
        val result = AtomicReference(false)
        scenario.onActivity { activity -> result.set(activity.hasWindowFocus()) }
        return result.get()
    }

    private fun pressBackFromSystemUi() {
        val result = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent ${KeyEvent.KEYCODE_BACK}")
        ParcelFileDescriptor.AutoCloseInputStream(result).bufferedReader().use { it.readText() }
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount)
            .asSequence()
            .mapNotNull { index -> findWebView(view.getChildAt(index)) }
            .firstOrNull()
        else -> null
    }

    private fun waitUntil(description: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun waitForServerStatus(expected: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Mobile.serverStatus() == expected) return true
            Thread.sleep(100)
        }
        return Mobile.serverStatus() == expected
    }
}
