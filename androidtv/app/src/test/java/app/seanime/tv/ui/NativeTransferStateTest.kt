package app.seanime.tv.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeTransferStateTest {
    @Test fun onlyFreshAllErroredQueuesCanBeCleared() {
        fun rows(vararg states: String) = states.map { JSONObject().put("status", it) }
        assertNull(mangaQueueClearBlocker(rows("errored", "errored"), true))
        listOf("downloading", "not_started", "background_paused", "unknown", "").forEach { state ->
            assertNotNull(state, mangaQueueClearBlocker(rows("errored", state), true))
        }
        assertNotNull(mangaQueueClearBlocker(rows("errored"), false))
        assertNotNull(mangaQueueClearBlocker(emptyList(), true))
    }

    @Test fun stoppedRowsDoNotProveThatCanceledWorkersHaveFinished() {
        val row = JSONObject().put("status", "downloading")
        assertNotNull(mangaQueueClearBlocker(listOf(row), true))
        row.put("status", "not_started") // Stop writes this before worker join.
        assertTrue(mangaQueueClearBlocker(listOf(row), true)!!.contains("canceling downloads"))
    }

    @Test fun emptySnapshotsAndAcknowledgementsNeverInventCompletion() {
        val empty = JSONObject("""{"animeTasks":{},"mangaTasks":{}}""")
        val idle = MetadataSyncState().snapshot(empty)
        assertEquals(MetadataSyncPhase.IDLE, idle.phase)
        val accepted = idle.requesting().accepted().snapshot(empty)
        assertEquals(MetadataSyncPhase.REQUESTED, accepted.phase)
        assertTrue(accepted.description.contains("does not confirm completion"))
    }

    @Test fun runningTitlesAndCompletionBeforeHttpAcknowledgementKeepTheirOrder() {
        val state = MetadataSyncState().requesting().snapshot(JSONObject("""{"animeTasks":{"7":{"mediaId":7,"title":"Fixture anime"}},"mangaTasks":{"8":{"mediaId":8,"title":"Fixture manga"}}}"""))
        assertEquals(MetadataSyncPhase.PROCESSING, state.accepted().phase)
        assertEquals(listOf("anime", "manga"), state.tasks.map { it.type })
        assertEquals(listOf(7L, 8L), state.tasks.map { it.id })
        val finished = state.finished().accepted().snapshot(JSONObject("""{"animeTasks":{},"mangaTasks":{}}"""))
        assertEquals(MetadataSyncPhase.FINISHED, finished.phase)
        assertTrue(finished.description.contains("individual failures"))
        assertTrue(finished.tasks.isEmpty())
    }

    @Test fun missingQueueShapeIsAnErrorInsteadOfAnEmptySuccessfulQueue() {
        assertTrue(runCatching { MetadataSyncState().snapshot(JSONObject()) }.isFailure)
    }
}
