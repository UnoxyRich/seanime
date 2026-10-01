package app.seanime.tv.platform

import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Process-scoped Android host ordering. The Go runtime and its API remain unchanged. */
internal class NativeHostQueue(initialize: () -> Unit) : Closeable {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "seanime-android-host").apply { isDaemon = true } }
    private data class Ownership(val id: Long, val retired: Boolean = false)
    private val ownership = AtomicReference(Ownership(0))
    private val initialization = executor.submit(Callable { initialize() })

    fun claimOwner(): Long {
        while (true) {
            val previous = ownership.get()
            val next = Ownership(previous.id + 1)
            if (ownership.compareAndSet(previous, next)) return next.id
        }
    }

    fun awaitInitialized() { initialization.get(90, TimeUnit.SECONDS) }

    fun <T> submit(action: () -> T): Future<T> = executor.submit(Callable {
        // Initialization is first in this queue; this only propagates its error.
        initialization.get()
        action()
    })

    /** Only the current, unfinished Activity may start or otherwise use the host. */
    fun runForOwner(expectedOwner: Long, action: () -> Unit): Future<Boolean> = submit {
        val current = ownership.get()
        if (current.id != expectedOwner || current.retired) false else { action(); true }
    }

    /**
     * Revoke startup authority immediately, even while initialization is blocked.
     * Already-running work finishes before this stop; a replacement owner makes
     * the old stop obsolete. No host work is awaited by the lifecycle caller.
     */
    fun stopForOwner(expectedOwner: Long, action: () -> Unit): Future<Boolean> {
        val current = ownership.get()
        val retired = Ownership(expectedOwner, retired = true)
        val claimed = current.id == expectedOwner && !current.retired && ownership.compareAndSet(current, retired)
        return submit {
            if (!claimed || ownership.get() !== retired) false else { action(); true }
        }
    }

    override fun close() { executor.shutdownNow() }
}
