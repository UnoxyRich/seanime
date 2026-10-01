package app.seanime.tv.ui

import app.seanime.tv.data.*
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class NativeAnimeFact(val label: String, val value: String)
internal data class NativeAnimeLink(val label: String, val url: String)
internal data class NativeAnimeCharacter(val id: Long, val name: String, val role: String, val image: String?)
internal data class NativeAnimeRelated(val key: String, val relation: String, val media: MediaCard)
internal data class NativeAnimeSupplement(
    val studios: List<String>, val rankings: List<String>, val characters: List<NativeAnimeCharacter>,
    val relations: List<NativeAnimeRelated>, val recommendations: List<NativeAnimeRelated>,
)

/** Render only facts actually supplied by the existing Go BaseAnime response. */
internal fun nativeAnimeFacts(media: MediaCard, showScore: Boolean): List<NativeAnimeFact> = buildList {
    val raw = media.raw
    fun fact(label: String, value: String?) { value?.takeIf(String::isNotBlank)?.let { add(NativeAnimeFact(label, it)) } }
    fact("Release status", raw.stringOrNull("status")?.let(::nativeAnimeLabel))
    fact("Format", raw.stringOrNull("format")?.let(::nativeAnimeLabel))
    val year = raw.optInt("seasonYear").takeIf { it in 1..9999 }
    fact("Season", listOfNotNull(raw.stringOrNull("season")?.let(::nativeAnimeLabel), year?.toString()).joinToString(" "))
    fact("Episodes", raw.optInt("episodes").takeIf { it > 0 }?.toString())
    fact("Episode duration", raw.optInt("duration").takeIf { it > 0 }?.let { "$it minutes" })
    fact("Genres", raw.optJSONArray("genres")?.let { values -> (0 until values.length()).mapNotNull {
        (values.opt(it) as? String)?.takeIf(String::isNotBlank)
    }.distinct().joinToString(" · ") })
    if (showScore) fact("Audience score", nativeAnimeScore(media))
    fact("Started", nativeAnimeDate(raw.optJSONObject("startDate")))
    fact("Ended", nativeAnimeDate(raw.optJSONObject("endDate")))
    raw.optJSONObject("nextAiringEpisode")?.let { next ->
        val episode = next.optInt("episode").takeIf { it > 0 }
        val seconds = (next.opt("airingAt") as? Number)?.toLong()?.takeIf { it in 1L..253_402_300_799L }
        if (episode != null && seconds != null) fact("Next airing", "Episode $episode · " +
            SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(seconds * 1000L)))
    }
    raw.optJSONObject("title")?.let { titles ->
        val used = mutableSetOf(media.title)
        for ((key, label) in listOf("english" to "English title", "romaji" to "Romaji title", "native" to "Native title")) {
            titles.stringOrNull(key)?.takeIf { used.add(it) }?.let { fact(label, it) }
        }
    }
}

internal fun nativeAnimeScore(media: MediaCard): String? = (media.raw.opt("meanScore") as? Number)?.toDouble()
    ?.takeIf { it.isFinite() && it > 0 && it <= 100 }?.let { String.format(Locale.ROOT, "%.1f / 10", it / 10) }

/** A missing month/day is retained as a partial date, never filled with January/1. */
internal fun nativeAnimeDate(value: JSONObject?): String? {
    value ?: return null
    val year = value.optInt("year").takeIf { it in 1..9999 } ?: return null
    val month = value.optInt("month").takeIf { it in 1..12 } ?: return year.toString()
    val day = value.optInt("day").takeIf { it in 1..31 }
    return String.format(Locale.ROOT, "%04d-%02d", year, month) + (day?.let { String.format(Locale.ROOT, "-%02d", it) } ?: "")
}

internal fun nativeAnimeLabel(value: String): String = when (value) {
    "TV", "OVA", "ONA" -> value
    "TV_SHORT" -> "TV short"
    else -> value.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
}

internal fun nativeAnimeLinks(media: MediaCard): List<NativeAnimeLink> = buildList {
    media.raw.stringOrNull("siteUrl")?.takeIf(::nativePluginExternalLink)?.let { url ->
        val host = URI(url).host.lowercase(Locale.ROOT)
        add(NativeAnimeLink(if (host == "anilist.co" || host == "www.anilist.co") "Open AniList" else "Open website", url))
    }
    media.raw.optJSONObject("trailer")?.let { trailer ->
        val id = trailer.stringOrNull("id")?.takeIf { it.length <= 128 && Regex("[A-Za-z0-9_-]+").matches(it) }
        val url = if (id == null) null else when (trailer.stringOrNull("site")?.lowercase(Locale.ROOT)) {
            "youtube" -> "https://www.youtube.com/watch?v=$id"
            "dailymotion" -> "https://www.dailymotion.com/video/$id"
            else -> null
        }
        url?.let { add(NativeAnimeLink("Open trailer", it)) }
    }
}

