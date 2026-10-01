package app.seanime.tv.ui

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Test-only diagnostics. Never requests UI work or waits for the main looper to become idle. */
internal class NativeUiStepWatchdog(name: String) : Closeable {
    private data class Step(val name: String, val startedAt: Long)

    private val testThread = Thread.currentThread()
    private val mainThread = Looper.getMainLooper().thread
    private val active = AtomicReference<Step?>()
    private val closed = AtomicBoolean(false)
    private val outputLock = Any()
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "NativeUiStepWatchdog").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val output = runCatching {
        require(name.matches(Regex("[a-z0-9_-]+")))
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "native-test-diagnostics")
            .apply { check(isDirectory || mkdirs()) }
            .resolve("$name.txt").apply { writeText("") }
    }.onFailure { Log.w(TAG, "Private diagnostic file unavailable: ${it.javaClass.simpleName}") }.getOrNull()

    init {
        emit("BEGIN $name pid=${android.os.Process.myPid()} epochMs=${System.currentTimeMillis()}")
    }

    fun <T> step(name: String, block: () -> T): T {
        check(!closed.get())
        val step = Step(name, SystemClock.uptimeMillis())
        check(active.compareAndSet(null, step)) { "Diagnostic steps must not overlap" }
        emit("START ${step.name}")
        // Two one-shot tasks per step, not a recurring timer. A later step gets a fresh budget.
        val snapshots = listOf(15L, 30L).map { seconds ->
            executor.schedule({ dumpIfStillActive(step) }, seconds, TimeUnit.SECONDS)
        }
        var outcome = "DONE"
        try {
            return block()
        } catch (error: Throwable) {
            outcome = "FAILED ${error.javaClass.simpleName}"
            throw error
        } finally {
            active.compareAndSet(step, null)
            snapshots.forEach { it.cancel(false) }
            emit("$outcome ${step.name} elapsedMs=${SystemClock.uptimeMillis() - step.startedAt}")
        }
    }

    private fun dumpIfStillActive(step: Step) {
        if (closed.get() || active.get() !== step) return
        runCatching {
            val stacks = Thread.getAllStackTraces()
            if (closed.get() || active.get() !== step) return
            val relevant = stacks.entries.filter { (thread, stack) ->
                thread === mainThread || thread === testThread ||
                    listOf("Compose", "Recomposer", "Espresso", "Instrumentation", "AndroidJUnit")
                        .any { thread.name.contains(it, ignoreCase = true) } ||
                    stack.any { it.className.startsWith("androidx.compose.") || it.className.startsWith("androidx.test.espresso.") }
            }.sortedWith(compareBy({ if (it.key === mainThread) 0 else if (it.key === testThread) 1 else 2 }, { it.key.id }))
            emit(buildString {
                appendLine("STALLED ${step.name} elapsedMs=${SystemClock.uptimeMillis() - step.startedAt} threads=${relevant.size}")
                relevant.take(16).forEach { (thread, stack) ->
                    appendLine("THREAD ${thread.name.take(100)} id=${thread.id} state=${thread.state}")
                    stack.take(256).forEach { appendLine("  at $it") }
                    if (stack.size > 256) appendLine("  [remaining frames omitted]")
                }
                if (relevant.size > 16) appendLine("[remaining relevant threads omitted]")
                append("END STACK SNAPSHOT")
            })
        }.onFailure { Log.w(TAG, "Stack snapshot unavailable: ${it.javaClass.simpleName}") }
    }

    private fun emit(message: String) {
        // Only fixed step names, thread identities and stack frames; no view contents or HTTP data.
        synchronized(outputLock) {
            message.lineSequence().forEach { Log.i(TAG, it) }
            runCatching { output?.appendText("$message\n") }
                .onFailure { Log.w(TAG, "Private diagnostic write failed: ${it.javaClass.simpleName}") }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            active.set(null)
            executor.shutdownNow()
            emit("END watchdog")
        }
    }

    private companion object { const val TAG = "NativeUiStepWatchdog" }
}
