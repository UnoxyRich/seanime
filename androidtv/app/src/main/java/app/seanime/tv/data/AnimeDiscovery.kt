package app.seanime.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** Fields supported by the unchanged anime/manga query documents, not just their HTTP handlers. */
data class AnimeDiscoveryFilters(
    val search: String = "",
    val sort: String = "AUTO",
    val statuses: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val averageScoreGreater: Int? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val format: String? = null,
    val isAdult: Boolean = false,
    val manga: Boolean = false,
    val countryOfOrigin: String? = null,
) {
    fun payload(page: Int, perPage: Int = 24): JSONObject {
        require(page > 0) { "Page must be positive" }
        require(perPage in 1..50) { "Page size must be between 1 and 50" }
        val maximumScore = if (manga) 9 else 99
        require(averageScoreGreater == null || averageScoreGreater in 0..maximumScore) { "Score must be between 0 and $maximumScore" }
        require(seasonYear == null || seasonYear in 1..9999) { "Enter a valid release year" }
        require(!manga || season == null) { "Manga discovery does not support seasons" }
        require(countryOfOrigin == null || manga && countryOfOrigin in setOf("JP", "KR", "CN", "TW")) { "Country filtering is only supported for manga" }
        require(format != "MUSIC" && format != "NOVEL") { "This format is excluded by the server's discovery query" }
        val formats = if (manga) setOf(null, "MANGA", "ONE_SHOT") else setOf(null, "TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA")
        require(format in formats) { "Choose a format for this catalog" }
        return jsonObject(
            "page" to page, "perPage" to perPage,
            "sort" to JSONArray(listOf(if (sort == "AUTO") if (search.isBlank()) "TRENDING_DESC" else "SEARCH_MATCH" else sort)),
            "isAdult" to isAdult,
        ).apply {
            search.trim().takeIf(String::isNotEmpty)?.let { put("search", it) }
            if (statuses.isNotEmpty()) put("status", JSONArray(statuses.distinct()))
            if (genres.isNotEmpty()) put("genres", JSONArray(genres.distinct()))
            if (tags.isNotEmpty()) put("tags", JSONArray(tags.map(String::trim).filter(String::isNotEmpty).distinct()))
            averageScoreGreater?.let { put("averageScore_greater", it) }
            season?.let { put("season", it) }
            seasonYear?.let { put(if (manga) "year" else "seasonYear", it) }
            format?.let { put("format", it) }
            countryOfOrigin?.let { put("countryOfOrigin", it) }
        }
    }

    /** Saveable screen state is separate from the resolved request sort. */
    fun save(): String = payload(1).put("sortMode", sort).put("manga", manga).toString()

    companion object {
        fun restore(saved: String): AnimeDiscoveryFilters {
            val value = JSONObject(saved)
            fun strings(key: String): List<String> = value.optJSONArray(key)?.let { array ->
                (0 until array.length()).map { array.getString(it) }
            }.orEmpty()
            fun optionalInt(key: String) = if (value.has(key) && !value.isNull(key)) value.getInt(key) else null
            return AnimeDiscoveryFilters(value.optString("search"), value.optString("sortMode", "AUTO"), strings("status"),
                strings("genres"), strings("tags"), optionalInt("averageScore_greater"), value.stringOrNull("season"),
                optionalInt(if (value.optBoolean("manga")) "year" else "seasonYear"), value.stringOrNull("format"), value.optBoolean("isAdult"),
                value.optBoolean("manga"), value.stringOrNull("countryOfOrigin"))
        }

        fun parseTags(value: String): List<String> = value.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
    }
}

data class AnimeDiscoveryPage(
    val media: List<MediaCard>,
    val currentPage: Int,
    val hasNextPage: Boolean,
    val lastPage: Int? = null,
    val total: Int? = null,
) {
    companion object {
        fun fromJson(value: Any?, requestedPage: Int, manga: Boolean = false): AnimeDiscoveryPage {
            val endpoint = if (manga) "/api/v1/manga/anilist/list" else "/api/v1/anilist/list-anime"
            val root = value as? JSONObject
                ?: throw ApiException(200, "Server returned an unexpected discovery response", endpoint)
            val page = root.optJSONObject("Page") ?: root.optJSONObject("page")
                ?: throw ApiException(200, "Server returned no discovery page", endpoint)
            // Go's typed Page.Media slice uses omitempty, so real empty results
            // can contain pageInfo without a media key (or with JSON null).
            val media = nativeResponseObjects(page.opt("media"), endpoint, "discovery results")
                .map { nativeResponseMedia(it, endpoint, manga) }.distinctBy { it.id }
            val info = nativeResponseObject(page.opt("pageInfo"), endpoint, "discovery page information")
            return AnimeDiscoveryPage(media, info?.optInt("currentPage")?.takeIf { it > 0 } ?: requestedPage,
                info?.optBoolean("hasNextPage") == true, info?.optInt("lastPage")?.takeIf { it > 0 },
                info?.let { if (it.has("total") && !it.isNull("total")) it.getInt("total").takeIf { count -> count >= 0 } else null })
        }
    }
}

/** Manga's existing Go adapter multiplies the 0–9 score threshold by ten. */
internal suspend fun discoverNativeCatalog(repo: SeanimeRepository, filters: AnimeDiscoveryFilters, page: Int): AnimeDiscoveryPage =
    AnimeDiscoveryPage.fromJson(repo.request("POST", if (filters.manga) "/api/v1/manga/anilist/list" else "/api/v1/anilist/list-anime",
        filters.payload(page)), page, filters.manga)

internal suspend fun nativeDiscoveryTags(repo: SeanimeRepository, manga: Boolean): List<String> {
    if (!manga) return repo.animeDiscoveryTags()
    val raw = repo.request("GET", "/api/v1/manga/anilist/collection/raw/tags") as? JSONObject ?: error("No manga collection tags returned")
    return raw.keys().asSequence().flatMap { key ->
        val values = raw.optJSONArray(key) ?: JSONArray()
        (0 until values.length()).asSequence().map { values.optString(it).trim() }
    }.filter(String::isNotEmpty).distinct().sorted().toList()
}
