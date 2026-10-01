package app.seanime.tv.data

import java.net.URI
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal data class NativeTorrentFile(val index: Int, val path: String, val length: Long, val completed: Long, val priority: Int)
internal data class NativeTorrentPeer(val address: String, val client: String)
internal data class NativeTorrentDetails(
    val hash: String, val name: String, val destination: String, val paused: Boolean, val queued: Boolean,
    val forceStart: Boolean, val sequential: Boolean, val queueIndex: Int, val length: Long, val completed: Long,
    val downloaded: Long, val uploaded: Long, val downSpeed: Long, val upSpeed: Long, val seeds: Int,
    val addedAt: String, val error: String, val files: List<NativeTorrentFile>, val trackers: List<String>, val peers: List<NativeTorrentPeer>,
)

internal fun parseNativeTorrentDetails(raw: JSONObject, requestedHash: String): NativeTorrentDetails {
    val torrent = raw.getJSONObject("torrent")
    check(requestedHash.isNotBlank() && torrent.getString("hash").equals(requestedHash, true)) { "The server returned another torrent. Refresh the list." }
    fun objects(key: String): List<JSONObject> {
        check(raw.isNull(key) || raw.opt(key) is JSONArray) { "The server returned invalid torrent details" }
        val rows = raw.optJSONArray(key) ?: return emptyList()
        return (0 until rows.length()).map { rows.getJSONObject(it) }
    }
    val files = objects("files").map { row ->
        NativeTorrentFile(row.getInt("index"), row.getString("path"), row.getLong("length"), row.optLong("completed"), row.getInt("priority"))
    }
    check(files.all { it.index >= 0 && it.path.isNotBlank() && it.length >= 0 && it.priority in 0..2 } && files.map { it.index }.distinct().size == files.size) {
        "The server returned invalid file identities. Refresh torrent details."
    }
    check(raw.isNull("trackers") || raw.opt("trackers") is JSONArray) { "The server returned invalid trackers" }
    val trackers = raw.optJSONArray("trackers") ?: JSONArray()
    return NativeTorrentDetails(torrent.getString("hash"), torrent.getString("name"), torrent.getString("destination"),
        torrent.optBoolean("paused"), torrent.optBoolean("queued"), torrent.optBoolean("forceStart"), torrent.optBoolean("sequential"), torrent.getInt("queueIndex"),
        torrent.optLong("length"), torrent.optLong("completed"), torrent.optLong("downloaded"), torrent.optLong("uploaded"),
        torrent.optLong("downSpeed"), torrent.optLong("upSpeed"), torrent.optInt("seeds"), torrent.optString("addedAt"), torrent.optString("error"),
        files, (0 until trackers.length()).map { trackers.getString(it) }.filter(String::isNotBlank).distinct(),
        objects("peers").map { NativeTorrentPeer(it.optString("address"), it.optString("client")) })
}

internal sealed interface NativeTorrentCommand {
    data class ForceStart(val enabled: Boolean) : NativeTorrentCommand
    data class Sequential(val enabled: Boolean) : NativeTorrentCommand
    data class Queue(val up: Boolean) : NativeTorrentCommand
    data object Recheck : NativeTorrentCommand
    data object Reannounce : NativeTorrentCommand
    data class FilePriority(val file: NativeTorrentFile, val priority: Int) : NativeTorrentCommand
    data class AddTracker(val tracker: String) : NativeTorrentCommand
    data class RemoveTracker(val tracker: String) : NativeTorrentCommand
}

internal fun nativeTrackerUrl(value: String): String {
    val text = value.trim()
    val uri = runCatching { URI(text) }.getOrNull()
    require(text.length <= 4096 && text.none(Char::isISOControl) && uri?.scheme?.lowercase(Locale.ROOT) in setOf("http", "https", "udp") &&
        !uri?.host.isNullOrBlank() && uri?.rawUserInfo == null && uri?.fragment == null) { "Enter an HTTP, HTTPS or UDP tracker URL without embedded login details or a fragment" }
    return text
}

internal fun NativeTorrentCommand.payload(hash: String): JSONObject {
    require(hash.isNotBlank()) { "Select a torrent first" }
    val body = JSONObject().put("hash", hash)
    when (this) {
        is NativeTorrentCommand.ForceStart -> body.put("action", "force-start").put("value", enabled)
        is NativeTorrentCommand.Sequential -> body.put("action", "set-sequential").put("value", enabled)
        is NativeTorrentCommand.Queue -> body.put("action", if (up) "queue-up" else "queue-down")
        NativeTorrentCommand.Recheck -> body.put("action", "recheck")
        NativeTorrentCommand.Reannounce -> body.put("action", "reannounce")
        is NativeTorrentCommand.FilePriority -> {
            require(file.index >= 0 && priority in 0..2) { "Choose Skip, Normal or High file priority" }
            body.put("action", "set-file-priority").put("index", file.index).put("priority", priority)
        }
        is NativeTorrentCommand.AddTracker -> body.put("action", "add-tracker").put("tracker", nativeTrackerUrl(tracker))
        is NativeTorrentCommand.RemoveTracker -> body.put("action", "remove-tracker").put("tracker", tracker)
    }
    return body
}

