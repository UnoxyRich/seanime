package app.seanime.tv.data

import java.net.URLEncoder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Endpoint-backed native workflows. All paths and payload names follow the registered Go handlers. */
class SeanimeRepository(val client: SeanimeApiClient = SeanimeApiClient()) {
    suspend fun request(method: String, path: String, body: JSONObject? = null, query: Map<String, String> = emptyMap()): Any? = client.request(method, path, body, query)
    private suspend fun get(path: String): Any? = request("GET", "/api/v1$path")
    private suspend fun post(path: String, body: JSONObject = JSONObject()): Any? = request("POST", "/api/v1$path", body)
    private fun Any?.objectValue(path: String): JSONObject = this as? JSONObject ?: throw ApiException(200, "Server returned an unexpected response", path)
    private fun Any?.objectList(): List<JSONObject> = when (this) {
        null -> emptyList()
        is JSONArray -> objects()
        else -> throw ApiException(200, "Server returned an unexpected list response", "")
    }

    suspend fun status(): ServerStatus = SeanimeJson.status(get("/status").objectValue("/status"))
    suspend fun library(): List<MediaCard> = authorizeOfflineArtwork(SeanimeJson.collection(get("/library/collection")))
    suspend fun animeList(refresh: Boolean = false): List<MediaCard> = authorizeOfflineArtwork(SeanimeJson.collection(if (refresh) post("/anilist/collection/raw") else get("/anilist/collection/raw")))
    suspend fun mangaList(): List<MediaCard> = authorizeOfflineArtwork(SeanimeJson.collection(get("/manga/collection"), true))
    suspend fun search(query: String, manga: Boolean = false, page: Int = 1): List<MediaCard> {
        val body = jsonObject("page" to page, "perPage" to 40, "sort" to JSONArray(listOf(if (query.isBlank()) "TRENDING_DESC" else "SEARCH_MATCH")))
        if (query.isNotBlank()) body.put("search", query)
        return SeanimeJson.collection(post(if (manga) "/manga/anilist/list" else "/anilist/list-anime", body), manga)
    }
    suspend fun discoverAnime(filters: AnimeDiscoveryFilters = AnimeDiscoveryFilters(), page: Int = 1): AnimeDiscoveryPage =
        AnimeDiscoveryPage.fromJson(post("/anilist/list-anime", filters.payload(page)), page)
    suspend fun animeDiscoveryTags(): List<String> {
        val raw = get("/anilist/collection/raw/tags").objectValue("/anilist/collection/raw/tags")
        return raw.keys().asSequence().flatMap { key ->
            val tags = raw.optJSONArray(key) ?: JSONArray()
            (0 until tags.length()).asSequence().map { tags.optString(it).trim() }
        }.filter(String::isNotEmpty).distinct().sorted().toList()
    }
    suspend fun animeDetails(id: Long): MediaDetails {
        val raw = get("/library/anime-entry/$id").objectValue("/library/anime-entry")
        val episodes = nativeResponseObjects(raw.opt("episodes"), "/api/v1/library/anime-entry/$id", "episodes").map(SeanimeJson::episode)
        val media = nativeResponseEntry(raw, id, "/api/v1/library/anime-entry/$id")
        return MediaDetails(authorizeOfflineArtwork(listOf(media), episodes.mapNotNull { it.imageUrl }).single(), episodes, raw)
    }
    suspend fun animeMetadata(id: Long): JSONObject = get("/anilist/media-details/$id").objectValue("/anilist/media-details")
    suspend fun episodeCollection(id: Long): List<Episode> = get("/anime/episode-collection/$id").objectValue("/anime/episode-collection").objects("episodes").map(SeanimeJson::episode)
    suspend fun mangaDetails(id: Long): MediaDetails {
        val raw = get("/manga/entry/$id").objectValue("/manga/entry")
        return MediaDetails(authorizeOfflineArtwork(listOf(nativeResponseEntry(raw, id, "/api/v1/manga/entry/$id", true))).single(), raw = raw)
    }

