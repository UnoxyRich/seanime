package app.seanime.tv.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AutoDownloaderPayloadTest {
    private fun rule() = AutoRule(id = 9, mediaId = 5114, title = "Example", destination = "/library/Example", episodes = listOf(1, 3), episodeType = "selected")
    private fun fails(block: () -> Unit) { try { block(); fail("Expected validation to reject the input") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }

    @Test fun ruleMutationUsesWrappedHandlerContractAndKeepsUnknownFields() {
        val original = rule().payload().put("futureRuleOption", JSONObject().put("keep", true)).put("profileId", 42).put("providers", org.json.JSONArray(listOf("missing-provider")))
        val edited = AutoRule.from(original).copy(enabled = false)
        val body = AutoDownloaderPayload.ruleBody(edited)
        assertEquals(setOf("rule"), body.keys().asSequence().toSet())
        val saved = body.getJSONObject("rule")
        assertEquals(9, saved.getInt("dbId"))
        assertFalse(saved.getBoolean("enabled"))
        assertTrue(saved.getJSONObject("futureRuleOption").getBoolean("keep"))
        assertEquals(42, saved.getInt("profileId"))
        assertEquals("missing-provider", saved.getJSONArray("providers").getString(0))
        assertTrue(original.getBoolean("enabled"))
    }
    @Test fun clearingLinkedProfileExplicitlyClearsOldAssociation() {
        val source = rule().copy(profileId = 3).payload()
        val saved = AutoRule.from(source).copy(profileId = null).payload()
        assertTrue(saved.has("profileId"))
        assertTrue(saved.isNull("profileId"))
    }
    @Test fun profileIsUnwrappedAndPreservesConditionIdentityAndExtras() {
        val source = JSONObject("""{"dbId":7,"name":"Quality","global":true,"releaseGroups":["Group"],"resolutions":["1080p","720p"],"conditions":[{"id":"original","term":"HEVC","isRegex":false,"action":"score","score":15,"futureFlag":"retained"}],"minimumScore":5,"minSeeders":2,"minSize":"500 MB","maxSize":"2 GiB","delayMinutes":30,"skipDelayScore":50,"providers":["source"],"futureProfileFlag":true}""")
        val saved = AutoDownloaderPayload.profileBody(AutoProfile.from(source).copy(name = "Renamed"))
        assertFalse(saved.has("profile"))
        assertEquals("Renamed", saved.getString("name"))
        assertEquals("original", saved.getJSONArray("conditions").getJSONObject(0).getString("id"))
        assertEquals("retained", saved.getJSONArray("conditions").getJSONObject(0).getString("futureFlag"))
        assertEquals("1080p", saved.getJSONArray("resolutions").getString(0))
        assertTrue(saved.getBoolean("futureProfileFlag"))
        assertEquals("Quality", source.getString("name"))
    }
    @Test fun ruleValidationRejectsMissingDataRelativePathsAndEmptySelection() {
        fails { rule().copy(mediaId = 0).payload() }
        fails { rule().copy(title = "").payload() }
        fails { rule().copy(destination = "Downloads/Anime").payload() }
        fails { rule().copy(episodes = emptyList()).payload() }
        fails { rule().copy(minSeeders = -1).payload() }
        fails { rule().copy(episodeType = "all").payload() }
        rule().copy(episodes = emptyList(), episodeType = "recent").payload()
    }
    @Test fun orderedTermsAndRangesAreValidatedWithoutSilentDataLoss() {
        assertEquals(listOf("1080p", "720p"), AutoDownloaderPayload.terms("1080p, 720p, 1080p"))
        assertEquals(listOf(1, 3, 4, 5, 9), AutoDownloaderPayload.episodes("1, 3-5, 9, 4"))
        fails { AutoDownloaderPayload.episodes("6-2") }
        fails { AutoDownloaderPayload.episodes("-1") }
        fails { AutoDownloaderPayload.episodes("1,garbage") }
        fails { AutoDownloaderPayload.episodes("1-10000") }
    }
    @Test fun sizeValidationUsesBinaryBackendUnitsAndRejectsInvertedRanges() {
        AutoDownloaderPayload.validateSizeRange("1024 MB", "1 GiB")
        AutoDownloaderPayload.validateSizeRange("", "")
        fails { AutoDownloaderPayload.validateSizeRange("2 GB", "500 MB") }
        fails { AutoDownloaderPayload.validateSizeRange("-5MB", "") }
        fails { AutoDownloaderPayload.validateSizeRange("NaN", "") }
    }
    @Test fun settingChangePreservesDeprecatedAndFutureFields() {
        val source = JSONObject("""{"interval":20,"provider":"source","enabled":true,"downloadAutomatically":false,"enableEnhancedQueries":true,"future":{"x":1}}""")
        val saved = AutoDownloaderPayload.settings(source, "interval", 30)
        assertEquals(30, saved.getInt("interval"))
        assertTrue(saved.getBoolean("enableEnhancedQueries"))
        assertEquals(1, saved.getJSONObject("future").getInt("x"))
        assertEquals(20, source.getInt("interval"))
        fails { AutoDownloaderPayload.settings(source, "interval", 14) }
    }
    @Test fun simulationOnlyUsesSelectedRuleIdsAndQueueDownloadUsesBackendResolution() {
        val body = AutoDownloaderPayload.simulationBody(listOf(5, 5, 8))
        assertEquals("[5,8]", body.getJSONArray("ruleIds").toString())
        assertEquals("[]", AutoDownloaderPayload.simulationBody(emptyList()).getJSONArray("ruleIds").toString())
        fails { AutoDownloaderPayload.simulationBody(listOf(0)) }
        val item = AutoQueueItem(12, 9, 5114, 3, "Release", false, false, "", 20, "")
        val download = AutoDownloaderPayload.queueDownload(item)
        assertEquals(12, download.getInt("queuedItemId"))
        assertEquals(9, download.getInt("ruleId"))
        assertEquals("", download.getString("magnetUrl"))
    }
    @Test fun profilesValidateThresholdsAndConditionActions() {
        fails { AutoProfile(name = "").payload() }
        fails { AutoProfile(name = "Valid", delayMinutes = -1).payload() }
        fails { AutoProfile(name = "Valid", conditions = listOf(AutoCondition("one", "term", action = "execute"))).payload() }
        fails { AutoProfile(name = "Valid", conditions = listOf(AutoCondition("one", ""))).payload() }
        AutoProfile(name = "Valid", minimumScore = -5, conditions = listOf(AutoCondition("one", "bad", score = -10))).payload()
    }
}
