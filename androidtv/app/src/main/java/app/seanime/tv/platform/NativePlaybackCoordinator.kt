package app.seanime.tv.platform

import android.app.Activity
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import app.seanime.tv.NativePlayerActivity
import app.seanime.tv.PlaybackRecoverySnapshot
import app.seanime.tv.SeanimeTvApplication
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.OnlineEpisodeIdentity
import app.seanime.tv.gomobile.mobile.Mobile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.File

/** Owns playback independently of the Compose screen lifecycle. No web page is involved. */
class NativePlaybackCoordinator(
    private var activity: Activity,
    private val api: SeanimeApiClient,
    private var onError: (String) -> Unit = {},
    private var onState: (String) -> Unit = {},
    private val recoveryFilesDir: File = activity.filesDir,
) : NativePlaybackBus.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var info: JSONObject? = null
    private var latest = JSONObject()
    private var metadataSent = false
    private var completionSent = false
    private var endedSent = false
    private var wasPaused: Boolean? = null
    private var lastHistoryWrite = 0L
    private var recovering: PlaybackRecoverySnapshot? = null
    private var episodePlaylist = NativeEpisodePlaylist()
    private var globalPlaylist = false
    private var globalPlaylistState: JSONObject? = null
    private var transcodeSession: String? = null
    private var conversionGeneration = 0
    private data class SourceHeaders(val origin: String, val headers: Map<String, String>)
    @Volatile private var sourceHeaders = SourceHeaders("", emptyMap())
    private var launchGeneration = 0
    private val serverOwner = (activity.application as SeanimeTvApplication).currentServerOwner()
    private var lastPlayerError = ""
    private var expectedPlaybackUrl = ""
    private var trackState = JSONObject()
    private var watchContinuityEnabled = false
    private var remoteTermination = false
    private var detached = false
    private var translateCues = false
    private var activeCue = ""
    private val translations = LinkedHashMap<String, String>()
    private val pendingTranslations = mutableSetOf<String>()
    private data class SubtitleLine(val track: Int, val start: Double, val end: Double, val text: String)
    private val subtitleEvents = ArrayList<SubtitleLine>()
    private var subtitleGeneration = -1L
    private var snapshotTime = 0L
    private var skipState = NativeSkipState()
    var autoNext: Boolean = false
    var onEpisodeRequested: ((JSONObject) -> Unit)? = null

    fun start() {
        if (started) return
        (NativePlaybackBus.listener as? NativePlaybackCoordinator)?.takeIf { it !== this && it.detached }?.let { previous ->
            check(api.restoreSession(previous.api.snapshotSession())) { "Cannot transfer playback to a different server origin" }
            info = previous.info
            latest = previous.latest
            trackState = previous.trackState
            snapshotTime = previous.snapshotTime
            subtitleGeneration = previous.subtitleGeneration
            subtitleEvents.addAll(previous.subtitleEvents)
            translateCues = previous.translateCues
            activeCue = previous.activeCue
            translations.putAll(previous.translations)
            wasPaused = previous.wasPaused
            lastHistoryWrite = previous.lastHistoryWrite
            episodePlaylist = previous.episodePlaylist.copy()
            globalPlaylist = previous.globalPlaylist
            globalPlaylistState = previous.globalPlaylistState?.let { JSONObject(it.toString()) }
            sourceHeaders = previous.sourceHeaders
            launchGeneration = previous.launchGeneration
            expectedPlaybackUrl = previous.expectedPlaybackUrl
            transcodeSession = previous.transcodeSession
            autoNext = previous.autoNext
            watchContinuityEnabled = previous.watchContinuityEnabled
            metadataSent = previous.metadataSent
            completionSent = previous.completionSent
            endedSent = previous.endedSent
            skipState = previous.skipState
            previous.close()
        }
        started = true
        NativePlaybackBus.listener = this
        NativePlaybackBus.headerProvider = { url ->
            val policy = sourceHeaders
            when {
                api.isServerUrl(url) -> api.requestHeaders()
                origin(url) == policy.origin -> policy.headers
                else -> emptyMap()
            }
        }
        info = runCatching { JSONObject(NativePlaybackBus.playbackInfoJson) }.getOrNull()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            api.events.collect { event ->
                try { onServerEvent(event.type, event.payload) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { reportError(error.message ?: "Invalid playback event") }
            }
        }
        api.connectEvents()
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(250)
                if (translateCues && trackState.optString("subtitleMime") == "text/x-ssa") {
                    val elapsed = if (latest.optBoolean("paused", true)) 0.0 else (SystemClock.elapsedRealtime() - snapshotTime) / 1000.0 * latest.optDouble("speed", 1.0)
                    val position = latest.optLong("positionMs") / 1000.0 + elapsed
                    val track = trackState.optInt("subtitleTrack", -1)
                    val text = subtitleEvents.filter { it.track == track && position >= it.start && position < it.end }.joinToString("\n") { it.text }
                    if (text != activeCue) {
                        activeCue = text
                        NativePlayerActivity.showTranslatedCaption("", "")
                        translateCurrentCue()
                    }
                }
            }
        }
    }

    fun startPlaylist(dbId: Int) = task {
        require(dbId > 0) { "Choose a saved playlist" }
        api.awaitEventsReady()
        onState("Starting playlist…")
        check(api.sendEvent("playlist", JSONObject().put("type", "start-playlist").put("payload", JSONObject()
            .put("clientId", api.clientId).put("dbId", dbId)
            .put("localFilePlaybackMethod", "transcode").put("streamPlaybackMethod", "transcode")))) {
            "The playlist connection was interrupted; try again"
        }
    }

    /** Play one saved item with its stored source choice, outside the global playlist. */
    fun playSinglePlaylistItem(raw: JSONObject) = task {
        require(raw.optJSONObject("episode") != null) { "The playlist episode is missing" }
        api.awaitEventsReady()
        if (globalPlaylist) {
            check(api.sendEvent("playlist", JSONObject().put("type", "stop-playlist").put("payload", JSONObject()))) {
                "The playlist connection was interrupted; try again"
            }
            globalPlaylist = false
            globalPlaylistState = null
        }
        playPlaylistItem(JSONObject(raw.toString())).join()
    }

    fun playLocalFile(path: String) = task {
        require(path.isNotBlank()) { "The episode does not have a local file" }
        api.awaitEventsReady()
        onState("Opening episode…")
        api.request("POST", "/api/v1/directstream/play/localfile", JSONObject().put("path", path).put("clientId", api.clientId))
    }

    fun playUnmatchedFile(file: NativeUnmatchedFile) = task {
        api.awaitEventsReady()
        if (globalPlaylist) {
            check(api.sendEvent("playlist", JSONObject().put("type", "stop-playlist").put("payload", JSONObject()))) {
                "The playlist connection was interrupted; try again"
            }
            globalPlaylist = false
            globalPlaylistState = null
        }
        sourceHeaders = SourceHeaders("", emptyMap())
        playPlaybackInfo(file.playbackInfo(api.baseUrl))
    }

    fun playStream(
        url: String, title: String, mediaId: Long = 0, episode: JSONObject? = null,
        subtitles: JSONArray = JSONArray(), headers: Map<String, String> = emptyMap(), sourceType: String = "unknown", rawMedia: JSONObject? = null,
    ) {
        sourceHeaders = SourceHeaders(origin(url), headers.toMap())
        playPlaybackInfo(JSONObject().put("id", UUID.randomUUID().toString())
            .put("playbackType", "onlinestream").put("streamType", if (sourceType in setOf("m3u8", "hls")) "hls" else sourceType).put("streamUrl", url)
            .put("media", rawMedia?.let { JSONObject(it.toString()) } ?: JSONObject().put("id", mediaId).put("title", JSONObject().put("userPreferred", title)))
            .put("episode", episode?.let(OnlineEpisodeIdentity::canonical) ?: JSONObject.NULL).put("subtitleTracks", subtitles)
            .put("onlinestreamParams", episode?.optJSONObject("onlinestreamParams")))
    }

    fun playPlaybackInfo(playbackInfo: JSONObject) {
        val generation = ++launchGeneration
        task {
            api.awaitEventsReady()
            val incoming = NativePlaybackProtocol.normalizePlaybackInfo(playbackInfo) { source ->
                api.absoluteUrl(source.replace("{{SERVER_URL}}", api.baseUrl))
            }
            val url = incoming.getString("streamUrl")
            require(Uri.parse(url).scheme in setOf("http", "https", "content", "file")) { "The stream URL is unsupported" }
            runCatching { api.request("GET", "/api/v1/status") as? JSONObject }.getOrNull()
                ?.optJSONObject("settings")?.let(::applySettings)
            val saved = recovering
            var startPosition = ((incoming.optJSONObject("initialState")?.optDouble("currentTime", 0.0) ?: 0.0) * 1000).toLong()
            if (watchContinuityEnabled && saved == null && startPosition == 0L && NativePlaybackProtocol.shouldRestoreContinuity(incoming)) {
                val mediaId = incoming.optJSONObject("media")?.optLong("id") ?: 0
                val progress = incoming.optJSONObject("episode")?.optInt("progressNumber") ?: 0
                if (mediaId > 0 && progress > 0) {
                    val history = runCatching { api.request("GET", "/api/v1/continuity/item/$mediaId") as? JSONObject }.getOrNull()?.optJSONObject("item")
                    startPosition = NativePlaybackProtocol.continuityPosition(history, progress)
                }
            }
            if (generation != launchGeneration) return@task
            (activity.application as SeanimeTvApplication).externalPlaybackLease.current?.let {
                (activity.application as SeanimeTvApplication).endExternalPlayback(it.id)
            }
            stopConversion()
            NativePlayerActivity.clearEventSubtitles()
            info = incoming
            episodePlaylist.begin(incoming.optString("id"), generation)
            sendVideoEvent("video-playlist", JSONObject().put("playlist", JSONObject.NULL))
            skipState.reset(incoming.optString("id"))
            expectedPlaybackUrl = url
            remoteTermination = false
            NativePlaybackBus.playbackInfoJson = incoming.toString()
            metadataSent = false
            subtitleEvents.clear()
            subtitleGeneration = -1
            activeCue = ""
            pendingTranslations.clear()
            lastPlayerError = ""
            completionSent = false
            endedSent = false
            wasPaused = null
            latest = JSONObject()
            sendLoaded()
            val title = incoming.optJSONObject("media")?.optJSONObject("title")?.optString("userPreferred").orEmpty().ifBlank { "Seanime TV" }
            val episodeTitle = incoming.optJSONObject("episode")?.optString("displayTitle").orEmpty()
            val displayTitle = episodeTitle.ifBlank { title }
            val subtitles = incoming.optJSONArray("subtitleTracks")?.toString() ?: "[]"
            if (saved != null) {
                val ticket = if (saved.checkpointId.startsWith("native:")) saved.checkpointId else {
                    require(NativePlaybackBus.localUrl(url) != null && Uri.parse(url).path == "/api/v1/directstream/stream") { "Restored source is invalid" }
                    withContext(Dispatchers.IO) { Mobile.refreshPlaybackResume(saved.checkpointId, url) }
                }
                val snapshot = saved.withStream(url, ticket, (activity.application as SeanimeTvApplication).processSessionId)
                    .copy(playbackInfoJson = incoming.toString(), subtitleTracksJson = subtitles)
                withContext(Dispatchers.IO) { PlaybackRecoverySnapshot.write(activity.filesDir, snapshot) }
                recovering = null
                (activity.application as SeanimeTvApplication).releasePlaybackRecovery(saved.checkpointId)
                NativePlayerActivity.markLaunchPending()
                activity.startActivity(NativePlayerActivity.recoveryIntent(activity, snapshot))
            } else if (NativePlayerActivity.isVisible()) {
                NativePlayerActivity.updateMedia(url, displayTitle, subtitles, startPosition, "{}", incoming.optJSONObject("initialState")?.opt("paused") as? Boolean)
            } else {
                NativePlayerActivity.markLaunchPending()
                activity.startActivity(NativePlayerActivity.intent(activity, Uri.parse(url), displayTitle, subtitles,
                    startPosition, "{}", incoming.optJSONObject("initialState")?.toString() ?: "{}"))
            }
            onState("")
            loadEpisodePlaylist(incoming, generation)
        }
    }

    fun recoverIfAvailable() {
        if (recovering != null || NativePlayerActivity.isVisible()) return
        val saved = PlaybackRecoverySnapshot.read(activity.filesDir)?.takeUnless { it.completed } ?: return
        if (saved.checkpointId.startsWith("native:")) {
            if (!NativePlaybackProtocol.usableNativeRecovery(saved.playbackInfoJson, saved.mediaUri) { java.io.File(it).isFile }) {
                PlaybackRecoverySnapshot.clear(activity.filesDir)
                android.util.Log.w("SeanimeTV", "Discarded unusable native playback recovery metadata")
                return
            }
        }
        recovering = saved
        (activity.application as SeanimeTvApplication).claimPlaybackRecovery(saved.checkpointId)
        task {
            api.awaitEventsReady()
            onState("Resuming previous episode…")
            if (saved.checkpointId.startsWith("native:")) {
                val previous = JSONObject(saved.playbackInfoJson)
                info = previous
                val params = previous.optJSONObject("onlinestreamParams")
                val episode = previous.optJSONObject("episode")
                if (params != null) playOnlineEpisode(episode ?: JSONObject().put("onlinestreamParams", params), params)
                else playPlaybackInfo(previous)
            } else withContext(Dispatchers.IO) { Mobile.restorePlaybackResume(saved.checkpointId, api.clientId) }
        }
    }

    fun onServerEvent(type: String, payload: Any?) {
        if (type == "error-toast") { reportError(payload?.toString() ?: "Playback request failed"); return }
        if (type == "settings") { (payload as? JSONObject)?.let(::applySettings); return }
        val envelope = payload as? JSONObject ?: return
        val action = envelope.optString("type")
        val value = envelope.opt("payload")
        when (type) {
            "native-player" -> when (action) {
                "watch" -> (value as? JSONObject)?.let(::playPlaybackInfo)
                "subtitle-event" -> {
                    if (value is JSONObject) NativePlayerActivity.receiveEventSubtitles(value.toString())
                    receiveSubtitleEvents(value)
                }
                "open-and-await" -> onState(value?.toString().orEmpty())
                "abort-open", "error" -> reportError((value as? JSONObject)?.optString("error") ?: value?.toString() ?: "Unable to open stream")
            }
            "videocore" -> handleCommand(action, value)
            "playlist" -> when (action) {
                "current-playlist" -> {
                    globalPlaylist = (value as? JSONObject)?.optJSONObject("playlist") != null
                    globalPlaylistState = (value as? JSONObject)?.takeIf { globalPlaylist }
                }
                "play-episode" -> {
                    val item = (value as? JSONObject)?.optJSONObject("playlistEpisode") ?: return
                    playPlaylistItem(item)
                }
            }
            "client-identity" -> if (info != null && NativePlayerActivity.isVisible()) {
                sendLoaded()
                translateCurrentCue()
            }
        }
    }

    private fun handleCommand(action: String, value: Any?) {
        val url = latest.optString("url").ifBlank { info?.optString("streamUrl").orEmpty() }
        val number = (value as? Number)?.toDouble() ?: 0.0
        when (action) {
            "pause" -> NativePlayerActivity.control(url, "pause", 0.0)
            "resume" -> NativePlayerActivity.control(url, "play", 0.0)
            "seek-to" -> NativePlayerActivity.control(url, "seekTo", number * 1000)
            "seek" -> NativePlayerActivity.control(url, "seekBy", number * 1000)
            "terminate" -> {
                val app = activity.application as SeanimeTvApplication
                if (url != expectedPlaybackUrl || serverOwner != app.currentServerOwner()) return
                if (app.externalPlaybackLease.current != null && app.externalPlaybackLease.matchingSource(
                        info?.optString("id").orEmpty(), url, launchGeneration, serverOwner) == null) return
                remoteTermination = true
                app.endExternalPlaybackForSource(
                    info?.optString("id").orEmpty(), url, launchGeneration, serverOwner)
                PlaybackRecoverySnapshot.clearSource(recoveryFilesDir, info?.optString("id").orEmpty(), url, latest.optString("checkpointId"))
                ++launchGeneration // A delayed prelaunch probe must not revive a terminated source.
                latest = JSONObject(latest.toString()).put("url", url).put("active", false).put("paused", true).put("closed", true)
                stopConversion()
                NativePlayerActivity.control(url, "stop", 0.0)
            }
            "set-audio-track" -> NativePlayerActivity.selectTrack(C.TRACK_TYPE_AUDIO, number.toInt(), url)
            "set-subtitle-track" -> NativePlayerActivity.selectTrack(C.TRACK_TYPE_TEXT, number.toInt(), url)
            "set-media-caption-track" -> NativePlayerActivity.selectMediaCaptionTrack(url, number.toInt())
            "start-onlinestream-watch-party" -> (value as? JSONObject)?.let(::startOnlineWatchParty)
            "get-status" -> {
                val playbackId = info?.optString("id").orEmpty()
                NativePlayerActivity.requestStatus(url) {
                    // onStop already froze this source's final paused position.
                    // Never substitute a cached value while a live player exists.
                    if (playbackId == info?.optString("id") && url == expectedPlaybackUrl && latest.optString("url") == url &&
                        latest.has("active") && !latest.optBoolean("active")) sendStatus("video-status")
                }
            }
            "get-text-tracks" -> sendVideoEvent("video-text-tracks", JSONObject().put("textTracks", trackState.optJSONArray("textTracks") ?: JSONArray()))
            "get-audio-track" -> sendVideoEvent("video-audio-track", JSONObject().put("trackNumber", trackState.optInt("audioTrack", -1)).put("isHLS", false))
            "get-subtitle-track" -> sendVideoEvent("video-subtitle-track", JSONObject().put("trackNumber", trackState.optInt("subtitleTrack", -1)).put("kind", if (trackState.optInt("subtitleTrack", -1) >= 1024) "file" else "event"))
            "get-media-caption-track" -> sendVideoEvent("video-media-caption-track", JSONObject().put("trackIndex", trackState.optInt("subtitleIndex", -1)))
            "get-playback-state" -> sendLoaded("video-playback-state")
            "get-playlist" -> sendVideoEvent("video-playlist", JSONObject().put("playlist", episodePlaylist.current(info?.optString("id").orEmpty()) ?: JSONObject.NULL))
            "get-fullscreen" -> sendVideoEvent("video-fullscreen", JSONObject().put("fullscreen", true))
            "get-pip" -> sendVideoEvent("video-pip", JSONObject().put("pip", false))
            "get-anime-4k" -> sendVideoEvent("video-anime-4k", JSONObject().put("option", NativePlayerActivity.currentAnime4K()))
            "set-skip-data" -> skipState.set(info?.optString("id").orEmpty(), value as? JSONObject)
            "get-skip-data" -> sendVideoEvent("video-skip-data", JSONObject().put("skipData", skipState.data(info?.optString("id").orEmpty())?.toJson() ?: JSONObject.NULL))
            "play-playlist-episode" -> playEpisode(value?.toString() ?: "next")
            "translated-text" -> {
                val translated = value as? JSONObject ?: return
                val original = translated.optString("original")
                val text = translated.optString("translated")
                pendingTranslations.remove(original)
                if (text.isNotBlank()) {
                    if (translations.size >= 128) translations.remove(translations.keys.first())
                    translations[original] = text
                    if (translateCues && original == activeCue) NativePlayerActivity.showTranslatedCaption(original, text)
                }
                translateCurrentCue()
            }
            "show-message" -> NativePlayerActivity.showNotice((value as? JSONObject)?.optString("message") ?: value?.toString().orEmpty())
            "add-external-subtitle-track", "add-subtitle-track" -> (value as? JSONObject)?.let { track ->
                val current = info ?: return
                val tracks = current.optJSONArray("subtitleTracks") ?: JSONArray()
                tracks.put(track)
                current.put("subtitleTracks", tracks)
                NativePlayerActivity.updateMedia(url, "Seanime TV", tracks.toString(), -1, "{}")
            }
        }
    }

    override fun onSnapshot(snapshot: JSONObject) {
        // Media3 emits stop/prepare callbacks for the previous source during replacement.
        // Never attribute their position or close event to the newly selected episode.
        if (expectedPlaybackUrl.isNotBlank() && snapshot.optString("url") != expectedPlaybackUrl) return
        latest = snapshot
        snapshotTime = SystemClock.elapsedRealtime()
        if (info == null) info = runCatching { JSONObject(NativePlaybackBus.playbackInfoJson) }.getOrNull()
        val duration = snapshot.optLong("durationMs")
        if (!metadataSent && duration > 0) {
            sendLoaded()
            sendStatus("video-loaded-metadata")
            sendStatus("video-can-play")
            metadataSent = true
        }
        val paused = snapshot.optBoolean("paused")
        if (wasPaused != paused) sendStatus(if (paused) "video-paused" else "video-resumed")
        wasPaused = paused
        sendStatus("video-status")
        val error = snapshot.optString("error")
        if (error.isNotBlank() && error != lastPlayerError) {
            lastPlayerError = error
            sendVideoEvent("video-error", JSONObject().put("error", error))
        }
        if (!completionSent && NativePlaybackProtocol.hasReachedCompletion(snapshot)) {
            completionSent = true
            sendStatus("video-completed")
        }
        if (duration > 0 && (SystemClock.elapsedRealtime() - lastHistoryWrite > 10_000 || snapshot.optBoolean("closed"))) {
            lastHistoryWrite = SystemClock.elapsedRealtime()
            saveContinuity(snapshot)
        }
        if (!endedSent && snapshot.optBoolean("completed")) {
            endedSent = true
            // The existing global-playlist handler advances on any video-ended event,
            // irrespective of autoNext. Keep its completed item active for manual Next.
            if (autoNext || !globalPlaylist) sendVideoEvent("video-ended", JSONObject().put("autoNext", autoNext))
            if (autoNext && !globalPlaylist && info?.optBoolean("isNakamaWatchParty") != true) playEpisode("next")
        }
        if (snapshot.optBoolean("closed")) {
            ++launchGeneration
            if (!remoteTermination) sendVideoEvent("video-terminated", JSONObject().put("id", info?.optString("id"))
                .put("clientId", api.clientId).put("playerType", playerType()).put("playbackType", info?.optString("playbackType")))
            stopConversion()
        }
    }

    fun skipTarget(url: String, positionMs: Long, durationMs: Long): NativeSkipTarget? {
        if (expectedPlaybackUrl.isBlank() || url != expectedPlaybackUrl) return null
        return skipState.data(info?.optString("id").orEmpty())?.target(positionMs, durationMs)
    }

    fun episodeNavigation(url: String): NativeEpisodeNavigation {
        if (expectedPlaybackUrl.isBlank() || url != expectedPlaybackUrl || info?.optBoolean("isNakamaWatchParty") == true) return NativeEpisodeNavigation()
        return if (globalPlaylist) NativeEpisodeNavigation.global(globalPlaylistState)
        else NativeEpisodeNavigation.local(episodePlaylist.current(info?.optString("id").orEmpty()))
    }

    internal data class ExternalRequest(val source: NativeExternalPlaybackSource, val generation: Int, val serverOwner: Long)
    internal fun externalPlaybackRequest(url: String): ExternalRequest {
        val current = info ?: error("Reopen this source before choosing another player")
        check(url.isNotBlank() && url == expectedPlaybackUrl) { "The selected source changed. Try again." }
        val session = api.snapshotSession()
        val authority = setOfNotNull(session.serverToken, session.identityProof).filter(String::isNotBlank).toSet()
        val parsed = url.toHttpUrlOrNull()
        check(parsed == null || parsed.queryParameterNames.none { key -> parsed.queryParameterValues(key).any { it in authority } }) {
            "This source contains private server authority and cannot be shared with another player."
        }
        return ExternalRequest(NativeExternalPlaybackSource(url, current.optString("id"), api.baseUrl,
            current.optString("playbackType"), current.optString("streamPath"), current.optString("mimeType"),
            sourceHeaders.takeIf { it.origin == origin(url) }?.headers.orEmpty(), transcodeSession != null,
            current.optBoolean("isNakamaWatchParty")), launchGeneration, serverOwner)
    }
    internal fun ownsExternalPlayback(request: ExternalRequest): Boolean =
        ownsExternalSource(request.source.playbackId, request.source.url, request.generation, request.serverOwner)
    internal fun ownsExternalSource(playbackId: String, url: String, generation: Int, expectedServerOwner: Long): Boolean =
        generation == launchGeneration && playbackId == info?.optString("id") && url == expectedPlaybackUrl &&
            expectedServerOwner == serverOwner && serverOwner == (activity.application as SeanimeTvApplication).currentServerOwner()

    override fun onPlayerEvent(type: String, payload: JSONObject) {
        when (type) {
            "cue" -> {
                activeCue = payload.optString("text").take(2000)
                translateCurrentCue()
            }
            "anime4k" -> sendVideoEvent("video-anime-4k", payload)
            "tracks" -> {
                trackState = payload
                sendVideoEvent("video-text-tracks", JSONObject().put("textTracks", payload.optJSONArray("textTracks") ?: JSONArray()))
                sendVideoEvent("video-audio-track", JSONObject().put("trackNumber", payload.optInt("audioTrack", -1)).put("isHLS", false))
                sendVideoEvent("video-subtitle-track", JSONObject().put("trackNumber", payload.optInt("subtitleTrack", -1)).put("kind", if (payload.optInt("subtitleTrack", -1) >= 1024) "file" else "event"))
            }
            "destroyed" -> if (detached && payload.optBoolean("closed")) {
                scope.launch { kotlinx.coroutines.delay(500); close() }
            }
            "stopped" -> if (detached && latest.optBoolean("closed")) {
                // Let the final continuity write and socket termination event complete.
                scope.launch { kotlinx.coroutines.delay(500); close() }
            }
            "seeked" -> sendStatus("video-seeked")
            "audio-track" -> sendVideoEvent("video-audio-track", payload.put("isHLS", false))
            "subtitle-track" -> {
                trackState.put("subtitleTrack", payload.optInt("trackNumber", -1))
                sendVideoEvent("video-subtitle-track", payload.put("kind", payload.optString("kind", "file")))
            }
            "started" -> if (transcodeSession != null) {
                sendStatus("video-seeked")
                if (payload.optBoolean("eventSubtitlesRestored")) info?.let(::loadSubtitleFonts)
            }
        }
    }

    override fun onAction(action: String) {
        if (action.startsWith("translate-track:")) {
            val index = action.substringAfter(':').toIntOrNull() ?: return
            val track = info?.optJSONArray("subtitleTracks")?.optJSONObject(index) ?: return
            sendVideoEvent("translate-subtitle-file-track", track)
            NativePlayerActivity.showNotice("Translating subtitle track with your configured provider…")
            return
        }
        when (action) {
            "next", "previous" -> playEpisode(action)
            "transcode" -> convertForDevice()
            "toggle-translation" -> {
                translateCues = !translateCues
                if (translateCues) translateCurrentCue() else NativePlayerActivity.showTranslatedCaption("", "")
                NativePlayerActivity.showNotice(if (translateCues) "Live subtitle translation is on" else "Live subtitle translation is off")
            }
            "toggle-auto-next" -> task {
                val enabled = !autoNext
                api.request("PATCH", "/api/v1/settings/path", JSONObject().put("path", "library.autoPlayNextEpisode").put("value", enabled))
                autoNext = enabled
                NativePlayerActivity.showNotice(if (autoNext) "Auto-next is on" else "Auto-next is off")
            }
        }
    }

    private fun receiveSubtitleEvents(value: Any?) {
        val envelope = value as? JSONObject
        if (envelope?.has("events") == true) {
            val id = envelope.optString("playbackId")
            if (id.isNotBlank() && id != info?.optString("id")) return
            val generation = envelope.optLong("generationId", 0)
            if (generation < subtitleGeneration) return
            if (generation > subtitleGeneration) { subtitleEvents.clear(); subtitleGeneration = generation }
        }
        val array = when (value) {
            is JSONArray -> value
            is JSONObject -> value.optJSONArray("events") ?: JSONArray().put(value)
            else -> return
        }
        for (i in 0 until array.length()) {
            val event = array.optJSONObject(i) ?: continue
            if (!event.optString("codecID").contains("ASS", true) && !event.optString("codecID").contains("SSA", true)) continue
            val interval = NativePlaybackProtocol.subtitleIntervalSeconds(event) ?: continue
            val text = event.optString("text").replace(Regex("\\{[^}]*}"), "").replace("\\N", "\n").replace("\\n", "\n").take(2000)
            if (text.isNotBlank()) subtitleEvents.add(SubtitleLine(event.optInt("trackNumber"), interval.first, interval.second, text))
        }
        if (subtitleEvents.size > 4000) subtitleEvents.subList(0, subtitleEvents.size - 4000).clear()
    }

    private fun translateCurrentCue() {
        if (!translateCues || activeCue.isBlank()) return
        translations[activeCue]?.let { NativePlayerActivity.showTranslatedCaption(activeCue, it); return }
        if (pendingTranslations.size >= 4 || activeCue in pendingTranslations) return
        if (sendVideoEvent("translate-text", JSONObject().put("text", activeCue))) {
            val requested = activeCue
            pendingTranslations.add(requested)
            scope.launch { kotlinx.coroutines.delay(30_000); pendingTranslations.remove(requested) }
        }
    }

    fun playEpisode(which: String) {
        if (info?.optBoolean("isNakamaWatchParty") == true) return
        if (globalPlaylist) {
            api.sendEvent("playlist", JSONObject().put("type", "play-episode").put("payload",
                JSONObject().put("which", which).put("isCurrentCompleted", false)))
            return
        }
        val list = episodePlaylist.current(info?.optString("id").orEmpty()) ?: return
        val episode = when (which) {
            "next" -> list.optJSONObject("nextEpisode")
            "previous" -> list.optJSONObject("previousEpisode")
            else -> list.optJSONArray("episodes")?.let { array -> (0 until array.length()).mapNotNull(array::optJSONObject).find { it.optString("aniDBEpisode") == which } }
        } ?: return
        requestEpisode(episode)
    }

    private fun playPlaylistItem(item: JSONObject) = sourceTask { request ->
        val episode = item.optJSONObject("episode") ?: error("The playlist episode is missing")
        val media = episode.optJSONObject("baseAnime") ?: error("The playlist media is missing")
        val mediaId = media.optLong("id")
        val path = episode.optJSONObject("localFile")?.optString("path").orEmpty()
        when {
            item.optBoolean("isNakama") || episode.optBoolean("_isNakamaEpisode") || item.optString("watchType") == "nakama" -> {
                api.request("POST", "/api/v1/nakama/play", JSONObject().put("path", path).put("mediaId", mediaId)
                    .put("anidbEpisode", episode.optString("aniDBEpisode")).put("clientId", api.clientId).put("forcePlaybackMethod", "nativeplayer"))
            }
            item.optString("watchType") in setOf("torrent", "debrid") -> {
                val route = if (item.optString("watchType") == "debrid") "/api/v1/debrid/stream/start" else "/api/v1/torrentstream/start"
                api.request("POST", route, JSONObject().put("mediaId", mediaId).put("episodeNumber", episode.optInt("episodeNumber"))
                    .put("aniDBEpisode", episode.optString("aniDBEpisode")).put("autoSelect", true)
                    .put("playbackType", "nativeplayer").put("clientId", api.clientId).put("fileId", ""))
            }
            item.optString("watchType") == "online" -> {
                var params = info?.optJSONObject("onlinestreamParams")
                if (params == null || params.optString("provider").isBlank()) {
                    val providers = NativePlaybackRequest.awaitResponse({ sourceRequest() == request }, {
                        api.request("GET", "/api/v1/extensions/list/onlinestream-provider") as? JSONArray ?: JSONArray()
                    }) ?: return@sourceTask
                    val provider = providers?.optJSONObject(0)?.optString("id").orEmpty()
                    require(provider.isNotBlank()) { "Install an online streaming provider before playing this playlist" }
                    params = JSONObject().put("provider", provider).put("dubbed", false)
                }
                val nextInfo = JSONObject().put("id", UUID.randomUUID().toString()).put("playbackType", "onlinestream")
                    .put("streamType", "unknown").put("media", media).put("episode", episode).put("onlinestreamParams", params)
                playOnlineEpisode(episode, requireNotNull(params), nextInfo)
            }
            path.isNotBlank() -> playLocalFile(path)
            else -> error("This playlist episode has no playable source")
        }
    }

    private fun requestEpisode(episode: JSONObject) {
        val path = episode.optJSONObject("localFile")?.optString("path").orEmpty()
        val params = info?.optJSONObject("onlinestreamParams")
        if (info?.optString("playbackType") == "onlinestream" && params != null) playOnlineEpisode(episode, params)
        else if (info?.optString("playbackType") in setOf("torrent", "debrid", "nakama")) {
            val next = JSONObject(episode.toString()).put("baseAnime", episode.optJSONObject("baseAnime") ?: info?.optJSONObject("media"))
            playPlaylistItem(JSONObject().put("episode", next).put("watchType", info?.optString("playbackType")))
        }
        else if (path.isNotBlank()) playLocalFile(path)
        else onEpisodeRequested?.invoke(episode) ?: reportError("Select a stream source for episode ${episode.optString("episodeNumber")}")
    }

    private fun startOnlineWatchParty(params: JSONObject) = sourceTask { request ->
        val mediaId = params.optLong("mediaId")
        val number = params.optInt("episodeNumber")
        require(mediaId > 0 && number > 0 && params.optString("provider").isNotBlank()) { "The watch party source is incomplete" }
        val response = NativePlaybackRequest.awaitResponse({ sourceRequest() == request }, {
            api.request("POST", "/api/v1/onlinestream/episode-list", JSONObject()
                .put("mediaId", mediaId).put("provider", params.optString("provider")).put("dubbed", params.optBoolean("dubbed"))) as? JSONObject
                ?: error("The watch party provider did not return episodes")
        }) ?: return@sourceTask
        val episodes = response.optJSONArray("episodes") ?: JSONArray()
        val selected = (0 until episodes.length()).mapNotNull(episodes::optJSONObject).firstOrNull { it.optInt("number") == number }
            ?: error("The host's episode is unavailable from this provider")
        val episode = OnlineEpisodeIdentity.withParams(selected, params)
        val media = response.optJSONObject("media") ?: error("The watch party media metadata is missing")
        val nextInfo = JSONObject().put("id", UUID.randomUUID().toString()).put("playbackType", "onlinestream")
            .put("streamType", "unknown").put("media", media).put("episode", episode).put("onlinestreamParams", params)
            .put("isNakamaWatchParty", true).put("disableRestoreFromContinuity", true)
        playOnlineEpisode(episode, params, nextInfo)
    }

    private fun playOnlineEpisode(episode: JSONObject, params: JSONObject, baseInfo: JSONObject? = info) = sourceTask { request ->
        val current = baseInfo ?: return@sourceTask
        val mediaId = current.optJSONObject("media")?.optLong("id") ?: 0
        val number = OnlineEpisodeIdentity.sourceNumber(episode).takeIf { it > 0 } ?: params.optInt("episodeNumber")
        require(number > 0) { "The provider did not identify this episode source" }
        onState("Finding episode $number…")
        val response = NativePlaybackRequest.awaitResponse({ sourceRequest() == request }, {
            api.request("POST", "/api/v1/onlinestream/episode-source", JSONObject()
                .put("mediaId", mediaId).put("episodeNumber", number).put("provider", params.optString("provider"))
                .put("dubbed", params.optBoolean("dubbed")).put("refresh", false)) as? JSONObject
                ?: error("The provider did not return an episode source")
        }) ?: return@sourceTask
        val sources = response.optJSONArray("videoSources") ?: error("No streams are available for episode $number")
        val candidates = (0 until sources.length()).mapNotNull(sources::optJSONObject).filter { it.optString("url").isNotBlank() }
        val selected = candidates.firstOrNull { it.optString("server") == params.optString("server") && it.optString("quality") == params.optString("quality") }
            ?: candidates.firstOrNull { it.optString("server") == params.optString("server") }
            ?: candidates.firstOrNull() ?: error("No streams are available for episode $number")
        val headers = selected.optJSONObject("headers")
        sourceHeaders = SourceHeaders(origin(selected.optString("url")),
            headers?.let { objectValue -> objectValue.keys().asSequence().associateWith { objectValue.optString(it) } }.orEmpty())
        val tracks = selected.optJSONArray("subtitles") ?: JSONArray()
        for (i in 0 until tracks.length()) tracks.optJSONObject(i)?.let { track ->
            track.put("src", track.optString("url")).put("default", track.optBoolean("isDefault")).put("label", track.optString("language"))
        }
        val next = JSONObject(current.toString()).put("id", UUID.randomUUID().toString())
            .put("streamUrl", selected.optString("url")).put("episode", OnlineEpisodeIdentity.canonical(episode) ?: JSONObject.NULL).put("subtitleTracks", tracks)
            .put("streamType", if (selected.optString("type") in setOf("m3u8", "hls")) "hls" else selected.optString("type", "unknown"))
            .put("onlinestreamParams", JSONObject(params.toString()).put("episodeNumber", number).put("mediaId", mediaId)
                .put("quality", selected.optString("quality")).put("server", selected.optString("server")))
        next.remove("initialState")
        playPlaybackInfo(next)
    }

    private suspend fun loadEpisodePlaylist(current: JSONObject, generation: Int) {
        val mediaId = current.optJSONObject("media")?.optLong("id") ?: 0
        if (mediaId <= 0) return
        val entry = runCatching { api.request("GET", "/api/v1/library/anime-entry/$mediaId") as? JSONObject }.getOrNull()
        val params = current.optJSONObject("onlinestreamParams")
        val episodes = when {
            current.optString("playbackType") == "onlinestream" && params != null ->
                runCatching { api.request("POST", "/api/v1/onlinestream/episode-list", JSONObject()
                    .put("mediaId", mediaId).put("provider", params.optString("provider")).put("dubbed", params.optBoolean("dubbed"))) as? JSONObject }.getOrNull()?.optJSONArray("episodes")
            NativeEpisodePlaylist.usesLibraryEpisodes(current.optString("playbackType")) -> entry?.optJSONArray("episodes")
            else -> runCatching { api.request("GET", "/api/v1/anime/episode-collection/$mediaId") as? JSONObject }.getOrNull()?.optJSONArray("episodes")
        }
        kotlin.coroutines.coroutineContext.ensureActive()
        if (info?.optString("id") != current.optString("id")) return
        val playlist = NativeEpisodePlaylist.build(current, entry, episodes)
        if (episodePlaylist.publish(current.optString("id"), generation, playlist)) {
            sendVideoEvent("video-playlist", JSONObject().put("playlist", playlist ?: JSONObject.NULL))
        }
    }

    private fun saveContinuity(snapshot: JSONObject) {
        if (!watchContinuityEnabled) return
        val current = info ?: return
        val mediaId = current.optJSONObject("media")?.optLong("id") ?: 0
        val episode = current.optJSONObject("episode")?.optInt("progressNumber") ?: 0
        if (mediaId <= 0 || episode <= 0 || snapshot.optLong("positionMs") <= 0) return
        val options = JSONObject().put("mediaId", mediaId).put("episodeNumber", episode)
            .put("currentTime", snapshot.optLong("positionMs") / 1000.0).put("duration", snapshot.optLong("durationMs") / 1000.0)
            .put("kind", if (current.optString("playbackType") == "onlinestream") "onlinestream" else "mediastream")
            .put("filepath", current.optString("streamPath"))
        scope.launch { runCatching { api.request("PATCH", "/api/v1/continuity/item", JSONObject().put("options", options)) } }
    }

    private fun convertForDevice() = task {
        if (transcodeSession != null) return@task
        val current = info ?: return@task
        val request = sourceRequest()
        val conversion = ++conversionGeneration
        onState("Converting for this device…")
        val response = NativePlaybackRequest.awaitResponse({ sourceRequest() == request && conversion == conversionGeneration }, {
            api.request("POST", "/api/v1/mediastream/source/request", JSONObject()
                .put("sourceUrl", current.optString("streamUrl")).put("playbackId", current.optString("id"))) as? JSONObject
                ?: error("The server did not return a conversion session")
        }, { obsolete ->
            obsolete.optString("sessionId").takeIf { it.isNotBlank() && it != transcodeSession }?.let { session ->
                runCatching { api.request("POST", "/api/v1/mediastream/source/stop", JSONObject().put("sessionId", session)) }
            }
        }) ?: return@task
        require(response.optDouble("timeOffset", 0.0) == 0.0) { "Converted stream must keep its original timeline" }
        transcodeSession = response.getString("sessionId")
        val originalTrack = trackState.optInt("subtitleTrack", -1).takeIf { it >= 0 }
            ?: if (trackState.optBoolean("subtitlesDisabled")) -1 else null
        NativePlayerActivity.configureEventSubtitles(current.toString(), originalTrack)
        loadSubtitleFonts(current)
        sendStatus("video-seeked")
        expectedPlaybackUrl = api.absoluteUrl(response.getString("streamUrl"))
        NativePlayerActivity.updateMedia(expectedPlaybackUrl, "Seanime TV",
            current.optJSONArray("subtitleTracks")?.toString() ?: "[]", -1, "{}")
        onState("")
    }

    private fun loadSubtitleFonts(current: JSONObject) = task {
        val playbackId = current.optString("id")
        val session = transcodeSession ?: return@task
        val attachments = current.optJSONObject("mkvMetadata")?.optJSONArray("attachments") ?: return@task
        var totalBytes = 0L
        for (i in 0 until attachments.length()) {
            val font = attachments.optJSONObject(i) ?: continue
            val size = font.optLong("size", -1)
            val name = font.optString("filename")
            if (font.optString("type") != "font" || name.isBlank() || size !in 1..16L * 1024 * 1024 || totalBytes + size > 48L * 1024 * 1024) continue
            if (info?.optString("id") != playbackId || transcodeSession != session) return@task
            val bytes = try { api.download("/api/v1/directstream/att/${Uri.encode(name)}", maxBytes = 16 * 1024 * 1024) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { continue }
            if (bytes.size > 16 * 1024 * 1024 || totalBytes + bytes.size > 48L * 1024 * 1024 ||
                info?.optString("id") != playbackId || transcodeSession != session) continue
            totalBytes += bytes.size
            NativePlayerActivity.addEventSubtitleFont(playbackId, name, bytes)
        }
    }

    private fun stopConversion() {
        ++conversionGeneration
        val session = transcodeSession ?: return
        transcodeSession = null
        scope.launch { runCatching { api.request("POST", "/api/v1/mediastream/source/stop", JSONObject().put("sessionId", session)) } }
    }

    private fun applySettings(settings: JSONObject) {
        val library = settings.optJSONObject("library") ?: return
        watchContinuityEnabled = library.optBoolean("enableWatchContinuity", false)
        autoNext = library.optBoolean("autoPlayNextEpisode", false)
    }

    private fun sendLoaded(type: String = "video-loaded") {
        val current = info ?: return
        sendVideoEvent(type, JSONObject().put("state", JSONObject().put("clientId", api.clientId)
            .put("playerType", playerType()).put("playbackInfo", current)))
    }

    private fun playerType() = if (info?.optString("playbackType") == "onlinestream") "web" else "native"
    private fun sendStatus(type: String) = sendVideoEvent(type, NativePlaybackProtocol.status(info, api.clientId, latest))
    private fun sendVideoEvent(type: String, payload: JSONObject) = api.sendEvent("videocore", NativePlaybackProtocol.event(api.clientId, type, payload))
    private fun origin(url: String): String = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }.orEmpty()
    private fun sourceRequest() = NativePlaybackRequest(launchGeneration, info?.optString("id").orEmpty(), expectedPlaybackUrl)
    private fun sourceTask(block: suspend (NativePlaybackRequest) -> Unit) = run {
        ++launchGeneration
        val request = sourceRequest()
        task { if (sourceRequest() == request) block(request) }
    }
    private fun task(block: suspend () -> Unit) = scope.launch {
        try { block() } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
            reportError(error.message ?: "Playback failed")
        }
    }
    private fun reportError(message: String) {
        NativePlayerActivity.clearFailedLaunch()
        onState("")
        onError(message)
        if (NativePlayerActivity.isVisible()) NativePlayerActivity.showNotice(message)
        recovering?.let { (activity.application as SeanimeTvApplication).releasePlaybackRecovery(it.checkpointId) }
        recovering = null
    }

    /** Keep an active player session alive if Android reclaims its background launcher activity. */
    fun detachToPlayer(): Boolean {
        val playerActivity = NativePlayerActivity.activeActivity() ?: return false
        activity = playerActivity
        detached = true
        onError = {}
        onState = {}
        return true
    }

    fun close() {
        if (NativePlaybackBus.listener === this) {
            NativePlaybackBus.listener = null
            NativePlaybackBus.headerProvider = null
        }
        scope.cancel()
        if (detached) api.close()
    }
}