    /** Call only for active-platform media routes, never direct custom catalog/plugin results. */
    internal suspend fun authorizeOfflineArtwork(media: List<MediaCard>, episodeImages: List<String> = emptyList()): List<MediaCard> {
        val snapshots = media.filter { card -> card.artworkOrigin == MediaArtworkOrigin.PROVIDER &&
            (listOfNotNull(card.imageUrl, card.bannerUrl) + episodeImages).any { MediaArtworkOrigin.isSnapshotAsset(it, card.id) } }.map { it.id }.toSet()
        if (snapshots.isEmpty()) return media
        // Fail closed for artwork without turning a status-read failure into a missing library.
        val offline = try { status().offline }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        return if (!offline) media else media.map { if (it.id in snapshots) it.withLocalArtwork() else it }
    }
    suspend fun editListEntry(mediaId: Long, status: String, progress: Int, score: Int? = null, manga: Boolean = false): Any? =
        post("/anilist/list-entry", jsonObject("mediaId" to mediaId, "status" to status, "progress" to progress, "type" to if (manga) "manga" else "anime").apply { score?.let { put("score", it) } })
    suspend fun deleteListEntry(mediaId: Long, manga: Boolean = false): Any? = request("DELETE", "/api/v1/anilist/list-entry", jsonObject("mediaId" to mediaId, "type" to if (manga) "manga" else "anime"))
    suspend fun loginAniList(token: String): ServerStatus = SeanimeJson.status(post("/auth/login", jsonObject("token" to token)).objectValue("/auth/login"))
    suspend fun logoutAniList(): ServerStatus = SeanimeJson.status(post("/auth/logout").objectValue("/auth/logout"))
    suspend fun loginMal(code: String, state: String, codeVerifier: String): Any? {
        val result = post("/mal/auth", jsonObject("code" to code, "state" to state, "code_verifier" to codeVerifier)).objectValue("/mal/auth")
        if (result.stringOrNull("access_token") == null || result.stringOrNull("token_type") == null) {
            throw ApiException(200, "MyAnimeList did not return a valid access token. Start sign-in again to get a new authorization code.", "/api/v1/mal/auth")
        }
        return result
    }
    suspend fun logoutMal(): Any? = post("/mal/logout")
    suspend fun scanLibrary(enhanced: Boolean = false): Any? = post("/library/scan", jsonObject("enhanced" to enhanced, "enhanceWithOfflineDatabase" to false, "skipLockedFiles" to true, "skipIgnoredFiles" to true))

