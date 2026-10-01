package app.seanime.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** Go omitempty collections may be absent/null; a non-null wrong shape is a protocol failure. */
internal fun nativeResponseObject(value: Any?, path: String, field: String): JSONObject? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> value
    else -> throw ApiException(200, "Server returned invalid $field", path)
}

internal fun nativeResponseObjects(value: Any?, path: String, field: String): List<JSONObject> = when (value) {
    null, JSONObject.NULL -> emptyList()
    is JSONArray -> (0 until value.length()).map { index ->
        value.opt(index) as? JSONObject
            ?: throw ApiException(200, "Server returned an invalid item in $field", path)
    }
    else -> throw ApiException(200, "Server returned invalid $field", path)
}

/** Preserve sparse title fields, but never expose an actionable card with no valid identity. */
internal fun nativeResponseMedia(value: JSONObject, path: String, manga: Boolean = false): MediaCard {
    if (value.has("media") && nativeResponseObject(value.opt("media"), path, "media") == null)
        throw ApiException(200, "Server returned no media", path)
    return SeanimeJson.media(value, manga).also {
        if (it.id <= 0L) throw ApiException(200, "Server returned an invalid media identity", path)
    }
}

internal fun nativeResponseEntry(raw: JSONObject, id: Long, path: String, manga: Boolean = false): MediaCard {
    val nested = nativeResponseObject(raw.opt("media"), path, "media")
        ?: throw ApiException(200, "Server returned no media", path)
    return nativeResponseMedia(raw, path, manga).also {
        if (nested.optMediaId() != id || it.id != id)
            throw ApiException(200, "Server returned a different media identity", path)
    }
}
