package app.seanime.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.seanime.tv.data.*
import app.seanime.tv.platform.NativeUnmatchedFile
import org.json.JSONObject

/** Native file matching, explorer and scan results. Mutations preserve complete server file metadata. */
@Composable
internal fun LibraryTools(repo: SeanimeRepository, onPlay: (PlaybackRequest) -> Unit, initialTab: String = "Files", initialDirectory: String = "", onClose: () -> Unit) {
    val action = rememberFeatureAction()
    val libraryListState = rememberLazyListState()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val entryFocus = remember { FocusRequester() }
    val entryFocusGranted = remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    var unmatchedOnly by rememberSaveable { mutableStateOf(false) }
    var directory by rememberSaveable { mutableStateOf(initialDirectory) }
    ReportNativePluginScreen(NativeScreenLocation(when (tab) { "Schedule" -> "/schedule"; "Scan reports" -> "/scan-summaries"; "Explorer" -> "/native/library-explorer"; else -> "/native/library-files" },
        if (tab == "Explorer" && directory.isNotBlank()) mapOf("directory" to directory) else emptyMap()), priority = 2)
    var files by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var tree by remember { mutableStateOf<JSONObject?>(null) }
    var reports by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var schedule by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var edit by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    var renaming by remember { mutableStateOf<JSONObject?>(null) }
    var selected by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var bulkDialog by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var editingFilter by remember { mutableStateOf(false) }
    var returningTo by rememberSaveable { mutableStateOf("") }
    var returningFromPlayer by rememberSaveable { mutableStateOf(false) }
    val returnFocus = remember { FocusRequester() }
    val returnGranted = remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (returningFromPlayer) { returnGranted.value = false; returningFromPlayer = false }
    }
    var scan by remember { mutableStateOf(false) }
    suspend fun reload(refreshTree: Boolean = false) {
        loaded = false
        when (tab) {
            "Files" -> files = repo.request("GET", "/api/v1/library/local-files").jsonObjects()
            "Explorer" -> {
                if (refreshTree) repo.request("POST", "/api/v1/library/explorer/file-tree/refresh")
                tree = (repo.request("GET", "/api/v1/library/explorer/file-tree") as? JSONObject)?.optJSONObject("root")
            }
            "Scan reports" -> reports = repo.request("GET", "/api/v1/library/scan-summaries").jsonObjects().asReversed()
            "Schedule" -> schedule = repo.request("GET", "/api/v1/library/schedule").jsonObjects()
        }
        loaded = true
    }
    fun upDirectory() {
        val target = directory
        fun parent(node: JSONObject): JSONObject? {
            if (node.objects("children").any { it.optString("path") == target }) return node
            return node.objects("children").firstNotNullOfOrNull(::parent)
        }
        directory = tree?.let(::parent)?.optString("path")?.takeUnless { it == "." }.orEmpty()
        returningTo = "folder:$target"; returnGranted.value = false
    }
    fun goBack() {
        if (selected.isNotEmpty()) { selected = emptyMap(); returningTo = "refresh"; returnGranted.value = false }
        else if (directory.isNotEmpty() && tab == "Explorer") upDirectory() else onClose()
    }
    fun toggleSelection(file: JSONObject) {
        val path = file.optString("path")
        if (loaded && !action.busy && path.isNotBlank()) selected = if (path in selected) selected - path else selected + (path to JSONObject(file.toString()))
    }
    BackHandler(onBack = ::goBack)
    LaunchedEffect(tab) { action.run { reload() } }
    LaunchedEffect(returningTo, returnGranted.value, action.busy, directory, bulkDialog, renaming, edit, deleting, editingFilter) {
        if (!returnGranted.value && !action.busy && !bulkDialog && renaming == null && edit == null && deleting == null && !editingFilter) {
            when {
                returningTo == "refresh" -> libraryListState.scrollToItem(0)
                returningTo == "selection" && selected.isNotEmpty() -> libraryListState.scrollToItem(3)
                returningTo.startsWith("folder:") && tab == "Explorer" -> {
                    val index = nativeLibraryFindNode(tree, directory).objects("children").indexOfFirst { it.optString("path") == returningTo.removePrefix("folder:") }
                    if (index >= 0) libraryListState.scrollToItem(4 + (if (selected.isNotEmpty()) 1 else 0) + (if (directory.isNotBlank()) 1 else 0) + index)
                }
            }
        }
    }
    FeaturePage("Library tools", "Scan, inspect and correct your media without leaving the TV", action, state = libraryListState) {
        item { ActionRow {
            listOf("Files", "Explorer", "Scan reports", "Schedule").forEach { name ->
                ActionButton(if (tab == name) "✓ $name" else name, !action.busy, Modifier.testTag("library-tab-$name")
                    // TV tabs remain focusable while their action is disabled. Establish
                    // a visible target immediately; later reads must not reclaim focus.
                    .then(if (tab == name) Modifier.initialTvFocus(entryFocus, entryFocusGranted) else Modifier)) {
                    if (tab != name) { selected = emptyMap(); loaded = false; tab = name; directory = "" }
                }
            }
            ActionButton("Back", onClick = ::goBack)
            NativePluginActions(repo, listOf(NativePluginActionKind.ANIME_LIBRARY), label = "Plugin actions")
        } }
        item { ActionRow {
            ActionButton("Refresh", !action.busy, Modifier.testTag("library-tools-refresh")
                .then(if (returningTo == "refresh" && !action.busy) Modifier.initialTvFocus(returnFocus, returnGranted) else Modifier)) { action.run { reload(refreshTree = true) } }
            ActionButton("Scan library", !action.busy) { scan = true }
            if (tab == "Explorer" && directory.isNotBlank()) ActionButton("Parent folder") { upDirectory() }
        } }
        if (selected.isNotEmpty()) item { FeaturePanel("${selected.size} files selected", "Selection contains exact indexed files. Back clears the selection.") {
            ActionRow {
                ActionButton("Selected file actions", loaded && !action.busy, Modifier.testTag("library-selected-actions")
                    .then(if (returningTo == "selection" && !action.busy) Modifier.initialTvFocus(returnFocus, returnGranted) else Modifier)) { bulkDialog = true }
                ActionButton("Clear selection", !action.busy, Modifier.testTag("library-clear-selection")) { selected = emptyMap(); returningTo = "refresh"; returnGranted.value = false }
            }
        } }
        when (tab) {
            "Files" -> {
                item { ActionButton("Filter files: ${filter.ifBlank { "All" }}", modifier = Modifier.testTag("library-tools-filter")
                    .then(if (returningTo == "filter") Modifier.initialTvFocus(returnFocus, returnGranted) else Modifier)) { editingFilter = true } }
                item { ActionButton(if (unmatchedOnly) "Show all files" else "Only unmatched") { unmatchedOnly = !unmatchedOnly } }
                val visible = files.filter { (!unmatchedOnly || it.optLong("mediaId") == 0L) && (filter.isBlank() || it.optString("path").contains(filter, true)) }
                if (visible.isEmpty() && !action.busy) item { EmptyFeature("No matching files", "Connect a media folder in Settings, then run a scan") }
                items(visible, key = { it.optString("path") }) { file ->
                    val path = file.optString("path")
                    LibraryFileRow(file, returnFocus, returnGranted, returningTo, loaded && !action.busy, path in selected,
                        select = { toggleSelection(file) }, rename = { returningTo = "rename:$path"; renaming = JSONObject(file.toString()) },
                        edit = { returningTo = "edit:$path"; edit = JSONObject(file.toString()) },
                        delete = { returningTo = "delete:$path"; deleting = JSONObject(file.toString()) }) {
                        returningTo = "play:$path"; returningFromPlayer = true; onPlay(filePlayback(file))
                    }
                }
            }
            "Explorer" -> {
                val folder = nativeLibraryFindNode(tree, directory)
                item { Text(folder?.optString("path").orEmpty().ifBlank { "Library roots" }) }
                if (directory.isNotBlank()) item { ActionRow {
                    val folderFiles = nativeLibraryFolderFiles(folder)
                    ActionButton("Select all files in this folder", loaded && !action.busy && folderFiles.isNotEmpty(), Modifier.testTag("library-current-folder-select")) {
                        selected = selected + folderFiles.associateBy { it.getString("path") }
                    }
                    ActionButton("Actions for this folder", loaded && !action.busy && folderFiles.isNotEmpty(), Modifier.testTag("library-current-folder-actions")) {
                        selected = folderFiles.associateBy { it.getString("path") }; bulkDialog = true
                    }
                } }
                val children = folder.objects("children")
                if (children.isEmpty() && !action.busy) item { EmptyFeature("This folder is empty", "Refresh after adding files or reconnecting storage") }
                items(children, key = { it.optString("path") }) { node ->
                    if (node.optString("kind") == "directory") {
                        val folderFiles = nativeLibraryFolderFiles(node)
                        val folderPath = node.optString("path")
                        FeaturePanel(node.optString("name"), "${node.optInt("matchedLocalFileCount")} matched of ${node.optInt("localFileCount")} files") {
                            ActionRow {
                                ActionButton("Open folder", !action.busy, Modifier.testTag("library-folder-open-$folderPath")
                                    .then(if (returningTo == "folder:$folderPath" && !action.busy) Modifier.initialTvFocus(returnFocus, returnGranted) else Modifier)) {
                                    directory = folderPath; returningTo = "refresh"; returnGranted.value = false
                                }
                                ActionButton(if (folderFiles.isNotEmpty() && folderFiles.all { it.optString("path") in selected }) "✓ Folder selected" else "Select folder files",
                                    loaded && !action.busy && folderFiles.isNotEmpty(), Modifier.testTag("library-folder-select-$folderPath")) {
                                    val paths = folderFiles.map { it.getString("path") }.toSet()
                                    selected = if (paths.all { it in selected }) selected - paths else selected + folderFiles.associateBy { it.getString("path") }
                                }
                                ActionButton("Folder actions", loaded && !action.busy && folderFiles.isNotEmpty(), Modifier.testTag("library-folder-actions-$folderPath")) {
                                    selected = folderFiles.associateBy { it.getString("path") }; bulkDialog = true
                                }
                            }
                        }
                    } else {
                        val file = node.optJSONObject("localFile")
                        if (file != null) {
                            val path = file.optString("path")
                            LibraryFileRow(file, returnFocus, returnGranted, returningTo, loaded && !action.busy, path in selected,
                                select = { toggleSelection(file) }, rename = { returningTo = "rename:$path"; renaming = JSONObject(file.toString()) },
                                edit = { returningTo = "edit:$path"; edit = JSONObject(file.toString()) },
                                delete = { returningTo = "delete:$path"; deleting = JSONObject(file.toString()) }) {
                                returningTo = "play:$path"; returningFromPlayer = true; onPlay(filePlayback(file))
                            }
                        }
                        else FeaturePanel(node.optString("name"), "Not indexed. Run a library scan to identify this file.")
                    }
                }
            }
            "Scan reports" -> {
                if (reports.isEmpty() && !action.busy) item { EmptyFeature("No scan reports", "Run a scan to see matched and unmatched files") }
                reports.forEach { report ->
                    val summary = report.optJSONObject("scanSummary") ?: JSONObject()
                    item { Text(report.optString("createdAt")) }
                    summary.objects("groups").forEach { group ->
                        item { FeaturePanel(group.optString("mediaTitle", "Unknown title"), "${group.objects("files").size} files · ${if (group.optBoolean("mediaIsInCollection")) "In your list" else "Not in your list"}") {
                            if (!group.optBoolean("mediaIsInCollection")) ActionButton("Add to planning list", !action.busy) {
                                action.run("Added to planning list") { repo.editListEntry(group.optLong("mediaId"), "PLANNING", 0) }
                            }
                        } }
                    }
                    summary.objects("unmatchedFiles").forEach { result ->
                        result.optJSONObject("localFile")?.let { file -> item {
                            FeaturePanel(file.optString("name", "Unmatched file"), result.objects("logs").joinToString("\n") { it.optString("message") }) {
                                ActionButton("Match this file") { edit = JSONObject(file.toString()) }
                            }
                        } }
                    }
                }
            }
            "Schedule" -> {
                if (schedule.isEmpty() && !action.busy) item { EmptyFeature("No upcoming episodes", "Your AniList collection determines this schedule") }
                items(schedule, key = { "${it.optLong("mediaId")}-${it.optInt("episodeNumber")}" }) { airing ->
                    FeaturePanel(airing.optString("title"), "Episode ${airing.optInt("episodeNumber")} · ${airing.optString("dateTime")}")
                }
            }
        }
    }
    if (scan) ConfirmFeatureDialog("Scan your library?", "The scanner reads connected media folders and updates local metadata. Locked and ignored files will be kept.", { scan = false }) {
        action.run("Scan complete. Open Scan reports for matching results.") { repo.scanLibrary(); reload() }
    }
    if (editingFilter) TextEntryDialog("Filter files", "Filename or path", filter, allowEmpty = true,
        onDismiss = { editingFilter = false; returningTo = "filter"; returnGranted.value = false }) { filter = it }
    edit?.let { file -> FileMetadataDialog(file, repo, { edit = null; returnGranted.value = false }) { updated ->
        saveNativeFileMatch(repo, file, updated)
        action.run("File metadata saved") { reload(refreshTree = true) }
    } }
    deleting?.let { file -> NativeFileDeleteDialog(file, repo,
        onClose = { deleting = null; returnGranted.value = false }, onDeleted = {
            returningTo = "refresh"; deleting = null; returnGranted.value = false
            action.run("File deleted") { reload(refreshTree = true) }
        }) }
    if (bulkDialog) NativeLibraryBulkDialog(selected.values.toList(), repo,
        onClose = { bulkDialog = false; returningTo = "selection"; returnGranted.value = false }, onApplied = {
            bulkDialog = false; selected = emptyMap(); returningTo = "refresh"; returnGranted.value = false
            action.run("Selected files updated") { reload(refreshTree = true) }
        })
    renaming?.let { file -> NativeLibraryRenameDialog(file, repo,
        onClose = { renaming = null; returnGranted.value = false }, onRenamed = {
            renaming = null; selected = selected - file.getString("path"); returningTo = "refresh"; returnGranted.value = false
            action.run("File renamed") { reload(refreshTree = true) }
        }) }
}