    suspend fun providers(type: String): List<ExtensionItem> {
        require(type in setOf("manga-provider", "onlinestream-provider", "anime-torrent-provider", "custom-source")) { "Unknown provider type" }
        return get("/extensions/list/$type").objectList().map { SeanimeJson.extension(it, type = type) }
    }
    suspend fun onlineEpisodes(mediaId: Long, provider: String, dubbed: Boolean = false): List<Episode> =
        post("/onlinestream/episode-list", jsonObject("mediaId" to mediaId, "provider" to provider, "dubbed" to dubbed)).objectValue("/onlinestream/episode-list").objects("episodes").map(SeanimeJson::episode)
    suspend fun onlineSources(mediaId: Long, episodeNumber: Int, provider: String, dubbed: Boolean = false, refresh: Boolean = false): List<StreamSource> =
        post("/onlinestream/episode-source", jsonObject("mediaId" to mediaId, "episodeNumber" to episodeNumber, "provider" to provider, "dubbed" to dubbed, "refresh" to refresh))
            .objectValue("/onlinestream/episode-source").objects("videoSources").map { raw ->
                StreamSource(ProviderUrlPolicy.requirePublicUrl(raw.optString("url")).toString(), raw.stringOrNull("label") ?: raw.optString("server"), raw.optString("quality"), raw.optString("type"),
                    raw.optJSONObject("headers")?.stringMap().orEmpty(), raw.objects("subtitles").map { StreamSubtitle(ProviderUrlPolicy.requirePublicUrl(it.optString("url")).toString(), it.optString("language"), it.optBoolean("isDefault")) }, raw)
            }
    suspend fun searchTorrents(media: MediaCard, episodeNumber: Int, provider: String, query: String = "", batch: Boolean = false): List<TorrentItem> =
        post("/torrent/search", jsonObject("media" to media.raw, "episodeNumber" to episodeNumber, "provider" to provider,
            "type" to if (query.isBlank()) "smart" else "simple", "query" to query, "batch" to batch))
            .objectValue("/torrent/search").objects("torrents").map(SeanimeJson::torrent)
    suspend fun torrentFilePreviews(media: MediaCard, episodeNumber: Int, torrent: TorrentItem, debrid: Boolean = false): JSONObject =
        jsonObject("files" to JSONArray(post(if (debrid) "/debrid/torrents/file-previews" else "/torrentstream/torrent-file-previews",
            jsonObject("media" to media.raw, "episodeNumber" to episodeNumber, "torrent" to torrent.raw)).objectList()))
    suspend fun startTorrentStream(mediaId: Long, episode: Episode, torrent: TorrentItem? = null, fileIndex: Int? = null, autoSelect: Boolean = true): Any? {
        client.awaitEventsReady()
        return post("/torrentstream/start", streamBody(mediaId, episode, torrent, fileIndex, autoSelect))
    }
    suspend fun startDebridStream(mediaId: Long, episode: Episode, torrent: TorrentItem? = null, fileId: String = "", fileIndex: Int? = null, autoSelect: Boolean = true): Any? {
        client.awaitEventsReady()
        return post("/debrid/stream/start", streamBody(mediaId, episode, torrent, fileIndex, autoSelect).put("fileId", fileId))
    }
    private fun streamBody(mediaId: Long, episode: Episode, torrent: TorrentItem?, fileIndex: Int?, autoSelect: Boolean) =
        jsonObject("mediaId" to mediaId, "episodeNumber" to episode.number, "aniDBEpisode" to episode.aniDbEpisode,
            "autoSelect" to (autoSelect && torrent == null), "playbackType" to "nativeplayer", "clientId" to client.clientId).apply {
            torrent?.let { put("torrent", it.raw) }; fileIndex?.let { put("fileIndex", it) }
        }
    suspend fun stopTorrentStream(): Any? = post("/torrentstream/stop")
    suspend fun stopDebridStream(): Any? = post("/debrid/stream/cancel")
    suspend fun playLocalFile(path: String): Any? { client.awaitEventsReady(); return post("/directstream/play/localfile", jsonObject("path" to path, "clientId" to client.clientId)) }

