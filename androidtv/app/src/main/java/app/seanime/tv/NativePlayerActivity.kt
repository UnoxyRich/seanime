package app.seanime.tv

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.seanime.tv.platform.NativeTvPlayerState
import app.seanime.tv.platform.NativeTvPlayerPresentation
import app.seanime.tv.platform.PlayerChoice
import app.seanime.tv.platform.PlayerChoiceDialog
import app.seanime.tv.platform.NativePlaybackCoordinator
import app.seanime.tv.platform.NativeTrackSelection
import app.seanime.tv.platform.NativeEpisodeNavigation
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.seanime.tv.platform.PlaybackHeaderInterceptor
import okhttp3.OkHttpClient
import okhttp3.Call
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import app.seanime.tv.data.ProviderUrlPolicy
import app.seanime.tv.data.ProviderMediaContext
import app.seanime.tv.data.NativeNetworkFailure
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.documentfile.provider.DocumentFile
import app.seanime.tv.gomobile.mobile.Mobile
import app.seanime.tv.platform.NativeAnime4K
import androidx.media3.common.ColorInfo
import app.seanime.tv.platform.NativeEventSubtitleOverlay
import app.seanime.tv.platform.NativeAssSession
import app.seanime.tv.platform.NativePlaybackBus
import app.seanime.tv.platform.NativePlatformActions
import app.seanime.tv.platform.NativeExternalPlaybackService
import app.seanime.tv.platform.NativeExternalLaunchGate
import app.seanime.tv.platform.planExternalPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class NativePlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var assSession: NativeAssSession? = null
    private var eventSubtitles: NativeEventSubtitleOverlay? = null
    private var eventTextWasDisabled: Boolean? = null
    private var eventBridgeRestored = false
    private var anime4kPreset = "off"
    private var anime4kKey = ""
    private var anime4kGeneration = 0
    private var anime4kEffectsApplied = false
    private var activePlayerView: PlayerView? = null
    private val presentation = NativeTvPlayerState()
    private var externalLaunchJob: Job? = null
    private val externalLaunchGate = NativeExternalLaunchGate()
    @Volatile private var externalProbeCall: Call? = null
    private var externalLeaseId: String? = null
    private var externalReturnSource: String? = null
    private var externalReturnPlaybackId: String? = null
    private var externalReturnGeneration = -1
    private var externalReturnServerOwner = -1L
    private val externalPlayerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        externalLeaseId?.let { id -> (application as SeanimeTvApplication).endExternalPlayback(id) }
        externalLeaseId = null
        val sourceMatches = externalReturnSource == mediaUri?.toString() &&
            (NativePlaybackBus.listener as? NativePlaybackCoordinator)?.ownsExternalSource(externalReturnPlaybackId.orEmpty(),
                externalReturnSource.orEmpty(), externalReturnGeneration, externalReturnServerOwner) == true
        externalReturnSource = null; externalReturnPlaybackId = null
        if (sourceMatches) {
            presentation.controls = true
            showPlayerNotice("Playback remains paused in Seanime. Progress in other players is not synchronized.")
        }
    }
    private var currentCueText = ""
    private var lastPositionMs = 0L
    private var completed = false
    private var mediaUri: Uri? = null
    private var mediaTitle = "Seanime TV"
    private var subtitleTracksJson = ""
    private var subtitleStyleJson = ""
    private var resumePlayWhenReady = true
    private var savedPlaybackParameters = PlaybackParameters.DEFAULT
    private var savedTrackSelectionParameters: TrackSelectionParameters? = null
    private var savedVolume = 1f
    private var muted = false
    private var playbackCheckpointId = ""
    private var playbackCheckpointUrl = ""
    private var checkpointGeneration = 0
    @Volatile private var playbackRecoveryDismissed = false
    private val checkpointExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "Seanime TV playback checkpoint") }
    private val subtitleCacheFiles = mutableListOf<File>()
    private val mediaHttpClients = mutableListOf<OkHttpClient>()
    private data class MediaAuthority(val context: ProviderMediaContext, val inlineSubtitles: Set<String>)
    private class MediaHeaders(val interceptor: PlaybackHeaderInterceptor)
    private val progressHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Seanime TV screenshot")
    }
    private val publishProgress = object : Runnable {
        override fun run() {
            val current = player
            if (current != null) {
                lastPositionMs = current.currentPosition
                publishSnapshot()
                persistPlaybackRecovery()
                progressHandler.postDelayed(this, 2_000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceActivityInstance = WeakReference(this)
        externalLeaseId = savedInstanceState?.getString("externalLeaseId")
        externalReturnSource = savedInstanceState?.getString("externalReturnSource")
        externalReturnPlaybackId = savedInstanceState?.getString("externalReturnPlaybackId")
        externalReturnGeneration = savedInstanceState?.getInt("externalReturnGeneration", -1) ?: -1
        externalReturnServerOwner = savedInstanceState?.getLong("externalReturnServerOwner", -1) ?: -1
        anime4kPreset = getSharedPreferences("native-player-settings", MODE_PRIVATE).getString("anime4k", "off") ?: "off"
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )

        val checkpointIdExtra = intent.getStringExtra(EXTRA_CHECKPOINT_ID).orEmpty()
        val suppliedPlaybackInfo = savedInstanceState?.getString("nativePlaybackInfo") ?: intent.getStringExtra("nativePlaybackInfo")
        if (!suppliedPlaybackInfo.isNullOrBlank()) NativePlaybackBus.playbackInfoJson = suppliedPlaybackInfo
        val persistedRecovery = PlaybackRecoverySnapshot.read(filesDir)
        val recovery = persistedRecovery?.takeIf { checkpointIdExtra.isNotBlank() && it.checkpointId == checkpointIdExtra }
        mediaUri = savedInstanceState?.getString(STATE_MEDIA_URI)?.let(Uri::parse)
            ?: recovery?.mediaUri?.let(Uri::parse) ?: intent.data
        if (mediaUri == null) {
            nativePlayerVisible = false
            finish()
            return
        }
        mediaTitle = (savedInstanceState?.getString(EXTRA_TITLE) ?: recovery?.title ?: intent.getStringExtra(EXTRA_TITLE)).orEmpty().ifBlank { "Seanime TV" }
        subtitleTracksJson = (savedInstanceState?.getString(EXTRA_SUBTITLES) ?: recovery?.subtitleTracksJson ?: intent.getStringExtra(EXTRA_SUBTITLES)).orEmpty()
        subtitleStyleJson = (savedInstanceState?.getString(EXTRA_SUBTITLE_STYLE) ?: recovery?.subtitleStyleJson ?: intent.getStringExtra(EXTRA_SUBTITLE_STYLE)).orEmpty()
        lastPositionMs = savedInstanceState?.getLong(EXTRA_START_POSITION) ?: recovery?.positionMs ?: intent.getLongExtra(EXTRA_START_POSITION, 0L)
        val playbackSettings = runCatching { JSONObject(intent.getStringExtra(EXTRA_PLAYBACK_SETTINGS).orEmpty()) }.getOrNull() ?: JSONObject()
        resumePlayWhenReady = savedInstanceState?.getBoolean(STATE_PLAY_WHEN_READY, true) ?: recovery?.playWhenReady ?: !playbackSettings.optBoolean("paused", false)
        completed = savedInstanceState?.getBoolean(STATE_COMPLETED, false) ?: recovery?.completed ?: false
        savedPlaybackParameters = PlaybackParameters(
            savedInstanceState?.getFloat(STATE_SPEED, 1f) ?: recovery?.speed ?: playbackSettings.optDouble("speed", 1.0).toFloat().takeIf { it.isFinite() && it > 0 } ?: 1f,
            savedInstanceState?.getFloat(STATE_PITCH, 1f) ?: recovery?.pitch ?: 1f,
        )
        savedVolume = savedInstanceState?.getFloat(STATE_VOLUME, 1f) ?: recovery?.volume ?: playbackSettings.optDouble("volume", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        muted = savedInstanceState?.getBoolean(STATE_MUTED, false) ?: recovery?.muted ?: playbackSettings.optBoolean("muted", false)
        savedTrackSelectionParameters = savedInstanceState?.getBundle(STATE_TRACK_SELECTION)?.let(TrackSelectionParameters::fromBundle)
            ?: recovery?.trackSelection?.let(PlaybackRecoverySnapshot::decodeBundle)?.let(TrackSelectionParameters::fromBundle)
        playbackCheckpointId = savedInstanceState?.getString(STATE_CHECKPOINT_ID) ?: recovery?.checkpointId ?: intent.getStringExtra(EXTRA_CHECKPOINT_ID).orEmpty()
        playbackCheckpointUrl = savedInstanceState?.getString(STATE_CHECKPOINT_URL) ?: recovery?.mediaUri.orEmpty()

        val appProcessSession = (application as SeanimeTvApplication).processSessionId
        val savedProcessSession = savedInstanceState?.getString(STATE_PROCESS_SESSION_ID).orEmpty()
        val persistedForThisActivity = persistedRecovery?.mediaUri == mediaUri?.toString()
        val processWasRecreated = (savedProcessSession.isNotBlank() && savedProcessSession != appProcessSession) ||
            (persistedForThisActivity && persistedRecovery?.processSessionId?.let { it.isNotBlank() && it != appProcessSession } == true)
        if (processWasRecreated && !completed) {
            val snapshot = recovery ?: persistedRecovery?.takeIf { persistedForThisActivity } ?: recoverySnapshot()
            if (snapshot != null) {
                val app = application as SeanimeTvApplication
                if (app.ownsPlaybackRecovery(snapshot.checkpointId)) {
                    nativePlayerVisible = false
                    finish()
                    return
                }
                app.claimPlaybackRecovery(snapshot.checkpointId)
                PlaybackRecoverySnapshot.write(filesDir, snapshot)
                nativePlayerVisible = false
                startActivity(NativePlaybackBus.recoveryIntent(this, snapshot.checkpointId))
                finish()
                return
            }
        }

        // Engine surfaces only. All controls and dialogs are authored in Compose for TV.
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        activePlayerView = PlayerView(this).apply {
            useController = false
            isFocusable = false
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyAnime4K() }
        }.also { frame.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        eventSubtitles = NativeEventSubtitleOverlay(this, frame, { player }) { message ->
            showPlayerNotice(message)
        }
        savedInstanceState?.getString("nativeEventSubtitleId")?.takeIf { it.isNotBlank() }?.let { id ->
            val metadata = runCatching { JSONObject(NativePlaybackBus.playbackInfoJson) }.getOrNull()
            if (metadata?.optString("id") == id) {
                eventSubtitles?.configure(metadata, savedInstanceState.getInt("nativeEventSubtitleTrack", -1))
                eventTextWasDisabled = savedInstanceState.getBoolean("nativeEventTextWasDisabled", false)
                eventBridgeRestored = true
            }
        }
        presentation.title = mediaTitle
        frame.addView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { NativeTvPlayerPresentation(presentation, ::handlePresentationAction, ::seekFromPresentation) }
        }, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) initializePlayer()
    }

    private fun initializePlayer() {
        if (player != null || isFinishing) return
        val uri = mediaUri ?: return
        if (NativePlaybackBus.listener == null && NativePlaybackBus.playbackInfoJson.isNotBlank() && uri.scheme in setOf("http", "https")) {
            // The process may survive while Android reclaims both background activities.
            // Recreate the native API owner before resuming authenticated network reads.
            recoverySnapshot()?.let { snapshot ->
                PlaybackRecoverySnapshot.write(filesDir, snapshot)
                nativePlayerVisible = false
                startActivity(NativePlaybackBus.recoveryIntent(this, snapshot.checkpointId))
                finish()
                return
            }
        }
        fun httpClient(provider: Boolean): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(false)
            .addNetworkInterceptor { chain -> requireNotNull(chain.request().tag(MediaHeaders::class.java)).interceptor.intercept(chain) }
            .apply { if (provider) ProviderUrlPolicy.secureClient(this) }
            .build().also { mediaHttpClients.add(it) }
        val trustedHttp = httpClient(false)
        val providerHttp = httpClient(true)
        val nativeAss = NativeAssSession(this, { item ->
            // This tag belongs to the MediaItem, not the mutable currently selected episode.
            val authority = item.localConfiguration?.tag as? MediaAuthority
                ?: MediaAuthority(ProviderMediaContext(true), emptySet())
            val calls = Call.Factory { request ->
                val initial = request.url.toString()
                authority.context.requireMediaUri(initial, authority.inlineSubtitles)
                val client = if (authority.context.requiresPublicUrl(initial)) providerHttp else trustedHttp
                val headers = MediaHeaders(PlaybackHeaderInterceptor { target -> authority.context.headersFor(initial, target) })
                client.newCall(request.newBuilder().tag(MediaHeaders::class.java, headers).build())
            }
            val httpFactory = OkHttpDataSource.Factory(calls).setUserAgent("Seanime TV/0.1.0")
            val delegates = DefaultDataSource.Factory(this, httpFactory)
            DataSource.Factory { ProviderMediaDataSource(delegates.createDataSource(), authority.context, authority.inlineSubtitles) }
        }, activePlayerView?.subtitleView)
        assSession = nativeAss
        val exoPlayer = nativeAss.player
        player = exoPlayer
        anime4kEffectsApplied = false
        activeInstance = WeakReference(this)
        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?) {
                if (player === exoPlayer) applyAnime4K(inputFormat = format)
            }
        })
        exoPlayer.addListener(object : Player.Listener {
            private fun ownsPlayer() = this@NativePlayerActivity.player === exoPlayer

            override fun onPlayerError(error: PlaybackException) {
                if (ownsPlayer()) NativeNetworkFailure.logDebug(this@NativePlayerActivity,
                    NativeNetworkFailure.Surface.PLAYER, error, error.errorCode)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!ownsPlayer()) return
                lastPositionMs = exoPlayer.currentPosition
                if (isPlaying) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!ownsPlayer()) return
                lastPositionMs = exoPlayer.currentPosition
                if (playbackState == Player.STATE_ENDED && !completed) {
                    completed = true
                    clearPlaybackRecovery()
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (!ownsPlayer()) return
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    completed = false
                    eventSubtitles?.seek()
                    publishSnapshot()
                    NativePlaybackBus.event("seeked")
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { if (ownsPlayer()) applyAnime4K() }

            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                if (!ownsPlayer()) return
                val text = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n").trim()
                if (text != currentCueText) {
                    currentCueText = text
                    presentation.translation = ""
                    NativePlaybackBus.event("cue", JSONObject().put("text", text))
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (!ownsPlayer()) return
                val textTracks = JSONArray()
                var audioIndex = 0
                var textIndex = 0
                var selectedAudio = -1
                var selectedText = -1
                var selectedTextNumber = -1
                var selectedTextMime = ""
                for (group in tracks.groups) for (index in 0 until group.length) {
                    val format = group.getTrackFormat(index)
                    when (group.type) {
                        C.TRACK_TYPE_AUDIO -> {
                            if (group.isTrackSelected(index)) selectedAudio = audioIndex
                            audioIndex += 1
                        }
                        C.TRACK_TYPE_TEXT -> {
                            val trackNumber = format.id?.substringAfterLast(':')?.toIntOrNull() ?: textIndex
                            if (group.isTrackSelected(index)) {
                                selectedText = textIndex
                                selectedTextNumber = trackNumber
                                selectedTextMime = if (format.codecs == MimeTypes.TEXT_SSA) MimeTypes.TEXT_SSA else format.sampleMimeType.orEmpty()
                            }
                            textTracks.put(JSONObject().put("number", trackNumber).put("index", textIndex).put("type", "subtitles")
                                .put("language", format.language ?: "").put("label", format.label ?: format.language ?: "Track ${textIndex + 1}"))
                            textIndex += 1
                        }
                    }
                }
                eventSubtitles?.takeIf { it.configured }?.let { bridge ->
                    bridge.tracks.values.forEach { track -> textTracks.put(JSONObject().put("number", track.optInt("number"))
                        .put("type", "subtitles").put("label", track.optString("name", "Track ${track.optInt("number")}"))
                        .put("language", track.optString("language"))) }
                    if (bridge.selectedTrack >= 0) { selectedTextNumber = bridge.selectedTrack; selectedTextMime = bridge.selectedMime }
                }
                NativePlaybackBus.event("tracks", JSONObject().put("textTracks", textTracks)
                    .put("subtitlesDisabled", exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
                    .put("audioTrack", selectedAudio).put("subtitleTrack", selectedTextNumber).put("subtitleIndex", selectedText).put("subtitleMime", selectedTextMime))
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (!ownsPlayer()) return
                if (events.contains(Player.EVENT_PLAYER_ERROR)) showPlaybackError(player.playerError)
                publishSnapshot()
            }
        })

        activePlayerView?.let {
            it.player = exoPlayer
            applySubtitleStyle(it, subtitleStyleJson)
        }
        exoPlayer.playbackParameters = savedPlaybackParameters
        savedTrackSelectionParameters?.let { exoPlayer.trackSelectionParameters = it }
        exoPlayer.volume = if (muted) 0f else savedVolume
        exoPlayer.setMediaItem(buildMediaItem(uri, mediaTitle, subtitleTracksJson), lastPositionMs.coerceAtLeast(0))
        anime4kKey = ""
        applyAnime4K(preparingPlayer = true)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = resumePlayWhenReady && !completed
        nativePlayerVisible = true
        capturePlaybackSource(uri)
        eventSubtitles?.resume()
        NativePlaybackBus.event("started", JSONObject().put("eventSubtitlesRestored", eventBridgeRestored))
        eventBridgeRestored = false
        progressHandler.postDelayed(publishProgress, 2_000)
        progressHandler.post(refreshPresentation)
    }

    private fun loadMedia(uri: Uri, title: String, subtitleTracksJson: String, startPositionMs: Long, subtitleStyleJson: String, paused: Boolean? = null) {
        cancelExternalPreparation()
        // A token/URL refresh of the current episode preserves the decoder's
        // position and pause state across source conversion and token refresh.
        val preservePosition = startPositionMs < 0
        if (mediaUri != uri) playbackRecoveryDismissed = false
        val position = if (preservePosition) player?.currentPosition ?: lastPositionMs else startPositionMs.coerceAtLeast(0)
        val playWhenReady = paused?.not() ?: if (preservePosition) player?.playWhenReady ?: resumePlayWhenReady else true
        if (!preservePosition) {
            playbackCheckpointId = ""
            playbackCheckpointUrl = ""
        }
        presentation.translation = ""
        currentCueText = ""
        mediaUri = uri
        mediaTitle = title
        this.subtitleTracksJson = subtitleTracksJson
        this.subtitleStyleJson = subtitleStyleJson
        capturePlaybackSource(uri)
        showPlaybackError(null)
        if (!preservePosition) completed = false
        lastPositionMs = position
        resumePlayWhenReady = playWhenReady
        val current = player ?: return
        current.stop()
        subtitleCacheFiles.forEach { it.delete() }
        subtitleCacheFiles.clear()
        activePlayerView?.let { applySubtitleStyle(it, subtitleStyleJson) }
        current.setMediaItem(buildMediaItem(uri, title, subtitleTracksJson), position)
        current.prepare()
        current.playWhenReady = playWhenReady
        publishSnapshot()
    }

    private fun publishSnapshot(active: Boolean = true, closed: Boolean = false) {
        val current = player ?: return
        val uri = current.currentMediaItem?.localConfiguration?.uri ?: return
        NativePlaybackBus.snapshot(JSONObject()
            .put("url", uri.toString())
            .put("checkpointId", playbackCheckpointId)
            .put("positionMs", current.currentPosition.coerceAtLeast(0))
            .put("durationMs", current.duration.coerceAtLeast(0))
            .put("bufferedPositionMs", current.bufferedPosition.coerceAtLeast(0))
            .put("paused", !active || !current.playWhenReady || completed || current.playerError != null)
            .put("buffering", current.playbackState == Player.STATE_BUFFERING)
            .put("completed", completed)
            .put("error", current.playerError?.errorCodeName ?: "")
            .put("speed", current.playbackParameters.speed.toDouble())
            .put("volume", (if (muted) savedVolume else current.volume).toDouble())
            .put("muted", muted)
            .put("videoWidth", current.videoSize.width)
            .put("videoHeight", current.videoSize.height)
            .put("active", active)
            .put("closed", closed))
    }

    private fun rememberPlaybackState() {
        player?.let {
            lastPositionMs = it.currentPosition
            resumePlayWhenReady = it.playWhenReady
            savedPlaybackParameters = it.playbackParameters
            savedTrackSelectionParameters = it.trackSelectionParameters
            if (!muted) savedVolume = it.volume
        }
    }

    private fun recoverySnapshot(): PlaybackRecoverySnapshot? {
        val uri = playbackCheckpointUrl.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: mediaUri ?: return null
        if (playbackRecoveryDismissed || playbackCheckpointId.isBlank() || completed) return null
        if (playbackCheckpointId.startsWith("native:")) {
            val info = runCatching { JSONObject(NativePlaybackBus.playbackInfoJson) }.getOrNull() ?: return null
            if (info.optString("streamUrl") != uri.toString()) return null
        }
        val current = player
        return PlaybackRecoverySnapshot(
            checkpointId = playbackCheckpointId,
            mediaUri = uri.toString(),
            processSessionId = (application as SeanimeTvApplication).processSessionId,
            title = mediaTitle,
            subtitleTracksJson = subtitleTracksJson,
            subtitleStyleJson = subtitleStyleJson,
            positionMs = current?.currentPosition?.coerceAtLeast(0) ?: lastPositionMs.coerceAtLeast(0),
            playWhenReady = current?.playWhenReady ?: resumePlayWhenReady,
            completed = completed,
            speed = current?.playbackParameters?.speed ?: savedPlaybackParameters.speed,
            pitch = current?.playbackParameters?.pitch ?: savedPlaybackParameters.pitch,
            volume = if (muted) savedVolume else current?.volume ?: savedVolume,
            muted = muted,
            playbackInfoJson = NativePlaybackBus.playbackInfoJson,
            trackSelection = PlaybackRecoverySnapshot.encodeBundle(
                current?.trackSelectionParameters?.toBundle() ?: savedTrackSelectionParameters?.toBundle(),
            ),
        )
    }

    private fun persistPlaybackRecovery() {
        if (playbackRecoveryDismissed) return
        val snapshot = recoverySnapshot() ?: return
        val generation = checkpointGeneration
        runCatching {
            checkpointExecutor.execute {
                if (!playbackRecoveryDismissed && generation == checkpointGeneration) PlaybackRecoverySnapshot.write(filesDir, snapshot)
            }
        }.onFailure { Log.w("SeanimeTV", "Could not queue the playback recovery snapshot", it) }
    }

    private fun clearPlaybackRecovery() {
        playbackRecoveryDismissed = true
        checkpointGeneration += 1
        val checkpoint = playbackCheckpointId
        val source = playbackCheckpointUrl.ifBlank { mediaUri?.toString().orEmpty() }
        runCatching {
            // Serialize deletion after any in-flight snapshot write. Later writes
            // check the dismissal flag and cannot recreate the checkpoint.
            checkpointExecutor.execute { PlaybackRecoverySnapshot.clearCheckpoint(filesDir, checkpoint, source) }
        }.onFailure {
            PlaybackRecoverySnapshot.clearCheckpoint(filesDir, checkpoint, source)
            Log.w("SeanimeTV", "Could not queue playback recovery cleanup", it)
        }
    }

    private fun capturePlaybackSource(uri: Uri) {
        val url = uri.toString()
        if (playbackCheckpointUrl == url && playbackCheckpointId.isNotBlank()) return
        if (NativePlaybackBus.localUrl(url) == null || uri.path != "/api/v1/directstream/stream") {
            if (playbackCheckpointId.isBlank()) {
                playbackCheckpointId = "native:${java.util.UUID.randomUUID()}"
                playbackCheckpointUrl = url
                persistPlaybackRecovery()
            }
            return
        }
        val generation = ++checkpointGeneration
        playbackCheckpointId = ""
        playbackCheckpointUrl = url
        checkpointExecutor.execute {
            runCatching { Mobile.capturePlaybackResume(url) }
                .onSuccess { checkpoint ->
                    runOnUiThread {
                        if (!isDestroyed && !isFinishing && checkpointGeneration == generation && mediaUri == uri) {
                            playbackCheckpointId = checkpoint
                            persistPlaybackRecovery()
                        }
                    }
                }
                .onFailure { Log.w("SeanimeTV", "Could not save playback source for process recovery", it) }
        }
    }

    private fun showPlayerNotice(message: String) {
        presentation.notice = message
        presentation.noticeVersion++
    }

    private fun showPlaybackError(error: PlaybackException?) {
        presentation.error = error?.let {
            when (it.errorCode) {
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The source file couldn’t be found."
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Seanime can’t access this source."
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "The source connection was interrupted."
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The source server couldn’t provide this video."
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This device can’t play the source format."
                else -> "Playback stopped (${it.errorCodeName})."
            }
        }.orEmpty()
        if (error != null) { presentation.dialog = null; presentation.controls = true }
        activePlayerView?.visibility = if (error == null) View.VISIBLE else View.INVISIBLE
    }

    private val refreshPresentation = object : Runnable {
        override fun run() {
            player?.let { current ->
                presentation.title = mediaTitle
                presentation.position = current.currentPosition.coerceAtLeast(0)
                presentation.duration = current.duration.coerceAtLeast(0)
                presentation.paused = !current.playWhenReady || completed
                presentation.buffering = current.playbackState == Player.STATE_BUFFERING
                presentation.skipLabel = currentSkipTarget()?.label.orEmpty()
                val navigation = (NativePlaybackBus.listener as? NativePlaybackCoordinator)?.episodeNavigation(mediaUri?.toString().orEmpty())
                    ?: NativeEpisodeNavigation()
                presentation.canPrevious = navigation.previous
                presentation.canNext = navigation.next
                progressHandler.postDelayed(this, 250)
            }
        }
    }

    private fun seekFromPresentation(position: Long) {
        val current = player ?: return
        completed = false
        current.seekTo(position.coerceIn(0, current.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
        presentation.position = current.currentPosition.coerceAtLeast(0)
        presentation.skipLabel = currentSkipTarget()?.label.orEmpty()
    }

    private fun currentSkipTarget() = player?.let { current ->
        (NativePlaybackBus.listener as? NativePlaybackCoordinator)?.skipTarget(mediaUri?.toString().orEmpty(), current.currentPosition, current.duration)
    }

    private fun handlePresentationAction(action: String) {
        when (action) {
            "resume" -> player?.play()
            "pause" -> player?.pause()
            "play" -> player?.let { if (it.playWhenReady && !completed) it.pause() else { if (completed) seekFromPresentation(0); it.play() } }
            "rewind" -> seekFromPresentation((player?.currentPosition ?: 0) - 10_000)
            "forward" -> seekFromPresentation((player?.currentPosition ?: 0) + 10_000)
            "skip" -> currentSkipTarget()?.let { seekFromPresentation(it.endMs) }
            "audio" -> showTrackDialog(C.TRACK_TYPE_AUDIO)
            "subtitles" -> showTrackDialog(C.TRACK_TYPE_TEXT)
            "picture" -> showAnime4KDialog()
            "options" -> showOptionsDialog()
            "external" -> openInAnotherPlayer()
            "previous", "next" -> NativePlaybackBus.action(action)
            "convert" -> NativePlaybackBus.action("transcode")
            "retry" -> retryPlayback()
            "exit" -> returnFromPlayer()
        }
        player?.let { presentation.paused = !it.playWhenReady || completed }
    }

    private fun showOptionsDialog() {
        val autoNext = (NativePlaybackBus.listener as? NativePlaybackCoordinator)?.autoNext == true
        presentation.dialog = PlayerChoiceDialog("Playback options", listOf(
            PlayerChoice("speed", "Speed · ${player?.playbackParameters?.speed ?: 1f}×", choose = ::showSpeedDialog),
            PlayerChoice("volume", "Volume", choose = ::showVolumeDialog),
            PlayerChoice("auto-next", "Auto-next · ${if (autoNext) "On" else "Off"}") { NativePlaybackBus.action("toggle-auto-next") },
            PlayerChoice("translate", "Subtitle translation", choose = ::showTranslationDialog),
            PlayerChoice("screenshot", "Save screenshot", choose = ::captureScreenshot),
            PlayerChoice("external", "Open in another player", choose = ::openInAnotherPlayer),
        ))
    }

    private fun openInAnotherPlayer() {
        if (externalLaunchJob?.isActive == true || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || isFinishing) return
        val currentUrl = mediaUri?.toString() ?: return
        val attempt = externalLaunchGate.begin()
        externalLaunchJob = lifecycleScope.launch {
            var lease: String? = null
            var launched = false
            try {
                val owner = NativePlaybackBus.listener as? NativePlaybackCoordinator
                    ?: error("Reopen this source before choosing another player")
                val request = owner.externalPlaybackRequest(currentUrl)
                val plan = planExternalPlayback(request.source)
                fun requireLaunchOwner() {
                    check(externalLaunchGate.canLaunch(attempt, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                        !isFinishing && !isDestroyed && owner.ownsExternalPlayback(request) && currentUrl == mediaUri?.toString())) {
                        "Return to the player and try again. The source or window changed."
                    }
                }
                showPlayerNotice("Preparing this source for another player…")
                val target = withContext(Dispatchers.IO) {
                    if (plan.safPath != null) {
                        val documentUri = Uri.parse(AndroidSafStorageAdapter(this@NativePlayerActivity).uri(plan.safPath))
                        val document = DocumentFile.fromSingleUri(this@NativePlayerActivity, documentUri)
                        check(document?.isFile == true && document.canRead()) { "The selected storage document is unavailable" }
                        contentResolver.openFileDescriptor(documentUri, "r")?.use { } ?: error("The selected storage document is unavailable")
                        documentUri
                    } else {
                        if (plan.probe) {
                            val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
                                .followRedirects(false).followSslRedirects(false).build()
                            try {
                                val call = client.newCall(Request.Builder().url(plan.uri).header("Range", "bytes=0-0").build())
                                externalProbeCall = call
                                kotlin.coroutines.coroutineContext.ensureActive()
                                call.execute().use { response ->
                                    check(response.code !in setOf(401, 403)) { "This source needs native authentication and cannot be shared with another player." }
                                    check(response.code == 206 && response.header("Content-Range").orEmpty().startsWith("bytes 0-0/") &&
                                        response.body?.source()?.readByte() != null) { "This source is not available for safe external playback. Keep using Seanime." }
                                }
                            } finally { externalProbeCall = null; client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
                        }
                        Uri.parse(plan.uri)
                    }
                }
                requireLaunchOwner()
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(target, plan.mimeType)
                if (target.scheme == "content") {
                    view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    view.clipData = ClipData.newRawUri("Selected video", target)
                }
                check(packageManager.queryIntentActivities(view, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()) {
                    "No installed app can play this source"
                }
                requireLaunchOwner()
                player?.pause()
                resumePlayWhenReady = false
                rememberPlaybackState()
                persistPlaybackRecovery()
                publishSnapshot()
                val ticket = (application as SeanimeTvApplication).beginExternalPlayback(request.source.playbackId, currentUrl, plan.needsHost,
                    request.generation, target.takeIf { it.scheme == "content" }?.toString(), request.serverOwner)
                lease = ticket.id
                if (plan.needsHost) NativeExternalPlaybackService.start(this@NativePlayerActivity, ticket)
                requireLaunchOwner()
                check((application as SeanimeTvApplication).externalPlaybackLease.current?.id == ticket.id) { "The external playback request ended. Try again." }
                externalLeaseId = ticket.id
                externalReturnSource = ticket.sourceUrl; externalReturnPlaybackId = ticket.playbackId
                externalReturnGeneration = ticket.sourceGeneration; externalReturnServerOwner = ticket.serverOwner
                externalPlayerLauncher.launch(Intent.createChooser(view, "Open video in another player"))
                externalLaunchGate.launched(attempt)
                launched = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // Networking exceptions may contain the scoped URL; never surface it or its token.
                if (currentUrl == mediaUri?.toString() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) showPlayerNotice(
                    if (error is IllegalArgumentException || error is IllegalStateException) error.message.orEmpty()
                    else "Could not open this source in another player. Playback remains available in Seanime.")
            } finally {
                if (!launched) {
                    lease?.let { (application as SeanimeTvApplication).endExternalPlayback(it) }
                    if (externalLeaseId == lease) {
                        externalLeaseId = null; externalReturnSource = null; externalReturnPlaybackId = null
                    }
                }
            }
        }
    }

    private fun cancelExternalPreparation() {
        if (externalLaunchGate.stopPreparation()) {
            externalLaunchJob?.cancel()
            externalProbeCall?.cancel()
        }
    }

    private fun showVolumeDialog() {
        presentation.dialog = PlayerChoiceDialog("Player volume", (0..10).map { step ->
            PlayerChoice("volume-$step", if (step == 0) "Mute" else "${step * 10}%", (player?.volume?.times(10)?.toInt() ?: 10) == step) {
                muted = step == 0; savedVolume = step / 10f; player?.volume = savedVolume
            }
        })
    }

    private fun retryPlayback() {
        val current = player ?: return
        showPlaybackError(null)
        // prepare() retains the failed item's position and playWhenReady,
        // including a paused stream. No playlist completion is synthesized.
        current.prepare()
    }

    private fun returnFromPlayer() {
        clearPlaybackRecovery()
        nativePlayerVisible = false
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        externalLeaseId?.let { outState.putString("externalLeaseId", it) }
        externalReturnSource?.let { outState.putString("externalReturnSource", it) }
        externalReturnPlaybackId?.let { outState.putString("externalReturnPlaybackId", it) }
        outState.putInt("externalReturnGeneration", externalReturnGeneration)
        outState.putLong("externalReturnServerOwner", externalReturnServerOwner)
        rememberPlaybackState()
        outState.putString("nativePlaybackInfo", NativePlaybackBus.playbackInfoJson)
        outState.putString("nativeEventSubtitleId", eventSubtitles?.playbackId)
        outState.putInt("nativeEventSubtitleTrack", eventSubtitles?.selectedTrack ?: -1)
        outState.putBoolean("nativeEventTextWasDisabled", eventTextWasDisabled == true)
        outState.putString(STATE_MEDIA_URI, mediaUri?.toString())
        outState.putString(EXTRA_TITLE, mediaTitle)
        outState.putString(EXTRA_SUBTITLES, subtitleTracksJson)
        outState.putString(EXTRA_SUBTITLE_STYLE, subtitleStyleJson)
        outState.putLong(EXTRA_START_POSITION, lastPositionMs)
        outState.putBoolean(STATE_PLAY_WHEN_READY, resumePlayWhenReady)
        outState.putBoolean(STATE_COMPLETED, completed)
        outState.putFloat(STATE_SPEED, savedPlaybackParameters.speed)
        outState.putFloat(STATE_PITCH, savedPlaybackParameters.pitch)
        outState.putFloat(STATE_VOLUME, savedVolume)
        outState.putBoolean(STATE_MUTED, muted)
        outState.putString(STATE_CHECKPOINT_ID, playbackCheckpointId)
        outState.putString(STATE_CHECKPOINT_URL, playbackCheckpointUrl)
        outState.putString(STATE_PROCESS_SESSION_ID, (application as SeanimeTvApplication).processSessionId)
        savedTrackSelectionParameters?.let { outState.putBundle(STATE_TRACK_SELECTION, it.toBundle()) }
        recoverySnapshot()?.let {
            PlaybackRecoverySnapshot.write(filesDir, it)
        }
        super.onSaveInstanceState(outState)
    }

    private fun applySubtitleStyle(playerView: PlayerView, json: String) {
        val subtitleView = playerView.subtitleView ?: return
        val style = runCatching { JSONObject(json) }.getOrNull() ?: JSONObject()
        val subtitleCustomization = style.optJSONObject("subtitleCustomization") ?: JSONObject()
        val captionCustomization = style.optJSONObject("captionCustomization") ?: JSONObject()
        val useAssCustomization = subtitleCustomization.optBoolean("enabled", false)
        val custom = if (useAssCustomization) subtitleCustomization else captionCustomization
        val foregroundColor = parseStyleColor(
            custom.optString(if (useAssCustomization) "primaryColor" else "textColor"),
            Color.WHITE,
        )
        val backgroundColor = parseStyleColor(
            custom.optString(if (useAssCustomization) "backColor" else "backgroundColor"),
            Color.BLACK,
        )
        val backgroundOpacity = if (useAssCustomization) {
            (255 - subtitleCustomization.optInt("backColorOpacity", 0)).coerceIn(0, 255) / 255f
        } else {
            captionCustomization.optDouble("backgroundOpacity", 0.7).toFloat().coerceIn(0f, 1f)
        }
        val outlineWidth = if (useAssCustomization) subtitleCustomization.optDouble("outline", 0.0) else 0.0
        val shadowDepth = if (useAssCustomization) subtitleCustomization.optDouble("shadow", 0.0)
        else captionCustomization.optDouble("textShadow", 0.0)
        val edgeType = when {
            outlineWidth > 0 -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            shadowDepth > 0 -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            else -> CaptionStyleCompat.EDGE_TYPE_NONE
        }
        val edgeColorKey = if (outlineWidth > 0) "outlineColor" else "textShadowColor"
        val edgeColor = parseStyleColor(custom.optString(edgeColorKey), Color.BLACK)
        val fontName = custom.optString("fontName").takeIf { useAssCustomization && it.isNotBlank() }
        val typeface = fontName?.let { Typeface.create(it, Typeface.NORMAL) }
        val size = if (useAssCustomization) {
            (subtitleCustomization.optDouble("fontSize", 62.0) / 1000.0).toFloat()
        } else {
            (captionCustomization.optDouble("fontSize", 5.0) / 100.0).toFloat()
        }.coerceIn(0.025f, 0.12f)

        subtitleView.setStyle(CaptionStyleCompat(
            foregroundColor,
            Color.argb((backgroundOpacity * 255).toInt(), Color.red(backgroundColor), Color.green(backgroundColor), Color.blue(backgroundColor)),
            Color.TRANSPARENT,
            edgeType,
            edgeColor,
            typeface,
        ))
        subtitleView.setFractionalTextSize(size)
        subtitleView.setApplyEmbeddedStyles(!useAssCustomization)
        subtitleView.setApplyEmbeddedFontSizes(!useAssCustomization)
    }

    private fun parseStyleColor(value: String, fallback: Int): Int =
        runCatching { Color.parseColor(value) }.getOrDefault(fallback)

    private fun buildMediaItem(uri: Uri, title: String, subtitleTracksJson: String): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        val playbackInfo = runCatching { JSONObject(NativePlaybackBus.playbackInfoJson) }.getOrNull()
        if (playbackInfo?.optString("streamUrl") == uri.toString()) {
            when (playbackInfo.optString("streamType")) {
                "hls", "m3u8" -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
                "mp4" -> builder.setMimeType(MimeTypes.VIDEO_MP4)
            }
        }
        val previousSubtitleFiles = subtitleCacheFiles.toSet()
        val subtitles = parseSubtitleConfigurations(subtitleTracksJson)
        if (subtitles.isNotEmpty()) builder.setSubtitleConfigurations(subtitles)
        val authority = (NativePlaybackBus.listener as? NativePlaybackCoordinator)?.mediaRequestContext(uri.toString())
            ?: ProviderMediaContext(ProviderMediaContext.isProviderPlayback(playbackInfo))
        val createdUris = subtitleCacheFiles.filter { it !in previousSubtitleFiles }.map { Uri.fromFile(it).toString() }.toSet()
        val inlineUris = subtitles.map { it.uri.toString() }.filter { it in createdUris }.toSet()
        builder.setTag(MediaAuthority(authority, inlineUris))
        return builder.build()
    }

    private fun parseSubtitleConfigurations(json: String): List<MediaItem.SubtitleConfiguration> {
        val tracks = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val ret = mutableListOf<MediaItem.SubtitleConfiguration>()
        for (index in 0 until tracks.length()) {
            val track = tracks.optJSONObject(index) ?: continue
            val type = track.optString("type").lowercase().ifBlank {
                Uri.parse(track.optString("src").ifBlank { track.optString("url") }).path?.substringAfterLast('.')?.lowercase().orEmpty()
            }.let { if (it in setOf("srt", "vtt", "ass", "ssa")) it else "vtt" }
            val content = track.optString("content")
            val source = track.optString("src").ifBlank { track.optString("url") }
            val subtitleUri = when {
                content.isNotBlank() -> {
                    val extension = type.takeIf { it in setOf("srt", "vtt", "ass", "ssa") } ?: "srt"
                    val file = File(cacheDir, "native-subtitle-${System.nanoTime()}.$extension")
                    runCatching { file.writeText(content) }.getOrNull() ?: continue
                    subtitleCacheFiles.add(file)
                    Uri.fromFile(file)
                }
                source.isNotBlank() -> {
                    val playbackInfo = runCatching { JSONObject(NativePlaybackBus.playbackInfoJson) }.getOrNull()
                    if (ProviderMediaContext.isProviderPlayback(playbackInfo) && runCatching { ProviderUrlPolicy.requirePublicUrl(source) }.isFailure) continue
                    val resolved = if (source.startsWith("/")) "http://127.0.0.1:$SERVER_PORT$source" else source
                    runCatching { Uri.parse(resolved) }.getOrNull() ?: continue
                }
                else -> continue
            }
            val mimeType = when (type) {
                "srt" -> MimeTypes.APPLICATION_SUBRIP
                "vtt" -> MimeTypes.TEXT_VTT
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                else -> continue
            }
            val configuration = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                .setId((1024 + index).toString())
                .setMimeType(mimeType)
                .setLanguage(track.optString("language").takeIf { it.isNotBlank() })
                .setLabel(track.optString("label").takeIf { it.isNotBlank() })
                .setSelectionFlags(if (track.optBoolean("default")) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
            ret.add(configuration)
        }
        return ret
    }

    private fun showAnime4KDialog() {
        presentation.dialog = PlayerChoiceDialog("Anime4K picture enhancement", NativeAnime4K.presets.filter { it.supported }.map { preset ->
            PlayerChoice(preset.id, preset.label, preset.id == anime4kPreset) {
                anime4kPreset = preset.id
                getSharedPreferences("native-player-settings", MODE_PRIVATE).edit().putString("anime4k", anime4kPreset).apply()
                anime4kKey = ""; applyAnime4K()
            }
        }, "SDR video · demanding presets require a capable GPU")
    }

    private fun applyAnime4K(preparingPlayer: Boolean = false, inputFormat: Format? = null) {
        val current = player ?: return
        val format = inputFormat ?: current.videoFormat
        // Effect playback need not emit videoSize callbacks. Re-check the actual
        // decoder input's transfer function before a cached configuration can return.
        if (anime4kPreset != "off" && ColorInfo.isTransferHdr(format?.colorInfo)) {
            anime4kPreset = "off"; anime4kKey = "off"
            val generation = ++anime4kGeneration
            getSharedPreferences("native-player-settings", MODE_PRIVATE).edit().putString("anime4k", "off").apply()
            restartPlayerForPicture(current, generation)
            NativePlaybackBus.event("anime4k", JSONObject().put("option", "off"))
            showPlayerNotice("Anime4K is disabled for HDR; the available networks are trained for SDR")
            return
        }
        val view = activePlayerView ?: return
        if (isFinishing) return
        val targetWidth = view.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val targetHeight = view.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (targetWidth <= 0 || targetHeight <= 0) return
        val preset = anime4kPreset
        val key = if (preset == "off") "off" else "$preset:$targetWidth:$targetHeight"
        if (key == anime4kKey) return
        anime4kKey = key
        val generation = ++anime4kGeneration
        if (preset == "off") {
            if (anime4kEffectsApplied) restartPlayerForPicture(current, generation)
            NativePlaybackBus.event("anime4k", JSONObject().put("option", "off"))
            return
        }
        val fail: (String) -> Unit = { reason -> runOnUiThread {
            if (player === current && anime4kGeneration == generation && anime4kPreset == preset && !isFinishing) {
                anime4kPreset = "off"; anime4kKey = "off"
                getSharedPreferences("native-player-settings", MODE_PRIVATE).edit().putString("anime4k", "off").apply()
                restartPlayerForPicture(current, generation)
                NativePlaybackBus.event("anime4k", JSONObject().put("option", "off"))
                showPlayerNotice(reason)
            }
        } }
        if (!preparingPlayer && !anime4kEffectsApplied) {
            restartPlayerForPicture(current, generation)
            return
        }
        val effects = NativeAnime4K.effects(preset, targetWidth, targetHeight, fail)
        if (effects.isNotEmpty()) {
            current.setVideoEffects(effects)
            anime4kEffectsApplied = true
        }
        NativePlaybackBus.event("anime4k", JSONObject().put("option", anime4kPreset))
    }

    private fun restartPlayerForPicture(current: ExoPlayer, generation: Int) {
        // Media3 picks its direct-surface or video-graph path at renderer enable.
        // An empty effects list still creates a graph, and setting a first effect
        // on an already enabled direct renderer cannot install that graph.
        // Reuse the lifecycle checkpoint to preserve pause/position/tracks/speed.
        // Post outside listener dispatch/initialization, including shader failures.
        window.decorView.post {
            if (player === current && anime4kGeneration == generation && !isFinishing) {
                releasePlayer()
                initializePlayer()
            }
        }
    }

    private fun showTranslationDialog() {
        val tracks = runCatching { JSONArray(subtitleTracksJson) }.getOrDefault(JSONArray())
        val choices = mutableListOf(PlayerChoice("live", "Toggle live subtitle translation") { NativePlaybackBus.action("toggle-translation") })
        for (i in 0 until tracks.length()) {
            val track = tracks.optJSONObject(i) ?: continue
            val label = track.optString("label").ifBlank { track.optString("language", "Track ${i + 1}") }
            choices.add(PlayerChoice("file-$i", "Translate full track · $label") { NativePlaybackBus.action("translate-track:$i") })
        }
        presentation.dialog = PlayerChoiceDialog("Subtitle translation", choices, "Uses the translation provider configured in Seanime")
    }

    private fun showSpeedDialog() {
        presentation.dialog = PlayerChoiceDialog("Playback speed", listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).map { speed ->
            PlayerChoice("speed-$speed", "${speed}×", speed == (player?.playbackParameters?.speed ?: 1f)) { player?.setPlaybackSpeed(speed) }
        })
    }

    private fun showTrackDialog(trackType: Int) {
        val bridge = eventSubtitles
        if (trackType == C.TRACK_TYPE_TEXT && bridge?.configured == true) {
            presentation.dialog = PlayerChoiceDialog("Original stream subtitles", listOf(PlayerChoice("off", "Off", bridge.selectedTrack == -1) { selectEventSubtitle(-1) }) +
                bridge.tracks.values.map { track ->
                    val number = track.optInt("number")
                    val label = track.optString("name").ifBlank { track.optString("language").ifBlank { "Track $number" } }
                    PlayerChoice("original-$number", label, number == bridge.selectedTrack) { selectEventSubtitle(number) }
                } + PlayerChoice("sidecars", "Other subtitle tracks") { bridge.select(-1); showMedia3TrackDialog(trackType) })
        } else showMedia3TrackDialog(trackType)
    }

    private fun selectEventSubtitle(number: Int) {
        eventSubtitles?.select(number)
        player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
        NativePlaybackBus.event("subtitle-track", JSONObject().put("trackNumber", number).put("kind", "event"))
    }

    private fun showMedia3TrackDialog(trackType: Int) {
        val current = player ?: return
        val disabled = current.trackSelectionParameters.disabledTrackTypes.contains(trackType)
        val choices = mutableListOf<PlayerChoice>()
        if (trackType == C.TRACK_TYPE_TEXT) choices.add(PlayerChoice("off", "Off", disabled) {
            current.trackSelectionParameters = current.trackSelectionParameters.buildUpon().clearOverridesOfType(trackType).setTrackTypeDisabled(trackType, true).build()
        })
        current.currentTracks.groups.filter { it.type == trackType }.forEachIndexed { groupIndex, group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                val language = format.language?.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage }
                val label = format.label?.takeIf { it.isNotBlank() } ?: language ?: "Track ${choices.size + 1}"
                val detail = if (trackType == C.TRACK_TYPE_AUDIO && format.channelCount > 0) when (format.channelCount) {
                    1 -> " · Mono"
                    2 -> " · Stereo"
                    else -> " · ${format.channelCount} channels"
                } else ""
                choices.add(PlayerChoice("$groupIndex-$index", label + detail, !disabled && group.isTrackSelected(index)) {
                    current.trackSelectionParameters = current.trackSelectionParameters.buildUpon().clearOverridesOfType(trackType)
                        .setTrackTypeDisabled(trackType, false).setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                    if (trackType == C.TRACK_TYPE_TEXT) NativePlaybackBus.event("subtitle-track", JSONObject().put("trackNumber", format.id?.substringAfterLast(':')?.toIntOrNull() ?: index).put("kind", "file"))
                })
            }
        }
        presentation.dialog = PlayerChoiceDialog(if (trackType == C.TRACK_TYPE_AUDIO) "Audio language" else "Subtitle tracks", choices,
            if (choices.isEmpty()) "No selectable tracks are available for this source" else "")
    }

    private fun captureScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            showPlayerNotice("Screenshot capture requires Android 7 or newer")
            return
        }
        val surface = activePlayerView?.videoSurfaceView as? SurfaceView
        if (surface == null || surface.width <= 0 || surface.height <= 0) {
            showPlayerNotice("The video frame is not ready yet")
            return
        }
        val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        presentation.screenshot = true
        // Let Compose remove its HUD before copying the window (authored subtitles remain).
        surface.postDelayed({
            val onCopy = PixelCopy.OnPixelCopyFinishedListener { result ->
                presentation.screenshot = false
                if (result != PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    showPlayerNotice("Could not capture the video frame")
                    return@OnPixelCopyFinishedListener
                }
                saveScreenshot(bitmap)
            }
            val handler = Handler(Looper.getMainLooper())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val location = IntArray(2)
                surface.getLocationInWindow(location)
                val crop = Rect(
                    location[0],
                    location[1],
                    location[0] + surface.width,
                    location[1] + surface.height,
                )
                PixelCopy.request(window, crop, bitmap, onCopy, handler)
            } else {
                PixelCopy.request(surface, bitmap, onCopy, handler)
            }
        }, 100)
    }

    private fun saveScreenshot(bitmap: Bitmap) {
        val uriString = getSharedPreferences("android-tv-storage", MODE_PRIVATE)
            .getString(NativePlatformActions.SCREENSHOT_TREE_URI, null)
        if (uriString.isNullOrBlank()) {
            bitmap.recycle()
            showPlayerNotice("Choose a screenshot folder in Seanime settings first")
            return
        }
        try {
            screenshotExecutor.execute {
                var image: DocumentFile? = null
                try {
                    val treeUri = Uri.parse(uriString)
                    val hasWriteGrant = contentResolver.persistedUriPermissions.any {
                        it.uri == treeUri && it.isReadPermission && it.isWritePermission
                    }
                    val folder = treeUri.takeIf { hasWriteGrant }?.let { DocumentFile.fromTreeUri(this, it) }
                    if (folder == null || !folder.isDirectory || !folder.canWrite()) {
                        throw IOException("Screenshot folder access was removed; select it again in settings")
                    }

                    val filename = "seanime_screenshot_${System.currentTimeMillis()}_${System.nanoTime()}.png"
                    val createdImage = folder.createFile("image/png", filename)
                        ?: throw IOException("Could not create a screenshot in that folder")
                    image = createdImage
                    val output = contentResolver.openOutputStream(createdImage.uri, "wt")
                        ?: throw IOException("Could not open the screenshot file")
                    output.use {
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                            throw IOException("Could not encode the screenshot")
                        }
                    }
                    runOnUiThread {
                        showPlayerNotice("Screenshot saved")
                    }
                } catch (error: Exception) {
                    image?.delete()
                    runOnUiThread {
                        showPlayerNotice(error.message ?: "Could not save the screenshot")
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (_: RejectedExecutionException) {
            bitmap.recycle()
            showPlayerNotice("Could not save the screenshot")
        }
    }

    override fun onPause() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) releasePlayer()
        super.onPause()
    }

    override fun onStop() {
        cancelExternalPreparation()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) releasePlayer()
        super.onStop()
    }

    private fun releasePlayer() {
        val current = player ?: return
        progressHandler.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rememberPlaybackState()
        persistPlaybackRecovery()
        publishSnapshot(active = false, closed = isFinishing)
        // Detaching the surface and releasing Media3 can deliver final callbacks.
        // Retire ownership before those callbacks so the inactive snapshot cannot
        // be overwritten, or an old renderer mutate a replacement player's state.
        player = null
        showPlaybackError(null)
        activePlayerView?.player = null
        eventSubtitles?.pause()
        assSession?.release() ?: current.release()
        assSession = null
        mediaHttpClients.forEach { client -> client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        mediaHttpClients.clear()
        subtitleCacheFiles.forEach { it.delete() }
        subtitleCacheFiles.clear()
        nativePlayerVisible = false
        if (activeInstance?.get() === this) activeInstance = null
        NativePlaybackBus.event("stopped")
    }

    override fun onDestroy() {
        cancelExternalPreparation()
        if (isFinishing) externalLeaseId?.let { (application as SeanimeTvApplication).endExternalPlayback(it) }
        releasePlayer()
        eventSubtitles?.close()
        eventSubtitles = null
        activePlayerView = null
        screenshotExecutor.shutdown()
        checkpointExecutor.shutdown()
        if (sourceActivityInstance?.get() === this) sourceActivityInstance = null
        NativePlaybackBus.event("destroyed", JSONObject().put("closed", isFinishing))
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Hardware media keys work with the HUD hidden and while a choice dialog is open.
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> handlePresentationAction("play")
            KeyEvent.KEYCODE_MEDIA_PLAY -> player?.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> player?.pause()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> handlePresentationAction("forward")
            KeyEvent.KEYCODE_MEDIA_REWIND -> handlePresentationAction("rewind")
            KeyEvent.KEYCODE_MEDIA_NEXT -> handlePresentationAction("next")
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> handlePresentationAction("previous")
            KeyEvent.KEYCODE_MEDIA_STOP -> returnFromPlayer()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLES = "subtitles"
        private const val EXTRA_SUBTITLE_STYLE = "subtitleStyle"
        private const val EXTRA_START_POSITION = "startPositionMs"
        private const val EXTRA_PLAYBACK_SETTINGS = "playbackSettings"
        private const val EXTRA_CHECKPOINT_ID = "playbackCheckpointId"
        private const val STATE_MEDIA_URI = "mediaUri"
        private const val STATE_PLAY_WHEN_READY = "playWhenReady"
        private const val STATE_COMPLETED = "completed"
        private const val STATE_SPEED = "speed"
        private const val STATE_PITCH = "pitch"
        private const val STATE_VOLUME = "volume"
        private const val STATE_MUTED = "muted"
        private const val STATE_TRACK_SELECTION = "trackSelection"
        private const val STATE_CHECKPOINT_ID = "playbackCheckpointId"
        private const val STATE_CHECKPOINT_URL = "playbackCheckpointUrl"
        private const val STATE_PROCESS_SESSION_ID = "processSessionId"
        private const val SERVER_PORT = 43211
        @Volatile private var nativePlayerVisible = false
        @Volatile private var activeInstance: WeakReference<NativePlayerActivity>? = null
        // Used only to close the exact stopped source; decoder controls still
        // require activeInstance and cannot restart a background player.
        @Volatile private var sourceActivityInstance: WeakReference<NativePlayerActivity>? = null

        fun markLaunchPending() {
            nativePlayerVisible = true
        }

        fun clearFailedLaunch() {
            if (activeActivity() == null) nativePlayerVisible = false
        }

        fun isVisible(): Boolean = nativePlayerVisible

        fun activeActivity(): NativePlayerActivity? = activeInstance?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

        fun requestStatus(url: String, onInactive: () -> Unit) {
            val deliver = Runnable {
                val activity = activeInstance?.get()
                if (activity?.player == null) onInactive()
                else if (!activity.isFinishing && !activity.isDestroyed && activity.mediaUri?.toString() == url) activity.publishSnapshot()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) deliver.run()
            else Handler(Looper.getMainLooper()).post(deliver)
        }

        fun control(url: String, command: String, value: Double) {
            if (!value.isFinite()) return
            if (command == "stop") {
                val source = sourceActivityInstance?.get() ?: return
                source.runOnUiThread {
                    if (!source.isFinishing && !source.isDestroyed && source.mediaUri?.toString() == url) source.returnFromPlayer()
                }
                return
            }
            val activity = activeInstance?.get() ?: return
            activity.runOnUiThread {
                val current = activity.player ?: return@runOnUiThread
                if (activity.isFinishing || activity.mediaUri?.toString() != url) return@runOnUiThread
                when (command) {
                    "play" -> {
                        if (current.playbackState == Player.STATE_ENDED) {
                            activity.completed = false
                            current.seekTo(0)
                        }
                        current.play()
                    }
                    "pause" -> current.pause()
                    "seekTo", "seekBy" -> {
                        activity.completed = false
                        val position = if (command == "seekBy") current.currentPosition.toDouble() + value else value
                        current.seekTo(position.toLong().coerceIn(0, current.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
                    }
                    "speed" -> if (value.toFloat() > 0 && value.toFloat().isFinite()) current.setPlaybackSpeed(value.toFloat())
                    "volume" -> {
                        activity.savedVolume = value.toFloat().coerceIn(0f, 1f)
                        current.volume = if (activity.muted) 0f else activity.savedVolume
                    }
                    "muted" -> {
                        if (!activity.muted) activity.savedVolume = current.volume
                        activity.muted = value != 0.0
                        current.volume = if (activity.muted) 0f else activity.savedVolume
                    }
                    else -> return@runOnUiThread
                }
                activity.publishSnapshot()
            }
        }

        fun updateMedia(url: String, title: String, subtitleTracksJson: String, startPositionMs: Long, subtitleStyleJson: String, paused: Boolean? = null) {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
            if (uri.scheme !in setOf("http", "https", "content", "file")) return
            val activity = activeInstance?.get() ?: return
            activity.runOnUiThread {
                if (!activity.isFinishing) activity.loadMedia(uri, title, subtitleTracksJson, startPositionMs, subtitleStyleJson, paused)
            }
        }

        fun configureEventSubtitles(info: String, selectedTrack: Int?) {
            val activity = activeInstance?.get() ?: return
            activity.runOnUiThread {
                val metadata = runCatching { JSONObject(info) }.getOrNull() ?: return@runOnUiThread
                if (activity.eventSubtitles?.configured != true) activity.eventTextWasDisabled = activity.player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT)
                activity.eventSubtitles?.configure(metadata, selectedTrack)
                activity.selectEventSubtitle(activity.eventSubtitles?.selectedTrack ?: -1)
            }
        }

        fun receiveEventSubtitles(json: String) {
            val activity = activeInstance?.get() ?: return
            activity.runOnUiThread { activity.eventSubtitles?.receive(json) }
        }

        fun addEventSubtitleFont(playbackId: String, name: String, bytes: ByteArray) {
            val activity = activeInstance?.get() ?: return
            activity.runOnUiThread { activity.eventSubtitles?.addFont(playbackId, name, bytes) }
        }

        fun clearEventSubtitles() {
            val activity = activeInstance?.get() ?: return
            activity.runOnUiThread {
                activity.eventSubtitles?.clear()
                activity.eventTextWasDisabled?.let { disabled -> activity.player?.let {
                    it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disabled).build()
                } }
                activity.eventTextWasDisabled = null
            }
        }

        fun currentAnime4K(): String = activeInstance?.get()?.anime4kPreset ?: "off"

        fun showTranslatedCaption(original: String, translation: String) {
            val activity = activeInstance?.get() ?: return
            val source = activity.mediaUri
            val metadata = NativePlaybackBus.playbackInfoJson
            activity.runOnUiThread {
                if (source == activity.mediaUri && metadata == NativePlaybackBus.playbackInfoJson) activity.presentation.translation = translation
            }
        }

        fun showNotice(message: String) {
            val activity = activeInstance?.get() ?: return
            activity.runOnUiThread { activity.showPlayerNotice(message) }
        }

        fun selectTrack(trackType: Int, index: Int, expectedUrl: String? = null) =
            selectTrack(trackType, index, expectedUrl, matchTrackNumber = true)

        fun selectMediaCaptionTrack(expectedUrl: String, index: Int) =
            selectTrack(C.TRACK_TYPE_TEXT, index, expectedUrl, matchTrackNumber = false)

        private fun selectTrack(trackType: Int, index: Int, expectedUrl: String?, matchTrackNumber: Boolean) {
            val activity = activeInstance?.get() ?: return
            val source = activity.mediaUri
            if (expectedUrl != null && source?.toString() != expectedUrl) return
            activity.runOnUiThread {
                if (activity.isFinishing || source != activity.mediaUri) return@runOnUiThread
                val current = activity.player ?: return@runOnUiThread
                if (matchTrackNumber && trackType == C.TRACK_TYPE_TEXT && activity.eventSubtitles?.configured == true &&
                    (index == -1 || activity.eventSubtitles?.tracks?.containsKey(index) == true)) {
                    activity.selectEventSubtitle(index)
                    return@runOnUiThread
                }
                val builder = current.trackSelectionParameters.buildUpon().clearOverridesOfType(trackType)
                    .setTrackTypeDisabled(trackType, index < 0)
                if (index >= 0) {
                    val tracks = current.currentTracks.groups.filter { it.type == trackType }
                    val resolved = NativeTrackSelection.resolve(tracks.map { group -> (0 until group.length).map {
                        group.getTrackFormat(it).id?.substringAfterLast(':')?.toIntOrNull()
                    } }, index, matchTrackNumber) ?: return@runOnUiThread
                    if (trackType == C.TRACK_TYPE_TEXT) activity.eventSubtitles?.select(-1)
                    builder.setOverrideForType(androidx.media3.common.TrackSelectionOverride(tracks[resolved.first].mediaTrackGroup, resolved.second))
                }
                current.trackSelectionParameters = builder.build()
            }
        }

        fun updateSubtitleStyle(subtitleStyleJson: String) {
            val activity = activeInstance?.get() ?: return
            activity.runOnUiThread {
                if (!activity.isFinishing) {
                    activity.subtitleStyleJson = subtitleStyleJson
                    activity.activePlayerView?.let { activity.applySubtitleStyle(it, subtitleStyleJson) }
                }
            }
        }

        fun intent(context: Context, uri: Uri, title: String, subtitleTracksJson: String, startPositionMs: Long, subtitleStyleJson: String, playbackSettingsJson: String = "{}", checkpointId: String = ""): Intent =
            Intent(context, NativePlayerActivity::class.java)
                .setData(uri)
                .putExtra("nativePlaybackInfo", NativePlaybackBus.playbackInfoJson)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SUBTITLES, subtitleTracksJson)
                .putExtra(EXTRA_START_POSITION, startPositionMs)
                .putExtra(EXTRA_SUBTITLE_STYLE, subtitleStyleJson)
                .putExtra(EXTRA_PLAYBACK_SETTINGS, playbackSettingsJson)
                .putExtra(EXTRA_CHECKPOINT_ID, checkpointId)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        fun recoveryIntent(context: Context, snapshot: PlaybackRecoverySnapshot): Intent =
            Intent(context, NativePlayerActivity::class.java)
                .setData(Uri.parse(snapshot.mediaUri))
                .putExtra("nativePlaybackInfo", snapshot.playbackInfoJson)
                .putExtra(EXTRA_CHECKPOINT_ID, snapshot.checkpointId)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** Every Media3 open is checked, including child manifests, encryption keys and subtitle URLs. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class ProviderMediaDataSource(
    private val delegate: DataSource,
    private val context: ProviderMediaContext,
    inlineSubtitleUris: Set<String>,
) : DataSource by delegate {
    private val inlineSubtitles = inlineSubtitleUris.toSet()
    override fun open(dataSpec: DataSpec): Long {
        try { context.requireMediaUri(dataSpec.uri.toString(), inlineSubtitles) }
        catch (error: IllegalArgumentException) { throw IOException(error.message, error) }
        return delegate.open(dataSpec)
    }
}
