package app.seanime.tv.platform

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderExtensionDirectoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun absentAndDefaultConfigResolveSameGoDataDirectory() {
        val data = temporary.newFolder("data")
        assertEquals(File(data, "extensions"), ProviderExtensionDirectory.resolve(data) { null })
        File(data, "config.toml").writeText("[extensions]\ndir = '\$SEANIME_DATA_DIR/extensions'\n")
        assertEquals(File(data, "extensions"), ProviderExtensionDirectory.resolve(data) { null })
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
            assertEquals(config, custom, ProviderExtensionDirectory.resolve(data) { null })
        }
    }

    @Test fun knownDataWorkingAndConfiguredEnvironmentVariablesAreExpanded() {
        val data = temporary.newFolder("data")
        listOf("\${SEANIME_DATA_DIR}", "\$SEANIME_WORKING_DIR", "\$CUSTOM_PROVIDER_ROOT").forEach { variable ->
            File(data, "config.toml").writeText("[extensions]\ndir = '$variable/custom'")
            assertEquals(File(data, "custom"), ProviderExtensionDirectory.resolve(data) { if (it == "CUSTOM_PROVIDER_ROOT") data.absolutePath else null })
        }
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
