package app.seanime.tv.ui

import app.seanime.tv.data.SeanimeRepository
import app.seanime.tv.data.objects
import org.json.JSONArray
import org.json.JSONObject

/** Rebase only the user's edited match fields onto the latest indexed file. */
internal fun nativeFileMatchPayload(original: JSONObject, current: JSONObject, edited: JSONObject): JSONObject {
    val path = original.getString("path")
    require(path.isNotBlank() && current.optString("path") == path && edited.optString("path") == path) { "The selected file changed. Reopen its match editor." }
    val before = original.optJSONObject("metadata") ?: JSONObject()
    val draft = edited.optJSONObject("metadata") ?: JSONObject()
    val metadata = JSONObject(current.optJSONObject("metadata")?.toString() ?: "{}")
    val number = draft.optInt("episode", -1)
    require(number >= 0) { "Enter a nonnegative episode number" }
    require(draft.optString("type") in setOf("main", "special", "nc")) { "Choose an episode type" }
    require(edited.optLong("mediaId", -1L) >= 0) { "Choose an anime or clear the match" }
    for (field in listOf("episode", "aniDBEpisode", "type")) {
        val unchanged = when (field) {
            "episode" -> draft.optInt(field) == before.optInt(field)
            "type" -> draft.optString(field) == before.optString(field).ifBlank { "main" }
            else -> draft.optString(field) == before.optString(field)
        }
        if (!unchanged) metadata.put(field, draft.opt(field))
    }
    if (!metadata.has("episode")) metadata.put("episode", 0)
    if (!metadata.has("aniDBEpisode")) metadata.put("aniDBEpisode", "")
    if (!metadata.has("type") || metadata.optString("type").isBlank()) metadata.put("type", draft.getString("type"))
    return JSONObject().put("path", path).put("metadata", metadata).apply {
        for (field in listOf("mediaId", "locked", "ignored")) {
            val changed = if (field == "mediaId") edited.optLong(field) != original.optLong(field)
                else edited.optBoolean(field) != original.optBoolean(field)
            put(field, if (changed) edited.opt(field) else current.opt(field))
        }
    }
}

private suspend fun currentIndexedFile(repo: SeanimeRepository, path: String): JSONObject {
    require(path.isNotBlank()) { "Choose an indexed file" }
    val matches = repo.request("GET", "/api/v1/library/local-files").jsonObjects().filter { it.optString("path") == path }
    check(matches.size == 1) { "This file is no longer uniquely indexed. Refresh the library and try again." }
    return matches.single()
}

internal suspend fun saveNativeFileMatch(repo: SeanimeRepository, original: JSONObject, edited: JSONObject) {
    val path = original.getString("path")
    val payload = nativeFileMatchPayload(original, currentIndexedFile(repo, path), edited)
    val response = repo.request("PATCH", "/api/v1/library/local-file", payload) as? JSONArray
        ?: error("The server did not return the updated file index")
    val saved = response.uiObjects().singleOrNull { it.optString("path") == path }
        ?: error("The updated file was not present in the server response")
    check(saved.optLong("mediaId") == payload.optLong("mediaId") && saved.optBoolean("locked") == payload.optBoolean("locked") &&
        saved.optBoolean("ignored") == payload.optBoolean("ignored")) { "The server did not confirm the requested file match" }
    for (field in listOf("episode", "aniDBEpisode", "type")) {
        check(saved.optJSONObject("metadata")?.opt(field) == payload.getJSONObject("metadata").opt(field)) { "The server did not confirm the requested episode metadata" }
    }
}

internal suspend fun deleteNativeLibraryFile(repo: SeanimeRepository, path: String) {
    currentIndexedFile(repo, path)
    check(repo.request("DELETE", "/api/v1/library/local-files", JSONObject().put("paths", JSONArray().put(path))) == true) {
        "The server did not confirm that the selected file was deleted"
    }
}

internal enum class NativeLibraryBulkAction(val value: String, val label: String, val effect: String) {
    MATCH("match", "Match to anime", "Match these files to the selected anime, lock their matches and stop ignoring them."),
    UNMATCH("unmatch", "Clear matches", "Clear anime matches, unlock the files and stop ignoring them."),
    LOCK("lock", "Lock matches", "Protect these file matches during future scans."),
    UNLOCK("unlock", "Unlock matches", "Allow future scans to update these file matches."),
    IGNORE("ignore", "Ignore files", "Clear anime matches, unlock the files and ignore them during scans."),
    UNIGNORE("unignore", "Stop ignoring", "Include these files in scans and unlock them. Previous anime matches are not restored.")
}

