package app.seanime.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** The custom Go query accepts time windows and sort; its accepted search field is unused. */
internal data class RecentAiringFilters(val from: Long, val until: Long, val upcoming: Boolean = true) {
    fun payload(page: Int): JSONObject {
        require(page > 0) { "Page must be positive" }
        require(from > 0 && until > from && until - from <= 32 * 86_400L) { "Choose an airing range of up to 32 days" }
        require(until <= Int.MAX_VALUE.toLong()) { "The airing range exceeds the server query's timestamp limit" }
        return jsonObject("page" to page, "perPage" to 24, "airingAt_greater" to from, "airingAt_lesser" to until,
            "notYetAired" to upcoming, "sort" to JSONArray(listOf(if (upcoming) "TIME" else "TIME_DESC")))
    }
    companion object {
        fun around(now: Long = System.currentTimeMillis() / 1000) = RecentAiringFilters(now - 2 * 86_400, now + 14 * 86_400)
    }
}
internal data class RecentAiringItem(val key: String, val media: MediaCard, val episode: Int, val airingAt: Long)
internal data class RecentAiringPage(val items: List<RecentAiringItem>, val page: Int, val hasNext: Boolean, val lastPage: Int?) {
    companion object {
        fun parse(value: Any?, requestedPage: Int): RecentAiringPage {
            val page = (value as? JSONObject)?.optJSONObject("Page") ?: error("The server returned no airing page")
            // The Go response omits an empty typed slice; a non-array value is
            // still a malformed response and must not appear as empty success.
            if (!page.isNull("airingSchedules") && page.optJSONArray("airingSchedules") == null)
                error("The server returned invalid airing schedules")
            val schedules = page.optJSONArray("airingSchedules") ?: JSONArray()
            val info = page.optJSONObject("pageInfo")
            val items = schedules.objects().mapNotNull { schedule ->
                val raw = schedule.optJSONObject("media") ?: return@mapNotNull null
                // Same catalog scope as the existing Discover Schedule caller.
                if (raw.optBoolean("isAdult") || raw.stringOrNull("type") != "ANIME" || raw.stringOrNull("countryOfOrigin") != "JP" || raw.stringOrNull("format") == "TV_SHORT") return@mapNotNull null
                val media = SeanimeJson.media(raw)
                val episode = schedule.optInt("episode")
                val time = schedule.optLong("airingAt")
                if (media.id <= 0 || episode <= 0 || time <= 0) return@mapNotNull null
                RecentAiringItem("${schedule.opt("id")}:${media.id}:$episode:$time", media, episode, time)
            }.distinctBy { it.key }
            return RecentAiringPage(items, info?.optInt("currentPage")?.takeIf { it > 0 } ?: requestedPage,
                info?.optBoolean("hasNextPage") == true, info?.optInt("lastPage")?.takeIf { it > 0 })
        }
    }
}
internal suspend fun loadRecentAiring(repo: SeanimeRepository, filters: RecentAiringFilters, page: Int): RecentAiringPage =
    RecentAiringPage.parse(repo.request("POST", "/api/v1/anilist/list-recent-anime", filters.payload(page)), page)
