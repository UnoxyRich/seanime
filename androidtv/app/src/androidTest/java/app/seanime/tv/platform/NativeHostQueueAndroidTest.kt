package app.seanime.tv.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeHostQueueAndroidTest {
    @Test fun androidMainLooperRemainsResponsiveWhileRuntimeInitializationIsBlocked() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var host: NativeHostQueue? = null
        try {
            instrumentation.runOnMainSync {
                host = NativeHostQueue { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
            }
            assertTrue("Host initializer did not begin", entered.await(10, TimeUnit.SECONDS))
            var uiDispatched = false
            instrumentation.runOnMainSync { uiDispatched = true }
            assertTrue("The UI looper must continue dispatching while Go initialization is pending", uiDispatched)
            val next = requireNotNull(host).submit { "ready" }
            assertFalse("Dependent startup ran before initialization", next.isDone)
            release.countDown()
            assertEquals("ready", next.get(10, TimeUnit.SECONDS))
        } finally { release.countDown(); host?.close() }
    }
}
