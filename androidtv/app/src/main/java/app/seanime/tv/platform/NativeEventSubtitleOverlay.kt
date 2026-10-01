package app.seanime.tv.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.peerless2012.ass.Ass
import io.github.peerless2012.ass.AssFrame
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.AssTexType
import io.github.peerless2012.ass.AssTrack
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.sqrt

/** Independent subtitle plane for converted HLS, retaining original ASS/PGS semantics. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class NativeEventSubtitleOverlay(
    private val context: Context,
    host: FrameLayout,
    private val player: () -> ExoPlayer?,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Seanime native stream subtitles") }
    private val busy = AtomicBoolean(false)
    private val revision = AtomicLong(0)
    private val failedRevision = AtomicLong(-1)
    private val view = SubtitlePlane(context)
    private var active = false
    private var disposed = false
    // Main-thread frame epoch is independent of source/packet generations.
    // A render queued before a seek or stop must not repopulate the cleared view.
    private var frameGeneration = 0L
    var playbackId: String = ""
        private set
    var selectedTrack: Int = -1
        private set
    val tracks = LinkedHashMap<Int, JSONObject>()
    val configured get() = playbackId.isNotBlank()
    val selectedMime get() = if (tracks[selectedTrack]?.optString("codecID")?.contains("PGS", true) == true) "application/pgs" else "text/x-ssa"

    // These values belong exclusively to the subtitle worker.
    private var timeline: NativeSubtitleTimeline? = null
    private var ass: Ass? = null
    private var renderer: AssRender? = null
    private var nativeTrack: AssTrack? = null
    private val fonts = LinkedHashMap<String, ByteArray>()
    private val fed = mutableSetOf<String>()
    private val readOrders = NativeAssReadOrders()
    // Include bitmaps still referenced by a queued/displayed plane, not only the cache.
    private val allocatedImages = java.util.WeakHashMap<Bitmap, Long>()
    private val images = LinkedHashMap<String, Bitmap>()
    private var previousFrame: AssFrame? = null
    private var renderWidth = 0
    private var renderHeight = 0
    private var workerTrack = -1
    private var lastPosition = Long.MIN_VALUE
    private var forceFrame = true

    init { host.addView(view, FrameLayout.LayoutParams(-1, -1)); view.isFocusable = false }

    fun configure(info: JSONObject, preferredTrack: Int?) {
        if (disposed) return
        val id = info.optString("id")
        if (id.isBlank()) return
        val generation = revision.incrementAndGet()
        playbackId = id
        tracks.clear()
        val entries = info.optJSONObject("mkvMetadata")?.optJSONArray("subtitleTracks")
        for (i in 0 until (entries?.length() ?: 0)) entries?.optJSONObject(i)?.let { track ->
            val codec = track.optString("codecID")
            if (listOf("ASS", "SSA", "UTF8", "WEBVTT", "PGS").any { codec.contains(it, true) }) tracks[track.optInt("number")] = JSONObject(track.toString())
        }
        selectedTrack = preferredTrack?.takeIf { it == -1 || tracks.containsKey(it) }
            ?: tracks.values.firstOrNull { it.optBoolean("default") }?.optInt("number") ?: tracks.keys.firstOrNull() ?: -1
        val selected = tracks[selectedTrack]?.toString()
        view.state = null
        submit {
            releaseNative(); fonts.clear(); images.clear(); fed.clear()
            timeline = NativeSubtitleTimeline(id)
            if (generation == revision.get()) buildTrack(selected)
        }
        resume()
    }

    fun select(number: Int) {
        if (disposed || (number != -1 && !tracks.containsKey(number))) return
        revision.incrementAndGet()
        selectedTrack = number
        val track = tracks[number]?.toString()
        view.state = null
        submit { buildTrack(track); forceFrame = true }
        resume()
    }

    fun receive(json: String) {
        if (!configured || json.length > 16 * 1024 * 1024 || disposed) return
        val version = revision.get()
        submit {
            if (version != revision.get()) return@submit
            val batch = runCatching { JSONObject(json) }.getOrNull() ?: return@submit
            val queue = timeline ?: return@submit
            val before = queue.generation
            val correction = queue.correctionRevision
            if (!queue.accept(batch)) return@submit
            if (queue.generation != before || queue.correctionRevision != correction) { nativeTrack?.clearEvent(); fed.clear(); readOrders.clear(); images.clear(); previousFrame = null }
            feedTrack(); forceFrame = true
        }
    }

    fun seek() {
        if (!configured || disposed) return
        frameGeneration++
        view.state = null
        submit { timeline?.seek(); nativeTrack?.clearEvent(); fed.clear(); readOrders.clear(); images.clear(); previousFrame = null; forceFrame = true }
    }

    fun addFont(id: String, name: String, data: ByteArray) {
        if (id != playbackId || data.isEmpty() || data.size > 16 * 1024 * 1024 || disposed) return
        val version = revision.get()
        submit {
            if (version != revision.get() || fonts.values.sumOf { it.size.toLong() } + data.size > 48L * 1024 * 1024 || fonts.containsKey(name)) return@submit
            fonts[name] = data
            ass?.addFont(name, data)
            // A fresh provider picks up newly arrived embedded fonts rather than caching a fallback.
            renderer?.release(); renderer = null; previousFrame = null; renderWidth = 0; forceFrame = true
        }
    }

    fun resume() {
        if (!configured || active || disposed) return
        active = true
        val track = tracks[selectedTrack]?.toString()
        submit { if (nativeTrack == null) buildTrack(track); forceFrame = true }
        main.post(clock)
    }

    fun pause() {
        active = false
        frameGeneration++
        main.removeCallbacks(clock)
        view.state = null
        submit { releaseNative(); images.clear() }
    }

    fun clear() {
        revision.incrementAndGet()
        playbackId = ""; selectedTrack = -1; tracks.clear(); active = false
        main.removeCallbacks(clock); view.state = null
        submit { releaseNative(); timeline = null; fonts.clear(); images.clear(); fed.clear() }
    }

    fun close() {
        if (disposed) return
        clear(); disposed = true
        worker.shutdown()
    }

    private val clock = object : Runnable {
        override fun run() {
            if (!active || disposed || failedRevision.get() == revision.get()) return
            val current = player()
            val selected = selectedTrack
            if (current != null && selected >= 0 && busy.compareAndSet(false, true)) {
                val position = current.currentPosition.coerceAtLeast(0)
                val video = current.videoSize
                val vw = video.width.takeIf { it > 0 } ?: 1920
                val vh = video.height.takeIf { it > 0 } ?: 1080
                val ratio = vw * video.pixelWidthHeightRatio / vh
                val area = fit(view.width, view.height, ratio)
                val version = revision.get()
                val frameVersion = frameGeneration
                val pgs = tracks[selected]?.optString("codecID")?.contains("PGS", true) == true
                submit {
                    try {
                        if (version != revision.get() || area.width() <= 0 || area.height() <= 0) return@submit
                        if (position == lastPosition && !forceFrame) return@submit
                        lastPosition = position; forceFrame = false
                        val state = if (pgs) renderPgs(selected, position, area, vw, vh) else renderAss(position, area, vw, vh)
                        main.post {
                            if (active && frameVersion == frameGeneration && version == revision.get() && selectedTrack == selected) view.state = state
                        }
                    } catch (error: Exception) {
                        failSource(error, version)
                    } finally { busy.set(false) }
                }
            }
            main.postDelayed(this, 33)
        }
    }

    private fun submit(block: () -> Unit) {
        if (disposed) return
        val version = revision.get()
        worker.execute {
            try { if (failedRevision.get() != version) block() } catch (error: Exception) { failSource(error, version) }
        }
    }

    private fun failSource(error: Exception, version: Long) {
        if (version != revision.get() || failedRevision.getAndSet(version) == version) return
        busy.set(false)
        runCatching { releaseNative() }
        main.post {
            if (!disposed && version == revision.get()) {
                active = false; main.removeCallbacks(clock); view.state = null
                onError(error.message ?: "Could not render stream subtitles")
            }
        }
    }

    private fun buildTrack(json: String?) {
        nativeTrack?.let { renderer?.setTrack(null); it.release() }
        nativeTrack = null; workerTrack = -1; fed.clear(); readOrders.clear(); previousFrame = null
        val meta = json?.let(::JSONObject) ?: return
        workerTrack = meta.optInt("number")
        if (meta.optString("codecID").contains("PGS", true)) return
        if (ass == null) {
            NativeFontConfiguration.prepare(context)
            ass = Ass().also { library -> fonts.forEach { (name, bytes) -> library.addFont(name, bytes) } }
        }
        val header = NativeSubtitleProtocol.header(meta)
        require(header.size <= 1024 * 1024) { "Subtitle style header is too large" }
        nativeTrack = ass!!.createTrack().also { it.readBuffer(header) }
        renderer?.setTrack(nativeTrack)
        feedTrack(); forceFrame = true
    }

    private fun feedTrack() {
        val target = nativeTrack ?: return
        timeline?.events?.filter { it.track == workerTrack && !it.isPgs }?.forEach { event ->
            if (fed.add(event.key)) target.readChunk(event.startMs, event.durationMs, NativeSubtitleProtocol.assChunk(event, readOrders.next(event)))
        }
    }

    private fun renderAss(position: Long, area: RectF, videoWidth: Int, videoHeight: Int): DrawState? {
        val library = ass ?: return null
        val track = nativeTrack ?: return null
        val scale = min(1.0, sqrt((1920.0 * 1080) / (area.width() * area.height())))
        val width = (area.width() * scale).toInt().coerceAtLeast(2)
        val height = (area.height() * scale).toInt().coerceAtLeast(2)
        if (renderer == null) renderer = library.createRender().apply { setTrack(track); setCacheLimit(10_000, 64) }
        val current = renderer!!
        if (width != renderWidth || height != renderHeight) {
            current.setStorageSize(videoWidth, videoHeight); current.setFrameSize(width, height)
            renderWidth = width; renderHeight = height
        }
        val frame = current.renderFrame(position, AssTexType.BITMAP_ALPHA)
        if (frame?.changed != 0) previousFrame = frame
        val drawable = previousFrame?.takeUnless { it.images.isNullOrEmpty() } ?: return null
        return DrawState(drawable, emptyList(), area, width, height)
    }

    private fun renderPgs(selected: Int, position: Long, area: RectF, videoWidth: Int, videoHeight: Int): DrawState? {
        val layers = timeline?.active(selected, position).orEmpty().mapNotNull { event ->
            val bitmap = images[event.key] ?: runCatching {
                val bytes = Base64.decode(event.text.substringAfter("base64,", event.text), Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val required = NativePgsBudget.allocationBytes(bounds.outWidth, bounds.outHeight) ?: return@runCatching null
                while (images.isNotEmpty() && (images.size >= 4 || images.values.sumOf { it.allocationByteCount.toLong() } + required > NativePgsBudget.BYTES)) images.remove(images.keys.first())
                if (!NativePgsBudget.fits(bounds.outWidth, bounds.outHeight, allocatedImages.values.sum())) return@runCatching null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })?.also {
                    if (it.allocationByteCount.toLong() > required) { it.recycle(); return@runCatching null }
                    while (images.isNotEmpty() && (images.size >= 4 || images.values.sumOf { image -> image.allocationByteCount.toLong() } + it.allocationByteCount > 32L * 1024 * 1024)) images.remove(images.keys.first())
                    allocatedImages[it] = it.allocationByteCount.toLong()
                    images[event.key] = it
                }
            }.getOrNull() ?: return@mapNotNull null
            val extra = event.extra
            val canvasW = extra.optDouble("canvas_width", videoWidth.toDouble()).coerceAtLeast(1.0)
            val canvasH = extra.optDouble("canvas_height", videoHeight.toDouble())
            val displayW = extra.optDouble("width", bitmap.width.toDouble())
            val displayH = extra.optDouble("height", bitmap.height.toDouble())
            val x = extra.optDouble("x", (canvasW - displayW) / 2).toFloat()
            val y = extra.optDouble("y", canvasH - displayH - 20).toFloat()
            if (listOf(canvasW, canvasH, displayW, displayH).any { !it.isFinite() || it <= 0 || it > 16384 } ||
                !x.isFinite() || !y.isFinite() || kotlin.math.abs(x) > 32768 || kotlin.math.abs(y) > 32768) return@mapNotNull null
            val cropX = extra.optInt("crop_x", 0).coerceIn(0, bitmap.width - 1)
            val cropY = extra.optInt("crop_y", 0).coerceIn(0, bitmap.height - 1)
            val width = extra.optInt("crop_width", bitmap.width - cropX).coerceIn(1, bitmap.width - cropX)
            val height = extra.optInt("crop_height", bitmap.height - cropY).coerceIn(1, bitmap.height - cropY)
            PgsLayer(bitmap, android.graphics.Rect(cropX, cropY, cropX + width, cropY + height),
                RectF((x / canvasW).toFloat(), (y / canvasH).toFloat(), ((x + displayW) / canvasW).toFloat(), ((y + displayH) / canvasH).toFloat()))
        }
        return if (layers.isEmpty()) null else DrawState(null, layers, area, 1, 1)
    }

    private fun releaseNative() {
        renderer?.release(); renderer = null
        nativeTrack?.release(); nativeTrack = null
        ass?.release(); ass = null
        previousFrame = null; renderWidth = 0; renderHeight = 0; fed.clear(); readOrders.clear(); forceFrame = true
    }

    private data class PgsLayer(val bitmap: Bitmap, val source: android.graphics.Rect, val dest: RectF)
    private data class DrawState(val ass: AssFrame?, val pgs: List<PgsLayer>, val area: RectF, val width: Int, val height: Int)
    private class SubtitlePlane(context: Context) : View(context) {
        var state: DrawState? = null
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(canvas: Canvas) {
            val frame = state ?: return
            val checkpoint = canvas.save()
            canvas.translate(frame.area.left, frame.area.top)
            canvas.clipRect(0f, 0f, frame.area.width(), frame.area.height())
            frame.ass?.images?.forEach { image -> image.bitmap?.let { bitmap ->
                val color = image.color
                paint.color = Color.argb(255 - (color and 255), color ushr 24 and 255, color ushr 16 and 255, color ushr 8 and 255)
                val sx = frame.area.width() / frame.width
                val sy = frame.area.height() / frame.height
                canvas.drawBitmap(bitmap, null, RectF(image.x * sx, image.y * sy, (image.x + image.w) * sx, (image.y + image.h) * sy), paint)
            } }
            paint.color = Color.WHITE
            frame.pgs.forEach { layer ->
                canvas.drawBitmap(layer.bitmap, layer.source, RectF(layer.dest.left * frame.area.width(), layer.dest.top * frame.area.height(),
                    layer.dest.right * frame.area.width(), layer.dest.bottom * frame.area.height()), paint)
            }
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun fit(width: Int, height: Int, ratio: Float): RectF {
        if (width <= 0 || height <= 0 || ratio <= 0) return RectF()
        val w = min(width.toFloat(), height * ratio)
        val h = w / ratio
        return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }
}
