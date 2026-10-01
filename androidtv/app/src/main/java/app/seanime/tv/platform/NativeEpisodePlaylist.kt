package app.seanime.tv.platform

import org.json.JSONArray
import org.json.JSONObject
import app.seanime.tv.data.OnlineEpisodeIdentity

/** A loaded episode list belongs to one accepted playback launch, including same-ID reloads. */
class NativeEpisodePlaylist {
    private var playbackId = ""
    private var generation = -1
    private var value: JSONObject? = null

    fun begin(id: String, launchGeneration: Int) {
        playbackId = id
        generation = launchGeneration
        value = null
    }

    fun publish(id: String, launchGeneration: Int, playlist: JSONObject?): Boolean {
        if (id != playbackId || launchGeneration != generation) return false
        value = playlist
        return true
    }

    fun current(id: String): JSONObject? = value.takeIf { id == playbackId }

    fun copy(): NativeEpisodePlaylist = NativeEpisodePlaylist().also {
        it.playbackId = playbackId
        it.generation = generation
        it.value = value?.let { playlist -> JSONObject(playlist.toString()) }
    }

    companion object {
        fun usesLibraryEpisodes(playbackType: String) = playbackType in setOf("localfile", "nakama")

        fun build(current: JSONObject, entry: JSONObject?, episodes: JSONArray?): JSONObject? {
            episodes ?: return null
            val progress = current.optJSONObject("episode")?.optInt("progressNumber") ?: return null
            if (progress <= 0) return null
            val external = current.optJSONArray("playlistExternalEpisodeNumbers")?.takeUnless {
                usesLibraryEpisodes(current.optString("playbackType"))
            }?.let { values -> (0 until values.length()).map { values.optInt(it, -1) }.toSet() }
            val online = current.optString("playbackType") == "onlinestream"
            val normalized = (0 until episodes.length()).mapNotNull(episodes::optJSONObject)
                .filter { !online || OnlineEpisodeIdentity.canonical(it) != null }.map { item ->
                (if (current.optString("playbackType") == "onlinestream")
                    OnlineEpisodeIdentity.withParams(item, current.optJSONObject("onlinestreamParams") ?: JSONObject())
                else JSONObject((item.optJSONObject("metadata") ?: item).toString())).apply {
                    if (!has("episodeNumber")) put("episodeNumber", item.optInt("number"))
                    if (!has("progressNumber")) put("progressNumber", item.optInt("number", optInt("episodeNumber")))
                }
            }.filter { it.optString("type", "main") == "main" &&
                (external == null || (if (online) OnlineEpisodeIdentity.sourceNumber(it) else it.optInt("episodeNumber")) in external) }
            val selected = normalized.find { it.optInt("progressNumber") == progress } ?: return null
            return JSONObject().put("type", current.optString("playbackType")).put("episodes", JSONArray(normalized))
                .put("currentEpisode", selected).put("animeEntry", entry)
                .put("previousEpisode", normalized.find { it.optInt("progressNumber") == progress - 1 })
                .put("nextEpisode", normalized.find { it.optInt("progressNumber") == progress + 1 })
        }
    }
}
