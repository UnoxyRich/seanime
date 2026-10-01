package app.seanime.tv.ui

import androidx.compose.foundation.background
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
import app.seanime.tv.data.SeanimeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class NativeDownloadFolder(val path: String, val label: String)

internal fun nativeDownloadFolders(settings: JSONObject): List<NativeDownloadFolder> {
    val library = settings.optJSONObject("library") ?: return emptyList()
    val primary = library.text("libraryPath")
    val other = library.optJSONArray("libraryPaths").let { array ->
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }
    }
    return (listOf(primary) + other).filter { it.isNotBlank() && it != "null" }.distinct().map { path ->
        NativeDownloadFolder(path, (if (path == primary) "Main library" else "Library folder") + " · " + path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\'))
    }
}

/** Shared native destination presentation for release downloads and existing debrid transfers. */
@Composable
internal fun NativeDownloadDestinationDialog(repo: SeanimeRepository, title: String, itemName: String, tagPrefix: String,
    onClose: () -> Unit, onDownload: suspend (String) -> Unit, onRequested: () -> Unit,
    confirmLabel: String = "Download here", confirmation: ((String) -> String)? = null,
    options: @Composable (busy: Boolean) -> Unit = {}) {
    var folders by remember { mutableStateOf<List<NativeDownloadFolder>>(emptyList()) }
    var folderError by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var advancedPath by remember { mutableStateOf(false) }
    var reviewPath by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val closeFocus = remember { FocusRequester() }
    val closeGranted = remember { mutableStateOf(false) }
    LaunchedEffect(repo, refresh) {
        try {
            folders = nativeDownloadFolders(repo.settings())
            folderError = null
            if (path.isBlank()) path = folders.firstOrNull()?.path.orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { folderError = failure.message ?: "Couldn't load library folders" }
        finally { if (path.isBlank()) loading = false }
    }
    LaunchedEffect(repo, path, refresh) {
        directory = null; error = null
        if (path.isBlank()) return@LaunchedEffect
        loading = true
        try {
            directory = repo.request("POST", "/api/v1/directory-selector", JSONObject().put("input", path)) as? JSONObject
                ?: throw IllegalStateException("The server did not return this folder")
            if (directory?.optBoolean("exists") != true) error = "This folder does not exist. Choose an available folder."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Couldn't open this folder" }
        finally { loading = false }
    }
    val selectedPath = directory?.text("fullPath").orEmpty()
    fun request(path: String) {
        busy = true; error = null
        scope.launch {
            try { onDownload(path); onRequested() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Couldn't complete this request. Try again." }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onClose() }) {
        Column(Modifier.widthIn(min = 440.dp, max = 700.dp).heightIn(max = 460.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(itemName, maxLines = 2)
            options(busy)
            LazyColumn(Modifier.weight(1f).testTag("${tagPrefix}-folders"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                folderError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                if (folders.isEmpty() && !loading) item { Text("No library folders are configured. Add one in Settings or enter an advanced server path.") }
                items(folders, key = { "root:${it.path}" }) { folder ->
                    ActionButton((if (path == folder.path) "✓ " else "") + folder.label, !busy,
                        Modifier.fillMaxWidth().testTag("${tagPrefix}-root-${folders.indexOf(folder)}")) { path = folder.path }
                }
                item { Text(path.ifBlank { "Choose a folder" }) }
                if (loading) item { Text("Loading folders…") }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                directory?.text("basePath")?.takeIf { it.isNotBlank() && it != selectedPath }?.let { parent ->
                    item { ActionButton("Parent folder", !busy && !loading, Modifier.testTag("${tagPrefix}-parent")) { path = parent } }
                }
                items(directory?.optJSONArray("content").uiObjects().filter { it.text("fullPath").isNotBlank() }.distinctBy { it.text("fullPath") },
                    key = { "child:${it.text("fullPath")}" }) { child ->
                    ActionButton("Open " + child.text("folderName", "Folder"), !busy && !loading,
                        Modifier.fillMaxWidth().testTag("${tagPrefix}-child-${child.text("folderName")}")) { path = child.text("fullPath") }
                }
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Refresh folders", !busy, Modifier.testTag("${tagPrefix}-refresh")) { refresh++ }
                    ActionButton("Advanced path", !busy, Modifier.testTag("${tagPrefix}-advanced")) { advancedPath = true }
                } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(if (busy) "Requesting…" else confirmLabel, !busy && !loading && selectedPath.isNotBlank() && directory?.optBoolean("exists") == true,
                    Modifier.testTag("${tagPrefix}-confirm")) {
                    if (confirmation == null) request(selectedPath) else reviewPath = selectedPath
                }
                ActionButton("Cancel", !busy, Modifier.testTag("${tagPrefix}-cancel").initialTvFocus(closeFocus, closeGranted), onClose)
            }
        }
    }
    reviewPath?.let { target -> ConfirmFeatureDialog("Confirm file move", confirmation?.invoke(target).orEmpty(), { reviewPath = null }) { request(target) } }
    if (advancedPath) TextEntryDialog("Advanced server folder", "Existing folder path", path, helper = "Use a folder accessible to the Seanime server.", onDismiss = { advancedPath = false; closeGranted.value = false }) {
        path = it.trim(); advancedPath = false; closeGranted.value = false
    }
}
