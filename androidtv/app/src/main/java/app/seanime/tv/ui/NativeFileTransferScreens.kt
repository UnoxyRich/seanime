package app.seanime.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import app.seanime.tv.data.*
import app.seanime.tv.platform.NativeLibraryFiles
import app.seanime.tv.platform.NativeOwnedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray

internal val LocalNativeLibraryFiles = staticCompositionLocalOf<NativeLibraryFiles?> { null }

@Composable
internal fun NativeExportDialog(repo: SeanimeRepository, kind: NativeExportKind, onClose: () -> Unit) {
    val files = LocalNativeLibraryFiles.current
    var duration by remember { mutableIntStateOf(30) }
    var prepared by remember { mutableStateOf<NativeExportFile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val initial = remember { FocusRequester() }
    val initialGranted = remember { mutableStateOf(false) }
    val saveFocus = remember { FocusRequester() }
    val saveGranted = remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.widthIn(min = 420.dp, max = 660.dp).heightIn(max = 450.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Export ${kind.label.lowercase()}", style = MaterialTheme.typography.titleLarge)
            Text(if (kind == NativeExportKind.LIBRARY_INDEX) "Save a JSON backup of file paths, matches, locks and ignored flags. Media files are not included."
                else "Save a server diagnostic profile for analysis. Review it before sharing; profiles can include server paths and runtime details.")
            if (kind == NativeExportKind.CPU) ActionRow {
                listOf(15, 30, 60, 120, 300).forEach { seconds -> ActionButton((if (duration == seconds) "✓ " else "") + "${seconds}s", !busy && prepared == null) { duration = seconds } }
            }
            if (busy) Text(if (kind == NativeExportKind.CPU) "Recording for $duration seconds, then downloading…" else "Preparing download…", Modifier.testTag("native-export-progress"))
            if (prepared != null) Text("Ready · ${prepared!!.bytes.size} bytes. Choose where to save the file.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("native-export-error")) }
            }
            ActionRow {
                if (prepared == null) ActionButton(if (error == null) "Prepare export" else "Retry export", !busy && files != null,
                    Modifier.testTag("native-export-prepare").initialTvFocus(initial, initialGranted)) {
                    busy = true; error = null
                    scope.launch {
                        try { prepared = downloadNativeExport(repo, kind, duration) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "The export could not be downloaded. Try again." }
                        finally { busy = false }
                    }
                }
                else ActionButton("Save as…", files != null, Modifier.testTag("native-export-save").initialTvFocus(saveFocus, saveGranted)) {
                    error = null
                    try { files!!.save(prepared!!) } catch (failure: Exception) { error = failure.message ?: "Couldn't open Android's save dialog. Try again." }
                }
                ActionButton(if (busy) "Cancel download" else "Close", modifier = Modifier.testTag("native-export-close"), onClick = onClose)
            }
            if (files == null) Text("File saving is unavailable in this window.")
        }
    }
}

