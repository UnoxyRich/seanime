package app.seanime.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** UI-facing models retain the server object for lossless edits and provider-specific fields. */
data class MediaCard(
    val id: Long,
    val title: String,
    val imageUrl: String? = null,
    val bannerUrl: String? = null,
    val description: String = "",
    val status: String = "",
    val progress: Int = 0,
    val totalEpisodes: Int? = null,
    val isManga: Boolean = false,
    val raw: JSONObject = JSONObject(),
)

data class MediaDetails(val media: MediaCard, val episodes: List<Episode> = emptyList(), val raw: JSONObject = JSONObject())
data class Episode(
    val number: Int,
    val title: String,
    val description: String = "",
    val imageUrl: String? = null,
    val localPath: String? = null,
    val aniDbEpisode: String = number.toString(),
    val progressNumber: Int = number,
    val isDownloaded: Boolean = false,
    val isNakama: Boolean = false,
    val raw: JSONObject = JSONObject(),
)
data class ServerStatus(
    val version: String,
    val ready: Boolean,
    val offline: Boolean,
    val serverHasPassword: Boolean,
    val userName: String,
    val isSimulated: Boolean,
    val anilistClientId: String,
    val settings: JSONObject,
    val raw: JSONObject,
)
data class MangaChapter(val id: String, val title: String, val number: String, val provider: String, val raw: JSONObject = JSONObject())
data class MangaPage(val index: Int, val url: String, val headers: Map<String, String> = emptyMap(), val raw: JSONObject = JSONObject())
data class Playlist(val id: Int, val name: String, val episodes: List<PlaylistEpisode> = emptyList(), val raw: JSONObject = JSONObject())
data class PlaylistEpisode(val episode: Episode?, val completed: Boolean = false, val watchType: String = "localfile", val raw: JSONObject = JSONObject())
data class ExtensionItem(
    val id: String,
    val name: String,
    val type: String = "",
    val version: String = "",
    val description: String = "",
    val manifestUrl: String = "",
    val disabled: Boolean = false,
    val raw: JSONObject = JSONObject(),
    val configurationError: String? = null,
)
data class DownloadItem(val id: String, val name: String, val status: String, val progress: Double, val speed: String, val kind: String, val raw: JSONObject)
data class TorrentItem(val name: String, val infoHash: String, val magnetUrl: String, val size: String, val seeders: Int, val raw: JSONObject)
data class StreamSubtitle(val url: String, val language: String, val isDefault: Boolean = false)
data class StreamSource(val url: String, val label: String, val quality: String, val type: String, val headers: Map<String, String>, val subtitles: List<StreamSubtitle>, val raw: JSONObject)
data class ServerEvent(val type: String, val payload: Any?, val raw: JSONObject)

