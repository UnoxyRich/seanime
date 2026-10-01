package app.seanime.tv.ui

import app.seanime.tv.data.Episode
import app.seanime.tv.data.SeanimeJson
import org.json.JSONObject

internal data class NativePluginEpisodeTab(val extensionId: String, val name: String)

internal fun nativePluginSourceId(value: String?): String? = when {
    value?.startsWith("ext:") == true -> value.removePrefix("ext:").takeIf(String::isNotBlank)
    value?.startsWith("episodeTab:") == true -> value.removePrefix("episodeTab:").takeIf(String::isNotBlank)
    else -> null
}

/** One initial choice. Callers retain the user's subsequent source selection independently. */
internal fun initialNativeSourceMode(explicit: String?, configured: String, hasLocalFile: Boolean, online: Boolean,
    torrent: Boolean, debrid: Boolean, registeredPluginIds: Set<String>): String {
    fun mode(value: String?, explicitChoice: Boolean): String? {
        nativePluginSourceId(value)?.let { return if (it in registeredPluginIds) "episodeTab:$it" else null }
        val core = nativeSourceTabs[value] ?: value?.takeIf { it in setOf("Device", "Online", "Torrent", "Debrid") }
        return core?.takeIf { explicitChoice || when (it) { "Device" -> true; "Online" -> online; "Torrent" -> torrent; "Debrid" -> debrid; else -> false } }
    }
    return mode(explicit, true) ?: mode(configured, false) ?: when {
        hasLocalFile -> "Device"; debrid -> "Debrid"; torrent -> "Torrent"; online -> "Online"; else -> "Device"
    }
}

internal fun nativePluginEpisodes(collection: JSONObject, mediaId: Long): List<Episode> {
    val episodes = requireNotNull(collection.optJSONArray("episodes")) { "The plugin did not return an episode collection" }
    require(episodes.length() <= 5000) { "The plugin episode collection is too large" }
    val rows = List(episodes.length()) { index ->
        requireNotNull(episodes.optJSONObject(index)) { "The plugin returned an invalid episode" }
    }
    fun titleId(value: Any?): Long? {
        if (value == null || value == JSONObject.NULL) return null
        val id = requireNotNull(value.toString().toLongOrNull()) { "The plugin returned an invalid title ID" }
        require(id in 0L..9_007_199_254_740_991L) { "The plugin returned an invalid title ID" }
        return id.takeIf { it > 0 }
    }
    // The event has no request/media ID. Core collections normally carry these IDs, but
    // plugin callbacks can replace the collection, so extension identity alone is unsafe.
    val collectionId = titleId(collection.optJSONObject("metadata")?.optJSONObject("mappings")?.opt("anilistId"))
    val episodeIds = rows.map { titleId(it.optJSONObject("baseAnime")?.opt("id")) }
    require(collectionId?.let { it == mediaId } != false && episodeIds.none { it != null && it != mediaId }) {
        "The plugin returned episodes for a different title. Refresh or choose another source."
    }
    require(collectionId == mediaId || (rows.isNotEmpty() && episodeIds.all { it == mediaId })) {
        "The plugin response has no reliable title identity. Native TV cannot safely show these episodes. Refresh or choose another source."
    }
    return rows.map(SeanimeJson::episode)
}

internal fun nativePluginEpisodeSelection(mediaId: Long, episode: Episode): JSONObject = JSONObject()
    .put("mediaId", mediaId).put("episodeNumber", episode.number)
    .put("aniDbEpisode", episode.raw.text("aniDBEpisode"))
    .put("episode", episode.raw)