    suspend fun mangaChapters(id: Long, provider: String): List<MangaChapter> =
        post("/manga/chapters", jsonObject("mediaId" to id, "provider" to provider)).objectValue("/manga/chapters").objects("chapters").map { mangaChapter(it, provider) }
    /** Only an explicit refresh discards the server's chapter and page cache. */
    suspend fun refreshMangaChapters(id: Long, provider: String): List<MangaChapter> {
        check(request("DELETE", "/api/v1/manga/entry/cache", jsonObject("mediaId" to id)) == true) { "The server did not confirm that the manga cache was cleared" }
        return mangaChapters(id, provider)
    }
    suspend fun mangaMapping(id: Long, provider: String): String? =
        post("/manga/get-mapping", jsonObject("mediaId" to id, "provider" to provider))
            .objectValue("/manga/get-mapping").stringOrNull("mangaId")?.takeIf(String::isNotBlank)
    suspend fun setMangaMapping(id: Long, provider: String, mangaId: String) {
        check(post("/manga/manual-mapping", jsonObject("mediaId" to id, "provider" to provider, "mangaId" to mangaId)) == true) { "The server did not confirm the source match" }
    }
    suspend fun resetMangaMapping(id: Long, provider: String) {
        check(post("/manga/remove-mapping", jsonObject("mediaId" to id, "provider" to provider)) == true) { "The server did not confirm the source match reset" }
    }
    suspend fun downloadedMangaChapters(id: Long): List<MangaChapter> = get("/manga/downloaded-chapters/$id").objectList().flatMap { container ->
        container.objects("chapters").map { mangaChapter(it, container.optString("provider")) }
    }
    private fun mangaChapter(raw: JSONObject, provider: String) = MangaChapter(raw.optString("id"), raw.stringOrNull("title") ?: "Chapter ${raw.optString("chapter")}", raw.optString("chapter"), raw.stringOrNull("provider") ?: provider, raw)
    suspend fun mangaPages(id: Long, chapterId: String, provider: String, doublePage: Boolean = false): List<MangaPage> =
        mangaPageCollection(id, chapterId, provider, doublePage).pages
    suspend fun mangaPageCollection(id: Long, chapterId: String, provider: String, doublePage: Boolean = false): MangaPageCollection {
        val container = post("/manga/pages", jsonObject("mediaId" to id, "chapterId" to chapterId, "provider" to provider, "doublePage" to doublePage)).objectValue("/manga/pages")
        val pages = container.objects("pages").map { raw ->
            val source = raw.optString("url")
            val providerResult = provider != "local-manga" && !container.optBoolean("isDownloaded")
            val url = when {
                providerResult -> ProviderUrlPolicy.requirePublicUrl(source).toString()
                provider == "local-manga" && source.startsWith("{{manga-local-assets}}") -> client.absoluteUrl("/api/v1/manga/local-page/${encodePathSegment(source)}")
                container.optBoolean("isDownloaded") -> client.absoluteUrl("/manga-downloads/" + source.split('/').joinToString("/") { encodePathSegment(it) })
                source.startsWith('/') -> client.absoluteUrl(source)
                else -> source
            }
            val headers = if (!providerResult && client.isServerUrl(url)) client.requestHeaders() else raw.optJSONObject("headers")?.stringMap().orEmpty()
            MangaPage(raw.optInt("index"), url, headers, raw, providerResult)
        }.sortedBy { it.index }
        val dimensions = container.optJSONObject("pageDimensions")
        return MangaPageCollection(pages, pages.mapNotNull { page ->
            val size = dimensions?.optJSONObject(page.index.toString()) ?: return@mapNotNull null
            val width = size.optInt("width")
            val height = size.optInt("height")
            if (width > 0 && height > 0) page.index to MangaPageDimensions(width, height) else null
        }.toMap())
    }
    suspend fun downloadMangaChapters(mediaId: Long, provider: String, chapterIds: List<String>, startNow: Boolean = true): Any? =
        post("/manga/download-chapters", jsonObject("mediaId" to mediaId, "provider" to provider, "chapterIds" to JSONArray(chapterIds), "startNow" to startNow))
    suspend fun mangaDownloadData(mediaId: Long, cached: Boolean = false): JSONObject = post("/manga/download-data", jsonObject("mediaId" to mediaId, "cached" to cached)).objectValue("/manga/download-data")
    suspend fun mangaDownloads(): List<JSONObject> = get("/manga/downloads").objectList()
    suspend fun updateMangaProgress(mediaId: Long, chapterNumber: Int, totalChapters: Int = 0, malId: Int? = null): Any? =
        post("/manga/update-progress", jsonObject("mediaId" to mediaId, "chapterNumber" to chapterNumber, "totalChapters" to totalChapters).apply { malId?.let { put("malId", it) } })
    suspend fun setOffline(enabled: Boolean): Any? = post("/local/offline", jsonObject("enabled" to enabled))
    suspend fun trackOffline(mediaId: Long, manga: Boolean = false): Any? = post("/local/track", jsonObject("media" to JSONArray().put(jsonObject("mediaId" to mediaId, "type" to if (manga) "manga" else "anime"))))
    suspend fun syncOffline(): Any? = post("/local/local")

