package app.seanime.tv.ui

import app.seanime.tv.data.AnimeDiscoveryFilters
import app.seanime.tv.data.parseNativeMediaId
import app.seanime.tv.data.RecentAiringFilters
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONObject

internal data class NativeScreenLocation(val pathname: String, val parameters: Map<String, String> = emptyMap()) {
    val query: String get() = parameters.filterValues(String::isNotBlank).entries.sortedBy { it.key }
        .joinToString("&", prefix = if (parameters.values.any(String::isNotBlank)) "?" else "") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
    val path: String get() = pathname + query
    fun payload(): JSONObject = JSONObject().put("pathname", pathname).put("query", query)
}

/** A route is a native destination and typed state, never a URL to launch. */
internal data class NativePluginDestination(
    val feature: TvFeature,
    val location: NativeScreenLocation,
    val animeId: Long = 0L,
    val mangaId: Long = 0L,
    val discovery: AnimeDiscoveryFilters? = null,
    val discoveryPage: Int = 1,
    val airing: RecentAiringFilters? = null,
    val libraryTab: String? = null,
    val libraryDirectory: String = "",
    val downloadTab: String? = null,
    val settingsSection: String? = null,
    val mangaMode: String? = null,
    val mangaQuery: String = "",
    val playground: Boolean = false,
    val marketplace: Boolean = false,
    val marketplaceType: String = "",
    val customSources: Boolean = false,
    val customSourceProvider: String = "",
    val sourceMode: String? = null,
    val episode: Int = 1,
)

internal val nativeSettingsTabs = mapOf(
    "library" to "library", "playback" to "library", "manga" to "manga", "anilist" to "anilist",
    "torrent-client" to "torrent", "media-player" to "mediaPlayer", "nakama" to "nakama", "notifications" to "notifications",
    "discord" to "discord", "auto-downloader" to "autoDownloader", "list-sync" to "listSync", "debrid" to "debrid",
    "torrentstream" to "torrentstream", "mediastream" to "mediastream",
)
internal val nativeSourceTabs = mapOf("library" to "Device", "onlinestream" to "Online", "torrentstream" to "Torrent", "debridstream" to "Debrid")

internal fun nativeFeatureLocation(feature: TvFeature): NativeScreenLocation = NativeScreenLocation(when (feature) {
    TvFeature.LIBRARY -> "/"; TvFeature.ANILIST -> "/lists"; TvFeature.MANGA -> "/manga"; TvFeature.OFFLINE -> "/sync"
    TvFeature.PLAYLISTS -> "/native/playlists"; TvFeature.EXTENSIONS -> "/extensions"; TvFeature.STREAMING -> "/native/streaming"
    TvFeature.DOWNLOADS -> "/native/manga-downloads"; TvFeature.NAKAMA -> "/native/nakama"; TvFeature.SETTINGS -> "/settings"; TvFeature.LOGS -> "/native/logs"
})

internal fun nativeDiscoveryLocation(filters: AnimeDiscoveryFilters, page: Int): NativeScreenLocation {
    val params = linkedMapOf<String, String>()
    if (filters.manga) params["type"] = "manga"
    if (filters.search.isNotBlank()) params["query"] = filters.search
    if (filters.sort != "AUTO") params["sorting"] = filters.sort
    if (filters.statuses.isNotEmpty()) params["status"] = filters.statuses.joinToString(",")
    if (filters.genres.isNotEmpty()) params["genre"] = filters.genres.joinToString(",")
    if (filters.tags.isNotEmpty()) params["tags"] = filters.tags.joinToString(",")
    filters.season?.let { params["season"] = it }
    filters.seasonYear?.let { params["year"] = it.toString() }
    filters.format?.let { params["format"] = it }
    filters.countryOfOrigin?.let { params["countryOfOrigin"] = it }
    filters.averageScoreGreater?.let { params["scoreAbove"] = it.toString() }
    if (filters.isAdult) params["adult"] = "true"
    if (page > 1) params["page"] = page.toString()
    return NativeScreenLocation(if (params.keys.all { it == "type" }) "/discover" else "/search", params)
}