private fun JSONObject?.objects(key: String): List<JSONObject> = this?.optJSONArray(key).objects()

internal fun filePlayback(file: JSONObject): PlaybackRequest {
    if (file.optLong("mediaId") <= 0L) {
        val path = file.getString("path")
        val title = file.optString("name").ifBlank { path.substringAfterLast('/') }
        return PlaybackRequest(title = title, unmatchedFile = NativeUnmatchedFile(path, title))
    }
    val metadata = file.optJSONObject("metadata") ?: JSONObject()
    val number = metadata.optInt("episode")
    val raw = JSONObject().put("episodeNumber", number).put("progressNumber", if (metadata.optString("type") == "main") number else 0)
        .put("aniDBEpisode", metadata.optString("aniDBEpisode")).put("localFile", file)
    return PlaybackRequest(file.optLong("mediaId"), Episode(number, file.optString("name"), localPath = file.optString("path"),
        aniDbEpisode = metadata.optString("aniDBEpisode"), progressNumber = raw.optInt("progressNumber"), isDownloaded = true, raw = raw), title = file.optString("name"))
}

@Composable
private fun LibraryFileRow(file: JSONObject, returnFocus: FocusRequester, returnGranted: MutableState<Boolean>, returningTo: String,
    enabled: Boolean, selected: Boolean, select: () -> Unit, rename: () -> Unit, edit: () -> Unit, delete: () -> Unit, play: () -> Unit) {
    val path = file.optString("path")
    FeaturePanel(file.optString("name", file.optString("path")), file.optString("path") + "\n" +
        (if (file.optLong("mediaId") == 0L) "Unmatched" else "Matched") + if (file.optBoolean("locked")) " · Locked" else "") {
        ActionRow {
            ActionButton(if (selected) "✓ Selected" else "Select file", enabled && path.isNotBlank(), Modifier.testTag("library-file-select-$path"), select)
            ActionButton("Edit match", enabled, modifier = Modifier.testTag("library-file-edit-$path")
                .then(if (returningTo == "edit:$path" && enabled) Modifier.initialTvFocus(returnFocus, returnGranted) else Modifier), onClick = edit)
            ActionButton("Play", enabled && path.isNotBlank(), modifier = Modifier.testTag("library-file-play-$path")
                .then(if (returningTo == "play:$path" && enabled) Modifier.initialTvFocus(returnFocus, returnGranted) else Modifier), onClick = play)
            ActionButton("Rename file", enabled, Modifier.testTag("library-file-rename-$path")
                .then(if (returningTo == "rename:$path" && enabled) Modifier.initialTvFocus(returnFocus, returnGranted) else Modifier), rename)
            ActionButton("Delete file", enabled, modifier = Modifier.testTag("library-file-delete-$path")
                .then(if (returningTo == "delete:$path" && enabled) Modifier.initialTvFocus(returnFocus, returnGranted) else Modifier), onClick = delete)
        }
    }
}

