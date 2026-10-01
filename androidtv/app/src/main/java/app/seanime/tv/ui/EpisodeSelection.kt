package app.seanime.tv.ui

import app.seanime.tv.data.Episode

/** File identity wins over numeric labels: specials and duplicate releases share episode numbers. */
internal fun episodeIdentity(episode: Episode): String = episode.localPath?.let { "file:$it" }
    ?: "episode:${episode.raw.optString("type")}:${episode.aniDbEpisode}:${episode.number}"

/** Numeric episode labels cannot identify specials or an unrecorded source-screen placeholder. */
internal suspend fun resolveStreamEpisode(mediaId: Long, selected: Episode, requestedNumber: Int,
    loadCollection: suspend () -> List<Episode>): Episode {
    require(requestedNumber > 0) { "Choose a positive episode number" }
    fun hasRecordedIdentity(episode: Episode): Boolean = episode.raw.optString("aniDBEpisode").let {
        it.isNotBlank() && it == episode.aniDbEpisode
    }
    fun requireMatchingTitle(episode: Episode) {
        val titleId = episode.raw.optJSONObject("baseAnime")?.optLong("id") ?: 0
        require(titleId == 0L || titleId == mediaId) { "The server returned episode metadata for another title" }
    }
    if (requestedNumber == selected.number && hasRecordedIdentity(selected)) {
        requireMatchingTitle(selected)
        return selected
    }
    val candidates = loadCollection().filter { it.number == requestedNumber }
    candidates.forEach(::requireMatchingTitle)
    require(candidates.isNotEmpty() && candidates.all(::hasRecordedIdentity)) {
        "The server did not identify episode $requestedNumber. Choose an episode from the title page."
    }
    val identities = candidates.distinctBy { Triple(it.aniDbEpisode, it.progressNumber, it.raw.optString("type")) }
    require(identities.size == 1) {
        "Episode $requestedNumber has multiple identities. Choose the episode or special from the title page."
    }
    return identities.single()
}
