package app.seanime.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import android.view.Gravity
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.documentfile.provider.DocumentFile
import app.seanime.tv.gomobile.mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

@OptIn(UnstableApi::class)
class NativePlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private var activePlayerView: PlayerView? = null
    private var playerControlsView: View? = null
    private var playbackErrorView: View? = null
    private var playbackErrorText: TextView? = null
    private var playbackRetryButton: Button? = null
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
    private val checkpointExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "Seanime TV playback checkpoint") }
    private val subtitleCacheFiles = mutableListOf<File>()
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
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )

        val checkpointIdExtra = intent.getStringExtra(EXTRA_CHECKPOINT_ID).orEmpty()
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
            if (snapshot != null && Uri.parse(snapshot.mediaUri).path == "/api/v1/directstream/stream") {
                val app = application as SeanimeTvApplication
                if (app.ownsPlaybackRecovery(snapshot.checkpointId)) {
                    nativePlayerVisible = false
                    finish()
                    return
                }
                app.claimPlaybackRecovery(snapshot.checkpointId)
                PlaybackRecoverySnapshot.write(filesDir, snapshot)
                nativePlayerVisible = false
                startActivity(MainActivity.playbackRecoveryIntent(this, snapshot.checkpointId))
                finish()
                return
            }
        }

        val frame = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val playerView = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            requestFocus()
        }
        activePlayerView = playerView
        frame.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        val trackButtons = FrameLayout(this)
        playerControlsView = trackButtons
        val audio = trackButton("Audio") { showTrackDialog(C.TRACK_TYPE_AUDIO) }
        val subtitles = trackButton("Subtitles") { showTrackDialog(C.TRACK_TYPE_TEXT) }
        val screenshot = trackButton("Screenshot") { captureScreenshot() }
        trackButtons.addView(audio, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP))
        val subtitleParams = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP)
        subtitleParams.topMargin = 72
        trackButtons.addView(subtitles, subtitleParams)
        val screenshotParams = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP)
        screenshotParams.topMargin = 144
        trackButtons.addView(screenshot, screenshotParams)
        frame.addView(trackButtons)
        val errorPanel = LinearLayout(this).apply {
            id = R.id.native_player_error_panel
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val inset = (48 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
            setBackgroundColor(0xFF08070D.toInt())
            visibility = View.GONE
        }
        playbackErrorText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
        }.also { errorPanel.addView(it) }
        playbackRetryButton = trackButton(getString(R.string.player_retry)) { retryPlayback() }.apply {
            id = R.id.native_player_retry
            nextFocusUpId = R.id.native_player_retry
            nextFocusDownId = R.id.native_player_return
        }.also { errorPanel.addView(it) }
        errorPanel.addView(trackButton(getString(R.string.player_return)) { returnFromPlayer() }.apply {
            id = R.id.native_player_return
            nextFocusUpId = R.id.native_player_retry
            nextFocusDownId = R.id.native_player_return
        })
        playbackErrorView = errorPanel
        frame.addView(errorPanel, FrameLayout.LayoutParams(-1, -1))
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
        val httpFactory = DefaultHttpDataSource.Factory().setUserAgent("Seanime TV/0.1.0")
        val upstreamFactory = DefaultDataSource.Factory(this, httpFactory)
        val resolvingFactory = ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            val cookie = CookieManager.getInstance().getCookie(dataSpec.uri.toString())
            if (cookie.isNullOrBlank()) dataSpec else dataSpec.withRequestHeaders(mapOf("Cookie" to cookie))
        }
        val playerViewFactory = DefaultMediaSourceFactory(resolvingFactory)
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(playerViewFactory)
            .build()
        player = exoPlayer
        activeInstance = WeakReference(this)
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                lastPositionMs = exoPlayer.currentPosition
                if (isPlaying) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                lastPositionMs = exoPlayer.currentPosition
                if (playbackState == Player.STATE_ENDED && !completed) {
                    completed = true
                    PlaybackRecoverySnapshot.clear(filesDir)
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
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
        exoPlayer.prepare()
        exoPlayer.playWhenReady = resumePlayWhenReady && !completed
        nativePlayerVisible = true
        capturePlaybackSource(uri)
        MainActivity.notifyNativePlayerStarted()
        progressHandler.postDelayed(publishProgress, 2_000)
    }

    private fun loadMedia(uri: Uri, title: String, subtitleTracksJson: String, startPositionMs: Long, subtitleStyleJson: String) {
        // A token/URL refresh of the current episode preserves the decoder's
        // position and pause state even if the web adapter is being replaced.
        val preservePosition = startPositionMs < 0
        val position = if (preservePosition) player?.currentPosition ?: lastPositionMs else startPositionMs.coerceAtLeast(0)
        val playWhenReady = if (preservePosition) player?.playWhenReady ?: resumePlayWhenReady else true
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
        MainActivity.notifyNativePlaybackProgress(JSONObject()
            .put("url", uri.toString())
            .put("positionMs", current.currentPosition.coerceAtLeast(0))
            .put("durationMs", current.duration.coerceAtLeast(0))
            .put("bufferedPositionMs", current.bufferedPosition.coerceAtLeast(0))
            .put("paused", !active || !current.playWhenReady || completed || current.playerError != null)
            .put("buffering", current.playbackState == Player.STATE_BUFFERING)
            .put("completed", completed)
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
        val uri = mediaUri ?: return null
        if (playbackCheckpointId.isBlank() || completed || uri.path != "/api/v1/directstream/stream") return null
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
            trackSelection = PlaybackRecoverySnapshot.encodeBundle(
                current?.trackSelectionParameters?.toBundle() ?: savedTrackSelectionParameters?.toBundle(),
            ),
        )
    }

    private fun persistPlaybackRecovery() {
        val snapshot = recoverySnapshot() ?: return
        runCatching {
            checkpointExecutor.execute { PlaybackRecoverySnapshot.write(filesDir, snapshot) }
        }.onFailure { Log.w("SeanimeTV", "Could not queue the playback recovery snapshot", it) }
    }

    private fun capturePlaybackSource(uri: Uri) {
        val url = uri.toString()
        if (playbackCheckpointUrl == url && playbackCheckpointId.isNotBlank()) return
        val generation = ++checkpointGeneration
        playbackCheckpointId = ""
        playbackCheckpointUrl = url
        if (MainActivity.localPageUrl(url) == null || uri.path != "/api/v1/directstream/stream") return
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

    private fun showPlaybackError(error: PlaybackException?) {
        val wasVisible = playbackErrorView?.visibility == View.VISIBLE
        playbackErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        activePlayerView?.visibility = if (error == null) View.VISIBLE else View.INVISIBLE
        playerControlsView?.visibility = if (error == null) View.VISIBLE else View.INVISIBLE
        if (error != null) {
            playbackErrorText?.text = getString(R.string.player_error, error.errorCodeName)
            playbackRetryButton?.requestFocus()
        } else if (wasVisible && !isFinishing) {
            activePlayerView?.requestFocus()
        }
    }

    private fun retryPlayback() {
        val current = player ?: return
        showPlaybackError(null)
        // prepare() retains the failed item's position and playWhenReady,
        // including a paused stream. No playlist completion is synthesized.
        current.prepare()
    }

    private fun returnFromPlayer() {
        PlaybackRecoverySnapshot.clear(filesDir)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberPlaybackState()
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
        val subtitles = parseSubtitleConfigurations(subtitleTracksJson)
        if (subtitles.isNotEmpty()) builder.setSubtitleConfigurations(subtitles)
        return builder.build()
    }

    private fun parseSubtitleConfigurations(json: String): List<MediaItem.SubtitleConfiguration> {
        val tracks = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val ret = mutableListOf<MediaItem.SubtitleConfiguration>()
        for (index in 0 until tracks.length()) {
            val track = tracks.optJSONObject(index) ?: continue
            val type = track.optString("type").lowercase()
            val content = track.optString("content")
            val source = track.optString("src")
            val subtitleUri = when {
                content.isNotBlank() -> {
                    val extension = type.takeIf { it in setOf("srt", "vtt", "ass", "ssa") } ?: "srt"
                    val file = File(cacheDir, "native-subtitle-${System.nanoTime()}.$extension")
                    runCatching { file.writeText(content) }.getOrNull() ?: continue
                    subtitleCacheFiles.add(file)
                    Uri.fromFile(file)
                }
                source.isNotBlank() -> {
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
                .setMimeType(mimeType)
                .setLanguage(track.optString("language").takeIf { it.isNotBlank() })
                .setLabel(track.optString("label").takeIf { it.isNotBlank() })
                .setSelectionFlags(if (track.optBoolean("default")) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
            ret.add(configuration)
        }
        return ret
    }

    private fun trackButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun showTrackDialog(trackType: Int) {
        val current = player ?: return
        TrackSelectionDialogBuilder(this, if (trackType == C.TRACK_TYPE_AUDIO) "Audio tracks" else "Subtitles", current, trackType)
            .setShowDisableOption(trackType == C.TRACK_TYPE_TEXT)
            .setAllowAdaptiveSelections(true)
            .build()
            .show()
    }

    private fun captureScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            android.widget.Toast.makeText(this, "Screenshot capture requires Android 7 or newer", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val surface = activePlayerView?.videoSurfaceView as? SurfaceView
        if (surface == null || surface.width <= 0 || surface.height <= 0) {
            android.widget.Toast.makeText(this, "The video frame is not ready yet", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        val playerView = activePlayerView
        val controls = playerControlsView
        val controlsVisibility = controls?.visibility ?: View.VISIBLE
        playerView?.hideController()
        controls?.visibility = View.INVISIBLE
        surface.post {
            val onCopy = PixelCopy.OnPixelCopyFinishedListener { result ->
                controls?.visibility = controlsVisibility
                playerView?.showController()
                if (result != PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    android.widget.Toast.makeText(this, "Could not capture the video frame", android.widget.Toast.LENGTH_LONG).show()
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
        }
    }

    private fun saveScreenshot(bitmap: Bitmap) {
        val uriString = getSharedPreferences("android-tv-storage", MODE_PRIVATE)
            .getString(MainActivity.SCREENSHOT_TREE_URI, null)
        if (uriString.isNullOrBlank()) {
            bitmap.recycle()
            android.widget.Toast.makeText(this, "Choose a screenshot folder in Seanime settings first", android.widget.Toast.LENGTH_LONG).show()
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
                        android.widget.Toast.makeText(this, "Screenshot saved", android.widget.Toast.LENGTH_LONG).show()
                    }
                } catch (error: Exception) {
                    image?.delete()
                    runOnUiThread {
                        android.widget.Toast.makeText(this, error.message ?: "Could not save the screenshot", android.widget.Toast.LENGTH_LONG).show()
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (_: RejectedExecutionException) {
            bitmap.recycle()
            android.widget.Toast.makeText(this, "Could not save the screenshot", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) releasePlayer()
        super.onPause()
    }

    override fun onStop() {
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
        showPlaybackError(null)
        activePlayerView?.player = null
        current.release()
        player = null
        subtitleCacheFiles.forEach { it.delete() }
        subtitleCacheFiles.clear()
        nativePlayerVisible = false
        if (activeInstance?.get() === this) activeInstance = null
        MainActivity.notifyNativePlayerStopped()
    }

    override fun onDestroy() {
        releasePlayer()
        activePlayerView = null
        playerControlsView = null
        screenshotExecutor.shutdown()
        checkpointExecutor.shutdown()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            PlaybackRecoverySnapshot.clear(filesDir)
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
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

        fun markLaunchPending() {
            nativePlayerVisible = true
        }

        fun isVisible(): Boolean = nativePlayerVisible

        fun control(url: String, command: String, value: Double) {
            if (!value.isFinite()) return
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
                    "seekTo" -> {
                        activity.completed = false
                        current.seekTo(value.toLong().coerceIn(0, current.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
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
                    "stop" -> activity.returnFromPlayer()
                    else -> return@runOnUiThread
                }
                activity.publishSnapshot()
            }
        }

        fun updateMedia(url: String, title: String, subtitleTracksJson: String, startPositionMs: Long, subtitleStyleJson: String) {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
            if (uri.scheme !in setOf("http", "https", "content", "file")) return
            val activity = activeInstance?.get() ?: return
            activity.runOnUiThread {
                if (!activity.isFinishing) activity.loadMedia(uri, title, subtitleTracksJson, startPositionMs, subtitleStyleJson)
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
                .putExtra(EXTRA_CHECKPOINT_ID, snapshot.checkpointId)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
