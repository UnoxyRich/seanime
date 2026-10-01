package app.seanime.tv.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NativePlaybackRequestTest {
    @Test fun `late online response cannot replace a newer source or same-source request`() = runBlocking {
        val first = NativePlaybackRequest(1, "a", "https://a.example/episode")
        var active = first
        val delayed = CompletableDeferred<String>()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            NativePlaybackRequest.awaitResponse({ active == first }, { delayed.await() })
        }
        active = NativePlaybackRequest(2, "b", "https://b.example/episode")
        delayed.complete("late-a-source")
        assertNull(request.await())
        val newerSameSource = first.copy(generation = 3)
        active = newerSameSource
        assertNull(NativePlaybackRequest.awaitResponse({ active == first }, { "older-a-source" }))
        assertEquals("current-a-source", NativePlaybackRequest.awaitResponse({ active == newerSameSource }, { "current-a-source" }))
        assertNull(NativePlaybackRequest.awaitResponse({ active == newerSameSource.copy(sourceUrl = "https://other.example/episode") }, { "wrong-source" }))
    }

    @Test fun `obsolete conversion cleans up only the session returned by its own response`() = runBlocking {
        val previous = NativePlaybackRequest(1, "a", "https://a.example/episode")
        var active = previous
        var conversionRequest = 1
        val stopSessions = mutableListOf<String>()
        val response = CompletableDeferred<String>()
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            NativePlaybackRequest.awaitResponse({ active == previous && conversionRequest == 1 }, { response.await() }, { stopSessions.add(it) })
        }
        active = NativePlaybackRequest(2, "b", "https://b.example/episode")
        conversionRequest = 2
        val currentSession = "session-b"
        response.complete("session-a")
        assertNull(old.await())
        assertEquals(listOf("session-a"), stopSessions)
        assertFalse(stopSessions.contains(currentSession))
    }

    @Test fun `obsolete network errors do not surface as errors for the new source`() = runBlocking {
        var current = true
        val delayed = CompletableDeferred<String>()
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            NativePlaybackRequest.awaitResponse({ current }, { delayed.await() })
        }
        current = false
        delayed.completeExceptionally(IOException("Old source failed"))
        assertNull(old.await())
        try {
            NativePlaybackRequest.awaitResponse({ true }, { throw IOException("Current source failed") })
            fail("A current source error was swallowed")
        } catch (expected: IOException) { assertEquals("Current source failed", expected.message) }
    }
}