    suspend fun playlists(): List<Playlist> = authorizePlaylistArtwork(get("/playlists").objectList().map(SeanimeJson::playlist))
    internal suspend fun authorizePlaylistArtwork(playlists: List<Playlist>): List<Playlist> {
        // Playlists retain old provider objects. Verify snapshot images against a fresh active
        // platform entry before enabling even the bounded, credential-free local asset route.
        val snapshots = mutableMapOf<Long, Set<String>>()
        for (item in playlists.flatMap { it.episodes }) {
            val media = item.episode?.raw?.optJSONObject("baseAnime")?.let { SeanimeJson.media(it) } ?: continue
            val image = item.episode?.imageUrl ?: media.imageUrl
            if (media.artworkOrigin != MediaArtworkOrigin.PROVIDER || !MediaArtworkOrigin.isSnapshotAsset(image, media.id) || media.id in snapshots) continue
            snapshots[media.id] = try {
                val details = animeDetails(media.id)
                if (details.media.artworkOrigin == MediaArtworkOrigin.LOCAL_ASSET)
                    (listOfNotNull(details.media.imageUrl, details.media.bannerUrl) + details.episodes.mapNotNull { it.imageUrl }).toSet()
                else emptySet()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { emptySet() }
        }
        return playlists.map { playlist -> playlist.copy(episodes = playlist.episodes.map { item ->
            val media = item.episode?.raw?.optJSONObject("baseAnime")?.let { SeanimeJson.media(it) }
            val image = item.episode?.imageUrl ?: media?.imageUrl
            if (image != null && image in snapshots[media?.id].orEmpty()) item.copy(artworkOrigin = MediaArtworkOrigin.LOCAL_ASSET) else item
        }) }
    }
    suspend fun createPlaylist(name: String, episodes: List<PlaylistEpisode> = emptyList()): Playlist =
        SeanimeJson.playlist(post("/playlist", jsonObject("name" to name, "episodes" to playlistEpisodesJson(episodes))).objectValue("/playlist"))
    suspend fun updatePlaylist(playlist: Playlist): Playlist = SeanimeJson.playlist(request("PATCH", "/api/v1/playlist", jsonObject("dbId" to playlist.id, "name" to playlist.name, "episodes" to playlistEpisodesJson(playlist.episodes))).objectValue("/playlist"))
    suspend fun deletePlaylist(id: Int): Any? = request("DELETE", "/api/v1/playlist", jsonObject("dbId" to id))
    suspend fun playlistEpisodes(mediaId: Long): List<PlaylistEpisode> = get("/playlist/episodes/$mediaId").objectList().map { PlaylistEpisode(it.optJSONObject("episode")?.let(SeanimeJson::episode), it.optBoolean("isCompleted"), it.optString("watchType"), it) }
    private fun playlistEpisodesJson(episodes: List<PlaylistEpisode>) = JSONArray().apply { episodes.forEach { item ->
        put(JSONObject(item.raw.toString()).put("episode", item.episode?.raw ?: JSONObject.NULL).put("isCompleted", item.completed).put("watchType", item.watchType))
    } }

    suspend fun extensions(withUpdates: Boolean = false): List<ExtensionItem> {
        val raw = post("/extensions/all", jsonObject("withUpdates" to withUpdates)).objectValue("/extensions/all")
        val configurationErrors = raw.objects("invalidUserConfigExtensions").associate { invalid ->
            invalid.optJSONObject("extension")?.stringOrNull("id").orEmpty().ifBlank { invalid.optString("id") } to
                invalid.stringOrNull("reason").orEmpty().ifBlank { "This extension needs configuration" }
        }
        return (raw.objects("extensions").map { SeanimeJson.extension(it) } + raw.objects("disabledExtensions").map { SeanimeJson.extension(it, true) } +
            raw.objects("invalidExtensions").mapNotNull { invalid -> invalid.optJSONObject("extension")?.let { SeanimeJson.extension(it, true).copy(description = "${invalid.optString("reason")}: ${it.optString("description")}") } })
            .distinctBy { it.id }.map { it.copy(configurationError = configurationErrors[it.id]) }
    }
    suspend fun installExtension(manifestUrl: String): Any? = post("/extensions/external/install", jsonObject("manifestUri" to manifestUrl))
    suspend fun removeExtension(id: String): Any? = post("/extensions/external/uninstall", jsonObject("id" to id))
    suspend fun setExtensionDisabled(id: String, disabled: Boolean): Any? = post("/extensions/external/disabled", jsonObject("id" to id, "disabled" to disabled))
    suspend fun extensionConfig(id: String): JSONObject = get("/extensions/user-config/${encodePathSegment(id)}").objectValue("/extensions/user-config")
    suspend fun saveExtensionConfig(id: String, version: Int, values: Map<String, String>): Any? = post("/extensions/user-config", jsonObject("id" to id, "version" to version, "values" to JSONObject(values)))
    suspend fun grantPluginPermissions(id: String): Boolean = coroutineScope {
        client.awaitEventsReady()
        val prefix = id + "$$$"
        val challenge = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(15_000) { client.events.first { it.type == "grant-plugin-permission-check" && (it.payload as? String)?.startsWith(prefix) == true }.payload as String }
        }
        try {
            post("/extensions/plugin-permissions/grant", jsonObject("id" to id, "clientId" to client.clientId))
            post("/extensions/plugin-permissions/grant", jsonObject("id" to id, "clientId" to "CODE:${challenge.await().removePrefix(prefix)}")) == true
        } finally { challenge.cancel() }
    }

