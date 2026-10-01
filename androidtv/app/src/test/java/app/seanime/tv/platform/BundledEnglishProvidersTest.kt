package app.seanime.tv.platform

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Byte/identity verification only. These tests never evaluate the third-party JavaScript. */
class BundledEnglishProvidersTest {
    @get:Rule val temporary = TemporaryFolder()

    private val assets: File get() = listOf(File("src/main/assets"), File("app/src/main/assets"), File("androidtv/app/src/main/assets"))
        .first { File(it, "providers/provenance.json").isFile }

    @Test fun approvedAssetsMatchRecordedGitBlobsSha256AndMitLicense() {
        val root = File(assets, "providers")
        val provenance = JSONObject(File(root, "provenance.json").readText())
        assertEquals(BundledEnglishProviders.commit, provenance.getString("commit"))
        assertEquals("MIT", provenance.getString("license"))
        val license = File(root, "LICENSE").readBytes()
        assertEquals(provenance.getString("licenseSha256"), NativeProviderBootstrap.sha256(license))
        assertTrue(license.toString(Charsets.UTF_8).contains("Copyright (c) 2025 Pal"))
        val records = provenance.getJSONArray("providers")
        assertEquals(setOf("animeheaven", "anidb", "atsumaru"), BundledEnglishProviders.entries.map { it.id }.toSet())
        assertEquals(3, records.length())
        BundledEnglishProviders.entries.forEach { provider ->
            val record = (0 until records.length()).map { records.getJSONObject(it) }.single { it.getString("id") == provider.id }
            assertEquals(provider.version, record.getString("version"))
            assertEquals(provider.manifestURI, record.getString("manifestURI"))
            for (name in listOf("manifest.json", "provider.js")) {
                val bytes = File(root, "${provider.id}/$name").readBytes()
                val entry = record.getJSONObject("files").getJSONObject(name)
                assertEquals(entry.getInt("bytes"), bytes.size)
                assertEquals(entry.getString("sha256"), NativeProviderBootstrap.sha256(bytes))
                val gitBlob = MessageDigest.getInstance("SHA-1").digest("blob ${bytes.size}\u0000".toByteArray() + bytes)
                    .joinToString("") { "%02x".format(it) }
                assertEquals(entry.getString("gitBlob"), gitBlob)
            }
        }
    }

    @Test fun realAssetsSeedOnlyApprovedEnglishIdsWithoutEvaluatingOrFetchingCode() {
        val data = temporary.newFolder("data")
        val bootstrap = NativeProviderBootstrap({ File(assets, it).readBytes() })
        val report = bootstrap.seed(data)
        assertEquals(listOf("animeheaven", "anidb", "atsumaru"), report.installed)
        assertTrue(report.warnings.toString(), report.warnings.isEmpty())
        BundledEnglishProviders.entries.forEach { provider ->
            val installed = JSONObject(File(data, "extensions/${provider.id}.json").readText())
            assertEquals("en", installed.getString("lang"))
            assertEquals(provider.version, installed.getString("version"))
            assertEquals(provider.manifestURI, installed.getString("manifestURI"))
            assertEquals("", installed.getString("payloadURI"))
            assertFalse(installed.getBoolean("isDevelopment"))
            assertEquals(provider.payloadSha256, NativeProviderBootstrap.sha256(installed.getString("payload").toByteArray()))
        }
        assertTrue(bootstrap.seed(data).installed.isEmpty())
    }
}
