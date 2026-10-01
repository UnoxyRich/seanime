package app.seanime.tv.ui

import app.seanime.tv.data.ApiException
import app.seanime.tv.data.SeanimeJson
import app.seanime.tv.data.SeanimeRepository
import app.seanime.tv.data.MAX_NATIVE_MEDIA_ID
import app.seanime.tv.data.optMediaId
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Lossless UI models for internal/library/anime/autodownloader_types.go. */
internal data class AutoRule(
    val id: Int = 0,
    val enabled: Boolean = true,
    val mediaId: Long = 0,
    val destination: String = "",
    val profileId: Int? = null,
    val releaseGroups: List<String> = emptyList(),
    val resolutions: List<String> = emptyList(),
    val episodes: List<Int> = emptyList(),
    val episodeType: String = "recent",
    val title: String = "",
    val titleMatch: String = "likely",
    val includeTerms: List<String> = emptyList(),
    val excludeTerms: List<String> = emptyList(),
    val minSeeders: Int = 0,
    val minSize: String = "",
    val maxSize: String = "",
    val episodeOffset: Int = 0,
    val providers: List<String> = emptyList(),
    val raw: JSONObject = JSONObject(),
) {
    fun validate() {
        require(mediaId in 1L..MAX_NATIVE_MEDIA_ID) { "Choose an anime with a valid media identity" }
        require(title.isNotBlank()) { "Enter the title used to match torrent releases" }
        require(destination.startsWith('/') && !destination.contains('\u0000')) { "Choose an absolute destination path from your configured library folders" }
        require(episodeType in setOf("recent", "selected")) { "Choose recent or selected episodes" }
        require(titleMatch in setOf("contains", "likely")) { "Choose a title comparison method" }
        require(episodes.all { it > 0 }) { "Episode numbers must be positive" }
        require(episodeType != "selected" || episodes.isNotEmpty()) { "Choose at least one episode" }
        require(profileId == null || profileId > 0) { "Choose a valid profile" }
        require(minSeeders >= 0) { "Minimum seeders cannot be negative" }
        AutoDownloaderPayload.validateSizeRange(minSize, maxSize)
    }
    fun payload(base: JSONObject = raw): JSONObject {
        validate()
        return JSONObject(base.toString()).put("dbId", id).put("enabled", enabled).put("mediaId", mediaId)
            .put("destination", destination.trim()).put("profileId", profileId ?: JSONObject.NULL)
            .put("releaseGroups", JSONArray(releaseGroups)).put("resolutions", JSONArray(resolutions))
            .put("episodeNumbers", JSONArray(episodes.distinct())).put("episodeType", episodeType)
            .put("comparisonTitle", title.trim()).put("titleComparisonType", titleMatch)
            .put("additionalTerms", JSONArray(includeTerms)).put("excludeTerms", JSONArray(excludeTerms))
            .put("minSeeders", minSeeders).put("minSize", minSize.trim()).put("maxSize", maxSize.trim())
            .put("customEpisodeNumberAbsoluteOffset", episodeOffset).put("providers", JSONArray(providers))
    }
    companion object {
        fun from(raw: JSONObject) = AutoRule(raw.optInt("dbId"), raw.optBoolean("enabled"), raw.optMediaId("mediaId"), raw.text("destination"),
            raw.optInt("profileId").takeIf { it > 0 }, raw.autoStrings("releaseGroups"), raw.autoStrings("resolutions"),
            raw.optJSONArray("episodeNumbers")?.let { array -> (0 until array.length()).map { array.optInt(it) } } ?: emptyList(),
            raw.text("episodeType", "recent"), raw.text("comparisonTitle"), raw.text("titleComparisonType", "likely"),
            raw.autoStrings("additionalTerms"), raw.autoStrings("excludeTerms"), raw.optInt("minSeeders"), raw.text("minSize"), raw.text("maxSize"),
            raw.optInt("customEpisodeNumberAbsoluteOffset"), raw.autoStrings("providers"), raw)
    }
}

internal data class AutoCondition(val id: String, val term: String = "", val regex: Boolean = false, val action: String = "score", val score: Int = 0, val raw: JSONObject = JSONObject()) {
    fun payload(): JSONObject {
        require(id.isNotBlank()) { "Condition ID is required" }
        require(term.isNotBlank()) { "A profile condition needs a search term" }
        require(action in setOf("score", "block", "require")) { "Unknown condition action" }
        return JSONObject(raw.toString()).put("id", id).put("term", term).put("isRegex", regex).put("action", action).put("score", score)
    }
    companion object { fun from(raw: JSONObject) = AutoCondition(raw.text("id"), raw.text("term"), raw.optBoolean("isRegex"), raw.text("action", "score"), raw.optInt("score"), raw) }
}

