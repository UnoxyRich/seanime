package app.seanime.tv.platform

import org.json.JSONObject

data class NativeEpisodeNavigation(val previous: Boolean = false, val next: Boolean = false) {
    companion object {
        fun local(playlist: JSONObject?) = NativeEpisodeNavigation(
            playlist?.optJSONObject("previousEpisode") != null, playlist?.optJSONObject("nextEpisode") != null)

        /** Mirror Go Playlist.NextEpisode/PreviousEpisode; incomplete global state stays usable. */
        fun global(payload: JSONObject?): NativeEpisodeNavigation {
            val state = payload ?: return NativeEpisodeNavigation(true, true)
            val episodes = state.optJSONObject("playlist")?.optJSONArray("episodes") ?: return NativeEpisodeNavigation(true, true)
            val rows = (0 until episodes.length()).mapNotNull(episodes::optJSONObject)
            if (rows.size != episodes.length()) return NativeEpisodeNavigation(true, true)
            if (rows.isEmpty()) return NativeEpisodeNavigation()
            val current = state.optJSONObject("playlistEpisode")?.optJSONObject("episode")
                ?: return NativeEpisodeNavigation(next = rows.any { !it.optBoolean("isCompleted") })
            val mediaId = current.optJSONObject("baseAnime")?.optLong("id") ?: 0
            if (mediaId <= 0) return NativeEpisodeNavigation(true, true)
            val index = rows.indexOfFirst { row ->
                val episode = row.optJSONObject("episode") ?: return@indexOfFirst false
                if (episode.optJSONObject("baseAnime")?.optLong("id") != mediaId) return@indexOfFirst false
                if (episode.optJSONObject("localFile") != null || current.optJSONObject("localFile") != null)
                    episode.has("progressNumber") && current.has("progressNumber") && episode.optInt("progressNumber") == current.optInt("progressNumber")
                else current.optString("aniDBEpisode").isNotBlank() && episode.optString("aniDBEpisode") == current.optString("aniDBEpisode")
            }
            return if (index < 0) NativeEpisodeNavigation(true, true)
            else NativeEpisodeNavigation(previous = index > 0, next = index < rows.lastIndex)
        }
    }
}
