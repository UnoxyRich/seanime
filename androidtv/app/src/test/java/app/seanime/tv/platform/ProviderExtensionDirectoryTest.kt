package app.seanime.tv.platform

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderExtensionDirectoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun absentAndDefaultConfigResolveSameGoDataDirectory() {
        val data = temporary.newFolder("data")
        assertEquals(File(data, "extensions").canonicalFile, ProviderExtensionDirectory.resolve(data) { null })
        File(data, "config.toml").writeText("[extensions]\ndir = '\$SEANIME_DATA_DIR/extensions'\n")
        assertEquals(File(data, "extensions").canonicalFile, ProviderExtensionDirectory.resolve(data) { null })
    }

    @Test fun tableDottedQuotedAndCaseInsensitiveFormsResolveExplicitCustomPath() {
        val data = temporary.newFolder("data")
        val custom = temporary.newFolder("custom #providers")
        val configs = listOf(
            "[extensions]\ndir = '${custom.absolutePath}' # comment",
            "extensions.dir = \"${custom.absolutePath}\"",
            "[\"Extensions\"]\n'Dir' = '${custom.absolutePath}'",
            "\"extensions\" . 'dir' = '${custom.absolutePath}'",
            "[server]\npassword = 'contains # and = signs'\n[extensions]\ndir = '${custom.absolutePath}'",
        )
        configs.forEach { config ->
            File(data, "config.toml").writeText(config)
            assertEquals(config, custom.canonicalFile, ProviderExtensionDirectory.resolve(data) { null })
        }
    }

    @Test fun knownDataWorkingAndConfiguredEnvironmentVariablesAreExpanded() {
        val data = temporary.newFolder("data")
        listOf("\${SEANIME_DATA_DIR}", "\$SEANIME_WORKING_DIR", "\$CUSTOM_PROVIDER_ROOT").forEach { variable ->
            File(data, "config.toml").writeText("[extensions]\ndir = '$variable/custom'")
            assertEquals(File(data, "custom").canonicalFile, ProviderExtensionDirectory.resolve(data) { if (it == "CUSTOM_PROVIDER_ROOT") data.absolutePath else null })
        }
    }

    @Test fun symlinkedDataAndConfiguredDirectoriesResolveTheSameCanonicalPaths() {
        val data = temporary.newFolder("data")
        val dataAlias = File(temporary.root, "data-alias")
        Files.createSymbolicLink(dataAlias.toPath(), data.canonicalFile.toPath())
        assertTrue(Files.isSymbolicLink(dataAlias.toPath()))
        val defaultDirectory = File(data, "extensions").canonicalFile
        assertEquals(defaultDirectory, ProviderExtensionDirectory.resolve(data) { null })
        assertEquals(defaultDirectory, ProviderExtensionDirectory.resolve(dataAlias) { null })

        val custom = temporary.newFolder("custom #providers")
        val customAlias = File(temporary.root, "custom-alias")
        Files.createSymbolicLink(customAlias.toPath(), custom.canonicalFile.toPath())
        assertTrue(Files.isSymbolicLink(customAlias.toPath()))
        // Keep alias spellings in the inputs; the resolver must canonicalize both kinds of path.
        File(data, "config.toml").writeText("[extensions]\ndir = '${customAlias.absolutePath}'")
        assertEquals(custom.canonicalFile, ProviderExtensionDirectory.resolve(data) { null })
        assertEquals(custom.canonicalFile, ProviderExtensionDirectory.resolve(dataAlias) { null })
    }

    @Test fun ambiguousUnsupportedRelativeAndUnresolvedPathsFailClosed() {
        val data = temporary.newFolder("data")
        listOf(
            "extensions = { dir = '/custom' }",
            "[extensions]\ndir = 'relative/path'",
            "[extensions]\ndir = '\$UNKNOWN_PROVIDER_ROOT/providers'",
            "extensions.dir = '/one'\nextensions.dir = '/two'",
            "[extensions.dir]\npath = '/custom'",
            "\"extensions.dir\" = '/custom'",
            "[extensions]\ndir = '''/custom'''",
            "[extensions]\ndir = \"/custom\n/providers\"",
        ).forEach { config ->
            File(data, "config.toml").writeText(config)
            assertTrue(config, runCatching { ProviderExtensionDirectory.resolve(data) { null } }.isFailure)
        }
    }
}