    suspend fun downloads(): List<DownloadItem> = get("/torrent-client/list").objectList().map { raw ->
        DownloadItem(raw.optString("hash"), raw.optString("name"), raw.optString("status"), raw.optDouble("progress", 0.0), raw.optString("downSpeed"), "torrent", raw)
    }
    internal suspend fun builtInTorrentDetails(hash: String): NativeTorrentDetails {
        require(hash.isNotBlank()) { "Select a torrent first" }
        val raw = request("GET", "/api/v1/torrent-client/details", query = mapOf("hash" to hash)).objectValue("/torrent-client/details")
        return parseNativeTorrentDetails(raw, hash)
    }
    internal suspend fun builtInTorrentAction(hash: String, command: NativeTorrentCommand): Any? = post("/torrent-client/action", command.payload(hash))
    internal suspend fun builtInTorrentLimits(download: Int, upload: Int): Any? {
        require(download in 0..2_097_151 && upload in 0..2_097_151) { "Choose valid speed limits" }
        return post("/torrent-client/action", jsonObject("action" to "set-limits", "downloadLimit" to download, "uploadLimit" to upload))
    }
    suspend fun debridDownloads(): List<DownloadItem> = get("/debrid/torrents").objectList().map { raw ->
        DownloadItem(raw.optString("id"), raw.optString("name"), raw.optString("status"), raw.optDouble("completionPercentage", 0.0) / 100.0, raw.optString("speed"), "debrid", raw)
    }
    suspend fun torrentAction(hash: String, action: String, directory: String? = null, name: String? = null): Any? {
        require(directory == null || action == "move-storage") { "A directory is only supported for moving torrent storage" }
        require(name == null || action == "rename") { "A name is only supported for renaming a torrent" }
        if (action == "move-storage") require(!directory.isNullOrBlank() && directory.length <= 4096 && directory.none { it == '\u0000' }) { "Choose an available destination folder" }
        if (action == "rename") require(!name.isNullOrBlank() && name.length <= 255 && name.none { it.isISOControl() }) { "Use a display name of 1–255 characters without control characters" }
        return post("/torrent-client/action", jsonObject("hash" to hash, "action" to action).apply {
            directory?.let { put("dir", it) }; name?.let { put("name", it) }
        })
    }
    suspend fun downloadTorrents(torrents: List<TorrentItem>, destination: String, media: MediaCard) {
        val body = torrentDownloadBody(torrents, destination, media)
            .put("smartSelect", jsonObject("enabled" to false, "missingEpisodeNumbers" to JSONArray()))
        check(post("/torrent-client/download", body) == true) { "The server did not confirm the torrent download request" }
    }
    suspend fun addDebridTorrents(torrents: List<TorrentItem>, destination: String, media: MediaCard) {
        check(post("/debrid/torrents", torrentDownloadBody(torrents, destination, media)) == true) {
            "The server did not confirm the debrid download request"
        }
    }
    private fun torrentDownloadBody(torrents: List<TorrentItem>, destination: String, media: MediaCard): JSONObject {
        require(torrents.isNotEmpty() && media.id > 0) { "Choose a release and title before downloading" }
        require(destination.isNotBlank() && !destination.contains('\u0000')) { "Choose a valid download folder" }
        return jsonObject("torrents" to JSONArray(torrents.map { it.raw }), "destination" to destination, "media" to media.raw)
    }
    suspend fun downloadDebridTorrent(item: DownloadItem, destination: String): Any? = post("/debrid/torrents/download", jsonObject("torrentItem" to item.raw, "destination" to destination))
    suspend fun cancelDebridDownload(id: String): Any? = post("/debrid/torrents/cancel", jsonObject("itemID" to id))
    suspend fun deleteDebridTorrent(item: DownloadItem): Any? = request("DELETE", "/api/v1/debrid/torrent", jsonObject("torrentItem" to item.raw))

