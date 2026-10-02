package app.seanime.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsPreservationObservationTest {
    @Test fun outputKeepsOnlyFixedSchemaAndNeverPrivateValuesOrUnknownKeys() {
        val expected = settings().put("private-root-key-sentinel", "private-root-value-sentinel")
        expected.getJSONObject("library").put("private-library-key-sentinel", "private-library-value-sentinel")
            .put("libraryPath", "/private/path-sentinel").put("libraryPaths", JSONArray().put("/private/extra-path-sentinel"))
        expected.put("torrent", JSONObject().put("qbittorrentPassword", "private-password-sentinel"))
        val actual = JSONObject(expected.toString()).put("private-root-key-sentinel", "private-replacement-sentinel")
        val evidence = SettingsPreservationObservation(100)
        evidence.record("after-number", expected, actual, 101)
        val encoded = evidence.toJson()
        listOf("sentinel", "qbittorrentPassword", "/private/").forEach { assertFalse(encoded.contains(it)) }

        val root = JSONObject(encoded)
        assertEquals(setOf("schemaVersion", "scenario", "startedAtMs", "observations"), keys(root))
        assertEquals(1, root.getInt("schemaVersion"))
        assertEquals("native-settings-preservation", root.getString("scenario"))
        val observation = root.getJSONArray("observations").getJSONObject(0)
        assertEquals(setOf("phase", "observedAtMs", "wholeConfigEqual", "auditTimesEqual", "sectionsEqual", "fields"), keys(observation))
        assertFalse(observation.getBoolean("wholeConfigEqual"))
        assertEquals(setOf("id", "library", "mediaPlayer", "torrent", "manga", "anilist", "listSync",
            "autoDownloader", "discord", "notifications", "nakama"), keys(observation.getJSONObject("sectionsEqual")))
        val fields = observation.getJSONObject("fields")
        assertEquals(setOf("hideAudienceScore", "scannerMatchingThreshold", "scannerMatchingAlgorithm", "libraryPath",
            "libraryPaths", "richPresenceUseMediaTitleStatus", "richPresenceShowAniListMediaButton"), keys(fields))
        keys(fields).forEach { assertEquals(setOf("equal", "expectedType", "actualType"), keys(fields.getJSONObject(it))) }
    }

    @Test fun exactFractionsAndEquivalentNumericRepresentationsCompareEqually() {
        listOf(0.625, 0.375).forEach { value ->
            val expected = settings(value)
            val actual = JSONObject(expected.toString()).put("id", 1.0)
            actual.getJSONObject("library").put("scannerMatchingThreshold", java.math.BigDecimal("${value}000"))
            val observation = observe(expected, actual)
            assertTrue(observation.getBoolean("wholeConfigEqual"))
            assertTrue(field(observation, "scannerMatchingThreshold").getBoolean("equal"))
            assertEquals("number", field(observation, "scannerMatchingThreshold").getString("actualType"))
        }
    }

    @Test fun numberMismatchIsDistinguishedFromUnrelatedDiscordMutation() {
        val expected = settings(0.625)
        val changedNumber = settings(0.375)
        val numberObservation = observe(expected, changedNumber)
        assertFalse(numberObservation.getBoolean("wholeConfigEqual"))
        assertFalse(numberObservation.getJSONObject("sectionsEqual").getBoolean("library"))
        assertFalse(field(numberObservation, "scannerMatchingThreshold").getBoolean("equal"))
        assertTrue(field(numberObservation, "hideAudienceScore").getBoolean("equal"))
        assertTrue(numberObservation.getJSONObject("sectionsEqual").getBoolean("discord"))

        val changedDiscord = JSONObject(expected.toString())
        changedDiscord.getJSONObject("discord").put("richPresenceUseMediaTitleStatus", false)
        val discordObservation = observe(expected, changedDiscord)
        assertFalse(discordObservation.getBoolean("wholeConfigEqual"))
        assertTrue(field(discordObservation, "scannerMatchingThreshold").getBoolean("equal"))
        assertTrue(discordObservation.getJSONObject("sectionsEqual").getBoolean("library"))
        assertFalse(discordObservation.getJSONObject("sectionsEqual").getBoolean("discord"))
        assertFalse(field(discordObservation, "richPresenceUseMediaTitleStatus").getBoolean("equal"))
    }

    @Test fun missingNullAndAllJsonTypesRemainDistinguishableWithoutTheirValues() {
        val variants = listOf("null" to JSONObject.NULL, "boolean" to true, "number" to 0.625,
            "string" to "private-value-sentinel", "object" to JSONObject().put("private-key-sentinel", "private-value-sentinel"),
            "array" to JSONArray().put("private-value-sentinel"))
        variants.forEach { (expectedType, value) ->
            val expected = settings().apply { getJSONObject("library").remove("scannerMatchingThreshold") }
            val actual = JSONObject(expected.toString())
            actual.getJSONObject("library").put("scannerMatchingThreshold", value)
            val observation = observe(expected, actual)
            val detail = field(observation, "scannerMatchingThreshold")
            assertFalse(detail.getBoolean("equal"))
            assertEquals("missing", detail.getString("expectedType"))
            assertEquals(expectedType, detail.getString("actualType"))
            assertFalse(observation.toString().contains("sentinel"))
        }
        val missing = JSONObject()
        assertTrue(field(observe(missing, missing), "scannerMatchingThreshold").getBoolean("equal"))
    }

    @Test fun rejectedWriteTimestampChangeIsVisibleEvenWhenConfigurationMatches() {
        val expected = settings().put("createdAt", "private-old-time-sentinel").put("updatedAt", "private-old-time-sentinel")
        val actual = JSONObject(expected.toString()).put("updatedAt", "private-new-time-sentinel")
        val observation = observe(expected, actual, "rejected-write")
        assertTrue(observation.getBoolean("wholeConfigEqual"))
        assertFalse(observation.getBoolean("auditTimesEqual"))
        assertFalse(observation.toString().contains("sentinel"))
        actual.remove("updatedAt")
        assertFalse(observe(expected, actual, "rejected-write").getBoolean("auditTimesEqual"))
    }

    @Test fun failureAndRollbackRemainSeparateOrderedObservationsWithoutHoldingInputs() {
        val evidence = SettingsPreservationObservation(100)
        val expected = settings(0.625)
        val actual = settings(0.375)
        evidence.record("after-number", expected, actual, 101)
        actual.getJSONObject("library").put("scannerMatchingThreshold", 0.625)
        assertThrows(IllegalArgumentException::class.java) { evidence.record("restored", expected, actual, 100) }
        evidence.record("restored", expected, actual, 102)
        val observations = JSONObject(evidence.toJson()).getJSONArray("observations")
        assertEquals(2, observations.length())
        assertEquals("after-number", observations.getJSONObject(0).getString("phase"))
        assertFalse(observations.getJSONObject(0).getBoolean("wholeConfigEqual"))
        assertEquals("restored", observations.getJSONObject(1).getString("phase"))
        assertTrue(observations.getJSONObject(1).getBoolean("wholeConfigEqual"))
        assertThrows(IllegalArgumentException::class.java) { evidence.record("after-choice", expected, actual, 103) }
        assertThrows(IllegalArgumentException::class.java) { evidence.record("restored", expected, actual, 103) }
        assertThrows(IllegalArgumentException::class.java) { evidence.record("private-phase-sentinel", expected, actual, 103) }
        assertFalse(evidence.toJson().contains("sentinel"))
    }

    @Test fun completeSequenceHasSixObservationsAndRejectsEarlierCaptureTime() {
        val evidence = SettingsPreservationObservation(100)
        val value = settings()
        assertThrows(IllegalArgumentException::class.java) { evidence.record("after-boolean", value, value, 99) }
        listOf("after-boolean", "after-number", "after-choice", "rejected-write", "fresh-client", "restored")
            .forEachIndexed { index, phase -> evidence.record(phase, value, value, 100 + index.toLong()) }
        assertEquals(6, JSONObject(evidence.toJson()).getJSONArray("observations").length())
    }

    private fun settings(threshold: Number = 0.625): JSONObject = JSONObject().put("id", 1)
        .put("library", JSONObject().put("scannerMatchingThreshold", threshold).put("scannerMatchingAlgorithm", "jaro")
            .put("libraryPath", "").put("libraryPaths", JSONArray()))
        .put("anilist", JSONObject().put("hideAudienceScore", false))
        .put("discord", JSONObject().put("richPresenceUseMediaTitleStatus", true).put("richPresenceShowAniListMediaButton", false))

    private fun observe(expected: JSONObject, actual: JSONObject, phase: String = "after-number"): JSONObject {
        val evidence = SettingsPreservationObservation(100)
        evidence.record(phase, expected, actual, 101)
        return JSONObject(evidence.toJson()).getJSONArray("observations").getJSONObject(0)
    }

    private fun field(observation: JSONObject, name: String) = observation.getJSONObject("fields").getJSONObject(name)
    private fun keys(value: JSONObject) = value.keys().asSequence().toSet()
}
