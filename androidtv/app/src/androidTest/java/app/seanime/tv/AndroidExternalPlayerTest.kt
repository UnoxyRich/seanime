package app.seanime.tv

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.seanime.tv.gomobile.mobile.Mobile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidExternalPlayerTest {
    @Test
    fun configuredPlayerSchemeLaunchesAndReturnsToNativeCompose() {
        TestExternalPlayerIntent.reset()
        val playerLink = "seanime-test://play?url=http%3A%2F%2F127.0.0.1%3A43211%2Fstream%2Ffixture"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(playerLink)).addCategory(Intent.CATEGORY_BROWSABLE)
        val candidates = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue("The debug fixture must resolve the production browsable player intent", candidates.any {
            it.activityInfo.packageName == context.packageName && it.activityInfo.name == TestExternalPlayerActivity::class.java.name
        })
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue("Seanime server did not become ready", waitForServerStatus("ready", 60_000))

            scenario.onActivity {
                assertNativeHost(it.window.decorView)
                it.openExternalUrl(playerLink)
            }

            assertTrue("Android did not deliver the configured player link", waitUntil(10_000) {
                TestExternalPlayerIntent.action == Intent.ACTION_VIEW && TestExternalPlayerIntent.uri == playerLink
            })

            assertTrue("Seanime did not regain window focus after the player returned", waitUntil(10_000) {
                var hasFocus = false
                scenario.onActivity { hasFocus = it.hasWindowFocus() && !it.isFinishing }
                hasFocus
            })
            scenario.onActivity { assertNativeHost(it.window.decorView) }
        } finally {
            scenario.close()
            Mobile.stopServer()
        }
        assertTrue("Seanime server did not stop after external-player handoff", waitForServerStatus("stopped", 20_000))
    }

    private fun assertNativeHost(root: View) {
        val pending = java.util.ArrayDeque<View>()
        pending.add(root)
        var composeFound = false
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            assertTrue("External player return must never recreate a UI WebView", view !is WebView)
            if (view is ComposeView) composeFound = true
            if (view is ViewGroup) for (index in 0 until view.childCount) pending.add(view.getChildAt(index))
        }
        assertTrue("Native Compose host was not restored after external playback", composeFound)
    }

    private fun waitForServerStatus(expected: String, timeoutMs: Long): Boolean = waitUntil(timeoutMs) {
        Mobile.serverStatus() == expected
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (condition()) return true
            SystemClock.sleep(50)
        }
        return condition()
    }
}