    /** Connected peer and party state are delivered by the nakama-* event protocol, not a fabricated /status route. */
    suspend fun nakamaStatus(): JSONObject = jsonObject("settings" to settings().optJSONObject("nakama"), "roomsAvailable" to get("/nakama/room/available"))
    suspend fun nakamaReconnect(): Any? = post("/nakama/reconnect")
    suspend fun nakamaCreateRoom(): Any? = post("/nakama/room/create")
    suspend fun nakamaDisconnect(): Any? = post("/nakama/room/disconnect")
    suspend fun joinWatchParty(): Any? { client.awaitEventsReady(); return post("/nakama/watch-party/join", jsonObject("clientId" to client.clientId)) }
    suspend fun createWatchParty(settings: JSONObject? = null): Any? = post("/nakama/watch-party/create", jsonObject("settings" to settings))
    suspend fun leaveWatchParty(): Any? = post("/nakama/watch-party/leave")
    suspend fun sendNakamaChat(message: String): Any? = post("/nakama/watch-party/chat", jsonObject("message" to message))
    suspend fun settings(): JSONObject = get("/settings").objectValue("/settings")
    suspend fun patchSetting(path: String, value: Any?): Any? = request("PATCH", "/api/v1/settings/path", jsonObject("path" to path, "value" to value))
    suspend fun saveSettings(settings: JSONObject): ServerStatus = SeanimeJson.status(request("PATCH", "/api/v1/settings", settings).objectValue("/settings"))
    suspend fun logs(): String = get("/logs/latest") as? String ?: ""
    suspend fun saveIssueReport(description: String, isAnimeLibraryIssue: Boolean = false): Boolean = post("/report/issue", jsonObject("description" to description, "isAnimeLibraryIssue" to isAnimeLibraryIssue,
        "clickLogs" to JSONArray(), "networkLogs" to JSONArray(), "consoleLogs" to JSONArray(), "reactQueryLogs" to JSONArray(), "navigationLogs" to JSONArray(), "screenshots" to JSONArray(), "websocketLogs" to JSONArray(), "rrwebEvents" to JSONArray())) == true
    suspend fun downloadIssueReport(): ByteArray = client.download("/api/v1/report/issue/download")

    suspend fun completeSetup(libraryPath: String, enableOnline: Boolean = true, enableTorrent: Boolean = true): ServerStatus {
        require(libraryPath.isNotBlank()) { "Choose a library folder" }
        val response = SeanimeJson.status(post("/start", setupPayload(libraryPath, enableOnline, enableTorrent)).objectValue("/start"))
        if (response.settings.optJSONObject("library") == null) throw ApiException(200, "Server did not save the library settings", "/api/v1/start")
        return response
    }