/** Fail closed on unsupported subpages/parameters instead of silently opening a broad landing page. */
internal fun resolveNativePluginDestination(path: String): NativePluginDestination {
    require(path.length in 1..4096 && path.startsWith('/') && !path.startsWith("//") && '\\' !in path && path.none(Char::isISOControl)) {
        "Only supported internal Seanime screens can be opened on this TV"
    }
    val uri = runCatching { URI(path) }.getOrElse { throw IllegalArgumentException("The plugin requested an invalid screen path") }
    require(uri.scheme == null && uri.rawAuthority == null && uri.rawFragment == null && uri.path == uri.rawPath && !uri.path.contains("..")) {
        "External addresses, fragments and encoded screen paths are not supported"
    }
    val params = linkedMapOf<String, String>()
    uri.rawQuery?.split('&')?.filter(String::isNotEmpty)?.forEach { entry ->
        val parts = entry.split('=', limit = 2)
        val key = URLDecoder.decode(parts[0], "UTF-8")
        val value = URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        require(key !in params && key.isNotBlank()) { "Repeated or empty screen parameters are not supported" }
        params[key] = value
    }
    val location = NativeScreenLocation(uri.path, params)
    fun only(vararg keys: String) { require(params.keys.all { it in keys }) { "This screen parameter is not supported by the native TV screen" } }
    fun positive(key: String, default: Int? = null): Int =
        (if (params.containsKey(key)) params[key]?.toIntOrNull() else default)?.takeIf { it > 0 }
        ?: throw IllegalArgumentException("The plugin must provide a positive $key")
    fun mediaId(key: String): Long = params[key]?.let(::parseNativeMediaId)
        ?: throw IllegalArgumentException("The plugin must provide a positive safe-integer $key")
    fun feature(feature: TvFeature): NativePluginDestination { only(); return NativePluginDestination(feature, location) }
    return when (uri.path) {
        "/" -> feature(TvFeature.LIBRARY)
        "/lists" -> feature(TvFeature.ANILIST)
        "/sync" -> feature(TvFeature.OFFLINE)
        "/native/playlists" -> feature(TvFeature.PLAYLISTS)
        "/native/streaming" -> feature(TvFeature.STREAMING)
        "/native/nakama" -> feature(TvFeature.NAKAMA)
        "/native/logs" -> feature(TvFeature.LOGS)
        "/entry" -> {
            only("id", "tab", "episode")
            val mode = params["tab"]?.let { tab -> nativeSourceTabs[tab]
                ?: nativePluginSourceId(tab)?.let { "episodeTab:$it" }
                ?: throw IllegalArgumentException("This plugin episode tab has no native TV adapter") }
            require(params["episode"] == null || mode != null) { "An episode parameter requires a supported source tab" }
            NativePluginDestination(TvFeature.LIBRARY, location, animeId = mediaId("id"), sourceMode = mode, episode = positive("episode", 1))
        }
        "/manga/entry" -> { only("id"); NativePluginDestination(TvFeature.MANGA, location, mangaId = mediaId("id")) }
        "/manga" -> feature(TvFeature.MANGA)
        "/native/manga-downloaded" -> { only(); NativePluginDestination(TvFeature.MANGA, location, mangaMode = "downloaded") }
        "/native/manga-search" -> { only("query"); NativePluginDestination(TvFeature.MANGA, location, mangaMode = "search", mangaQuery = params["query"].orEmpty()) }
        "/discover" -> {
            only("type", "page", "from", "until", "upcoming")
            when (params["type"]) {
                null, "anime" -> { only("type", "page"); NativePluginDestination(TvFeature.ANILIST, location, discovery = AnimeDiscoveryFilters(), discoveryPage = positive("page", 1)) }
                "manga" -> { only("type", "page"); NativePluginDestination(TvFeature.MANGA, location, mangaMode = "search", discovery = AnimeDiscoveryFilters(manga = true), discoveryPage = positive("page", 1)) }
                "schedule" -> {
                    require(params["upcoming"] in setOf(null, "true", "false")) { "Invalid upcoming-airing filter" }
                    require(params.containsKey("from") == params.containsKey("until")) { "Provide both airing range endpoints" }
                    val defaults = RecentAiringFilters.around()
                    val filters = RecentAiringFilters(params["from"]?.let { it.toLongOrNull() ?: throw IllegalArgumentException("Invalid airing start") } ?: defaults.from,
                        params["until"]?.let { it.toLongOrNull() ?: throw IllegalArgumentException("Invalid airing end") } ?: defaults.until, params["upcoming"] != "false")
                    filters.payload(positive("page", 1))
                    NativePluginDestination(TvFeature.ANILIST, location, airing = filters, discoveryPage = positive("page", 1))
                }
                else -> throw IllegalArgumentException("This discovery type is not supported on TV")
            }
        }
        "/search" -> {
            only("query", "sorting", "status", "genre", "tags", "season", "year", "format", "type", "scoreAbove", "adult", "page", "countryOfOrigin")
            require(params["type"] in setOf(null, "anime", "manga")) { "Invalid discovery type" }
            val manga = params["type"] == "manga" || params["format"] in setOf("MANGA", "ONE_SHOT", "NOVEL")
            require(params["type"] != "anime" || !manga) { "The format does not belong to the selected catalog" }
            val sort = params["sorting"] ?: "AUTO"
            require(sort in setOf("AUTO", "TRENDING_DESC", "POPULARITY_DESC", "SCORE_DESC", "START_DATE_DESC", "FAVOURITES_DESC", "TITLE_ROMAJI", "SEARCH_MATCH", if (manga) "CHAPTERS_DESC" else "EPISODES_DESC")) { "This discovery sort is not supported on TV" }
            val statuses = AnimeDiscoveryFilters.parseTags(params["status"].orEmpty())
            require(statuses.all { it in setOf("RELEASING", "FINISHED", "NOT_YET_RELEASED", "HIATUS", "CANCELLED") }) { "Invalid release status" }
            val seasons = if (manga) setOf(null) else setOf(null, "WINTER", "SPRING", "SUMMER", "FALL")
            val formats = if (manga) setOf(null, "MANGA", "ONE_SHOT") else setOf(null, "TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA")
            require(params["season"] in seasons) { "Seasons are supported only for anime" }
            require(params["format"] in formats) { "This format is excluded by the server's discovery query" }
            require(params["adult"] in setOf(null, "false", "true")) { "Invalid adult filter" }
            val filters = AnimeDiscoveryFilters(params["query"].orEmpty(), sort, statuses, AnimeDiscoveryFilters.parseTags(params["genre"].orEmpty()),
                AnimeDiscoveryFilters.parseTags(params["tags"].orEmpty()), params["scoreAbove"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid score") },
                params["season"], params["year"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid year") }, params["format"], params["adult"] == "true", manga, params["countryOfOrigin"])
            filters.payload(positive("page", 1))
            NativePluginDestination(if (manga) TvFeature.MANGA else TvFeature.ANILIST, location, discovery = filters, discoveryPage = positive("page", 1), mangaMode = if (manga) "search" else null)
        }
        "/settings" -> {
            only("tab")
            val section = params["tab"]?.let { nativeSettingsTabs[it] ?: throw IllegalArgumentException("This settings tab has no native TV adapter") }
            NativePluginDestination(TvFeature.SETTINGS, location, settingsSection = section)
        }
        "/schedule", "/scan-summaries", "/native/library-files", "/native/library-explorer" -> {
            if (uri.path == "/native/library-explorer") only("directory") else only()
            val tab = when (uri.path) { "/schedule" -> "Schedule"; "/scan-summaries" -> "Scan reports"; "/native/library-explorer" -> "Explorer"; else -> "Files" }
            NativePluginDestination(TvFeature.LIBRARY, location, libraryTab = tab, libraryDirectory = params["directory"].orEmpty())
        }
        "/torrent-list", "/torrent-client", "/debrid", "/auto-downloader", "/native/manga-downloads" -> {
            only()
            val tab = when (uri.path) { "/debrid" -> "debrid"; "/auto-downloader" -> "auto"; "/native/manga-downloads" -> "manga"; else -> "torrent" }
            NativePluginDestination(TvFeature.DOWNLOADS, location, downloadTab = tab)
        }
        "/extensions" -> {
            only("tab", "type")
            require(params["tab"] in setOf(null, "installed", "marketplace")) { "Unknown extension page" }
            require(params["type"] == null || (params["tab"] == "marketplace" && params["type"] in setOf("plugin", "anime-torrent-provider", "manga-provider", "onlinestream-provider", "custom-source"))) { "Unknown marketplace type" }
            NativePluginDestination(TvFeature.EXTENSIONS, location, marketplace = params["tab"] == "marketplace", marketplaceType = params["type"].orEmpty())
        }
        "/extensions/playground" -> { only(); NativePluginDestination(TvFeature.EXTENSIONS, location, playground = true) }
        "/custom-sources" -> {
            only("provider")
            require(params["provider"].orEmpty().length <= 256 && params["provider"].orEmpty().none(Char::isISOControl)) { "Invalid custom-source provider" }
            NativePluginDestination(TvFeature.EXTENSIONS, location, customSources = true, customSourceProvider = params["provider"].orEmpty())
        }
        else -> throw IllegalArgumentException("This screen has no native TV adapter. Arbitrary webview and browser DOM pages cannot open here.")
    }
}

internal class NativePluginScreenProtocol(
    private val current: () -> NativeScreenLocation,
    private val send: (String, String, JSONObject) -> Unit,
    private val navigate: (NativePluginDestination) -> Unit,
    private val reload: () -> Unit,
    private val unsupported: (String) -> Unit,
) {
    private val unsupportedCapabilities = mutableSetOf<String>()
    private fun unavailable(event: JSONObject, capability: String, message: String) {
        if (unsupportedCapabilities.add(event.optString("extensionId") + ":" + capability)) unsupported(message)
    }
    fun changed() = send("", "screen:changed", current().payload())
    fun receive(event: JSONObject) {
        when (event.optString("type")) {
            "screen:get-current" -> send(event.optString("extensionId"), "screen:changed", current().payload())
            "screen:reload" -> reload()
            "screen:navigate-to" -> runCatching { resolveNativePluginDestination(event.optJSONObject("payload")?.optString("path").orEmpty()) }
                .onSuccess(navigate).onFailure { unsupported(it.message ?: "The requested screen is unsupported") }
            "webview:iframe" -> unavailable(event, "webview", "This plugin requests custom HTML. A native TV adapter is required for its webview content.")
            else -> if (event.optString("type").startsWith("dom:")) unavailable(event, "dom", "This plugin requests browser DOM access, which is unavailable in the native TV interface.")
        }
    }
}
