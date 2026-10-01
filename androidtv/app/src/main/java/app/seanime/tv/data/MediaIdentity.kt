package app.seanime.tv.data

import org.json.JSONObject

/** Go custom-source identities use the complete positive JavaScript-safe integer range. */
const val MAX_NATIVE_MEDIA_ID: Long = 9_007_199_254_740_991L

fun parseNativeMediaId(value: String): Long? = value.toLongOrNull()?.takeIf { it in 1L..MAX_NATIVE_MEDIA_ID }

fun JSONObject.optMediaId(key: String = "id", fallback: Long = 0L): Long {
    val raw = opt(key)
    if (raw is String) return parseNativeMediaId(raw) ?: fallback
    if (raw !is Number) return fallback
    val value = optLong(key)
    return value.takeIf { it in 1L..MAX_NATIVE_MEDIA_ID && raw.toDouble() == it.toDouble() } ?: fallback
}
