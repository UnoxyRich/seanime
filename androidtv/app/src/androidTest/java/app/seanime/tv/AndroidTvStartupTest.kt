package app.seanime.tv

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.seanime.tv.gomobile.mobile.Mobile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidTvStartupTest {
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
                        "window.AndroidTV?.serverStatus() === 'ready' && " +
                        "window.AndroidTVNativeBridge?.serverStatus('invalid-token') === ''",
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

            assertTrue(
                "Android TV arrow keys did not move focus spatially",
                evaluateJavascript(scenario, dpadNavigationProbe()) == "true",
            )
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
                const probe = '(function(){let token="readable";try{void parent.__seanimeAndroidTVBridgeToken}catch(e){token="blocked"}let nativeStatus="missing";try{const bridge=window.AndroidTVNativeBridge;if(bridge)nativeStatus=bridge.serverStatus("untrusted-frame")}catch(e){nativeStatus="blocked"}parent.postMessage({token:token,nativeStatus:nativeStatus},"*")})()';
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