    companion object {
        fun encodePathSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        /** Defaults from server/settings.ts, using the built-in client rather than desktop executables. */
        internal fun setupPayload(libraryPath: String, enableOnline: Boolean, enableTorrent: Boolean): JSONObject = jsonObject(
            "library" to jsonObject("libraryPath" to libraryPath, "libraryPaths" to JSONArray(), "autoUpdateProgress" to true, "disableUpdateCheck" to false,
                "torrentProvider" to "", "autoSelectTorrentProvider" to "", "autoScan" to false, "disableAnimeCardTrailers" to false,
                "enableManga" to true, "enableOnlinestream" to enableOnline, "includeOnlineStreamingInLibrary" to enableOnline,
                "dohProvider" to "", "openTorrentClientOnStart" to false, "openWebURLOnStart" to false, "refreshLibraryOnStart" to false,
                "autoPlayNextEpisode" to false, "enableWatchContinuity" to true, "autoSyncOfflineLocalData" to false,
                "scannerMatchingThreshold" to 0, "scannerMatchingAlgorithm" to "", "autoSyncToLocalAccount" to false,
                "autoSaveCurrentMediaOffline" to false, "useFallbackMetadataProvider" to false, "scannerUseLegacyMatching" to false,
                "scannerConfig" to "", "updateChannel" to "github", "enableExtensionSecureMode" to false, "defaultPlaybackSource" to "", "showTorrentAvailability" to false),
            "nakama" to jsonObject("enabled" to false, "isHost" to false, "hostPassword" to "", "remoteServerURL" to "", "remoteServerPassword" to "", "hostShareLocalAnimeLibrary" to false,
                "username" to "", "includeNakamaAnimeLibrary" to false, "hostUnsharedAnimeIds" to JSONArray(), "hostEnablePortForwarding" to false),
            "manga" to jsonObject("defaultMangaProvider" to "", "mangaAutoUpdateProgress" to false, "mangaLocalSourceDirectory" to ""),
            "mediaPlayer" to jsonObject("host" to "127.0.0.1", "defaultPlayer" to "mpv", "vlcPort" to 8080, "vlcUsername" to "", "vlcPassword" to "", "vlcPath" to "", "mpcPort" to 13579, "mpcPath" to "",
                "mpvSocket" to "", "mpvPath" to "", "mpvArgs" to "", "iinaSocket" to "", "iinaPath" to "", "iinaArgs" to "", "vcTranslate" to false, "screenshotDir" to ""),
            "discord" to jsonObject("enableRichPresence" to false, "enableAnimeRichPresence" to true, "enableMangaRichPresence" to true, "richPresenceHideSeanimeRepositoryButton" to false,
                "richPresenceShowAniListMediaButton" to false, "richPresenceShowAniListProfileButton" to false, "richPresenceUseMediaTitleStatus" to true),
            "torrent" to jsonObject("defaultTorrentClient" to "seanime", "qbittorrentPath" to "", "qbittorrentHost" to "", "qbittorrentPort" to 8081, "qbittorrentUsername" to "", "qbittorrentPassword" to "",
                "transmissionPath" to "", "transmissionHost" to "", "transmissionPort" to 9091, "transmissionUsername" to "", "transmissionPassword" to "", "seanimePort" to 50007,
                "seanimeMaxConnections" to 50, "seanimeDownloadLimit" to 0, "seanimeUploadLimit" to 0, "seanimeMaxActiveDownloads" to 3, "showActiveTorrentCount" to false, "hideTorrentList" to false),
            "anilist" to jsonObject("hideAudienceScore" to false, "enableAdultContent" to true, "blurAdultContent" to false, "disableCacheLayer" to false),
            "notifications" to jsonObject("disableNotifications" to false, "disableAutoDownloaderNotifications" to false, "disableAutoScannerNotifications" to false),
            "enableTorrentStreaming" to enableTorrent, "enableTranscode" to false, "debridProvider" to "none", "debridApiKey" to "")
    }
}
