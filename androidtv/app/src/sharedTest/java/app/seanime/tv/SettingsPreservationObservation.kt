package app.seanime.tv

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Test-only equality evidence. Input values and input JSON keys are never retained in the output. */
internal class SettingsPreservationObservation(private val startedAtMs: Long) {
    private val observations = JSONArray()
    private val root = JSONObject().put("schemaVersion", 1).put("scenario", "native-settings-preservation")
        .put("startedAtMs", startedAtMs).put("observations", observations)
    private var previousPhase = -1
    private var previousObservedAtMs = startedAtMs

    init { require(startedAtMs > 0) }

    fun record(phase: String, expected: JSONObject, actual: JSONObject, observedAtMs: Long) {
        val phaseIndex = PHASES.indexOf(phase)
        require(phaseIndex > previousPhase && phaseIndex >= 0 && observations.length() < PHASES.size)
        require(observedAtMs >= previousObservedAtMs)
        val sections = JSONObject()
        SECTION_NAMES.forEach { name -> sections.put(name, equal(member(expected, name), member(actual, name))) }
        val fields = JSONObject()
        FIELDS.forEach { (name, section) ->
            val expectedField = member(member(expected, section), name)
            val actualField = member(member(actual, section), name)
            fields.put(name, JSONObject().put("equal", equal(expectedField, actualField))
                .put("expectedType", type(expectedField)).put("actualType", type(actualField)))
        }
        observations.put(JSONObject().put("phase", phase).put("observedAtMs", observedAtMs)
            .put("wholeConfigEqual", equal(configuration(expected), configuration(actual)))
            .put("auditTimesEqual", AUDIT_NAMES.all { equal(member(expected, it), member(actual, it)) })
            .put("sectionsEqual", sections).put("fields", fields))
        previousPhase = phaseIndex
        previousObservedAtMs = observedAtMs
    }

    fun toJson(): String = root.toString(2)

    private fun configuration(value: JSONObject) = JSONObject(value.toString()).apply {
        remove("createdAt"); remove("updatedAt")
    }

    private fun member(value: Any?, name: String): Any? =
        if (value is JSONObject && value.has(name)) value.get(name) else Missing

    private fun equal(expected: Any?, actual: Any?): Boolean =
        if (expected === Missing || actual === Missing) expected === actual else canonical(expected) == canonical(actual)

    private fun type(value: Any?): String = when (value) {
        Missing -> "missing"
        null, JSONObject.NULL -> "null"
        is Boolean -> "boolean"
        is Number -> "number"
        is JSONObject -> "object"
        is JSONArray -> "array"
        else -> "string"
    }

    // Match the integration assertion, including numeric representation normalization.
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted()
            .joinToString(prefix = "{", postfix = "}") { "${JSONObject.quote(it)}:${canonical(value.get(it))}" }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
        is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private object Missing

    private companion object {
        val PHASES = listOf("after-boolean", "after-number", "after-choice", "rejected-write", "fresh-client", "restored")
        val SECTION_NAMES = listOf("id", "library", "mediaPlayer", "torrent", "manga", "anilist", "listSync",
            "autoDownloader", "discord", "notifications", "nakama")
        val AUDIT_NAMES = listOf("createdAt", "updatedAt")
        val FIELDS = linkedMapOf("hideAudienceScore" to "anilist", "scannerMatchingThreshold" to "library",
            "scannerMatchingAlgorithm" to "library", "libraryPath" to "library", "libraryPaths" to "library",
            "richPresenceUseMediaTitleStatus" to "discord", "richPresenceShowAniListMediaButton" to "discord")
    }
}