@Composable
internal fun NativeLibraryIndexScreen(repo: SeanimeRepository, onPlatformAction: (String) -> Unit, onBack: () -> Unit) {
    val access = LocalNativeLibraryFiles.current
    val action = rememberFeatureAction()
    var roots by remember { mutableStateOf<List<NativeOwnedFile>>(emptyList()) }
    var children by remember { mutableStateOf<List<NativeOwnedFile>>(emptyList()) }
    var path by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<NativeMetadataPreview?>(null) }
    var currentCount by remember { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val exportFocus = remember { FocusRequester() }
    val exportGranted = remember { mutableStateOf(true) }
    val root = roots.firstOrNull { path == it.path || path.startsWith(it.path.trimEnd('/') + "/") }
    BackHandler(onBack = onBack)
    LaunchedEffect(access, path, refresh) { action.run {
        roots = access?.roots().orEmpty()
        currentCount = (repo.request("GET", "/api/v1/library/local-files") as? JSONArray)?.length()
        if (path.isNotBlank() && roots.none { path == it.path || path.startsWith(it.path.trimEnd('/') + "/") }) { path = ""; children = emptyList(); preview = null }
        else if (path.isNotBlank()) children = access?.list(path).orEmpty()
    } }
    FeaturePage("Library index backup", "Back up or replace scanned file metadata", action) {
        item { ActionRow {
            ActionButton("Back", onClick = onBack)
            ActionButton("Export index", !action.busy, Modifier.testTag("metadata-export").initialTvFocus(exportFocus, exportGranted)) { export = true }
            ActionButton("Refresh folders", !action.busy, Modifier.testTag("metadata-refresh")) { refresh++ }
        } }
        item { Text("Import replaces the active file index, including anime matches, episode metadata, locks and ignored flags. It does not move or delete media files. Export a backup first.") }
        item { Text("Current index: ${currentCount?.toString() ?: "unavailable"} files") }
        item { Text("Choose an existing JSON backup in a connected storage folder.") }
        if (roots.isEmpty()) item { Text("No readable storage folders are connected. Connect a folder in Settings, then refresh here.") }
        item { ActionButton("Connect storage folder", !action.busy) { onPlatformAction("storage:library-additional") } }
        items(roots, key = { "root:${it.path}" }) { folder ->
            ActionButton(folder.name, !action.busy, Modifier.fillMaxWidth().testTag("metadata-root-${roots.indexOf(folder)}")) { preview = null; children = emptyList(); path = folder.path }
        }
        if (path.isNotBlank()) item { Text(path) }
        if (root != null && path != root.path) item { ActionButton("Parent folder", !action.busy) { preview = null; children = emptyList(); path = path.substringBeforeLast('/') } }
        items(children, key = { it.path }) { child ->
            ActionButton((if (child.directory) "Open folder: " else "Preview index: ") + child.name, !action.busy,
                Modifier.fillMaxWidth().testTag("metadata-file-${child.name}")) {
                if (child.directory) { preview = null; children = emptyList(); path = child.path }
                else action.run { preview = null; preview = previewNativeMetadata(child.path, checkNotNull(access).read(child.path)) }
            }
        }
        preview?.let { selected -> item { FeaturePanel("Import preview", selected.path) {
            Text("${selected.count} files · ${selected.matched} matched · ${selected.locked} locked · ${selected.ignored} ignored")
            ActionButton("Replace index…", !action.busy, Modifier.testTag("metadata-import-review")) { confirm = true }
        } } }
    }
    if (confirm && preview != null) ConfirmFeatureDialog("Replace library index?", "Replace ${currentCount?.toString() ?: "the current"} indexed files with ${preview!!.count} entries from ${preview!!.path}. Current matches, locks and ignored flags will be replaced. Media files stay on their storage devices.", { confirm = false }) {
        val selected = preview!!
        action.run("Library index imported and verified. Refresh the Library to see the new matches.") {
            importNativeMetadata(repo, selected) { checkNotNull(access).read(it) }
            currentCount = selected.count; preview = null
        }
    }
    if (export) NativeExportDialog(repo, NativeExportKind.LIBRARY_INDEX) { export = false; exportGranted.value = false }
}

@Composable
internal fun NativeTorrentRenameDialog(repo: SeanimeRepository, torrent: DownloadItem, onClose: () -> Unit, onRenamed: () -> Unit) {
    var name by remember { mutableStateOf(torrent.name) }
    var editing by remember { mutableStateOf(true) }
    var submitted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val cancel = remember { FocusRequester() }
    val cancelGranted = remember { mutableStateOf(false) }
    if (!editing) Dialog(onDismissRequest = { if (!busy) onClose() }) {
        Column(Modifier.widthIn(min = 420.dp, max = 620.dp).heightIn(max = 450.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Rename torrent?", style = MaterialTheme.typography.titleLarge)
            Text("Change ${torrent.name} to $name? File names stay unchanged.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("torrent-rename-error")) }
            }
            ActionRow {
                ActionButton(if (busy) "Renaming…" else if (error == null) "Confirm rename" else "Retry rename", !busy,
                    Modifier.testTag("torrent-rename-confirm")) {
                    busy = true; error = null
                    scope.launch {
                        try { changeNativeTorrent(repo, torrent, name = name); onRenamed() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Couldn't rename the torrent. Try again." }
                        finally { busy = false }
                    }
                }
                ActionButton("Edit name", !busy) { editing = true }
                ActionButton("Cancel", !busy, Modifier.testTag("torrent-rename-cancel").initialTvFocus(cancel, cancelGranted), onClose)
            }
        }
    }
    if (editing) TextEntryDialog("Rename torrent", "Display name", name, helper = "Change the torrent's display name. File names stay unchanged.",
        maxLength = 255, inputModifier = Modifier.testTag("torrent-name-editor"), onDismiss = {
            editing = false
            if (!submitted) onClose()
            submitted = false; cancelGranted.value = false
        }) { name = it; error = null; submitted = true }
}