@Composable
internal fun FileMetadataDialog(file: JSONObject, repo: SeanimeRepository, onClose: () -> Unit, onSave: suspend (JSONObject) -> Unit) {
    val action = rememberFeatureAction()
    var mediaId by remember { mutableStateOf(file.optLong("mediaId").toString()) }
    var matchedTitle by remember { mutableStateOf(file.optJSONObject("parsedInfo")?.optString("title").orEmpty()) }
    var choosingTitle by remember { mutableStateOf(false) }
    var editingNumber by remember { mutableStateOf<String?>(null) }
    val titleFocus = remember { FocusRequester() }
    val titleGranted = remember { mutableStateOf(false) }
    val numberFocus = remember { FocusRequester() }
    val numberGranted = remember { mutableStateOf(true) }
    val aniDbFocus = remember { FocusRequester() }
    val aniDbGranted = remember { mutableStateOf(true) }
    val metadata = file.optJSONObject("metadata") ?: JSONObject()
    var number by remember { mutableStateOf(metadata.optInt("episode").toString()) }
    var aniDb by remember { mutableStateOf(metadata.optString("aniDBEpisode")) }
    var type by remember { mutableStateOf(metadata.optString("type").ifBlank { "main" }) }
    var locked by remember { mutableStateOf(file.optBoolean("locked")) }
    var ignored by remember { mutableStateOf(file.optBoolean("ignored")) }
    AlertDialog(onDismissRequest = { if (!action.busy) onClose() }, modifier = Modifier.testTag("file-match-dialog"), title = { Text("Edit file match") }, text = {
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(file.optString("name"))
            Text(if (mediaId.toLongOrNull() == 0L) "No anime matched" else matchedTitle.ifBlank { "Saved anime match" })
            ActionRow {
                ActionButton("Choose anime", !action.busy, Modifier.testTag("file-match-title").initialTvFocus(titleFocus, titleGranted)) { choosingTitle = true }
                ActionButton("Clear match", !action.busy) { mediaId = "0"; matchedTitle = ""; locked = false }
            }
            ActionButton("Episode number: $number", !action.busy, Modifier.testTag("file-match-number").initialTvFocus(numberFocus, numberGranted)) { editingNumber = "number" }
            ActionButton("AniDB episode: ${aniDb.ifBlank { "Not set" }}", !action.busy, Modifier.testTag("file-match-anidb").initialTvFocus(aniDbFocus, aniDbGranted)) { editingNumber = "anidb" }
            ActionRow { listOf("main" to "Episode", "special" to "Special", "nc" to "Credits").forEach { (value, label) ->
                ActionButton(if (type == value) "✓ $label" else label, !action.busy) { type = value }
            } }
            ActionButton("Lock match: ${if (locked) "On" else "Off"}", !action.busy) { locked = !locked }
            ActionButton("Ignore during scans: ${if (ignored) "On" else "Off"}", !action.busy) { ignored = !ignored }
            action.error?.let { Text(it, modifier = Modifier.testTag("file-match-error")) }
        }
    }, confirmButton = { ActionButton(if (action.busy) "Saving…" else "Save", !action.busy && mediaId.toLongOrNull() != null && number.toIntOrNull() != null, Modifier.testTag("file-match-save")) {
        val updated = JSONObject(file.toString()).put("mediaId", mediaId.toLong()).put("locked", locked).put("ignored", ignored)
            .put("metadata", JSONObject(metadata.toString()).put("episode", number.toInt()).put("aniDBEpisode", aniDb).put("type", type))
        action.run { onSave(updated); onClose() }
    } }, dismissButton = { ActionButton("Cancel", !action.busy, Modifier.testTag("file-match-cancel"), onClick = onClose) })
    if (choosingTitle) NativeMediaPickerDialog(repo, "Match this file to an anime", onDismiss = { choosingTitle = false; titleGranted.value = false }) { media ->
        mediaId = media.id.toString(); matchedTitle = media.title
    }
    editingNumber?.let { field -> TextEntryDialog(if (field == "number") "Episode number" else "AniDB episode", "Value",
        if (field == "number") number else aniDb, helper = if (field == "number") "Use 0 for an unknown episode." else "Use the matching episode identifier, such as 1 or S1.",
        allowEmpty = field == "anidb", keyboardType = if (field == "number") KeyboardType.Number else KeyboardType.Text,
        inputModifier = Modifier.testTag("file-match-editor-$field"), onDismiss = {
            editingNumber = null
            if (field == "number") numberGranted.value = false else aniDbGranted.value = false
        }) { value ->
        if (field == "number") {
            if (type == "main" && aniDb == number) aniDb = value
            number = value
        } else aniDb = value
    } }
}

@Composable
internal fun NativeFileDeleteDialog(file: JSONObject, repo: SeanimeRepository, onClose: () -> Unit, onDeleted: suspend () -> Unit) {
    val action = rememberFeatureAction()
    val cancelFocus = remember { FocusRequester() }
    val cancelGranted = remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!action.busy) onClose() }, modifier = Modifier.testTag("file-delete-dialog"),
        title = { Text("Permanently delete this media file?") }, text = {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(file.optString("name"))
                Text(file.optString("path"))
                Text("This deletes the file from its storage device and removes it from Seanime's index. It cannot be undone here. Your AniList entry stays in your list.")
                action.error?.let { Text(it, modifier = Modifier.testTag("file-delete-error")) }
            }
        }, confirmButton = { ActionButton(if (action.busy) "Deleting…" else "Delete file", !action.busy, Modifier.testTag("file-delete-confirm")) {
            action.run { deleteNativeLibraryFile(repo, file.getString("path")); onDeleted() }
        } }, dismissButton = { ActionButton("Cancel", !action.busy, Modifier.testTag("file-delete-cancel").initialTvFocus(cancelFocus, cancelGranted), onClose) })
}