/** A folder selection is a snapshot of indexed descendants, never a path-prefix filesystem operation. */
internal fun nativeLibraryFolderFiles(node: JSONObject?): List<JSONObject> {
    if (node == null) return emptyList()
    val own = node.optJSONObject("localFile")?.takeIf { it.optString("path").isNotBlank() }
    return (listOfNotNull(own) + node.objects("children").flatMap(::nativeLibraryFolderFiles)).distinctBy { it.optString("path") }
}

internal fun nativeLibraryFindNode(root: JSONObject?, path: String): JSONObject? {
    if (root == null || path.isBlank() || root.optString("path") == path) return root
    return root.objects("children").firstNotNullOfOrNull { nativeLibraryFindNode(it, path) }
}

internal fun nativeLibrarySelectedFiles(paths: Set<String>, current: List<JSONObject>): List<JSONObject> {
    require(paths.isNotEmpty() && paths.all(String::isNotBlank)) { "Select at least one indexed file" }
    val byPath = current.groupBy { it.optString("path") }
    return paths.map { path ->
        val matches = byPath[path].orEmpty()
        check(matches.size == 1) { "A selected file is no longer uniquely indexed. Refresh the library and select it again." }
        matches.single()
    }
}

internal suspend fun applyNativeLibraryBulkAction(repo: SeanimeRepository, paths: Set<String>, action: NativeLibraryBulkAction, mediaId: Long? = null) {
    require(action != NativeLibraryBulkAction.MATCH || mediaId != null && mediaId > 0) { "Choose an anime to match" }
    nativeLibrarySelectedFiles(paths, repo.request("GET", "/api/v1/library/local-files").jsonObjects())
    val payload = JSONObject().put("paths", JSONArray(paths.toList())).put("action", action.value)
    if (action == NativeLibraryBulkAction.MATCH) payload.put("mediaId", mediaId)
    check(repo.request("PATCH", "/api/v1/library/local-files", payload) == true) { "The server did not confirm the selected file action" }
}

internal fun nativeLibraryRenameTarget(path: String, newName: String): String {
    require(path.isNotBlank() && path.lastIndexOf('/') >= 0) { "Choose an indexed file with a complete path" }
    require(newName.isNotBlank() && newName == newName.trim() && newName !in setOf(".", "..") &&
        !newName.endsWith('.') && newName.none { it.code < 32 || it.code == 127 || it in "/\\<>:\"|?*" } &&
        newName.toByteArray(Charsets.UTF_8).size <= 255) { "Use a filename of at most 255 bytes, without path separators or reserved characters" }
    val oldName = path.substringAfterLast('/')
    val extension = oldName.substringAfterLast('.', "")
    require(extension.isBlank() || newName.endsWith(".$extension", ignoreCase = true)) { "Keep the .$extension media extension" }
    val stem = if (extension.isBlank()) newName else newName.dropLast(extension.length + 1)
    require(stem.any { it != '.' && !it.isWhitespace() }) { "Enter a filename before the media extension" }
    require(newName != oldName) { "Choose a different filename" }
    return path.substringBeforeLast('/') + "/" + newName
}

internal suspend fun renameNativeLibraryFile(repo: SeanimeRepository, original: JSONObject, newName: String) {
    val path = original.getString("path")
    val target = nativeLibraryRenameTarget(path, newName)
    val index = repo.request("GET", "/api/v1/library/local-files").jsonObjects()
    val current = nativeLibrarySelectedFiles(setOf(path), index).single()
    check(current.optString("name") == original.optString("name") &&
        current.optJSONObject("parsedInfo")?.optString("original") == original.optJSONObject("parsedInfo")?.optString("original")) {
        "The selected file identity changed. Refresh the library and reopen Rename."
    }
    check(index.none { it.optString("path").equals(target, ignoreCase = true) }) { "That filename is already indexed in this folder" }
    check(repo.request("POST", "/api/v1/library/explorer/file-tree/refresh") == true) { "Couldn't refresh the folder before renaming" }
    val root = (repo.request("GET", "/api/v1/library/explorer/file-tree") as? JSONObject)?.optJSONObject("root")
        ?: error("Couldn't inspect the folder before renaming")
    fun contains(node: JSONObject): Boolean = node.optString("path").equals(target, ignoreCase = true) || node.objects("children").any(::contains)
    check(!contains(root)) { "That filename already exists in this folder" }
    // The existing API has no conditional revision/no-clobber option; it cannot close the final check-to-write race.
    val payload = JSONObject().put("files", JSONArray().put(JSONObject().put("path", path).put("newName", newName)))
    check(repo.request("PATCH", "/api/v1/library/local-files/super-update", payload) == true) { "The server did not confirm the file rename" }
}
