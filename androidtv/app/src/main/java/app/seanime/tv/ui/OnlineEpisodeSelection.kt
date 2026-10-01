package app.seanime.tv.ui

import app.seanime.tv.data.Episode
import app.seanime.tv.data.OnlineEpisodeIdentity

/** Resolve the actual provider row, including when sources were requested before its list. */
internal suspend fun resolveOnlineEpisode(mediaId: Long, sourceNumber: Int, cached: List<Episode>, load: suspend () -> List<Episode>): Episode {
    require(sourceNumber > 0) { "Choose a positive episode number" }
    val cachedMatches = cached.filter { OnlineEpisodeIdentity.sourceNumber(it.raw) == sourceNumber }
    val matches = if (cachedMatches.isNotEmpty()) cachedMatches else load().filter { OnlineEpisodeIdentity.sourceNumber(it.raw) == sourceNumber }
    require(matches.size == 1) { "The provider did not identify a unique episode $sourceNumber. Refresh its episode list." }
    val selected = matches.single()
    val titleId = OnlineEpisodeIdentity.canonical(selected.raw)?.optJSONObject("baseAnime")?.optLong("id") ?: 0
    require(titleId == 0L || titleId == mediaId) { "The provider returned episode metadata for another title" }
    return selected
}
