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
    enum class Operation {
        LAUNCH,
        WAIT_NAVIGATION, VERIFY_POST_LAUNCH, GET_ACTIVITY_CLIENT, OPEN_MANAGE,
        WAIT_LIBRARY_READY, WAIT_FIRST_FILE, SCROLL_FIRST_FILE, CLICK_FIRST_FILE,
        SCROLL_SECOND_FILE, CLICK_SECOND_FILE, SCROLL_BULK_ACTIONS, OPEN_BULK,
        WAIT_BULK_FOCUS, SCROLL_BULK_IGNORE, CHOOSE_BULK_IGNORE, WAIT_BULK_CONFIRMATION,
        VERIFY_PRE_APPLY, FOCUS_BULK_APPLY, ASSERT_BULK_APPLY_FOCUS, APPLY_BULK_IGNORE,
        WAIT_BULK_CLOSED, WAIT_LIBRARY_AFTER_BULK, READ_BULK_RESULT, CAPTURE_BULK_RESULT,
        CLOSE,
    }
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

    /** Observes the original UI call without advancing clocks or changing its execution thread. */
    fun <T> observe(stage: Operation, block: () -> T): T {
        require(stage != Operation.LAUNCH && stage != Operation.CLOSE)
        return operation(stage, null, block)
    }

    /** ScrollBy queues test-dispatched work before Compose's synchronous bounds refetch. */
    fun <T> withClockPumping(block: () -> T): T {
        check(Thread.currentThread() === instrumentationThread)
        return withOwnedTestClockPump(
            advanceFrame = { compose.mainClock.advanceTimeByFrame() },
            action = block,
        )
    }

    private fun <T> operation(kind: Operation, worker: Thread?, block: () -> T): T {
        val step = Active(kind, SystemClock.uptimeMillis())
        synchronized(outputLock) {
            check(active.compareAndSet(null, step))
            emit("START $kind")
        }
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
            snapshots.forEach { it.cancel(false) }
            synchronized(outputLock) {
                active.compareAndSet(step, null)
                emit("$outcome $kind elapsedMs=${SystemClock.uptimeMillis() - step.startedAt}")
            }
        }
    }

    private fun snapshot(step: Active, worker: Thread?) {
        if (active.get() !== step) return
        val message = runCatching {
            buildString {
                appendLine("SNAPSHOT ${step.operation} elapsedMs=${SystemClock.uptimeMillis() - step.startedAt}")
                // Only these exact thread references are inspected. Never enumerate threads,
                // print thread names, or include exception messages / request data.
                (listOf("MAIN" to mainThread, "INSTRUMENTATION" to instrumentationThread) +
                    listOfNotNull(worker?.let { "LAUNCH" to it }))
                    .forEach { (role, thread) ->
                        appendLine("ROLE $role state=${thread.state}")
                        thread.stackTrace.take(96).forEach { frame ->
                            appendLine("  ${frame.className}.${frame.methodName}:${frame.lineNumber}")
                        }
                    }
            }
        }.getOrElse { "SNAPSHOT_UNAVAILABLE ${step.operation} ${it.javaClass.simpleName}" }
        synchronized(outputLock) {
            if (active.get() === step) emit(message)
        }
    }

    private fun emit(message: String) {
        synchronized(outputLock) {
            message.lineSequence().forEach { Log.i("NativeScenarioClock", it) }
            runCatching { output.appendText("$message\n") }
                .onFailure { Log.w("NativeScenarioClock", "Snapshot file write failed: ${it.javaClass.simpleName}") }
        }
    }

    override fun close() {
        synchronized(outputLock) { active.set(null) }
        watchdog.shutdownNow()
    }
}