internal suspend fun requireNativeTorrentClient(repo: SeanimeRepository): JSONObject {
    val settings = repo.settings().optJSONObject("torrent") ?: error("Torrent settings are unavailable")
    check(settings.optString("defaultTorrentClient") == "seanime") { "This action requires the built-in Seanime torrent client. Return to Downloads and refresh." }
    return settings
}

internal data class NativeTorrentCommandResult(val details: NativeTorrentDetails, val message: String)

/** Every mutation rechecks the selected torrent and any file identity, then verifies observable state. */
internal suspend fun applyNativeTorrentCommand(repo: SeanimeRepository, reviewed: NativeTorrentDetails, command: NativeTorrentCommand): NativeTorrentCommandResult {
    command.payload(reviewed.hash) // Validate input before any request.
    requireNativeTorrentClient(repo)
    val before = repo.builtInTorrentDetails(reviewed.hash)
    check(before.name == reviewed.name && before.destination == reviewed.destination) { "This torrent changed. Refresh details and review the action again." }
    when (command) {
        is NativeTorrentCommand.FilePriority -> check(before.files.any { it.index == command.file.index && it.path == command.file.path && it.length == command.file.length }) {
            "The selected file changed or is no longer available. Refresh files before changing priority."
        }
        NativeTorrentCommand.Recheck -> check(!before.paused && before.files.isNotEmpty()) { "Resume the torrent and wait for file metadata before requesting a recheck." }
        NativeTorrentCommand.Reannounce, is NativeTorrentCommand.AddTracker, is NativeTorrentCommand.RemoveTracker ->
            check(!before.paused) { "Resume the torrent before changing or contacting trackers." }
        else -> Unit
    }
    // Idempotent desired-state changes reconcile a successful request whose response was lost.
    fun matches(details: NativeTorrentDetails): Boolean = when (command) {
        is NativeTorrentCommand.ForceStart -> details.forceStart == command.enabled
        is NativeTorrentCommand.Sequential -> details.sequential == command.enabled
        is NativeTorrentCommand.FilePriority -> details.files.any { it.index == command.file.index && it.path == command.file.path && it.length == command.file.length && it.priority == command.priority }
        is NativeTorrentCommand.AddTracker -> nativeTrackerUrl(command.tracker) in details.trackers
        is NativeTorrentCommand.RemoveTracker -> command.tracker !in details.trackers
        else -> false
    }
    if (!matches(before)) check(repo.builtInTorrentAction(reviewed.hash, command) == true) { "The server did not accept the action. Refresh details before retrying." }
    val after = repo.builtInTorrentDetails(reviewed.hash)
    check(after.name == before.name && after.destination == before.destination) { "The request was accepted, but this torrent changed during readback. Refresh before taking another action." }
    val message = when (command) {
        NativeTorrentCommand.Recheck -> "Recheck requested. Verification runs in the background; this does not confirm it has finished."
        NativeTorrentCommand.Reannounce -> "Tracker reannounce requested. Peer discovery may take time."
        is NativeTorrentCommand.Queue -> if (after.queueIndex == before.queueIndex) "Queue position unchanged; this torrent may already be at the queue boundary."
            else "Queue position updated to ${after.queueIndex + 1}."
        else -> {
            check(matches(after)) { "The request was accepted, but the refreshed state does not match. Refresh and review before retrying." }
            when (command) {
                is NativeTorrentCommand.ForceStart -> if (command.enabled) "Force start enabled." else "Force start disabled."
                is NativeTorrentCommand.Sequential -> if (command.enabled) "Sequential download enabled." else "Sequential download disabled."
                is NativeTorrentCommand.FilePriority -> "File priority updated."
                is NativeTorrentCommand.AddTracker -> "Tracker added."
                is NativeTorrentCommand.RemoveTracker -> "Tracker removed."
                else -> error("Unknown torrent action")
            }
        }
    }
    return NativeTorrentCommandResult(after, message)
}

internal fun nativeTorrentLimit(value: String): Int = value.trim().toIntOrNull()?.takeIf { it in 0..2_097_151 }
    ?: error("Use a whole number from 0 to 2,097,151 KB/s; 0 means unlimited")

internal suspend fun applyNativeTorrentLimits(repo: SeanimeRepository, download: String, upload: String) {
    val down = nativeTorrentLimit(download); val up = nativeTorrentLimit(upload)
    requireNativeTorrentClient(repo)
    check(repo.builtInTorrentLimits(down, up) == true) {
        "The server did not accept these limits. Retry or cancel."
    }
    // The Go action changes in-memory limiters only. No endpoint reads them back.
}
