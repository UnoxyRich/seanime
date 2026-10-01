package app.seanime.tv.platform

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeProviderBootstrapTest {
    @get:Rule val temporary = TemporaryFolder()

    private data class Fixture(val spec: BundledProviderSpec, val assets: Map<String, ByteArray>)

    private fun fixture(id: String = "fixture", version: String = "1.2.3", manifestId: String = id): Fixture {
        val manifest = JSONObject().put("id", manifestId).put("name", "Fixture provider").put("version", version)
            .put("type", "onlinestream-provider").put("lang", "en").put("language", "javascript")
            .put("manifestURI", "https://example.invalid/moving/manifest.json")
            .put("payloadURI", "https://example.invalid/moving/provider.js").toString().toByteArray()
        val payload = "class Provider { getSettings() { return { episodeServers: [], supportsDub: false }; } }\n".toByteArray()
        return Fixture(BundledProviderSpec(id, version, "onlinestream-provider", "anime/$id",
            NativeProviderBootstrap.sha256(manifest), NativeProviderBootstrap.sha256(payload)),
            mapOf("providers/$id/manifest.json" to manifest, "providers/$id/provider.js" to payload))
    }

    private fun bootstrap(fixture: Fixture = fixture(), files: ProviderBootstrapFiles = ProviderBootstrapFiles()) =
        NativeProviderBootstrap({ fixture.assets.getValue(it) }, listOf(fixture.spec), files)

    private fun dataDir() = temporary.newFolder("data")
    private fun ledger(data: File) = JSONObject(File(data, NativeProviderBootstrap.LEDGER_NAME).readText()).getJSONObject("providers")
    private fun provider(data: File) = File(data, "extensions/fixture.json")

    @Test fun freshInstallPublishesInlinePayloadWithPinnedExternalIdentityAndDurableLedger() {
        val data = dataDir()
        val fixture = fixture()
        val report = bootstrap(fixture).seed(data)
        assertEquals(listOf("fixture"), report.installed)
        assertTrue(report.warnings.toString(), report.warnings.isEmpty())
        val installed = JSONObject(provider(data).readText())
        assertEquals("fixture", installed.getString("id"))
        assertEquals("1.2.3", installed.getString("version"))
        assertEquals("en", installed.getString("lang"))
        assertEquals("", installed.getString("payloadURI"))
        assertEquals(fixture.assets.getValue("providers/fixture/provider.js").toString(Charsets.UTF_8), installed.getString("payload"))
        assertEquals(fixture.spec.manifestURI, installed.getString("manifestURI"))
        assertFalse(installed.getBoolean("isDevelopment"))
        assertEquals("complete", ledger(data).getJSONObject("fixture").getString("state"))
        assertEquals(listOf("fixture.json"), File(data, "extensions").listFiles()!!.map { it.name })
    }

    @Test fun existingNestedOlderNewerAndInvalidProvidersKeepTheirExactBytesAndConfiguration() {
        listOf("0.1.0", "9.8.7", "not-a-version").forEachIndexed { index, version ->
            val data = temporary.newFolder("existing-$index")
            val existing = File(data, "extensions/custom/subfolder/custom-name.json")
            existing.parentFile!!.mkdirs()
            val content = "{\"id\":\"fixture\",\"version\":\"$version\",\"disabled\":true,\"userConfig\":{\"custom\":true},\"payload\":\"user-edited\"}"
            existing.writeText(content)
            val savedSettings = File(data, "settings-fixture").apply { writeText("disabled fixture; user settings unchanged") }
            val report = bootstrap().seed(data)
            assertEquals(listOf("fixture"), report.preserved)
            assertFalse(provider(data).exists())
            assertEquals(content, existing.readText())
            assertEquals("disabled fixture; user settings unchanged", savedSettings.readText())
            existing.delete()
            assertTrue(bootstrap().seed(data).installed.isEmpty())
            assertFalse(provider(data).exists())
        }
    }

    @Test fun removalAndManualUpgradeArePreservedAcrossRestartsAndBundleVersionChanges() {
        val data = dataDir()
        bootstrap().seed(data)
        provider(data).writeText("{\"id\":\"fixture\",\"version\":\"99.0.0\",\"payload\":\"my version\"}")
        val edited = provider(data).readText()
        bootstrap(fixture(version = "2.0.0")).seed(data)
        assertEquals(edited, provider(data).readText())
        provider(data).delete()
        val report = bootstrap(fixture(version = "2.0.0")).seed(data)
        assertEquals(listOf("fixture"), report.preserved)
        assertFalse(provider(data).exists())
    }

    @Test fun customConfiguredDirectoryIsReportedWithoutFallbackOrChanges() {
        val data = dataDir()
        val custom = temporary.newFolder("custom providers #1")
        val config = "[server]\nport = 43211\n[extensions]\ndir = '${custom.absolutePath}' # preserve this setting\n"
        File(data, "config.toml").writeText(config)
        val existing = File(custom, "fixture.json").apply { writeText("{\"id\":\"fixture\",\"payload\":\"custom\"}") }
        val report = bootstrap().seed(data)
        assertTrue(report.installed.isEmpty())
        assertTrue(report.warnings.single().contains("custom or redirected extensions.dir"))
        assertEquals("{\"id\":\"fixture\",\"payload\":\"custom\"}", existing.readText())
        assertFalse(File(data, "extensions").exists())
        assertFalse(File(data, NativeProviderBootstrap.LEDGER_NAME).exists())
        assertEquals(config, File(data, "config.toml").readText())
    }

    @Test fun changingCustomPathAfterCompletedBootstrapDoesNotReinstallRemovedProvider() {
        val data = dataDir()
        bootstrap().seed(data)
        val custom = temporary.newFolder("replacement")
        File(data, "config.toml").writeText("extensions.dir = '${custom.absolutePath}'")
        assertTrue(bootstrap().seed(data).warnings.single().contains("custom or redirected extensions.dir"))
        assertFalse(File(custom, "fixture.json").exists())
    }

    @Test fun defaultDirectorySymlinkCannotRedirectPublicationIntoSharedStorage() {
        val data = dataDir()
        val custom = temporary.newFolder("shared")
        java.nio.file.Files.createSymbolicLink(File(data, "extensions").toPath(), custom.toPath())
        assertTrue(bootstrap().seed(data).warnings.single().contains("custom or redirected extensions.dir"))
        assertFalse(File(custom, "fixture.json").exists())
        assertFalse(File(data, NativeProviderBootstrap.LEDGER_NAME).exists())
    }

    @Test fun unresolvedCustomConfigurationReportsAndDoesNotSilentlyUseDefaultOrConsumeLedger() {
        val data = dataDir()
        File(data, "config.toml").writeText("extensions = { dir = '/custom/provider/location' }")
        val report = bootstrap().seed(data)
        assertEquals(1, report.warnings.size)
        assertFalse(File(data, "extensions").exists())
        assertFalse(File(data, NativeProviderBootstrap.LEDGER_NAME).exists())
    }

    @Test fun failedRenameRetainsPendingStageAndRetriesNeverPublishedProvider() {
        val data = dataDir()
        val failure = object : ProviderBootstrapFiles() {
            override fun publish(stage: File, target: File) { throw IOException("fixture rename failure") }
        }
        val failed = bootstrap(files = failure).seed(data)
        assertTrue(failed.warnings.single().contains("fixture rename failure"))
        assertFalse(provider(data).exists())
        val pending = ledger(data).getJSONObject("fixture")
        assertEquals("pending", pending.getString("state"))
        val stage = File(data, "extensions/${pending.getString("stage")}")
        assertTrue(stage.isFile)
        assertFalse(stage.name.endsWith(".json"))
        val retry = bootstrap().seed(data)
        assertEquals(listOf("fixture"), retry.installed)
        assertTrue(provider(data).isFile)
        assertFalse(stage.exists())
    }

    @Test fun pendingStageRetriesOriginalBytesAfterBundleUpdate() {
        val data = dataDir()
        val failure = object : ProviderBootstrapFiles() {
            override fun publish(stage: File, target: File) { throw IOException("fixture interrupted rename") }
        }
        bootstrap(files = failure).seed(data)
        assertEquals(listOf("fixture"), bootstrap(fixture(version = "2.0.0")).seed(data).installed)
        assertEquals("1.2.3", JSONObject(provider(data).readText()).getString("version"))
    }

    @Test fun failureBeforePendingLedgerCommitPublishesNothingAndNextAttemptCanInstall() {
        val data = dataDir()
        val failure = object : ProviderBootstrapFiles() {
            override fun writeLedger(target: File, bytes: ByteArray) { throw IOException("fixture disk full") }
        }
        assertTrue(bootstrap(files = failure).seed(data).warnings.single().contains("fixture disk full"))
        assertFalse(provider(data).exists())
        assertFalse(File(data, NativeProviderBootstrap.LEDGER_NAME).exists())
        assertEquals(listOf("fixture"), bootstrap().seed(data).installed)
    }

    private fun failAfterPublish(): ProviderBootstrapFiles = object : ProviderBootstrapFiles() {
        private var writes = 0
        override fun writeLedger(target: File, bytes: ByteArray) {
            if (++writes == 2) throw IOException("fixture interrupted completion")
            super.writeLedger(target, bytes)
        }
    }

    @Test fun crashAfterPublishRecoversExistingProviderWithoutOverwritingIt() {
        val data = dataDir()
        assertTrue(bootstrap(files = failAfterPublish()).seed(data).warnings.single().contains("fixture interrupted completion"))
        assertTrue(provider(data).exists())
        assertEquals("pending", ledger(data).getJSONObject("fixture").getString("state"))
        provider(data).writeText("{\"id\":\"fixture\",\"payload\":\"edited after startup\"}")
        val edited = provider(data).readText()
        assertEquals(listOf("fixture"), bootstrap().seed(data).preserved)
        assertEquals(edited, provider(data).readText())
    }

    @Test fun removalAfterPublishBeforeCompletionLedgerIsNeverReinstalled() {
        val data = dataDir()
        bootstrap(files = failAfterPublish()).seed(data)
        provider(data).delete()
        val report = bootstrap().seed(data)
        assertEquals(listOf("fixture"), report.preserved)
        assertFalse(provider(data).exists())
        assertEquals("complete", ledger(data).getJSONObject("fixture").getString("state"))
    }

    @Test fun changedPendingStageIsNotPublishedOrReplaced() {
        val data = dataDir()
        val failure = object : ProviderBootstrapFiles() {
            override fun publish(stage: File, target: File) { throw IOException("fixture interrupted rename") }
        }
        bootstrap(files = failure).seed(data)
        val pending = ledger(data).getJSONObject("fixture")
        val stage = File(data, "extensions/${pending.getString("stage")}").apply { writeText("edited stage") }
        assertTrue(bootstrap().seed(data).warnings.single().contains("stage changed"))
        assertEquals("edited stage", stage.readText())
        assertFalse(provider(data).exists())
    }

    @Test fun corruptLedgerAndMalformedExtensionFailClosedWithoutChangingUserFiles() {
        val ledgerData = temporary.newFolder("ledger-data")
        val marker = File(ledgerData, NativeProviderBootstrap.LEDGER_NAME).apply { writeText("broken ledger") }
        assertTrue(bootstrap().seed(ledgerData).warnings.isNotEmpty())
        assertEquals("broken ledger", marker.readText())
        assertFalse(provider(ledgerData).exists())
        val brokenData = temporary.newFolder("broken-data")
        val existing = File(brokenData, "extensions/nested/user.json")
        existing.parentFile!!.mkdirs()
        existing.writeText("{\"id\":\"fixture\",broken user file")
        assertTrue(bootstrap().seed(brokenData).warnings.isNotEmpty())
        assertEquals("{\"id\":\"fixture\",broken user file", existing.readText())
        assertFalse(provider(brokenData).exists())
    }

    @Test fun assetIdentityOrChecksumMismatchCannotSeedProvider() {
        val wrongIdentity = fixture(manifestId = "different")
        val data = dataDir()
        assertTrue(bootstrap(wrongIdentity).seed(data).warnings.single().contains("identity mismatch"))
        val valid = fixture()
        val changed = valid.copy(assets = valid.assets + ("providers/fixture/provider.js" to "tampered".toByteArray()))
        assertTrue(bootstrap(changed).seed(data).warnings.single().contains("checksum mismatch"))
        assertFalse(provider(data).exists())
        assertFalse(File(data, NativeProviderBootstrap.LEDGER_NAME).exists())
    }

    @Test fun hostQueueOrdersBootstrapBeforeServerAndLifecycleWithoutBlockingCaller() {
        val data = dataDir()
        val fixture = fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = mutableListOf<String>()
        val seeder = NativeProviderBootstrap({ path ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            fixture.assets.getValue(path)
        }, listOf(fixture.spec))
        NativeHostQueue { calls += "runtime" }.use { queue ->
            val owner = queue.claimOwner()
            val startup = queue.runForOwner(owner) {
                seeder.beforeServerStart(data) {
                    assertTrue(provider(data).isFile)
                    assertEquals("complete", ledger(data).getJSONObject("fixture").getString("state"))
                    calls += "server"
                }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val lifecycle = queue.submit { calls += "foreground" }
                assertFalse(startup.isDone)
                assertFalse(lifecycle.isDone)
                assertFalse(provider(data).exists())
                release.countDown()
                assertTrue(startup.get(5, TimeUnit.SECONDS))
                lifecycle.get(5, TimeUnit.SECONDS)
                assertEquals(listOf("runtime", "server", "foreground"), calls)
            } finally { release.countDown() }
        }
    }

    @Test fun bootstrapFailureReportsButDoesNotPreventExistingServerStartup() {
        val data = dataDir()
        var started = false
        File(data, "config.toml").writeText("extensions = { dir = '/custom' }")
        val report = bootstrap().beforeServerStart(data) { started = true }
        assertTrue(started)
        assertTrue(report.warnings.isNotEmpty())
        assertFalse(provider(data).exists())
    }
}
