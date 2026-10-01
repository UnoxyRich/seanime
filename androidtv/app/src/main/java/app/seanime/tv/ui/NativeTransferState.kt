package app.seanime.tv.ui

import org.json.JSONObject

/** The queue API exposes DB rows, not the downloader's worker/join state. */
internal fun mangaQueueClearBlocker(chapters: List<JSONObject>, loaded: Boolean): String? = when {
    !loaded -> "Refresh the queue before clearing it."
    chapters.isEmpty() -> "The queue is empty."
    chapters.any { it.text("status") == "downloading" } -> "Clear is unavailable while chapters are downloading."
    chapters.any { it.text("status") != "errored" } ->
        "Clear is available only when every chapter has failed. Waiting or paused rows may still have canceling downloads; the server does not report when those workers have stopped."
    else -> null
}

internal enum class MetadataSyncPhase { UNKNOWN, IDLE, REQUESTING, REQUESTED, PROCESSING, FINISHED }

internal data class MetadataSyncTask(val id: Long, val title: String, val type: String)

internal data class MetadataSyncState(
    val phase: MetadataSyncPhase = MetadataSyncPhase.UNKNOWN,
    val tasks: List<MetadataSyncTask> = emptyList(),
) {
    fun requesting() = copy(phase = MetadataSyncPhase.REQUESTING, tasks = emptyList())
    fun accepted() = if (phase == MetadataSyncPhase.REQUESTING) copy(phase = MetadataSyncPhase.REQUESTED) else this

    fun snapshot(payload: JSONObject): MetadataSyncState {
        require(payload.has("animeTasks") && payload.has("mangaTasks")) { "The server did not return a metadata queue state" }
        val current = listOf("animeTasks" to "anime", "mangaTasks" to "manga").flatMap { (key, type) ->
            val entries = payload.optJSONObject(key) ?: JSONObject()
            entries.keys().asSequence().mapNotNull { entries.optJSONObject(it) }.map { item ->
                MetadataSyncTask(item.optLong("mediaId"), item.text("title", "Title ${item.optLong("mediaId")}"), type)
            }.toList()
        }
        return copy(tasks = current, phase = when {
            current.isNotEmpty() -> MetadataSyncPhase.PROCESSING
            phase == MetadataSyncPhase.PROCESSING -> MetadataSyncPhase.REQUESTED
            phase == MetadataSyncPhase.UNKNOWN -> MetadataSyncPhase.IDLE
            else -> phase
        })
    }

    // This is a processing-complete signal, not a per-title success receipt:
    // internal/local/sync.go emits it even if collection synchronization logs an error.
    fun finished() = copy(phase = MetadataSyncPhase.FINISHED, tasks = emptyList())

    val description: String get() = when (phase) {
        MetadataSyncPhase.UNKNOWN -> "Metadata queue status has not been loaded."
        MetadataSyncPhase.IDLE -> "No active metadata tasks reported."
        MetadataSyncPhase.REQUESTING -> "Requesting metadata synchronization…"
        MetadataSyncPhase.REQUESTED -> "Metadata request accepted. Waiting for processing or completion confirmation. An empty queue alone does not confirm completion."
        MetadataSyncPhase.PROCESSING -> "Saving metadata for ${tasks.size} ${if (tasks.size == 1) "title" else "titles"}."
        MetadataSyncPhase.FINISHED -> "The server finished metadata processing. Check Logs if any title is missing; individual failures are not included in the completion event."
    }
}
