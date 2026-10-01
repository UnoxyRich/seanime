package app.seanime.tv.platform

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import okio.ByteString.Companion.decodeBase64

internal data class NativeExternalPlaybackSource(
    val url: String, val playbackId: String, val serverUrl: String, val playbackType: String,
    val streamPath: String = "", val mimeType: String = "", val headers: Map<String, String> = emptyMap(),
    val converted: Boolean = false, val watchParty: Boolean = false,
)

internal data class NativeExternalPlaybackPlan(
    val uri: String, val mimeType: String, val needsHost: Boolean,
    val probe: Boolean = false, val safPath: String? = null,
)

/** Reuses media-read authority already returned by Go. Never creates or exports server/client credentials. */
internal fun planExternalPlayback(source: NativeExternalPlaybackSource, nowSeconds: Long = System.currentTimeMillis() / 1000): NativeExternalPlaybackPlan {
    fun reject(message: String): Nothing = throw IllegalArgumentException(message)
    val uri = runCatching { URI(source.url) }.getOrNull() ?: reject("This source cannot be opened in another player")
    if (source.converted || uri.path.orEmpty().startsWith("/api/v1/mediastream/source/") || uri.path.orEmpty().startsWith("/api/v1/mediastream/transcode/"))
        reject("Converted video needs Seanime to stay open. Use the native player for this source.")
    if (source.watchParty || source.playbackType == "nakama") reject("Keep watch parties in Seanime so playback stays synchronized.")
    if (uri.rawUserInfo != null || uri.rawFragment != null) reject("This source includes credentials or an unsupported address")
    val mime = source.mimeType.takeIf { it.startsWith("video/") || it in setOf("application/vnd.apple.mpegurl", "application/x-mpegURL") } ?: "video/*"
    if (source.playbackType == "localfile" && source.streamPath.startsWith("/androidtv/")) {
        val segments = source.streamPath.removePrefix("/androidtv/").split('/')
        if (segments.size < 2 || segments.any { it.isBlank() || it == "." || it == ".." }) reject("The selected storage document is unavailable")
        return NativeExternalPlaybackPlan("", mime, false, safPath = source.streamPath)
    }
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.port !in -1..65535 || uri.port == 0)
        reject("This file cannot be shared safely. Open it through a connected media folder first.")
    val server = runCatching { URI(source.serverUrl) }.getOrNull()
    fun port(value: URI): Int = if (value.port >= 0) value.port else if (value.scheme == "https") 443 else 80
    val sameServer = server != null && uri.scheme == server.scheme && uri.host.equals(server.host, true) && port(uri) == port(server)
    if (!sameServer) {
        if (source.headers.isNotEmpty()) reject("This provider needs private request headers. Use the native player for this source.")
        if (uri.host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")) reject("This local source is not owned by the current Seanime server")
        return NativeExternalPlaybackPlan(source.url, mime, false)
    }
    val pairs = runCatching { uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).map {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
    } }.getOrElse { reject("This media address is invalid") }
    if (pairs.map { it.first }.distinct().size != pairs.size) reject("This media address has ambiguous source details")
    val query = pairs.toMap()
    when (uri.path) {
        "/api/v1/directstream/stream" -> {
            if (query.keys != setOf("id", "token") || source.playbackId.isBlank() || query["id"] != source.playbackId)
                reject("This stream has no shareable media permission. Reopen the episode and try again.")
            val pieces = query.getValue("token").split('.')
            val claims = runCatching {
                require(pieces.size == 2 && pieces.all { it.isNotBlank() } && pieces[0].length <= 4096)
                JSONObject(requireNotNull(pieces[0].decodeBase64()).utf8())
            }.getOrNull()
            if (claims == null || claims.optString("endpoint") != "/api/v1/directstream/stream" || claims.optLong("exp") <= nowSeconds)
                reject("This stream has no current media-only permission. Reopen the episode and try again.")
        }
        "/api/v1/mediastream/file" -> if (query.keys != setOf("path") || source.streamPath.isBlank() || query["path"] != source.streamPath)
            reject("This media address does not match the selected file")
        else -> reject("This server source needs the native player. It has no shareable media address.")
    }
    // Signature/password checks remain in Go. A header-free, non-redirecting range
    // request must succeed before the URL is offered to another application.
    return NativeExternalPlaybackPlan(source.url, mime, uri.host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]"), probe = true)
}

/** Source and server owner must both match before a stale callback can release a host. */
internal class NativeExternalHostLease(private val onRelease: (Ticket) -> Unit = {}) {
    data class Ticket(val id: String, val playbackId: String, val sourceUrl: String, val serverOwner: Long, val needsHost: Boolean,
        val sourceGeneration: Int = 0, val grantedDocument: String? = null)
    @Volatile var current: Ticket? = null
        private set
    @Volatile var leftApplication: Boolean = false
        private set
    @Synchronized fun acquire(ticket: Ticket) {
        current?.let { release(it.id) }
        current = ticket; leftApplication = false
    }
    @Synchronized fun markBackground() { if (current != null) leftApplication = true }
    @Synchronized fun release(id: String): Ticket? {
        val active = current?.takeIf { it.id == id } ?: return null
        current = null; leftApplication = false
        onRelease(active)
        return active
    }
    @Synchronized fun matchingSource(playbackId: String, sourceUrl: String, sourceGeneration: Int, serverOwner: Long): Ticket? =
        current?.takeIf { it.playbackId == playbackId && it.sourceUrl == sourceUrl && it.sourceGeneration == sourceGeneration && it.serverOwner == serverOwner }
    @Synchronized fun protects(owner: Long): Boolean = current?.let { it.needsHost && it.serverOwner == owner } == true
}

/** An old probe may finish after Stop and Resume; it must never regain launch authority. */
internal class NativeExternalLaunchGate {
    private var revision = 0
    private var preparing: Int? = null
    fun begin(): Int = (++revision).also { preparing = it }
    fun canLaunch(attempt: Int, resumed: Boolean, currentSource: Boolean): Boolean = preparing == attempt && resumed && currentSource
    fun launched(attempt: Int) { if (preparing == attempt) preparing = null }
    fun stopPreparation(): Boolean {
        if (preparing == null) return false
        preparing = null; revision++
        return true
    }
}
