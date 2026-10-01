package app.seanime.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import app.seanime.tv.data.SeanimeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Select the backend report category before preparing an archive for manual review/export. */
@Composable
internal fun NativeIssueReportDialog(repo: SeanimeRepository, onClose: () -> Unit, onCreated: () -> Unit) {
    var description by rememberSaveable { mutableStateOf("") }
    var libraryIssue by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val cancelFocus = remember { FocusRequester() }
    val cancelGranted = remember { mutableStateOf(false) }
    val editFocus = remember { FocusRequester() }
    val editGranted = remember { mutableStateOf(true) }
    Dialog(onDismissRequest = { if (!busy) onClose() }) {
        Column(Modifier.widthIn(min = 480.dp, max = 700.dp).heightIn(max = 460.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Create issue report", style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.weight(1f).testTag("report-content"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Prepare an archive on the server, then export and review it before sharing. It includes server logs and settings.") }
                item { ActionRow {
                    ActionButton((if (!libraryIssue) "✓ " else "") + "General issue", !busy, Modifier.testTag("report-general")) { libraryIssue = false }
                    ActionButton((if (libraryIssue) "✓ " else "") + "Anime library issue", !busy, Modifier.testTag("report-library")) { libraryIssue = true }
                } }
                item { Text(if (libraryIssue) "Includes scanner logs and anime library file details, including file paths." else "For playback, manga, plugins or other issues.") }
                item { Text(description.ifBlank { "Describe what happened and how to reproduce it." }, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("report-description")) }
                item { ActionButton("Edit description", !busy, Modifier.testTag("report-edit-description").initialTvFocus(editFocus, editGranted)) { editing = true } }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
            ActionRow {
                ActionButton(if (busy) "Preparing…" else "Prepare report", !busy && description.isNotBlank(), Modifier.testTag("report-prepare")) {
                    busy = true; error = null
                    scope.launch {
                        try {
                            check(repo.saveIssueReport(description, libraryIssue)) { "The server did not confirm that the report was prepared" }
                            onCreated()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Couldn't prepare the report. Try again." }
                        finally { busy = false }
                    }
                }
                ActionButton("Cancel", !busy, Modifier.testTag("report-cancel").initialTvFocus(cancelFocus, cancelGranted), onClose)
            }
        }
    }
    if (editing) TextEntryDialog("Describe the issue", "What happened?", description, multiline = true, allowEmpty = true,
        inputModifier = Modifier.testTag("report-description-editor"), onDismiss = { editing = false; editGranted.value = false }) { description = it }
}
