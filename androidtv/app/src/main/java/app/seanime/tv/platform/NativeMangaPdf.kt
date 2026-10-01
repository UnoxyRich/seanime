package app.seanime.tv.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.seanime.tv.AndroidSafStorageAdapter
import app.seanime.tv.data.LocalMangaPdfSource
import app.seanime.tv.data.MangaPageDimensions
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Native PDF session. Only visible pages are rasterized; no WebView or server modification. */
class NativeMangaPdf private constructor(
    val cacheIdentity: String,
    private val directory: File,
    private val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
) : Closeable {
    val pageCount: Int = renderer.pageCount
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val pageCache = LinkedHashMap<Int, File>(8, 0.75f, true)

    fun pageUrl(index: Int): String {
        require(index in 0 until pageCount)
        return File(directory, "page-$index.png").toURI().toString()
    }

    /** Reads metadata only. PdfRenderer still has exactly one open page under the session lock. */
    suspend fun pageDimensions(): Map<Int, MangaPageDimensions> = withContext(Dispatchers.IO) {
        val work = currentCoroutineContext()
        work.ensureActive()
        synchronized(lock) {
            check(!closed.get()) { "PDF reader has closed" }
            buildMap {
                // pageCount was bounded when the document was opened; no bitmap or raster file is created.
                for (index in 0 until pageCount) {
                    work.ensureActive()
                    check(!closed.get()) { "PDF reader has closed" }
                    renderer.openPage(index).use { page ->
                        if (page.width > 0 && page.height > 0) put(index, MangaPageDimensions(page.width, page.height))
                    }
                }
            }
        }
    }

    suspend fun renderPage(index: Int): File = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        require(index in 0 until pageCount) { "PDF page is out of range" }
        synchronized(lock) {
            check(!closed.get()) { "PDF reader has closed" }
            pageCache[index]?.takeIf(File::isFile)?.let { return@synchronized it }
            val destination = File(directory, "page-$index.png")
            renderer.openPage(index).use { page ->
                require(page.width > 0 && page.height > 0) { "The PDF page has invalid dimensions" }
                val scale = MAX_PAGE_EDGE.toFloat() / max(page.width, page.height).coerceAtLeast(1)
                val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    val matrix = Matrix().apply { setScale(width.toFloat() / page.width, height.toFloat() / page.height) }
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    check(!closed.get()) { "PDF reader has closed" }
                    destination.outputStream().use { output ->
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("PDF page could not be rendered")
                    }
                } catch (error: Throwable) {
                    destination.delete()
                    throw error
                } finally { bitmap.recycle() }
            }
            pageCache[index] = destination
            while (pageCache.size > MAX_CACHED_PAGES || (pageCache.size > 2 && pageCache.values.sumOf { it.length() } > MAX_PAGE_CACHE_BYTES)) {
                val oldest = pageCache.entries.iterator()
                val entry = oldest.next()
                entry.value.delete()
                oldest.remove()
            }
            destination
        }
    }

    /** Nonblocking for Compose disposal; an in-flight render finishes before native resources close. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cleanupExecutor.execute {
            synchronized(lock) {
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
                pageCache.clear()
                try { directory.deleteRecursively() } finally { activeDirectories.remove(directory.path) }
            }
        }
    }

    companion object {
        private const val MAX_PDF_BYTES = 128L * 1024 * 1024
        private const val MAX_PAGE_EDGE = 2048
        private const val MAX_CACHED_PAGES = 8
        private const val MAX_PAGE_CACHE_BYTES = 32L * 1024 * 1024
        private val activeDirectories = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        private val cacheLock = Any()
        private val cleanupExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "seanime-pdf-cleanup").apply { isDaemon = true } }

        suspend fun open(context: Context, source: LocalMangaPdfSource): NativeMangaPdf {
            var opened: NativeMangaPdf? = null
            try {
                return withContext(Dispatchers.IO) {
                    val app = context.applicationContext
                    val parent = File(app.cacheDir, "native-manga-pdf").apply { if (!mkdirs() && !isDirectory) throw IOException("PDF cache is unavailable") }
                    val directory = synchronized(cacheLock) {
                        // Sessions left by a killed app process are disposable; preserve every currently open reader.
                        parent.listFiles()?.filter { it.isDirectory && it.path !in activeDirectories }?.forEach { it.deleteRecursively() }
                        File(parent, "${source.cacheIdentity}-${UUID.randomUUID()}").apply {
                            if (!mkdir()) throw IOException("PDF cache is unavailable")
                            activeDirectories.add(path)
                        }
                    }
                    var descriptor: ParcelFileDescriptor? = null
                    var renderer: PdfRenderer? = null
                    try {
                        val destination = File(directory, "source.pdf")
                        val input = if (source.usesSaf) {
                            // The existing adapter enforces the saved tree's persisted read grant and path segments.
                            val uri = Uri.parse(AndroidSafStorageAdapter(app).uri(source.path))
                            app.contentResolver.openInputStream(uri) ?: throw IOException("The selected PDF is unavailable")
                        } else {
                            FileInputStream(File(source.path))
                        }
                        input.use { from -> destination.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = from.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_PDF_BYTES) { "This PDF exceeds the 128 MB TV reader limit" }
                                output.write(buffer, 0, count)
                            }
                        } }
                        descriptor = ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_READ_ONLY)
                        renderer = PdfRenderer(descriptor!!)
                        require(renderer.pageCount in 1..10_000) { "This PDF has no readable pages or exceeds the page limit" }
                        NativeMangaPdf(source.cacheIdentity, directory, renderer, descriptor!!).also { opened = it }
                    } catch (error: Throwable) {
                        runCatching { renderer?.close() }
                        runCatching { descriptor?.close() }
                        directory.deleteRecursively()
                        activeDirectories.remove(directory.path)
                        throw error
                    }
                }
            } catch (error: Throwable) {
                // Cancellation after opening but before returning must not leak a native descriptor/session.
                opened?.close()
                throw error
            }
        }
    }
}