/** Supplemental facts are a separate response; reject a mismatched title before presentation. */
internal fun parseNativeAnimeSupplement(raw: JSONObject, expectedId: Long,
    providerResult: Boolean = isCustomSourceMediaId(expectedId)): NativeAnimeSupplement {
    require(raw.optMediaId() == expectedId && expectedId > 0) { "The server returned metadata for a different or missing title. Try again." }
    fun linked(node: JSONObject?): MediaCard? {
        node ?: return null
        if (node.optMediaId() <= 0 || node.stringOrNull("type") !in setOf("ANIME", "MANGA")) return null
        if (node.stringOrNull("format") in setOf("NOVEL", "MUSIC")) return null
        val title = node.optJSONObject("title")
        if (listOf("userPreferred", "english", "romaji", "native").none { title?.stringOrNull(it) != null } &&
            (node.opt("title") as? String).isNullOrBlank()) return null
        return SeanimeJson.media(node, node.stringOrNull("type") == "MANGA").let {
            // Custom details may reference AniList IDs; those image fields still came from the provider.
            if (providerResult) it.copy(artworkOrigin = MediaArtworkOrigin.PROVIDER) else it
        }
    }
    val relations = raw.optJSONObject("relations").objects("edges").mapNotNull { edge ->
        if (edge.stringOrNull("relationType") == "CHARACTER") return@mapNotNull null
        val media = linked(edge.optJSONObject("node")) ?: return@mapNotNull null
        if (media.id == expectedId && !media.isManga) return@mapNotNull null
        NativeAnimeRelated("relation:${if (media.isManga) "manga" else "anime"}:${media.id}",
            edge.stringOrNull("relationType")?.let(::nativeAnimeLabel).orEmpty(), media)
    }.distinctBy { it.key }
    val recommendations = raw.optJSONObject("recommendations").objects("edges").mapNotNull { edge ->
        val media = linked(edge.optJSONObject("node")?.optJSONObject("mediaRecommendation")) ?: return@mapNotNull null
        if (media.id == expectedId && !media.isManga) return@mapNotNull null
        NativeAnimeRelated("recommendation:${if (media.isManga) "manga" else "anime"}:${media.id}", "", media)
    }.distinctBy { it.key }
    val characters = raw.optJSONObject("characters").objects("edges").mapNotNull { edge ->
        val role = edge.stringOrNull("role")?.takeIf { it in setOf("MAIN", "SUPPORTING") } ?: return@mapNotNull null
        val node = edge.optJSONObject("node") ?: return@mapNotNull null
        val id = node.optMediaId().takeIf { it > 0 } ?: return@mapNotNull null
        val name = node.optJSONObject("name")?.stringOrNull("full") ?: return@mapNotNull null
        NativeAnimeCharacter(id, name, nativeAnimeLabel(role), node.optJSONObject("image")?.stringOrNull("large"))
    }.distinctBy { it.id }.take(10)
    val rankings = raw.objects("rankings").mapNotNull { rank ->
        val position = rank.optInt("rank").takeIf { it > 0 } ?: return@mapNotNull null
        val category = when (rank.stringOrNull("type")) { "RATED" -> "Highest rated"; "POPULAR" -> "Most popular"; else -> return@mapNotNull null }
        val period = if (rank.optBoolean("allTime")) "All time" else listOfNotNull(
            rank.stringOrNull("season")?.let(::nativeAnimeLabel), rank.optInt("year").takeIf { it in 1..9999 }?.toString()).joinToString(" ")
        listOf("#$position $category", rank.stringOrNull("context"), period.takeIf(String::isNotBlank)).filterNotNull().distinct().joinToString(" · ")
    }.distinct()
    return NativeAnimeSupplement(raw.optJSONObject("studios").objects("nodes").mapNotNull { it.stringOrNull("name") }.distinct(),
        rankings, characters, relations, recommendations)
}

private fun JSONObject?.objects(key: String): List<JSONObject> = this?.optJSONArray(key).objects()
