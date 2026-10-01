package app.seanime.tv.data

import org.json.JSONObject

/** Provider numbers select URLs; nested anime metadata identifies progress and specials. */
object OnlineEpisodeIdentity {
    fun canonical(raw: JSONObject): JSONObject? = if (raw.has("metadata")) raw.optJSONObject("metadata")
        else raw.takeIf { it.has("episodeNumber") || it.has("aniDBEpisode") || it.has("progressNumber") }

    fun sourceNumber(raw: JSONObject): Int = raw.optJSONObject("onlinestreamParams")?.optInt("episodeNumber")?.takeIf { it > 0 }
        ?: raw.optInt("number", raw.optInt("episodeNumber"))

    fun withParams(raw: JSONObject, params: JSONObject): JSONObject {
        val number = sourceNumber(raw).takeIf { it > 0 } ?: params.optInt("episodeNumber")
        require(number > 0) { "The provider did not identify this episode source" }
        return (canonical(raw)?.let { JSONObject(it.toString()) } ?: JSONObject())
            .put("onlinestreamParams", JSONObject(params.toString()).put("episodeNumber", number))
    }
}
