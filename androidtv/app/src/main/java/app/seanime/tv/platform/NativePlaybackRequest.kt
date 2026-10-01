package app.seanime.tv.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Identity captured before an asynchronous source lookup, rather than when it finishes. */
data class NativePlaybackRequest(val generation: Int, val playbackId: String, val sourceUrl: String) {
    companion object {
        suspend fun <T : Any> awaitResponse(
            isCurrent: () -> Boolean,
            fetch: suspend () -> T,
            discard: suspend (T) -> Unit = {},
        ): T? {
            val response = try { fetch() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (isCurrent()) throw error else return null }
            coroutineContext.ensureActive()
            if (!isCurrent()) { discard(response); return null }
            return response
        }
    }
}
