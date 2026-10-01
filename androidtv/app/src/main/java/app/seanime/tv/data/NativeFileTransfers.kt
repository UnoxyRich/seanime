package app.seanime.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal const val NATIVE_EXPORT_LIMIT = 32 * 1024 * 1024
internal enum class NativeExportKind(val label: String, val filename: String, val mimeType: String, val path: String) {
    LIBRARY_INDEX("Library index", "seanime-library-index.json", "application/json", "/api/v1/library/local-files/dump"),
    HEAP("Heap profile", "seanime-heap.pprof", "application/octet-stream", "/api/v1/memory/profile"),
    ALLOCATIONS("Allocations profile", "seanime-allocations.pprof", "application/octet-stream", "/api/v1/memory/profile"),
    GOROUTINE("Goroutine profile", "seanime-goroutine.pprof", "application/octet-stream", "/api/v1/memory/goroutine"),
    CPU("CPU profile", "seanime-cpu.pprof", "application/octet-stream", "/api/v1/memory/cpu"),
}
internal data class NativeExportFile(val filename: String, val mimeType: String, val bytes: ByteArray)

internal suspend fun downloadNativeExport(repo: SeanimeRepository, kind: NativeExportKind, duration: Int = 30): NativeExportFile {
    require(duration in 1..300) { "Choose a duration from 1 to 300 seconds" }
    val query = when (kind) {
        NativeExportKind.HEAP -> mapOf("heap" to "true")
        NativeExportKind.ALLOCATIONS -> mapOf("allocs" to "true")
        NativeExportKind.CPU -> mapOf("duration" to duration.toString())
        else -> emptyMap()
    }
    val bytes = repo.client.download(kind.path, NATIVE_EXPORT_LIMIT, query,
        readTimeoutSeconds = if (kind == NativeExportKind.CPU) duration.toLong() + 30 else 120)
    check(bytes.isNotEmpty()) { "The server returned an empty export" }
    if (kind == NativeExportKind.LIBRARY_INDEX) JSONArray(bytes.toString(Charsets.UTF_8))
    return NativeExportFile(kind.filename, kind.mimeType, bytes)
}

internal data class NativeMetadataPreview(val path: String, val sha256: String, val paths: Set<String>, val matched: Int, val locked: Int, val ignored: Int) {
    val count: Int get() = paths.size
}
internal fun previewNativeMetadata(path: String, bytes: ByteArray): NativeMetadataPreview {
    require(bytes.isNotEmpty() && bytes.size <= NATIVE_EXPORT_LIMIT) { "Choose a nonempty JSON index smaller than 32 MiB" }
    val array = JSONArray(bytes.toString(Charsets.UTF_8))
    require(array.length() > 0) { "The server cannot import an empty index" }
    val files = (0 until array.length()).map { array.getJSONObject(it) }
    val paths = files.map { it.getString("path").also { value -> require(value.isNotBlank() && value.none { c -> c == '\u0000' }) { "Every index entry needs a valid file path" } } }
    require(paths.distinct().size == paths.size) { "The index contains duplicate file paths" }
    return NativeMetadataPreview(path, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, paths.toSet(),
        files.count { it.optLong("mediaId") > 0 }, files.count { it.optBoolean("locked") }, files.count { it.optBoolean("ignored") })
}

internal suspend fun importNativeMetadata(repo: SeanimeRepository, approved: NativeMetadataPreview, read: suspend (String) -> ByteArray) {
    val latest = previewNativeMetadata(approved.path, read(approved.path))
    check(latest.sha256 == approved.sha256) { "The selected index changed. Preview it again before importing." }
    check(repo.request("POST", "/api/v1/library/local-files/import", JSONObject().put("dataFilePath", approved.path)) == true) {
        "The server did not confirm the index import"
    }
    val current = repo.request("GET", "/api/v1/library/local-files") as? JSONArray
        ?: error("The import was accepted, but the refreshed index could not be verified. Refresh before retrying.")
    val paths = (0 until current.length()).map { current.getJSONObject(it).getString("path") }
    check(paths.size == approved.count && paths.toSet() == approved.paths) { "The import was accepted, but the active index changed. Refresh before retrying." }
}

internal suspend fun changeNativeTorrent(repo: SeanimeRepository, original: DownloadItem, directory: String? = null, name: String? = null) {
    require((directory != null) != (name != null)) { "Choose one torrent change" }
    check(repo.settings().optJSONObject("torrent")?.optString("defaultTorrentClient") == "seanime") { "This action requires the built-in Seanime torrent client" }
    val current = repo.downloads().singleOrNull { it.id == original.id && it.kind == "torrent" }
        ?: error("This torrent is no longer available. Refresh the list.")
    check(current.name == original.name && current.raw.optString("contentPath") == original.raw.optString("contentPath")) {
        "This torrent changed. Close this dialog, refresh, and review it again."
    }
    if (directory != null) {
        val folder = repo.request("POST", "/api/v1/directory-selector", JSONObject().put("input", directory)) as? JSONObject
        check(folder?.optBoolean("exists") == true && folder.optString("fullPath") == directory) { "The selected folder is no longer available. Choose it again." }
    }
    check(repo.torrentAction(current.id, if (directory != null) "move-storage" else "rename", directory, name) == true) {
        "The server did not confirm this torrent change"
    }
}
