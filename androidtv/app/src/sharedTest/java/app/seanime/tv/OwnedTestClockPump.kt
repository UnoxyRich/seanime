package app.seanime.tv

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps the action on its calling thread while a temporary worker advances a test clock.
 * The budget limits new frame requests; it cannot cancel a blocked action or an in-flight
 * frame request. Neither the next action nor test teardown may run until this worker exits.
 * An unresponsive framework call therefore still requires the isolated runner's force-stop.
 */
internal fun <T> withOwnedTestClockPump(
    advanceFrame: () -> Unit,
    timeoutMillis: Long = 45_000,
    nanoTime: () -> Long = System::nanoTime,
    pause: () -> Unit = { Thread.sleep(10) },
    action: () -> T,
): T {
    require(timeoutMillis in 1L..45_000L)
    val stopping = AtomicBoolean(false)
    val pumpFailure = AtomicReference<Throwable?>()
    val worker = Thread({
        try {
            val start = nanoTime()
            val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (!stopping.get() && nanoTime() - start < budget) {
                advanceFrame()
                if (!stopping.get() && nanoTime() - start < budget) pause()
            }
        } catch (failure: Throwable) {
            pumpFailure.set(failure)
        }
    }, "OwnedTestClockPump").apply { isDaemon = true }
    worker.start()
    var actionFailure: Throwable? = null
    try {
        return action()
    } catch (failure: Throwable) {
        actionFailure = failure
        throw failure
    } finally {
        stopping.set(true)
        // Interruption is not proof that runOnMainSync finished. Retain ownership until
        // actual termination, rather than racing a pending frame against Compose teardown.
        var interrupted = false
        while (worker.isAlive) {
            try { worker.join() }
            catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
        pumpFailure.get()?.let { failure ->
            val original = actionFailure
            if (original == null) throw failure
            if (original !== failure) original.addSuppressed(failure)
        }
    }
}