internal data class AutoProfile(
    val id: Int = 0,
    val name: String = "",
    val global: Boolean = false,
    val releaseGroups: List<String> = emptyList(),
    val resolutions: List<String> = emptyList(),
    val conditions: List<AutoCondition> = emptyList(),
    val minimumScore: Int = 0,
    val minSeeders: Int = 0,
    val minSize: String = "",
    val maxSize: String = "",
    val delayMinutes: Int = 0,
    val skipDelayScore: Int = 0,
    val providers: List<String> = emptyList(),
    val raw: JSONObject = JSONObject(),
) {
    fun payload(base: JSONObject = raw): JSONObject {
        require(name.isNotBlank()) { "Enter a profile name" }
        require(minSeeders >= 0) { "Minimum seeders cannot be negative" }
        require(delayMinutes >= 0) { "Delay cannot be negative" }
        require(conditions.map { it.id }.distinct().size == conditions.size) { "Condition IDs must be unique" }
        AutoDownloaderPayload.validateSizeRange(minSize, maxSize)
        return JSONObject(base.toString()).put("dbId", id).put("name", name.trim()).put("global", global)
            .put("releaseGroups", JSONArray(releaseGroups)).put("resolutions", JSONArray(resolutions))
            .put("conditions", JSONArray(conditions.map { it.payload() })).put("minimumScore", minimumScore)
            .put("minSeeders", minSeeders).put("minSize", minSize.trim()).put("maxSize", maxSize.trim())
            .put("delayMinutes", delayMinutes).put("skipDelayScore", skipDelayScore).put("providers", JSONArray(providers))
    }
    companion object {
        fun from(raw: JSONObject) = AutoProfile(raw.optInt("dbId"), raw.text("name"), raw.optBoolean("global"), raw.autoStrings("releaseGroups"),
            raw.autoStrings("resolutions"), raw.optJSONArray("conditions").uiObjects().map(AutoCondition::from), raw.optInt("minimumScore"),
            raw.optInt("minSeeders"), raw.text("minSize"), raw.text("maxSize"), raw.optInt("delayMinutes"), raw.optInt("skipDelayScore"), raw.autoStrings("providers"), raw)
    }
}

internal data class AutoQueueItem(val id: Int, val ruleId: Int, val mediaId: Long, val episode: Int, val name: String, val downloaded: Boolean, val delayed: Boolean, val delayUntil: String, val score: Int, val magnet: String) {
    companion object { fun from(raw: JSONObject) = AutoQueueItem(raw.optInt("id"), raw.optInt("ruleId"), raw.optMediaId("mediaId"), raw.optInt("episode"), raw.text("torrentName"), raw.optBoolean("downloaded"), raw.optBoolean("isDelayed"), raw.text("delayUntil"), raw.optInt("score"), raw.text("magnet")) }
}

internal data class AutoBatchEntry(val mediaId: Long, val displayTitle: String, val releaseTitle: String, val destination: String)
internal enum class AutoBatchState { SAVED, EXISTS, FAILED, UNCERTAIN, CONFLICT }
internal data class AutoBatchResult(val state: AutoBatchState, val message: String, val ruleId: Int? = null) {
    val complete: Boolean get() = state == AutoBatchState.SAVED || state == AutoBatchState.EXISTS
}
internal data class AutoCleanupResult(val removed: Set<Int>, val errors: Map<Int, String>)

internal fun autoBatchRule(template: AutoRule, entry: AutoBatchEntry): AutoRule = template.copy(id = 0, mediaId = entry.mediaId,
    title = entry.releaseTitle, destination = entry.destination, episodeType = "recent", episodes = emptyList(), raw = JSONObject())

internal fun sameAutoRuleConfiguration(first: AutoRule, second: AutoRule): Boolean {
    val ignoredRaw = JSONObject()
    fun normalized(rule: AutoRule) = rule.copy(id = 0, destination = rule.destination.trim(), title = rule.title.trim(),
        minSize = rule.minSize.trim(), maxSize = rule.maxSize.trim(), episodes = rule.episodes.distinct(), raw = ignoredRaw)
    return normalized(first) == normalized(second)
}

