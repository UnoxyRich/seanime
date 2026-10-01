package app.seanime.tv.ui

import app.seanime.tv.data.ExtensionItem
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SettingsFieldModelsTest {
    @Test fun nativeListPayloadKeepsNumericIdsAndDeduplicatesWithoutMutation() {
        val values = listOf("12", " 42 ", "12")
        val result = settingListValue("hostUnsharedAnimeIds", values)
        assertEquals(2, result.length()); assertEquals(12, result.getInt(0)); assertEquals(42, result.getInt(1))
        assertTrue(result.get(0) is Number); assertEquals(3, values.size)
    }
    @Test fun invalidIdsAndUnrepresentableFolderPathsAreRejected() {
        listOf("0", "-1", "1.5", "9007199254740992", "9223372036854775807", "text").forEach {
            assertTrue(runCatching { settingListValue("hostUnsharedAnimeIds", listOf(it)) }.isFailure)
        }
        listOf("", "   ", "/media/a,b", "bad\u0000path").forEach {
            assertTrue(runCatching { settingListValue("libraryPaths", listOf(it)) }.isFailure)
        }
        assertEquals("/androidtv/library/show", settingListValue("libraryPaths", listOf("/androidtv/library/show")).getString(0))
        assertEquals(0, settingListValue("libraryPaths", emptyList()).length())
    }
    @Test fun genericExclusionSerializerAlsoRetainsCompleteMediaIdentities() {
        val identities = listOf(2_147_483_648L, 4_294_967_297L, 9_007_199_254_740_991L)
        val serialized = settingListValue("hostUnsharedAnimeIds", identities.map(Long::toString)).toString()
        val restored = JSONArray(serialized)
        assertEquals(identities, List(restored.length()) { restored.getLong(it) })
    }
    @Test fun choiceListsKeepUnknownPluginAndEncoderSettings() {
        assertTrue(settingChoices("library", "defaultPlaybackSource", "ext:provider").any { it.value == "ext:provider" })
        assertTrue(settingChoices("mediastream", "transcodeHwAccel", "vaapi").any { it.value == "vaapi" })
        assertTrue(settingChoices("mediastream", "transcodeHwAccel", "auto").any { it.value == "mediacodec" })
        assertEquals("androidtv", settingChoices("library", "updateChannel", "androidtv").single().value)
    }
    @Test fun numericFieldsRejectInvalidRangesAndPreserveIntegerType() {
        assertEquals(65535L, validateSettingNumber("seanimePort", "65535", true))
        assertEquals(.75, validateSettingNumber("scannerMatchingThreshold", "0.75", false))
        listOf("-1", "65536", "NaN").forEach { assertTrue(runCatching { validateSettingNumber("vlcPort", it, true) }.isFailure) }
        assertTrue(runCatching { validateSettingNumber("scannerMatchingThreshold", "2", false) }.isFailure)
    }
    @Test fun providerChoicesUseInstalledNamesAndKeepAnUnavailableSavedValue() {
        val installed = listOf(ExtensionItem("provider-a", "Readable provider"), ExtensionItem("provider-b", "Disabled", disabled = true))
        val values = settingProviderChoices("saved-provider", installed)
        assertEquals("Readable provider", values.single { it.value == "provider-a" }.label)
        assertFalse(values.any { it.value == "provider-b" })
        assertTrue(values.any { it.value == "saved-provider" })
        assertEquals("manga-provider", settingProviderType("manga", "defaultMangaProvider"))
        assertNull(settingProviderType("mediaPlayer", "host"))
    }
    @Test fun pluginPlaybackChoicesUseEpisodeTabIdsAndPreserveUnavailableSources() {
        val tab = JSONObject().put("id", "fixture-plugin").put("name", "Fixture plugin").put("tabName", "Custom episodes")
        val choices = settingPlaybackSourceChoices("ext:unavailable", listOf(tab, tab))
        assertEquals(1, choices.count { it.value == "ext:fixture-plugin" })
        assertEquals("Custom episodes (Fixture plugin)", choices.single { it.value == "ext:fixture-plugin" }.label)
        assertTrue(choices.any { it.value == "ext:unavailable" })
        assertTrue(choices.any { it.value.isEmpty() })
    }
}
