package app.seanime.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.data.MediaCard
import app.seanime.tv.data.SeanimeRepository
import org.json.JSONObject

@Composable
internal fun NativeLibraryBulkDialog(files: List<JSONObject>, repo: SeanimeRepository, onClose: () -> Unit, onApplied: () -> Unit) {
    val action = rememberFeatureAction()
    val paths = remember(files) { files.map { it.getString("path") }.toSet() }
    var selected by remember { mutableStateOf<NativeLibraryBulkAction?>(null) }
    var chosenAnime by remember { mutableStateOf<MediaCard?>(null) }
    var choosingAnime by remember { mutableStateOf(false) }
    var returningAction by remember { mutableStateOf(NativeLibraryBulkAction.MATCH) }
    val firstFocus = remember { FocusRequester() }
    val firstGranted = remember { mutableStateOf(false) }
    fun dismissConfirmation() { selected = null; action.error = null; firstGranted.value = false }
    Dialog(onDismissRequest = { if (!action.busy) onClose() }) {
        Column(Modifier.widthIn(min = 440.dp, max = 640.dp).heightIn(max = 480.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp)).padding(24.dp).testTag("library-bulk-dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Actions for ${paths.size} selected files", style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.weight(1f, fill = false).testTag("library-bulk-actions"), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(NativeLibraryBulkAction.entries) { item ->
                    ActionButton(item.label, paths.isNotEmpty() && !action.busy, Modifier.fillMaxWidth().testTag("library-bulk-${item.value}")
                        .then(if (item == returningAction && !action.busy) Modifier.initialTvFocus(firstFocus, firstGranted) else Modifier)) {
                        returningAction = item
                        action.error = null
                        if (item == NativeLibraryBulkAction.MATCH) choosingAnime = true else selected = item
                    }
                }
            }
            ActionButton("Cancel", !action.busy, Modifier.testTag("library-bulk-cancel"), onClose)
        }
    }
    if (choosingAnime) NativeMediaPickerDialog(repo, "Match ${paths.size} files to an anime",
        onDismiss = { choosingAnime = false; firstGranted.value = false }) { media -> chosenAnime = media; selected = NativeLibraryBulkAction.MATCH }
    selected?.let { operation ->
        val cancelFocus = remember { FocusRequester() }
        val cancelGranted = remember { mutableStateOf(false) }
        val applyFocus = remember { FocusRequester() }
        val applyGranted = remember { mutableStateOf(true) }
        LaunchedEffect(action.error, action.busy) { if (action.error != null && !action.busy) applyGranted.value = false }
        AlertDialog(onDismissRequest = { if (!action.busy) dismissConfirmation() }, modifier = Modifier.testTag("library-bulk-confirmation"),
            title = { Text("${operation.label}: ${paths.size} files?") }, text = {
                TvScrollableText(Modifier.fillMaxWidth().heightIn(max = 260.dp).testTag("library-bulk-confirm-body"), exitFocus = cancelFocus) {
                    if (operation == NativeLibraryBulkAction.MATCH) Text("Anime: ${chosenAnime?.title.orEmpty()}")
                    Text(operation.effect)
                    Text("The files stay in their current folders.")
                    paths.forEach { Text(it) }
                    action.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("library-bulk-error")) }
                }
            }, confirmButton = { ActionButton(if (action.busy) "Applying…" else "Apply", !action.busy, Modifier.testTag("library-bulk-apply")
                .then(if (!action.busy) Modifier.initialTvFocus(applyFocus, applyGranted) else Modifier)) {
                action.run { applyNativeLibraryBulkAction(repo, paths, operation, chosenAnime?.id?.toLong()); onApplied() }
            } }, dismissButton = { ActionButton("Cancel", !action.busy, Modifier.testTag("library-bulk-confirm-cancel").initialTvFocus(cancelFocus, cancelGranted), ::dismissConfirmation) })
    }
}

@Composable
internal fun NativeLibraryRenameDialog(file: JSONObject, repo: SeanimeRepository, onClose: () -> Unit, onRenamed: () -> Unit) {
    val path = file.getString("path")
    var name by remember(path) { mutableStateOf(path.substringAfterLast('/')) }
    var editing by remember { mutableStateOf(false) }
    val action = rememberFeatureAction()
    val editFocus = remember { FocusRequester() }
    val editGranted = remember { mutableStateOf(false) }
    val confirmFocus = remember { FocusRequester() }
    val confirmGranted = remember { mutableStateOf(true) }
    LaunchedEffect(action.error, action.busy) { if (action.error != null && !action.busy) confirmGranted.value = false }
    AlertDialog(onDismissRequest = { if (!action.busy) onClose() }, modifier = Modifier.testTag("library-rename-dialog"),
        title = { Text("Rename this media file?") }, text = {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Current: $path")
                ActionButton("New filename: $name", !action.busy, Modifier.testTag("library-rename-edit")
                    .then(if (!action.busy) Modifier.initialTvFocus(editFocus, editGranted) else Modifier)) { editing = true }
                Text("Preview: ${path.substringBeforeLast('/')}/$name", modifier = Modifier.testTag("library-rename-preview"))
                Text("The file stays in this folder and keeps its match and episode metadata. Keep the media extension.")
                action.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("library-rename-error")) }
            }
        }, confirmButton = { ActionButton(if (action.busy) "Renaming…" else "Rename file", !action.busy && name != path.substringAfterLast('/'), Modifier.testTag("library-rename-confirm")
            .then(if (!action.busy && name != path.substringAfterLast('/')) Modifier.initialTvFocus(confirmFocus, confirmGranted) else Modifier)) {
            action.run { renameNativeLibraryFile(repo, file, name); onRenamed() }
        } }, dismissButton = { ActionButton("Cancel", !action.busy, Modifier.testTag("library-rename-cancel"), onClose) })
    if (editing) TextEntryDialog("New filename", "Filename including extension", name,
        inputModifier = Modifier.testTag("library-rename-input"), onDismiss = { editing = false; editGranted.value = false }) { name = it; action.error = null }
}
