package app.seanime.tv.platform

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.SubtitleView
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView

/**
 * Native libass integration through io.github.peerless2012:ass-media:0.5.1 (MIT).
 * The OpenGL subtitle overlay preserves authored ASS animation and embedded fonts
 * without sending video through a WebView or flattening the video HDR pipeline.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class NativeAssSession(context: Context, private val dataSources: (MediaItem) -> DataSource.Factory, private val subtitleView: SubtitleView?) {
    init { NativeFontConfiguration.prepare(context) }

    private val handler = AssHandler(AssRenderType.OVERLAY_OPEN_GL,
        AssHandlerConfig(glyphSize = 10_000, cacheSize = 64, maxRenderPixels = 1920 * 1080))
    private val overlay = AssSubtitleView(context, handler)
    private val parser = AssSubtitleParserFactory(handler)
    private fun mediaSourceFactory(item: MediaItem) = DefaultMediaSourceFactory(dataSources(item),
        DefaultExtractorsFactory().withAssMkvSupport(parser, handler)).setSubtitleParserFactory(parser)
    private val sourceFactory = object : MediaSource.Factory by DefaultMediaSourceFactory(context) {
        override fun createMediaSource(mediaItem: MediaItem): MediaSource = mediaSourceFactory(mediaItem).createMediaSource(mediaItem)
    }
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(sourceFactory)
        .setRenderersFactory(DefaultRenderersFactory(context).withAssSupport(handler))
        .build()

    init {
        subtitleView?.addView(overlay)
        handler.init(player)
    }

    fun release() {
        // Stop Media3's render thread before destroying native subtitle state.
        subtitleView?.removeView(overlay)
        player.release()
        handler.release()
    }
}
