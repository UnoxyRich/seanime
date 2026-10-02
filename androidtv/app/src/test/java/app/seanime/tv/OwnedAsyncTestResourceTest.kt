package app.seanime.tv

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

class OwnedAsyncTestResourceTest {
    @Test fun readyResourceIsBorrowedAndDisposedOnItsOwningWorkerExactlyOnce() {
        val resource = Any()
        val creator = AtomicReference<Thread>()
        val disposer = AtomicReference<Thread>()
        val closes = AtomicInteger()
        val owner = OwnedAsyncTestResource(create = {
            creator.set(Thread.currentThread()); resource
        }, dispose = {
            assertSame(resource, it)
            disposer.set(Thread.currentThread()); closes.incrementAndGet()
        })
        try {
            awaitLaunch(owner)
            assertSame(resource, owner.borrow())
            assertFalse(owner.cleanupCompleted)
            assertNotSame(Thread.currentThread(), creator.get())
        } finally { finish(owner) }
        owner.requestClose()
        assertEquals(1, closes.get())
        assertSame(creator.get(), disposer.get())
    }

    @Test fun abandonmentWhileCreationIsBlockedClosesTheLateResultWithoutBorrowingIt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val owner = OwnedAsyncTestResource(create = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); Any()
        }, dispose = { closes.incrementAndGet() })
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            owner.requestClose()
            assertFalse(owner.launchCompleted)
            assertFalse(owner.cleanupCompleted)
            release.countDown()
            finish(owner)
            assertThrows(IllegalStateException::class.java) { owner.borrow() }
            assertEquals(1, closes.get())
        } finally { release.countDown(); finish(owner) }
    }

    @Test fun closingAPublishedButUnclaimedResultRetainsCleanupOwnership() {
        val closes = AtomicInteger()
        val owner = OwnedAsyncTestResource(create = { Any() }, dispose = { closes.incrementAndGet() })
        try {
            awaitLaunch(owner)
            owner.requestClose()
            assertThrows(IllegalStateException::class.java) { owner.borrow() }
        } finally { finish(owner) }
        assertEquals(1, closes.get())
    }

    @Test fun racingCloseRequestsAndCreationStillDisposeExactlyOnce() {
        repeat(25) {
            val release = CountDownLatch(1)
            val closes = AtomicInteger()
            val owner = OwnedAsyncTestResource(create = {
                check(release.await(5, TimeUnit.SECONDS)); Any()
            }, dispose = { closes.incrementAndGet() })
            val closers = List(4) { Thread { release.await(); owner.requestClose() } }
            try {
                closers.forEach(Thread::start)
                release.countDown()
                closers.forEach { it.join(5_000); assertFalse(it.isAlive) }
                finish(owner)
                assertEquals(1, closes.get())
            } finally { release.countDown(); finish(owner) }
        }
    }

    @Test fun failedCreationPreservesTheOriginalFailureAndHasNothingToDispose() {
        val failure = IOException("creation failure")
        val closes = AtomicInteger()
        val owner = OwnedAsyncTestResource<Any>(create = { throw failure }, dispose = { closes.incrementAndGet() })
        try {
            awaitLaunch(owner)
            assertSame(failure, assertThrows(IOException::class.java) { owner.borrow() })
            assertTrue(owner.cleanupCompleted)
        } finally { finish(owner) }
        assertEquals(0, closes.get())
    }

    @Test fun aBlockedDisposerRemainsPendingAndItsFailureIsNotReportedAsSuccess() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = IOException("cleanup failure")
        val owner = OwnedAsyncTestResource(create = { Any() }, dispose = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); throw failure
        })
        try {
            awaitLaunch(owner)
            owner.requestClose()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            owner.requestClose()
            assertFalse(owner.cleanupCompleted)
            assertThrows(IllegalStateException::class.java) { owner.requireCleanupComplete() }
        } finally { release.countDown(); owner.requestClose(); owner.worker.join(5_000) }
        assertFalse(owner.worker.isAlive)
        assertSame(failure, assertThrows(IOException::class.java) { owner.requireCleanupComplete() })
    }

    private fun awaitLaunch(owner: OwnedAsyncTestResource<*>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!owner.launchCompleted && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000)
        assertTrue("Creation did not complete", owner.launchCompleted)
    }

    private fun finish(owner: OwnedAsyncTestResource<*>) {
        owner.requestClose()
        owner.worker.join(5_000)
        assertFalse("Owned worker did not finish", owner.worker.isAlive)
        owner.requireCleanupComplete()
    }
}
