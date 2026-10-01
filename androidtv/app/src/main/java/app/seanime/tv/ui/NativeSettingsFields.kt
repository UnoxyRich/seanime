package app.seanime.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import org.json.JSONArray

@Composable
internal fun SettingChoiceDialog(title: String, current: String, choices: List<SettingChoice>, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    val firstFocusGranted = remember { mutableStateOf(false) }
    val selectedIndex = choices.indexOfFirst { it.value == current }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(min = 380.dp, max = 620.dp).heightIn(max = 480.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.weight(1f, fill = false), state = listState, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(choices) { index, choice ->
                    Button(onClick = { onSelect(choice.value); onDismiss() },
                        modifier = if (index == selectedIndex) Modifier.initialTvFocus(firstFocus, firstFocusGranted) else Modifier) {
                        Text((if (choice.value == current) "✓ " else "") + choice.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            ActionButton("Cancel", onClick = onDismiss)
        }
    }
}

@Composable
internal fun SettingListDialog(field: String, current: JSONArray, onDismiss: () -> Unit, onSave: (JSONArray) -> Unit) {
    var entries by remember { mutableStateOf((0 until current.length()).map { current.opt(it).toString() }) }
    var editing by remember { mutableIntStateOf(-2) }
    var error by remember { mutableStateOf<String?>(null) }
    val addFocus = remember { FocusRequester() }
    val addFocusGranted = remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(min = 440.dp, max = 680.dp).heightIn(max = 500.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(settingLabel(field), style = MaterialTheme.typography.titleLarge)
            Text(if (field == "libraryPaths") "Use the folder picker in Device & accounts to grant new Android storage access. Removing a path here does not delete files." else "AniList media IDs hidden from Nakama peers. This does not remove titles from your library.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ActionButton("Add entry", modifier = Modifier.initialTvFocus(addFocus, addFocusGranted)) { editing = -1 }
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (entries.isEmpty()) item { Text("No entries") }
                itemsIndexed(entries) { index, entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry)
                        ActionRow {
                            ActionButton("Edit ${index + 1}") { editing = index }
                            ActionButton("Remove ${index + 1}") {
                                // Keep a stable remote target when the focused row disappears.
                                addFocus.requestFocus()
                                entries = entries.filterIndexed { i, _ -> i != index }
                            }
                        }
                    }
                }
            }
            ActionRow {
                ActionButton("Save list") {
                    runCatching { settingListValue(field, entries) }.onSuccess { onSave(it); onDismiss() }.onFailure { error = it.message }
                }
                ActionButton("Cancel", onClick = onDismiss)
            }
        }
    }
    if (editing >= -1) TextEntryDialog(if (editing == -1) "Add entry" else "Edit entry", if (field == "libraryPaths") "Folder path" else "AniList media ID", entries.getOrNull(editing).orEmpty(), onDismiss = { editing = -2 }) { value ->
        runCatching { settingListValue(field, listOf(value)) }.onSuccess {
            entries = if (editing == -1) entries + value else entries.mapIndexed { index, old -> if (index == editing) value else old }
            error = null
        }.onFailure { error = it.message }
    }
}
