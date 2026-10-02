package app.seanime.tv

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.ComposeTestRule
import java.io.Closeable
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives queued Compose effects while ActivityScenario waits off the instrumentation thread.
 * A blocked main thread can also block clock advancement beyond waitUntil's wall-clock budget;
 * the isolated runner's external force-stop remains the final cleanup boundary.
 */
internal class NativeScenarioClockDriver(
    private val compose: ComposeTestRule,
    private val output: File,
) : Closeable {
    private enum class Operation { LAUNCH, CLOSE }
    private data class Active(val operation: Operation, val startedAt: Long)
    private val mainThread = Looper.getMainLooper().thread
    private val instrumentationThread = Thread.currentThread()
    private val active = AtomicReference<Active?>()
    private val outputLock = Any()
    private val watchdog = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "NativeScenarioClockWatchdog").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    init { output.writeText("SCHEMA native-scenario-clock-v1 startedAtMs=${System.currentTimeMillis()}\n") }

    fun <T : Any> awaitLaunch(owner: OwnedAsyncTestResource<T>): T =
        operation(Operation.LAUNCH, owner.worker) {
            // The predicate deliberately avoids semantics/idling: the clock must run
            // before the synchronous framework launch can return.
            compose.waitUntil(45_000) { owner.launchCompleted }
            owner.borrow()
        }

    fun <T : Any> awaitClose(owner: OwnedAsyncTestResource<T>) {
        owner.requestClose()
        operation(Operation.CLOSE, owner.worker) {
            compose.waitUntil(45_000) { owner.cleanupCompleted }
            owner.requireCleanupComplete()
        }
    }

    private fun <T> operation(kind: Operation, worker: Thread, block: () -> T): T {
        val step = Active(kind, SystemClock.uptimeMillis())
        check(active.compareAndSet(null, step))
        emit("START $kind")
        val snapshots = listOf(15L, 30L).map { seconds ->
            watchdog.schedule({ snapshot(step, worker) }, seconds, TimeUnit.SECONDS)
        }
        var outcome = "DONE"
        try {
            return block()
        } catch (failure: Throwable) {
            outcome = "FAILED ${failure.javaClass.simpleName}"
            throw failure
        } finally {
            active.compareAndSet(step, null)
            snapshots.forEach { it.cancel(false) }
            emit("$outcome $kind elapsedMs=${SystemClock.uptimeMillis() - step.startedAt}")
        }
    }

    private fun snapshot(step: Active, worker: Thread) {
        if (active.get() !== step) return
        val message = runCatching {
            buildString {
                appendLine("SNAPSHOT ${step.operation} elapsedMs=${SystemClock.uptimeMillis() - step.startedAt}")
                // Only these exact thread references are inspected. Never enumerate threads,
                // print thread names, or include exception messages / request data.
                listOf("MAIN" to mainThread, "INSTRUMENTATION" to instrumentationThread, "LAUNCH" to worker)
                    .forEach { (role, thread) ->
                        appendLine("ROLE $role state=${thread.state}")
                        thread.stackTrace.take(96).forEach { frame ->
                            appendLine("  ${frame.className}.${frame.methodName}:${frame.lineNumber}")
                        }
                    }
            }
        }.getOrElse { "SNAPSHOT_UNAVAILABLE ${step.operation} ${it.javaClass.simpleName}" }
        if (active.get() === step) emit(message)
    }

    private fun emit(message: String) {
        synchronized(outputLock) {
            message.lineSequence().forEach { Log.i("NativeScenarioClock", it) }
            runCatching { output.appendText("$message\n") }
                .onFailure { Log.w("NativeScenarioClock", "Snapshot file write failed: ${it.javaClass.simpleName}") }
        }
    }

    override fun close() {
        active.set(null)
        watchdog.shutdownNow()
    }
}
