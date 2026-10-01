package app.seanime.tv

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeLibraryRootTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun appOwnedDefaultUsesThePhysicalRootWhenAndroidExposesAnAlias() {
        val physicalFiles = temporary.newFolder("physical-files")
        val alias = File(temporary.root, "files-alias")
        Files.createSymbolicLink(alias.toPath(), physicalFiles.toPath())

        val library = nativeDefaultLibraryPath(alias)
        val media = File(library, "owned.mkv").apply { writeText("owned fixture") }

        assertNotEquals(alias.resolve("seanime/library").absolutePath, library)
        assertEquals(physicalFiles.resolve("seanime/library").canonicalPath, library)
        assertEquals(library, media.canonicalFile.parent)
        assertEquals(media.canonicalFile, alias.resolve("seanime/library/owned.mkv").canonicalFile)
        assertEquals("owned fixture", alias.resolve("seanime/library/owned.mkv").readText())
    }
}
