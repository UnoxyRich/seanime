package app.seanime.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** Keep list metadata beside the media object; release status is not personal list status. */
internal data class PersonalCollectionEntry(
    val media: MediaCard, val status: String, val listName: String, val listIndex: Int,
    val entryIndex: Int, val listData: JSONObject, val raw: JSONObject,
)
internal data class PersonalCollection(val entries: List<PersonalCollectionEntry> = emptyList(), val raw: JSONObject = JSONObject())
internal data class PersonalCollectionSortContext(
    val library: JSONObject = JSONObject(), val watchHistory: JSONObject = JSONObject(),
    val latestChapters: JSONObject = JSONObject(), val mangaPreferences: JSONObject = JSONObject(),
)
internal enum class PersonalCollectionSort(val label: String) {
    SERVER_ORDER("List order"), TITLE("Title (A–Z)"), TITLE_DESC("Title (Z–A)"),
    SCORE_DESC("Highest score"), SCORE("Lowest score"), AUDIENCE_SCORE_DESC("Highest audience score"), AUDIENCE_SCORE("Lowest audience score"),
    PROGRESS_DESC("Highest progress"), PROGRESS("Lowest progress"), START_DATE_DESC("Started recently"), START_DATE("Oldest start date"),
    END_DATE_DESC("Completed recently"), END_DATE("Oldest completion date"), RELEASE_DATE_DESC("Released recently"), RELEASE_DATE("Oldest release"),
    CREATED_AT_DESC("Date added (newest first)"), UPDATED_AT_DESC("Last updated"),
    AIRDATE_DESC("Aired recently and not up-to-date"), AIRDATE("Aired oldest and not up-to-date"),
    UNWATCHED_EPISODES_DESC("Most unwatched episodes"), UNWATCHED_EPISODES("Least unwatched episodes"),
    LAST_WATCHED_DESC("Most recent watch"), LAST_WATCHED("Least recent watch"),
    UNREAD_CHAPTERS_DESC("Most unread chapters"), UNREAD_CHAPTERS("Least unread chapters");
    val needsLibrary get() = name.startsWith("AIRDATE") || name.startsWith("UNWATCHED")
    val needsHistory get() = name.startsWith("LAST_WATCHED")
    val needsMangaSources get() = name.startsWith("UNREAD")
}
internal fun personalCollectionSorts(manga: Boolean) = PersonalCollectionSort.entries.filter {
    if (manga) !it.needsLibrary && !it.needsHistory else !it.needsMangaSources
}
internal val personalCollectionStatuses = listOf("", "CURRENT", "PLANNING", "PAUSED", "COMPLETED", "DROPPED", "REPEATING", "UNLISTED")
internal fun personalCollectionStatusLabel(status: String, manga: Boolean = false) = when (status) {
    "" -> "All statuses"; "CURRENT" -> if (manga) "Reading" else "Watching"; "PLANNING" -> "Planning"
    "PAUSED" -> "Paused"; "COMPLETED" -> "Completed"; "DROPPED" -> "Dropped"
    "REPEATING" -> if (manga) "Rereading" else "Rewatching"; "UNLISTED" -> "Not on a list"; else -> status
}
internal fun parsePersonalCollection(raw: Any?, manga: Boolean = false): PersonalCollection {
    val path = if (manga) "/api/v1/manga/collection" else "/api/v1/library/collection"
    // A nil personal collection is a valid logged-out response; scalars are not.
    val root = if (raw is JSONArray) JSONObject() else nativeResponseObject(raw, path, "collection") ?: JSONObject()
    val collection = nativeResponseObject(root.opt("MediaListCollection"), path, "collection lists") ?: root
    val groups = nativeResponseObjects(collection.opt("lists"), path, "collection lists")
    val entries = mutableListOf<PersonalCollectionEntry>()
    fun add(value: JSONObject, group: JSONObject, listIndex: Int, index: Int) {
        val listData = value.optJSONObject("listData") ?: value
        val status = listData.stringOrNull("status")?.takeIf { it in personalCollectionStatuses }
            ?: group.stringOrNull("status") ?: group.stringOrNull("type") ?: "UNLISTED"
        val media = nativeResponseMedia(value, path, manga).copy(status = status)
        if (entries.none { it.media.id == media.id }) entries += PersonalCollectionEntry(media, status,
            (group.stringOrNull("name") ?: personalCollectionStatusLabel(status, manga)), listIndex, index, listData, value)
    }
    if (raw is JSONArray) nativeResponseObjects(raw, path, "collection entries").forEachIndexed { index, value -> add(value, JSONObject(), 0, index) }
    else groups.forEachIndexed { listIndex, group ->
        nativeResponseObjects(group.opt("entries"), path, "collection entries").forEachIndexed { index, value -> add(value, group, listIndex, index) }
    }
    nativeResponseObject(root.opt("stream"), path, "stream collection")?.let { stream -> nativeResponseObjects(stream.opt("anime"), path, "stream titles").forEachIndexed { index, media ->
        add(jsonObject("media" to media, "listData" to stream.optJSONObject("listData")?.optJSONObject(media.optMediaId().toString())),
            jsonObject("status" to "CURRENT"), groups.size, index)
    } }
    return PersonalCollection(entries, root)
}
internal fun filterPersonalCollection(collection: PersonalCollection, query: String = "", status: String = "",
    sort: PersonalCollectionSort = PersonalCollectionSort.SERVER_ORDER, context: PersonalCollectionSortContext = PersonalCollectionSortContext()): List<PersonalCollectionEntry> {
    fun normalized(value: String) = value.lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
    val needle = normalized(query)
    val filtered = collection.entries.filter { entry ->
        (status.isEmpty() || entry.status == status) && (needle.isEmpty() || run {
            val media = entry.media.raw
            val title = media.optJSONObject("title")
            val aliases = listOf(entry.media.title) + listOf("userPreferred", "english", "romaji", "native").map { title?.optString(it).orEmpty() } +
                (media.optJSONArray("synonyms")?.let { array -> (0 until array.length()).map { array.optString(it) } } ?: emptyList())
            aliases.any { normalized(it).contains(needle) }
        })
    }
    if (sort == PersonalCollectionSort.SERVER_ORDER) return filtered
    val descending = sort.name.endsWith("_DESC")
    val libraryEntries = if (sort.name.startsWith("UNWATCHED")) parsePersonalCollection(context.library).entries.associate { it.media.id to it.raw } else emptyMap()
    val values = filtered.associate { it.media.id to collectionSortValue(it, sort, collection, context, libraryEntries) }
    return filtered.sortedWith { a, b ->
        val av = values[a.media.id]
        val bv = values[b.media.id]
        when {
            av == null && bv == null -> 0
            av == null -> 1
            bv == null -> -1
            else -> av.compareTo(bv) * if (descending) -1 else 1
        }
    }
}
private fun JSONObject.number(key: String): Double? = (opt(key) as? Number)?.toDouble()?.takeIf(Double::isFinite)
/** Partial dates sort by their supplied precision without rewriting the backend date. */
private fun collectionDate(value: Any?): String? = when (value) {
    is JSONObject -> value.optInt("year").takeIf { it > 0 }?.let { "%04d-%02d-%02d".format(Locale.ROOT, it, value.optInt("month"), value.optInt("day")) }
    is String -> value.takeIf { it.isNotBlank() && it != "null" }
    else -> null
}
private val historyTimestampPattern = Regex(
    """([0-9]{4})-([0-9]{2})-([0-9]{2})[Tt]([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\.([0-9]{1,9}))?([Zz]|([+-])([0-9]{2}):([0-9]{2}))""",
)
private data class HistoryTimestamp(val seconds: Long, val nanos: Int) : Comparable<HistoryTimestamp> {
    override fun compareTo(other: HistoryTimestamp): Int = seconds.compareTo(other.seconds).takeIf { it != 0 }
        ?: nanos.compareTo(other.nanos)
}
/** Go time.Time uses RFC3339Nano. Keep nanoseconds exact without java.time on API 23. */
private fun historyTimestamp(value: Any?): HistoryTimestamp? {
    val match = historyTimestampPattern.matchEntire(value as? String ?: return null) ?: return null
    val fields = match.groupValues
    val offsetSeconds = if (fields[8].equals("Z", ignoreCase = true)) 0 else {
        val hours = fields[10].toInt()
        val minutes = fields[11].toInt()
        if (hours !in 0..23 || minutes !in 0..59) return null
        (hours * 3600 + minutes * 60) * if (fields[9] == "-") -1 else 1
    }
    val year = fields[1].toInt()
    val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
        gregorianChange = Date(Long.MIN_VALUE)
        isLenient = false
        clear()
        set(Calendar.ERA, if (year == 0) GregorianCalendar.BC else GregorianCalendar.AD)
        set(if (year == 0) 1 else year, fields[2].toInt() - 1, fields[3].toInt(),
            fields[4].toInt(), fields[5].toInt(), fields[6].toInt())
    }
    val seconds = try { calendar.timeInMillis / 1000L - offsetSeconds } catch (_: IllegalArgumentException) { return null }
    return HistoryTimestamp(seconds, fields[7].padEnd(9, '0').toInt())
}
private fun collectionSortValue(entry: PersonalCollectionEntry, sort: PersonalCollectionSort, collection: PersonalCollection,
    context: PersonalCollectionSortContext, libraryEntries: Map<Long, JSONObject>): CollectionSortValue? {
    val media = entry.media.raw
    val list = entry.listData
    fun number(value: Double?) = value?.let { CollectionSortValue(number = it) }
    fun date(value: Any?) = collectionDate(value)?.let { CollectionSortValue(text = it) }
    return when (sort) {
        PersonalCollectionSort.TITLE, PersonalCollectionSort.TITLE_DESC -> CollectionSortValue(text = entry.media.title.lowercase(Locale.ROOT))
        PersonalCollectionSort.SCORE, PersonalCollectionSort.SCORE_DESC -> number(list.number("score")?.takeIf { it > 0 })
        PersonalCollectionSort.AUDIENCE_SCORE, PersonalCollectionSort.AUDIENCE_SCORE_DESC -> number(media.number("meanScore")?.takeIf { it > 0 })
        // Go's library/manga EntryListData omits progress when its value is zero.
        PersonalCollectionSort.PROGRESS, PersonalCollectionSort.PROGRESS_DESC -> number(entry.media.progress.toDouble())
        PersonalCollectionSort.START_DATE, PersonalCollectionSort.START_DATE_DESC -> date(list.opt("startedAt"))
        PersonalCollectionSort.END_DATE, PersonalCollectionSort.END_DATE_DESC -> date(list.opt("completedAt"))
        PersonalCollectionSort.RELEASE_DATE, PersonalCollectionSort.RELEASE_DATE_DESC -> date(media.opt("startDate"))
        PersonalCollectionSort.CREATED_AT_DESC -> number(list.number("createdAt"))
        PersonalCollectionSort.UPDATED_AT_DESC -> number(list.number("updatedAt"))
        PersonalCollectionSort.LAST_WATCHED, PersonalCollectionSort.LAST_WATCHED_DESC ->
            historyTimestamp(context.watchHistory.optJSONObject(entry.media.id.toString())?.opt("timeUpdated"))?.let { CollectionSortValue(instant = it) }
        PersonalCollectionSort.AIRDATE, PersonalCollectionSort.AIRDATE_DESC -> {
            val library = if (context.library.has("continueWatchingList")) context.library else collection.raw
            val episodes = library.objects("continueWatchingList") + library.optJSONObject("stream")?.objects("continueWatchingList").orEmpty()
            date(episodes.firstOrNull { it.optJSONObject("baseAnime")?.optMediaId() == entry.media.id }?.optJSONObject("episodeMetadata")?.opt("airDate"))
        }
        PersonalCollectionSort.UNWATCHED_EPISODES, PersonalCollectionSort.UNWATCHED_EPISODES_DESC -> {
            val libraryEntry = libraryEntries[entry.media.id] ?: entry.raw
            val local = libraryEntry.optJSONObject("libraryData")?.takeIf { it.optInt("mainFileCount") > 0 }
                ?: libraryEntry.optJSONObject("nakamaLibraryData")?.takeIf { it.optInt("mainFileCount") > 0 }
            val total = media.optJSONObject("nextAiringEpisode")?.number("episode")?.minus(1) ?: media.number("episodes")
            number(local?.number("unwatchedCount") ?: total?.let { (it - entry.media.progress).coerceAtLeast(0.0) })
        }
        PersonalCollectionSort.UNREAD_CHAPTERS, PersonalCollectionSort.UNREAD_CHAPTERS_DESC -> {
            val preferences = parseNativeMangaPreferences(context.mangaPreferences.optJSONObject("entries")?.optJSONObject(entry.media.id.toString()))
            val filter = preferences.filters[preferences.provider]
            val latest = if (preferences.provider.isBlank()) null else context.latestChapters.optJSONArray(entry.media.id.toString()).objects().filter {
                it.stringOrNull("provider").orEmpty() == preferences.provider && (filter?.language.isNullOrBlank() || it.stringOrNull("language").orEmpty() == filter?.language) &&
                    (filter?.scanlators.isNullOrEmpty() || it.stringOrNull("scanlator").orEmpty() in filter!!.scanlators)
            }.mapNotNull { it.number("number") }.maxOrNull()
            number(latest?.let { (it - entry.media.progress).coerceAtLeast(0.0) })
        }
        PersonalCollectionSort.SERVER_ORDER -> null
    }
}
private data class CollectionSortValue(val number: Double? = null, val text: String? = null, val instant: HistoryTimestamp? = null) : Comparable<CollectionSortValue> {
    override fun compareTo(other: CollectionSortValue): Int = when {
        instant != null && other.instant != null -> instant.compareTo(other.instant)
        number != null && other.number != null -> number.compareTo(other.number)
        else -> text.orEmpty().compareTo(other.text.orEmpty())
    }
}
internal suspend fun loadPersonalCollection(repo: SeanimeRepository, manga: Boolean = false, library: Boolean = true): PersonalCollection =
    parsePersonalCollection(repo.request("GET", when { manga -> "/api/v1/manga/collection"; library -> "/api/v1/library/collection"; else -> "/api/v1/anilist/collection/raw" }), manga)
internal suspend fun loadPersonalCollectionSortContext(repo: SeanimeRepository, sort: PersonalCollectionSort): PersonalCollectionSortContext {
    suspend fun read(path: String) = repo.request("GET", path) as? JSONObject ?: error("Couldn't load collection sort data")
    return when {
        sort.needsLibrary -> PersonalCollectionSortContext(library = read("/api/v1/library/collection"))
        sort.needsHistory -> PersonalCollectionSortContext(watchHistory = read("/api/v1/continuity/history"))
        sort.needsMangaSources -> PersonalCollectionSortContext(latestChapters = read("/api/v1/manga/latest-chapter-numbers"), mangaPreferences = read("/api/v1/manga/preferences"))
        else -> PersonalCollectionSortContext()
    }
}
