package app.seanime.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import org.json.JSONArray
import java.io.File
import java.lang.ref.WeakReference

@OptIn(UnstableApi::class)
class NativePlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private var lastPositionMs = 0L
    private var completed = false
    private val subtitleCacheFiles = mutableListOf<File>()
    private val progressHandler = Handler(Looper.getMainLooper())
    private val publishProgress = object : Runnable {
        override fun run() {
            val current = player
            if (current != null) {
                lastPositionMs = current.currentPosition
                MainActivity.notifyNativePlaybackProgress(lastPositionMs)
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

        val mediaUri = intent.data ?: run {
            nativePlayerVisible = false
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Seanime TV" }
        val subtitleTracksJson = intent.getStringExtra(EXTRA_SUBTITLES).orEmpty()
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
                if (playbackState == Player.STATE_ENDED) {
                    completed = true
                    MainActivity.notifyNativePlaybackProgress(lastPositionMs, true)
                }
            }
        })

        val frame = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val playerView = PlayerView(this).apply {
            player = exoPlayer
            useController = true
            controllerAutoShow = true
            requestFocus()
        }
        frame.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        val trackButtons = FrameLayout(this)
        val audio = trackButton("Audio") { showTrackDialog(C.TRACK_TYPE_AUDIO) }
        val subtitles = trackButton("Subtitles") { showTrackDialog(C.TRACK_TYPE_TEXT) }
        trackButtons.addView(audio, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP))
        val subtitleParams = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP)
        subtitleParams.topMargin = 72
        trackButtons.addView(subtitles, subtitleParams)
        frame.addView(trackButtons)
        setContentView(frame)

        loadMedia(mediaUri, title, subtitleTracksJson, intent.getLongExtra(EXTRA_START_POSITION, 0L))
        nativePlayerVisible = true
        MainActivity.notifyNativePlaybackProgress(0)
        progressHandler.postDelayed(publishProgress, 2_000)
    }

    private fun loadMedia(uri: Uri, title: String, subtitleTracksJson: String, startPositionMs: Long) {
        val current = player ?: return
        current.stop()
        subtitleCacheFiles.forEach { it.delete() }
        subtitleCacheFiles.clear()
        completed = false
        lastPositionMs = 0
        current.setMediaItem(buildMediaItem(uri, title, subtitleTracksJson), startPositionMs.coerceAtLeast(0))
        current.prepare()
        current.playWhenReady = true
        MainActivity.notifyNativePlaybackProgress(0)
    }

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

    override fun onStop() {
        progressHandler.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player?.let {
            lastPositionMs = it.currentPosition
            it.release()
        }
        player = null
        subtitleCacheFiles.forEach { it.delete() }
        subtitleCacheFiles.clear()
        nativePlayerVisible = false
        if (activeInstance?.get() === this) activeInstance = null
        MainActivity.notifyNativePlaybackEnded(lastPositionMs, completed)
        MainActivity.notifyNativePlayerStopped()
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLES = "subtitles"
        private const val EXTRA_START_POSITION = "startPositionMs"
        private const val SERVER_PORT = 43211
        @Volatile private var nativePlayerVisible = false
        @Volatile private var activeInstance: WeakReference<NativePlayerActivity>? = null

        fun markLaunchPending() {
            nativePlayerVisible = true
        }

        fun isVisible(): Boolean = nativePlayerVisible

        fun updateMedia(url: String, title: String, subtitleTracksJson: String, startPositionMs: Long) {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
            if (uri.scheme !in setOf("http", "https", "content", "file")) return
            val activity = activeInstance?.get() ?: return
            activity.runOnUiThread {
                if (!activity.isFinishing) activity.loadMedia(uri, title, subtitleTracksJson, startPositionMs)
            }
        }

        fun intent(context: Context, uri: Uri, title: String, subtitleTracksJson: String, startPositionMs: Long): Intent =
            Intent(context, NativePlayerActivity::class.java)
                .setData(uri)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SUBTITLES, subtitleTracksJson)
                .putExtra(EXTRA_START_POSITION, startPositionMs)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