/** Helpers distinguish JSON null from missing fields and never render literal "null" to the UI. */
fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key).objects()
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.stringMap(): Map<String, String> = keys().asSequence().mapNotNull { key -> stringOrNull(key)?.let { key to it } }.toMap()
fun jsonObject(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply { pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }

/** Pure mapping functions are intentionally independent of Android and covered with fixture tests. */
object SeanimeJson {
    fun media(value: JSONObject, manga: Boolean = false): MediaCard {
        val media = value.optJSONObject("media") ?: value
        val listData = value.optJSONObject("listData") ?: value
        val title = media.optJSONObject("title")
        val cover = media.optJSONObject("coverImage")
        return MediaCard(
            id = media.optMediaId("id", value.optMediaId("mediaId")),
            title = title?.stringOrNull("userPreferred") ?: title?.stringOrNull("english")
                ?: title?.stringOrNull("romaji") ?: title?.stringOrNull("native")
                ?: (media.opt("title") as? String)?.takeIf { it.isNotBlank() } ?: "Untitled",
            imageUrl = cover?.stringOrNull("extraLarge") ?: cover?.stringOrNull("large") ?: cover?.stringOrNull("medium"),
            bannerUrl = media.stringOrNull("bannerImage"),
            description = media.stringOrNull("description").orEmpty(),
            status = listData.stringOrNull("status")?.takeIf { it in setOf("CURRENT", "PLANNING", "COMPLETED", "PAUSED", "DROPPED", "REPEATING") }.orEmpty(),
            progress = listData.optInt("progress"),
            totalEpisodes = if (manga) media.optInt("chapters").takeIf { it > 0 } else media.optInt("episodes").takeIf { it > 0 },
            isManga = manga,
            raw = media,
        )
    }

    fun collection(value: Any?, manga: Boolean = false): List<MediaCard> {
        if (value is JSONArray) return value.objects().map { media(it, manga) }.distinctBy { it.id }
        val root = value as? JSONObject ?: return emptyList()
        val collection = root.optJSONObject("MediaListCollection") ?: root
        val entries = collection.objects("lists").flatMap { it.objects("entries") }
        val stream = root.optJSONObject("stream")
        val streamListData = stream?.optJSONObject("listData")
        val fromStream = stream?.objects("anime").orEmpty().map { raw ->
            media(jsonObject("media" to raw, "listData" to streamListData?.optJSONObject(raw.optMediaId().toString())), manga)
        }
        val page = root.optJSONObject("Page") ?: root.optJSONObject("page") ?: root
        return (entries.map { media(it, manga) } + fromStream + page.objects("media").map { media(it, manga) }).distinctBy { it.id }
    }

    fun episode(raw: JSONObject): Episode {
        val identity = OnlineEpisodeIdentity.canonical(raw)
        val episode = identity ?: raw
        val metadata = episode.optJSONObject("episodeMetadata")
        val number = episode.optInt("episodeNumber", raw.optInt("number"))
        return Episode(number,
            episode.stringOrNull("episodeTitle") ?: episode.stringOrNull("displayTitle") ?: raw.stringOrNull("title") ?: "Episode $number",
            metadata?.stringOrNull("summary") ?: metadata?.stringOrNull("overview") ?: raw.stringOrNull("description").orEmpty(),
            metadata?.stringOrNull("image") ?: raw.stringOrNull("image"),
            episode.optJSONObject("localFile")?.stringOrNull("path"),
            episode.stringOrNull("aniDBEpisode") ?: if (raw.has("metadata")) "" else number.toString(),
            episode.optInt("progressNumber", if (raw.has("metadata") && identity == null) 0 else number),
            episode.optBoolean("isDownloaded"), episode.optBoolean("_isNakamaEpisode"), raw)
    }

    fun status(raw: JSONObject): ServerStatus {
        val user = raw.optJSONObject("user")
        return ServerStatus(raw.optString("version"), raw.optBoolean("serverReady"), raw.optBoolean("isOffline"),
            raw.optBoolean("serverHasPassword"), user?.optJSONObject("viewer")?.stringOrNull("name") ?: "User",
            user?.optBoolean("isSimulated", true) ?: true, raw.optString("anilistClientId"),
            raw.optJSONObject("settings") ?: JSONObject(), raw)
    }

    fun playlist(raw: JSONObject): Playlist = Playlist(raw.optInt("dbId"), raw.optString("name"), raw.objects("episodes").map {
        PlaylistEpisode(it.optJSONObject("episode")?.let(::episode), it.optBoolean("isCompleted"), it.optString("watchType"), it)
    }, raw)

    fun extension(raw: JSONObject, disabled: Boolean = false, type: String = ""): ExtensionItem = ExtensionItem(
        raw.optString("id"), raw.optString("name"), raw.stringOrNull("type") ?: type, raw.optString("version"),
        raw.optString("description"), raw.optString("manifestURI"), disabled, raw)

    fun torrent(raw: JSONObject): TorrentItem = TorrentItem(raw.optString("name"), raw.optString("infoHash"), raw.optString("magnetLink"),
        raw.optString("formattedSize"), raw.optInt("seeders"), raw)
}
