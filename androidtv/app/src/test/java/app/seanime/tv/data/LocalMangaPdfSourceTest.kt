package app.seanime.tv.data

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMangaPdfSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun chapter(id: String = "Series/Chapter 1.pdf", provider: String = "local-manga", pdf: Boolean = true) =
        MangaChapter(id, "Chapter 1", "1", provider, JSONObject().put("localIsPDF", pdf))
    private fun source(root: String, item: MangaChapter = chapter(), server: String = "http://127.0.0.1:43211", mediaId: Long = 42L) =
        LocalMangaPdfSource.resolve(server, root, mediaId, item)

    @Test fun sourceUsesExactRelativePathAndStableIdentity() {
        val root = temporary.newFolder("manga").path
        val a = source(root)
        val b = source(root)
        assertEquals(File(root, "Series/Chapter 1.pdf").canonicalPath, a.path)
        assertEquals(a.cacheIdentity, b.cacheIdentity)
        assertFalse(a.usesSaf)
        assertEquals(64, a.cacheIdentity.length)
        assertFalse(a.toString().contains(root))
    }

    @Test fun cacheIdentitySeparatesRootMediaChapterAndServerPort() {
        val root = temporary.newFolder("manga").path
        val a = source(root)
        assertNotEquals(a.cacheIdentity, source(temporary.newFolder("other").path).cacheIdentity)
        assertNotEquals(a.cacheIdentity, source(root, mediaId = 99).cacheIdentity)
        assertNotEquals(a.cacheIdentity, source(root, chapter("Series/Chapter 2.pdf")).cacheIdentity)
        assertNotEquals(a.cacheIdentity, source(root, server = "http://127.0.0.1:43212").cacheIdentity)
    }

    @Test fun cacheIdentitySeparatesCompleteCustomSourceIds() {
        val root = temporary.newFolder("custom-manga").path
        val ids = listOf(1L, 2_147_483_648L, 4_294_967_297L, 9_007_199_254_740_991L)
        assertEquals(ids.size, ids.map { source(root, mediaId = it).cacheIdentity }.toSet().size)
    }

    @Test fun onlyLocalPdfProviderMetadataCanOpenLocalFiles() {
        val root = temporary.newFolder("manga").path
        for (item in listOf(chapter(provider = "remote-provider"), chapter(pdf = false), chapter("Series/Chapter 1.cbz"))) {
            assertTrue(runCatching { source(root, item) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun remoteServerPathsCannotBecomeTvLocalPaths() {
        val root = temporary.newFolder("manga").path
        for (url in listOf("https://seanime.example", "http://127.0.0.1.evil.example:43211", "file:///storage", "http://192.168.1.2:43211", "http://user:pass@127.0.0.1:43211", "http://127.0.0.1", "http://127.0.0.1:43211/other", "http://localhost:43211")) {
            assertTrue(runCatching { source(root, server = url) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun traversalAbsoluteAndAmbiguousSeparatorsAreRejected() {
        val root = temporary.newFolder("manga").path
        for (id in listOf("../secret.pdf", "/secret.pdf", "Series/../secret.pdf", "Series//Chapter.pdf", "Series/./Chapter.pdf", "Series\\Chapter.pdf", "content:Chapter.pdf", "Series/\u0000.pdf")) {
            assertTrue("Rejected $id", runCatching { source(root, chapter(id)) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun safRootKeepsGrantedStorageIdentityAndValidatesSegments() {
        val resolved = source("/androidtv/0123456789abcdef/Manga")
        assertTrue(resolved.usesSaf)
        assertEquals("/androidtv/0123456789abcdef/Manga/Series/Chapter 1.pdf", resolved.path)
        for (root in listOf("/androidtv/no-grant/Manga", "/androidtv/0123456789abcdef/../escape")) {
            assertTrue(runCatching { source(root) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun percentEncodedFileNamesStayLiteralRatherThanBecomingTraversal() {
        val root = temporary.newFolder("manga").path
        assertEquals(File(root, "Series/%2e%2e%2fChapter.pdf").canonicalPath, source(root, chapter("Series/%2e%2e%2fChapter.pdf")).path)
    }

    @Test fun filesystemSymlinkCannotEscapeSelectedRoot() {
        val root = temporary.newFolder("manga")
        val outside = temporary.newFolder("outside")
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        assertTrue(runCatching { source(root.path, chapter("escape/chapter.pdf")) }.exceptionOrNull() is IllegalArgumentException)
    }
}
