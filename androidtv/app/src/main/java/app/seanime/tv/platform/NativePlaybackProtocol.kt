package app.seanime.tv.platform

import org.json.JSONObject
import java.net.URI

/** Pure wire transformations shared by the native player and test fixtures. Units are explicit. */
object NativePlaybackProtocol {
    fun normalizePlaybackInfo(raw: JSONObject, resolveUrl: (String) -> String): JSONObject {
        val copy = JSONObject(raw.toString())
        val streamType = copy.optString("streamType")
        if (!copy.has("playbackType")) copy.put("playbackType", streamType)
        if (streamType in setOf("localfile", "torrent", "debrid", "url", "nakama")) copy.put("streamType", "native")
        copy.put("streamUrl", resolvePlaybackSource(copy.optString("streamUrl"), resolveUrl))
        return copy
    }

    fun resolvePlaybackSource(source: String, resolveUrl: (String) -> String): String {
        val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(source)?.value?.dropLast(1)?.lowercase()
        require(scheme == null || scheme in setOf("http", "https", "file", "content")) { "The stream URL is unsupported" }
        val resolved = if (scheme in setOf("file", "content")) source else resolveUrl(source)
        val uri = runCatching { URI(resolved) }.getOrNull() ?: throw IllegalArgumentException("Invalid stream URL")
        require(uri.rawUserInfo == null && when (uri.scheme?.lowercase()) {
            "http", "https" -> !uri.host.isNullOrBlank() && (uri.port == -1 || uri.port in 1..65535)
            "content" -> !uri.rawAuthority.isNullOrBlank()
            "file" -> (uri.rawAuthority.isNullOrBlank() || uri.host == "localhost") && uri.path?.startsWith('/') == true
            else -> false
        }) { "Invalid stream URL" }
        return resolved
    }

    fun status(info: JSONObject?, clientId: String, snapshot: JSONObject): JSONObject = JSONObject()
        .put("id", info?.optString("id").orEmpty()).put("clientId", clientId)
        .put("currentTime", snapshot.optLong("positionMs").coerceAtLeast(0) / 1000.0)
        .put("duration", snapshot.optLong("durationMs").coerceAtLeast(0) / 1000.0)
        .put("paused", snapshot.optBoolean("paused", true))

    fun event(clientId: String, type: String, payload: JSONObject): JSONObject = JSONObject()
        .put("clientId", clientId).put("type", type).put("payload", payload)

    fun continuityPosition(history: JSONObject?, episodeNumber: Int): Long {
        if (history == null || episodeNumber <= 0 || history.optInt("episodeNumber") != episodeNumber) return 0
        val position = history.optDouble("currentTime", 0.0)
        val duration = history.optDouble("duration", 0.0)
        if (!position.isFinite() || !duration.isFinite() || duration <= 0 || position <= 0 || position / duration >= 0.9) return 0
        return (position * 1000).toLong().coerceAtLeast(0)
    }

    /** An explicit initial state, including zero, takes precedence over history. */
    fun shouldRestoreContinuity(info: JSONObject): Boolean =
        info.optJSONObject("initialState") == null && !info.optBoolean("disableRestoreFromContinuity")

    /** MKV websocket timing is milliseconds, despite a stale Go struct comment. */
    fun subtitleIntervalSeconds(event: JSONObject): Pair<Double, Double>? {
        val start = event.optDouble("startTime", -1.0)
        val duration = event.optDouble("duration", 0.0)
        if (!start.isFinite() || !duration.isFinite() || start < 0 || duration <= 0) return null
        return start / 1000.0 to (start + duration) / 1000.0
    }

    fun usableNativeRecovery(metadata: String, originalUri: String, fileExists: (String) -> Boolean): Boolean {
        val info = runCatching { JSONObject(metadata) }.getOrNull() ?: return false
        val source = info.optString("streamUrl")
        if (source.isBlank() || source != originalUri) return false
        val uri = runCatching { URI(source) }.getOrNull() ?: return false
        if (uri.rawUserInfo != null) return false
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> !uri.host.isNullOrBlank() && (uri.port == -1 || uri.port in 1..65535)
            "content" -> !uri.authority.isNullOrBlank()
            "file" -> !uri.path.isNullOrBlank() && fileExists(uri.path)
            else -> false
        }
    }

    fun hasReachedCompletion(snapshot: JSONObject): Boolean {
        val duration = snapshot.optLong("durationMs")
        return duration > 0 && snapshot.optLong("positionMs").toDouble() / duration >= 0.8
    }
}