internal suspend fun freshAutoRules(repo: SeanimeRepository): List<AutoRule> {
    val response = repo.request("GET", "/api/v1/auto-downloader/rules") as? JSONArray ?: error("The server did not return the current rules")
    val rules = response.uiObjects().map(AutoRule::from)
    check(rules.size == response.length() && rules.all { it.id > 0 && it.mediaId > 0 } && rules.map { it.id }.distinct().size == rules.size) { "The current rule list contains invalid identities" }
    return rules
}

/** Reconcile before every POST. A lost response never authorizes a second POST solely because a read is still empty. */
internal suspend fun createNativeAutoBatch(repo: SeanimeRepository, desired: List<AutoRule>, previous: Map<Long, AutoBatchResult> = emptyMap()): Map<Long, AutoBatchResult> {
    require(desired.isNotEmpty() && desired.map { it.mediaId }.distinct().size == desired.size) { "Choose each anime once" }
    desired.forEach(AutoRule::validate)
    val result = previous.toMutableMap()
    fun reconcile(rule: AutoRule, current: List<AutoRule>): AutoBatchResult? {
        val matches = current.filter { it.mediaId == rule.mediaId }
        return when {
            matches.size == 1 && sameAutoRuleConfiguration(matches.single(), rule) -> AutoBatchResult(AutoBatchState.EXISTS, "Matching rule already saved", matches.single().id)
            matches.isNotEmpty() -> AutoBatchResult(AutoBatchState.CONFLICT, "This anime already has a different rule. Review it in Rules.")
            else -> null
        }
    }
    for (rule in desired) {
        if (previous[rule.mediaId]?.complete == true) continue
        try {
            val current = freshAutoRules(repo)
            val found = reconcile(rule, current)
            if (found != null) { result[rule.mediaId] = found; continue }
            if (previous[rule.mediaId]?.state == AutoBatchState.UNCERTAIN) {
                result[rule.mediaId] = AutoBatchResult(AutoBatchState.UNCERTAIN, "The earlier request is still unconfirmed. Refresh results or inspect Rules before creating another rule.")
                continue
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { result[rule.mediaId] = previous[rule.mediaId] ?: AutoBatchResult(AutoBatchState.FAILED, failure.message ?: "Couldn't read current rules"); continue }
        var postReturned = false
        try {
            val rawResponse = repo.request("POST", "/api/v1/auto-downloader/rule", AutoDownloaderPayload.ruleBody(rule))
            postReturned = true
            val response = rawResponse as? JSONObject
                ?: error("The server did not return the created rule")
            val created = AutoRule.from(response)
            check(created.id > 0 && sameAutoRuleConfiguration(created, rule)) { "The server did not confirm the requested rule" }
            val saved = freshAutoRules(repo).singleOrNull { it.id == created.id }
            check(saved != null && sameAutoRuleConfiguration(saved, rule)) { "The created rule is not confirmed in the current rule list" }
            result[rule.mediaId] = AutoBatchResult(AutoBatchState.SAVED, "Created and verified", created.id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val found = try { reconcile(rule, freshAutoRules(repo)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            result[rule.mediaId] = found ?: AutoBatchResult(
                if (!postReturned && failure is ApiException && failure.statusCode in setOf(400, 401, 403, 404, 405, 409, 422)) AutoBatchState.FAILED else AutoBatchState.UNCERTAIN,
                failure.message ?: "The create request could not be confirmed")
        }
    }
    return result
}

internal suspend fun finishedAutoRuleCandidates(repo: SeanimeRepository): List<AutoRule> {
    val rules = freshAutoRules(repo)
    val collection = repo.request("POST", "/api/v1/anilist/collection/raw") as? JSONObject ?: error("Couldn't refresh the anime collection")
    check(collection.has("MediaListCollection") || collection.has("lists")) { "The server did not return the refreshed anime collection" }
    val finished = SeanimeJson.collection(collection).filter { it.raw.text("status") == "FINISHED" }.map { it.id.toLong() }.toSet()
    return rules.filter { it.mediaId in finished }
}

/** Delete only the confirmed finished-title rules, with fresh identity/configuration checks and readback per ID. */
internal suspend fun removeFinishedAutoRules(repo: SeanimeRepository, confirmed: List<AutoRule>, alreadyRemoved: Set<Int> = emptySet()): AutoCleanupResult {
    require(confirmed.isNotEmpty() && confirmed.all { it.id > 0 } && confirmed.map { it.id }.distinct().size == confirmed.size)
    val candidates = finishedAutoRuleCandidates(repo).associateBy { it.id }
    val removed = alreadyRemoved.toMutableSet()
    val errors = mutableMapOf<Int, String>()
    for (rule in confirmed) {
        if (rule.id in removed) continue
        try {
            val current = freshAutoRules(repo).singleOrNull { it.id == rule.id }
            if (current == null) { removed += rule.id; continue }
            check(candidates[rule.id]?.mediaId == rule.mediaId && sameAutoRuleConfiguration(current, rule)) { "Rule or airing status changed. Close this preview and refresh the candidates." }
            check(repo.request("DELETE", "/api/v1/auto-downloader/rule/${rule.id}") == true) { "The server did not acknowledge removal" }
            check(freshAutoRules(repo).none { it.id == rule.id }) { "The rule is still present after removal" }
            removed += rule.id
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { errors[rule.id] = failure.message ?: "Couldn't remove this rule" }
    }
    return AutoCleanupResult(removed, errors)
}
internal data class AutoSimulationItem(val ruleId: Int, val episode: Int, val name: String, val score: Int, val provider: String, val delayed: Boolean) {
    companion object { fun from(raw: JSONObject) = AutoSimulationItem(raw.optInt("ruleId"), raw.optInt("episode"), raw.text("torrentName"), raw.optInt("score"), raw.text("extensionId"), raw.optBoolean("isDelayed")) }
}

internal fun JSONObject.autoStrings(name: String): List<String> = optJSONArray(name)?.let { array ->
    (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() && value != "null" } }
} ?: emptyList()

internal object AutoDownloaderPayload {
    fun ruleBody(rule: AutoRule, base: JSONObject = rule.raw): JSONObject = JSONObject().put("rule", rule.payload(base))
    fun profileBody(profile: AutoProfile, base: JSONObject = profile.raw): JSONObject = profile.payload(base)
    fun simulationBody(ids: List<Int>): JSONObject {
        require(ids.all { it > 0 }) { "Simulation rule IDs must be positive" }
        return JSONObject().put("ruleIds", JSONArray(ids.distinct()))
    }
    fun queueDownload(item: AutoQueueItem): JSONObject {
        require(item.id > 0 && item.ruleId > 0) { "Queue item and rule IDs are required" }
        return JSONObject().put("queuedItemId", item.id).put("ruleId", item.ruleId).put("magnetUrl", item.magnet)
    }
    fun settings(current: JSONObject, field: String, value: Any): JSONObject {
        val copy = JSONObject(current.toString()).put(field, value)
        require(copy.optInt("interval", 20) >= 15) { "The check interval must be at least 15 minutes" }
        return copy
    }
    fun terms(text: String): List<String> = text.split(',', '\n').map(String::trim).filter(String::isNotEmpty).distinct()
    fun episodes(text: String): List<Int> {
        if (text.isBlank()) return emptyList()
        val result = linkedSetOf<Int>()
        text.split(',', '\n').forEach { item ->
            val token = item.trim()
            if (token.isBlank()) return@forEach
            if ('-' in token) {
                val range = token.split('-').map(String::trim)
                require(range.size == 2) { "Use episode numbers or ranges such as 1, 3–6 (with a hyphen)" }
                val first = range[0].toIntOrNull() ?: error("Invalid episode number")
                val last = range[1].toIntOrNull() ?: error("Invalid episode number")
                require(first > 0 && last >= first && last - first < 1000) { "Use positive episode ranges of at most 1,000 episodes" }
                result.addAll(first..last)
            } else result.add(token.toIntOrNull()?.takeIf { it > 0 } ?: error("Episode numbers must be positive integers"))
            require(result.size <= 1000) { "Select at most 1,000 episodes at a time" }
        }
        return result.toList()
    }
    private fun sizeBytes(value: String): Double? {
        if (value.isBlank()) return null
        val match = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*(B|KIB|KB|MIB|MB|GIB|GB|TIB|TB)?$", RegexOption.IGNORE_CASE).matchEntire(value.trim())
            ?: error("Use a size such as 500 MB, 1.5 GiB, or leave it empty")
        val amount = match.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("Size is too large")
        val power = when (match.groupValues[2].uppercase().firstOrNull()) { 'K' -> 1; 'M' -> 2; 'G' -> 3; 'T' -> 4; else -> 0 }
        val bytes = amount * Math.pow(1024.0, power.toDouble())
        require(bytes <= Long.MAX_VALUE.toDouble()) { "Size exceeds the backend limit" }
        return bytes
    }
    fun validateSizeRange(min: String, max: String) {
        val low = sizeBytes(min); val high = sizeBytes(max)
        require(low == null || high == null || low <= high) { "Minimum size cannot exceed maximum size" }
    }
}
