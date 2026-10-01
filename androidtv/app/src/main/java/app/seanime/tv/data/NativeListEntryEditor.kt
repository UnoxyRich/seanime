package app.seanime.tv.data

import java.math.BigDecimal
import org.json.JSONObject

internal val nativeListStatuses = listOf("CURRENT", "PLANNING", "COMPLETED", "PAUSED", "DROPPED", "REPEATING")

/** Nullable components are retained as returned by the raw collection, never inferred from detail date strings. */
internal data class NativeListDate(val year: Int? = null, val month: Int? = null, val day: Int? = null) {
    val empty: Boolean get() = year == null && month == null && day == null
    val complete: Boolean get() = year != null && month != null && day != null
    val label: String get() = if (empty) "Not set" else
        "${year?.toString()?.padStart(4, '0') ?: "????"}-${month?.toString()?.padStart(2, '0') ?: "??"}-${day?.toString()?.padStart(2, '0') ?: "??"}"
    fun payload(): JSONObject = JSONObject().put("year", year ?: JSONObject.NULL)
        .put("month", month ?: JSONObject.NULL).put("day", day ?: JSONObject.NULL)
}

internal fun validateNativeListDate(date: NativeListDate, allowPartial: Boolean) {
    require(allowPartial || date.complete) { "AniList date changes need a complete year, month and day. Keep the existing date unchanged or choose a full date." }
    require(date.year == null || date.year in 1..9999) { "Year must be between 1 and 9999" }
    require(date.month == null || date.month in 1..12) { "Month must be between 1 and 12" }
    require(date.day == null || date.day in 1..31) { "Day must be between 1 and 31" }
    if (date.month != null && date.day != null) {
        // A missing year permits February 29; once a year is chosen its leap-year rule applies.
        // Keep this proleptic Gregorian calculation compatible with Android API 23.
        val leapYear = date.year == null || (date.year % 4 == 0 && (date.year % 100 != 0 || date.year % 400 == 0))
        val monthLength = when (date.month) { 2 -> if (leapYear) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
        require(date.day <= monthLength) { "Choose a valid calendar date" }
    }
}

private fun readNativeListDate(entry: JSONObject?, field: String): NativeListDate {
    if (entry == null || !entry.has(field) || entry.isNull(field)) return NativeListDate()
    val date = entry.optJSONObject(field) ?: error("The server returned an invalid $field date")
    fun component(name: String): Int? = if (!date.has(name) || date.isNull(name)) null else
        date.opt(name)?.toString()?.toIntOrNull() ?: error("The server returned an invalid $field $name")
    return NativeListDate(component("year"), component("month"), component("day"))
}

internal data class NativeListEntrySnapshot(val media: MediaCard, val listData: JSONObject?, val offline: Boolean,
    val simulated: Boolean = false, val startedAt: NativeListDate = NativeListDate(), val completedAt: NativeListDate = NativeListDate()) {
    val allowsPartialDates: Boolean get() = offline || simulated
    val exists: Boolean get() = listData?.optString("status") in nativeListStatuses
    val status: String get() = listData?.optString("status")?.takeIf { it in nativeListStatuses } ?: "PLANNING"
    val progress: Int get() = listData?.optInt("progress") ?: 0
    val scoreText: String get() = listData?.opt("score")?.takeUnless { it == JSONObject.NULL }?.toString()
        ?.toBigDecimalOrNull()?.movePointLeft(1)?.stripTrailingZeros()?.toPlainString().orEmpty()
}

/** Only explicitly changed fields are sent; repeat and other list metadata are never copied back. */
internal data class NativeListEntryDraft(val status: String, val progress: String, val score: String,
    val startedAt: NativeListDate = NativeListDate(), val completedAt: NativeListDate = NativeListDate()) {
    companion object {
        fun from(snapshot: NativeListEntrySnapshot) = NativeListEntryDraft(snapshot.status, snapshot.progress.toString(), snapshot.scoreText, snapshot.startedAt, snapshot.completedAt)
    }
}

internal fun nativeScoreRaw(value: String): Int {
    if (value.isBlank()) return 0 // Explicitly clearing the rating removes it.
    val decimal = value.trim().toBigDecimalOrNull() ?: error("Enter a rating from 0 to 10, with at most one decimal place")
    require(decimal >= BigDecimal.ZERO && decimal <= BigDecimal.TEN && decimal.stripTrailingZeros().scale() <= 1) {
        "Enter a rating from 0 to 10, with at most one decimal place"
    }
    return decimal.movePointRight(1).intValueExact()
}

internal fun nativeListEntryPayload(snapshot: NativeListEntrySnapshot, draft: NativeListEntryDraft): JSONObject {
    require(snapshot.media.id > 0)
    require(draft.status in nativeListStatuses) { "Choose a list status" }
    val initial = NativeListEntryDraft.from(snapshot)
    return jsonObject("mediaId" to snapshot.media.id, "type" to if (snapshot.media.isManga) "manga" else "anime").apply {
        if (!snapshot.exists || draft.status != initial.status) put("status", draft.status)
        if (draft.progress != initial.progress) {
            val progress = draft.progress.trim().toIntOrNull()?.takeIf { it >= 0 } ?: error("Enter a nonnegative whole progress number")
            snapshot.media.totalEpisodes?.let { require(progress <= it) { "Progress cannot exceed $it" } }
            put("progress", progress)
        }
        if (draft.score != initial.score) put("score", nativeScoreRaw(draft.score))
        if (draft.startedAt != initial.startedAt) {
            validateNativeListDate(draft.startedAt, snapshot.allowsPartialDates)
            put("startedAt", draft.startedAt.payload())
        }
        if (draft.completedAt != initial.completedAt) {
            validateNativeListDate(draft.completedAt, snapshot.allowsPartialDates)
            put("completedAt", draft.completedAt.payload())
        }
    }
}

internal suspend fun loadNativeListEntry(repo: SeanimeRepository, mediaId: Long, manga: Boolean, includeDates: Boolean = true): NativeListEntrySnapshot {
    val status = repo.status()
    val details = if (manga) repo.mangaDetails(mediaId) else repo.animeDetails(mediaId)
    check(details.media.id == mediaId) { "The server returned another title's list entry" }
    val snapshot = NativeListEntrySnapshot(details.media, details.raw.optJSONObject("listData"), status.offline,
        simulated = status.raw.optJSONObject("user")?.opt("isSimulated") == true)
    if (!includeDates) return snapshot
    val raw = repo.request("GET", if (manga) "/api/v1/manga/anilist/collection/raw" else "/api/v1/anilist/collection/raw") as? JSONObject
        ?: error("Couldn't read the current list dates")
    check(raw.has("MediaListCollection") || raw.has("lists")) { "Couldn't read the current list dates" }
    val collection = raw.optJSONObject("MediaListCollection") ?: raw
    val entry = collection.objects("lists").flatMap { it.objects("entries") }.firstOrNull {
        (it.optJSONObject("media")?.optLong("id") ?: it.optLong("mediaId")) == mediaId
    }
    check(entry != null || !snapshot.exists) { "Couldn't find this title's current list dates. Reload your list and try again." }
    return snapshot.copy(listData = entry ?: snapshot.listData, startedAt = readNativeListDate(entry, "startedAt"), completedAt = readNativeListDate(entry, "completedAt"))
}

internal suspend fun saveNativeListEntry(repo: SeanimeRepository, snapshot: NativeListEntrySnapshot, draft: NativeListEntryDraft): Boolean {
    val payload = nativeListEntryPayload(snapshot, draft)
    if (payload.length() == 2) return false
    if (payload.has("startedAt") || payload.has("completedAt")) {
        // A mode change while the dialog is open must not send a local-only clear to AniList.
        val current = repo.status()
        nativeListEntryPayload(snapshot.copy(offline = current.offline,
            simulated = current.raw.optJSONObject("user")?.opt("isSimulated") == true), draft)
    }
    check(repo.request("POST", "/api/v1/anilist/list-entry", payload) == true) { "The list entry could not be saved" }
    return true
}

internal suspend fun removeNativeListEntry(repo: SeanimeRepository, mediaId: Long, manga: Boolean) {
    val current = loadNativeListEntry(repo, mediaId, manga, includeDates = false)
    check(!current.offline) { "Go online before removing a list entry" }
    check(current.exists) { "This title is no longer in your list" }
    check(repo.deleteListEntry(mediaId, manga) == true) { "The list entry could not be removed" }
}
