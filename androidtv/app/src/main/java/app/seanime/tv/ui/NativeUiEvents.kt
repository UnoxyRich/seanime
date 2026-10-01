package app.seanime.tv.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** Keep native state and layout callbacks off the transport's emitter thread. */
internal suspend fun <T> Flow<T>.collectOnMain(consume: suspend (T) -> Unit) {
    collect { event ->
        // Shared-flow continuations can resume on the emitter under an unconfined
        // dispatcher. Navigation may remeasure immediately; mutable UI catalogs
        // also require one owning thread, including in instrumentation tests.
        withContext(Dispatchers.Main.immediate) { consume(event) }
    }
}
