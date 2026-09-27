package app.seanime.tv

import android.content.Intent
import android.os.SystemClock
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
    fun configuredPlayerSchemeLaunchesAndReturnsToSeanime() {
        TestExternalPlayerIntent.reset()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val playerLink = "seanime-test://play?url=http%3A%2F%2F127.0.0.1%3A43211%2Fstream%2Ffixture"
        try {
            assertTrue("Seanime server did not become ready", waitForServerStatus("ready", 60_000))

            scenario.onActivity { it.openExternalUrl(playerLink) }

            assertTrue("Android did not deliver the configured player link", waitUntil(10_000) {
                TestExternalPlayerIntent.action == Intent.ACTION_VIEW && TestExternalPlayerIntent.uri == playerLink
            })

            assertTrue("Seanime did not regain window focus after the player returned", waitUntil(10_000) {
                var hasFocus = false
                scenario.onActivity { hasFocus = it.hasWindowFocus() && !it.isFinishing }
                hasFocus
            })
        } finally {
            scenario.close()
            Mobile.stopServer()
        }
        assertTrue("Seanime server did not stop after external-player handoff", waitForServerStatus("stopped", 20_000))
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
