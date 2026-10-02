package app.seanime.tv

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class OwnedTestClockPumpTest {
    @Test fun actionStaysOnCallerAndAllFrameWorkFinishesBeforeReturning() {
        val caller = Thread.currentThread()
        val advanced = CountDownLatch(1)
        val pump = AtomicReference<Thread>()
        val answer = withOwnedTestClockPump(advanceFrame = {
            pump.set(Thread.currentThread()); advanced.countDown()
        }) {
            assertSame(caller, Thread.currentThread())
            assertTrue(advanced.await(5, TimeUnit.SECONDS))
            42
        }
        assertEquals(42, answer)
        assertNotSame(caller, pump.get())
        assertFalse(pump.get().isAlive)
    }

    @Test fun aSuccessfulActionCannotProceedToTeardownWhileAFrameIsStillRunning() {
        verifyFrameOwnership(actionFails = false, interruptWaiter = false)
    }

    @Test fun aFailedActionCannotProceedToTeardownWhileAFrameIsStillRunning() {
        verifyFrameOwnership(actionFails = true, interruptWaiter = false)
    }

    @Test fun interruptingTheJoinCannotReleaseAnInFlightFrameIntoTeardown() {
        verifyFrameOwnership(actionFails = false, interruptWaiter = true)
    }

    @Test fun pumpDeadlineStopsNewFramesWithoutCancellingTheCallingAction() {
        val clock = AtomicLong()
        val advanced = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val actionReturned = AtomicBoolean(false)
        val frames = AtomicInteger()
        val pump = AtomicReference<Thread>()
        val failure = AtomicReference<Throwable?>()
        val caller = Thread {
            try {
                withOwnedTestClockPump(advanceFrame = {
                    pump.set(Thread.currentThread())
                    frames.incrementAndGet(); clock.set(TimeUnit.MILLISECONDS.toNanos(45_000))
                    advanced.countDown()
                }, nanoTime = clock::get, pause = { error("Budget already expired") }) {
                    check(releaseAction.await(5, TimeUnit.SECONDS))
                }
                actionReturned.set(true)
            } catch (error: Throwable) { failure.set(error) }
        }
        caller.start()
        try {
            assertTrue(advanced.await(5, TimeUnit.SECONDS))
            pump.get().join(5_000)
            assertFalse(pump.get().isAlive)
            assertEquals(1, frames.get())
            assertFalse(actionReturned.get())
        } finally { releaseAction.countDown(); caller.join(5_000) }
        assertFalse(caller.isAlive)
        assertNull(failure.get())
        assertTrue(actionReturned.get())
    }

    @Test fun pumpFailureIsReportedWhenTheActionSucceeds() {
        val advanced = CountDownLatch(1)
        val failure = IOException("clock failure")
        assertSame(failure, assertThrows(IOException::class.java) {
            withOwnedTestClockPump(advanceFrame = { advanced.countDown(); throw failure }) {
                assertTrue(advanced.await(5, TimeUnit.SECONDS))
            }
        })
    }

    @Test fun actionFailureRemainsPrimaryWhenThePumpAlsoFails() {
        val advanced = CountDownLatch(1)
        val actionFailure = AssertionError("action failure")
        val pumpFailure = IOException("clock failure")
        assertSame(actionFailure, assertThrows(AssertionError::class.java) {
            withOwnedTestClockPump(advanceFrame = { advanced.countDown(); throw pumpFailure }) {
                assertTrue(advanced.await(5, TimeUnit.SECONDS))
                throw actionFailure
            }
        })
        assertArrayEquals(arrayOf(pumpFailure), actionFailure.suppressed)
    }

    private fun verifyFrameOwnership(actionFails: Boolean, interruptWaiter: Boolean) {
        val frameStarted = CountDownLatch(1)
        val releaseFrame = CountDownLatch(1)
        val actionFinished = CountDownLatch(1)
        val teardownStarted = CountDownLatch(1)
        val teardown = AtomicBoolean(false)
        val frameFinished = AtomicBoolean(false)
        val interruptPreserved = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val original = AssertionError("original action failure")
        val caller = Thread {
            try {
                withOwnedTestClockPump(advanceFrame = {
                    frameStarted.countDown()
                    check(releaseFrame.await(5, TimeUnit.SECONDS))
                    frameFinished.set(true)
                }) {
                    assertTrue(frameStarted.await(5, TimeUnit.SECONDS))
                    actionFinished.countDown()
                    if (actionFails) throw original
                }
            } catch (error: Throwable) { failure.set(error) }
            finally {
                interruptPreserved.set(Thread.currentThread().isInterrupted)
                teardown.set(true)
                teardownStarted.countDown()
            }
        }
        caller.start()
        try {
            assertTrue(actionFinished.await(5, TimeUnit.SECONDS))
            if (interruptWaiter) caller.interrupt()
            assertFalse(frameFinished.get())
            assertFalse("Teardown raced the in-flight frame", teardownStarted.await(100, TimeUnit.MILLISECONDS))
            assertFalse(teardown.get())
        } finally { releaseFrame.countDown(); caller.join(5_000) }
        assertFalse(caller.isAlive)
        assertTrue(frameFinished.get())
        assertTrue(teardown.get())
        if (actionFails) assertSame(original, failure.get()) else assertNull(failure.get())
        if (interruptWaiter) assertTrue(interruptPreserved.get())
    }
}
