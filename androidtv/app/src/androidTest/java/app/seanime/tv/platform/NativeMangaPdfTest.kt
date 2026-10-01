package app.seanime.tv.platform

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.seanime.tv.data.LocalMangaPdfSource
import app.seanime.tv.data.MangaChapter
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Android's real PDF writer and PdfRenderer; no provider, network or WebView fixtures. */
@RunWith(AndroidJUnit4::class)
class NativeMangaPdfTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun generatedPdfRendersPagesAndClosesItsSessionCache() = runBlocking {
        val root = createPdf(pages = 3)
        var document: NativeMangaPdf? = null
        try {
            val opened = NativeMangaPdf.open(context, source(root))
            document = opened
            assertEquals(3, opened.pageCount)
            val dimensions = opened.pageDimensions()
            assertEquals(setOf(0, 1, 2), dimensions.keys)
            assertEquals(300, dimensions.getValue(0).width)
            assertEquals(420, dimensions.getValue(0).height)
            assertFalse(dimensions.getValue(0).isWide)
            assertTrue(dimensions.getValue(1).isWide)
            val untouchedDirectory = File(URI(opened.pageUrl(0))).parentFile!!
            assertTrue("Page metadata must not rasterize hidden pages", untouchedDirectory.listFiles().orEmpty().none { it.extension == "png" })
            val page = opened.renderPage(0)
            assertTrue(page.isFile)
            val bitmap = BitmapFactory.decodeFile(page.path)
            try {
                assertNotNull(bitmap)
                assertTrue(bitmap.width <= 2048 && bitmap.height <= 2048)
                assertEquals(Color.RED, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            } finally { bitmap?.recycle() }
            assertEquals(page.path, opened.renderPage(0).path)
            val cachedDirectory = page.parentFile!!
            opened.close()
            assertTrue(runCatching { opened.renderPage(1) }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { opened.pageDimensions() }.exceptionOrNull() is IllegalStateException)
            awaitRemoved(cachedDirectory)
        } finally { document?.close(); root.deleteRecursively() }
    }

    @Test fun visiblePageWorkIsSerializedAndRasterCacheIsBounded() = runBlocking {
        val root = createPdf(pages = 10)
        var document: NativeMangaPdf? = null
        try {
            val opened = NativeMangaPdf.open(context, source(root))
            document = opened
            (0 until 10).map { index -> async { opened.renderPage(index) } }.awaitAll()
            val directory = File(URI(opened.pageUrl(0))).parentFile!!
            assertTrue(directory.listFiles().orEmpty().count { it.extension == "png" } <= 8)
            assertTrue(opened.renderPage(0).isFile) // An evicted page is rendered again when revisited.
            opened.close()
            awaitRemoved(directory)
        } finally { document?.close(); root.deleteRecursively() }
    }

    @Test fun invalidPdfFailsWithoutLeavingItsPrivateCopyBehind() = runBlocking {
        val root = File(context.cacheDir, "pdf-test-${UUID.randomUUID()}").apply { mkdirs() }
        File(root, "chapter.pdf").writeText("This is not a PDF")
        val input = source(root)
        try {
            assertNotNull(runCatching { NativeMangaPdf.open(context, input) }.exceptionOrNull())
            val cache = File(context.cacheDir, "native-manga-pdf")
            assertFalse(cache.listFiles().orEmpty().any { it.name.startsWith(input.cacheIdentity) })
        } finally { root.deleteRecursively() }
    }

    private fun source(root: File): LocalMangaPdfSource = LocalMangaPdfSource.resolve(
        "http://127.0.0.1:43211", root.path, 42,
        MangaChapter("chapter.pdf", "Chapter 1", "1", "local-manga", JSONObject().put("localIsPDF", true)),
    )

    private fun createPdf(pages: Int): File {
        val root = File(context.cacheDir, "pdf-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val pdf = PdfDocument()
        try {
            repeat(pages) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(if (index == 1) 600 else 300, 420, index + 1).create())
                page.canvas.drawColor(if (index % 2 == 0) Color.RED else Color.BLUE)
                pdf.finishPage(page)
            }
            File(root, "chapter.pdf").outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        return root
    }

    private fun awaitRemoved(directory: File) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (directory.exists() && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse("PDF session files must be removed after close", directory.exists())
    }
}
