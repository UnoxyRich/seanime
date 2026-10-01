package app.seanime.tv.platform

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import org.junit.Assert.*
import org.junit.Test

class NativeHostQueueTest {
    @Test fun initializationDoesNotBlockCallerAndWorkWaitsUntilReady() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val work = CountDownLatch(1)
        val caller = Thread.currentThread()
        NativeHostQueue {
            assertNotEquals(caller, Thread.currentThread())
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS))
        }.use { queue ->
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val pending = queue.submit { work.countDown() }
            assertFalse(pending.isDone)
            assertEquals(1, work.count)
            release.countDown()
            pending.get(5, TimeUnit.SECONDS)
            assertEquals(0, work.count)
        }
    }
    @Test fun quickReplacementIgnoresOldActivityStopAndOrdersNewStartup() {
        val calls = mutableListOf<String>()
        val release = CountDownLatch(1)
        NativeHostQueue { calls += "initialize" }.use { queue ->
            val first = queue.claimOwner()
            assertTrue(queue.runForOwner(first) { calls += "start first" }.get(5, TimeUnit.SECONDS))
            queue.submit { release.await(5, TimeUnit.SECONDS) }
            val staleStop = queue.stopForOwner(first) { calls += "stop first" }
            val replacement = queue.claimOwner()
            val nextStart = queue.runForOwner(replacement) { calls += "start replacement" }
            release.countDown()
            assertFalse(staleStop.get(5, TimeUnit.SECONDS))
            assertTrue(nextStart.get(5, TimeUnit.SECONDS))
            assertTrue(queue.stopForOwner(replacement) { calls += "stop replacement" }.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("initialize", "start first", "start replacement", "stop replacement"), calls)
        }
    }
    @Test fun finishingDuringInitializationRejectsLateStartupAndAllowsReplacement() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = mutableListOf<String>()
        NativeHostQueue {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            calls += "initialize"
        }.use { queue ->
            val finished = queue.claimOwner()
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val stopped = queue.stopForOwner(finished) { calls += "stop finished" }
                assertFalse("Lifecycle shutdown must return while initialization is pending", stopped.isDone)
                release.countDown()
                assertTrue(stopped.get(5, TimeUnit.SECONDS))
                // The canceled IO coroutine can wake from its blocking readiness
                // wait after shutdown was queued or even completed.
                assertFalse(queue.runForOwner(finished) { calls += "late start" }.get(5, TimeUnit.SECONDS))
                val replacement = queue.claimOwner()
                assertTrue(queue.runForOwner(replacement) { calls += "start replacement" }.get(5, TimeUnit.SECONDS))
                assertEquals(listOf("initialize", "stop finished", "start replacement"), calls)
            } finally { release.countDown() }
        }
    }
    @Test fun finishingRevokesStartupAlreadyQueuedBehindInitialization() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = mutableListOf<String>()
        NativeHostQueue {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }.use { queue ->
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val owner = queue.claimOwner()
                val pendingStart = queue.runForOwner(owner) { calls += "start" }
                val stopped = queue.stopForOwner(owner) { calls += "stop" }
                release.countDown()
                assertFalse(pendingStart.get(5, TimeUnit.SECONDS))
                assertTrue(stopped.get(5, TimeUnit.SECONDS))
                assertFalse(queue.stopForOwner(owner) { calls += "duplicate stop" }.get(5, TimeUnit.SECONDS))
                assertEquals(listOf("stop"), calls)
            } finally { release.countDown() }
        }
    }
    @Test fun finishingOrdersShutdownAfterRunningStartupWithoutBlockingCaller() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = mutableListOf<String>()
        NativeHostQueue {}.use { queue ->
            try {
                val owner = queue.claimOwner()
                val startup = queue.runForOwner(owner) {
                    calls += "start"
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val stopped = queue.stopForOwner(owner) { calls += "stop" }
                assertFalse(stopped.isDone)
                release.countDown()
                assertTrue(startup.get(5, TimeUnit.SECONDS))
                assertTrue(stopped.get(5, TimeUnit.SECONDS))
                assertFalse(queue.runForOwner(owner) { calls += "restart" }.get(5, TimeUnit.SECONDS))
                assertEquals(listOf("start", "stop"), calls)
            } finally { release.countDown() }
        }
    }
    @Test fun obsoleteShutdownRequestCannotRetireTheReplacementOwner() {
        NativeHostQueue {}.use { queue ->
            val previous = queue.claimOwner()
            val replacement = queue.claimOwner()
            assertFalse(queue.stopForOwner(previous) { fail("Obsolete owner stopped the host") }.get(5, TimeUnit.SECONDS))
            assertTrue(queue.runForOwner(replacement) {}.get(5, TimeUnit.SECONDS))
        }
    }
    @Test fun initializationFailurePreventsDependentServerWork() {
        NativeHostQueue { error("fixture initialization failure") }.use { queue ->
            var called = false
            val result = runCatching { queue.submit { called = true }.get(5, TimeUnit.SECONDS) }
            assertTrue(result.exceptionOrNull() is ExecutionException)
            assertFalse(called)
        }
    }
}
