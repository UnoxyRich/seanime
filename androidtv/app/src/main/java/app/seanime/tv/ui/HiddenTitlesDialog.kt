package app.seanime.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.data.SeanimeRepository
import org.json.JSONArray
import kotlinx.coroutines.CancellationException

@Composable
internal fun HiddenTitlesDialog(repo: SeanimeRepository, current: JSONArray, onDismiss: () -> Unit, onSave: (JSONArray) -> Unit) {
    var ids by remember { mutableStateOf((0 until current.length()).map { current.getLong(it) }.distinct()) }
    var titles by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var choosing by remember { mutableStateOf(false) }
    val addFocus = remember { FocusRequester() }
    val initialFocus = remember { mutableStateOf(false) }
    LaunchedEffect(repo) {
        try { titles = repo.library().associate { it.id to it.title } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep saved exclusions editable while the collection is unavailable. */ }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(min = 440.dp, max = 680.dp).heightIn(max = 470.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Titles hidden from Nakama", style = MaterialTheme.typography.titleLarge)
            Text("These titles stay in your library and are hidden from peers.")
            ActionButton("Add title", modifier = Modifier.initialTvFocus(addFocus, initialFocus)) { choosing = true }
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (ids.isEmpty()) item { Text("No hidden titles") }
                items(ids, key = { it }) { id ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(titles[id] ?: "Saved title $id", modifier = Modifier.weight(1f))
                        ActionButton("Remove") { addFocus.requestFocus(); ids = ids - id }
                    }
                }
            }
            ActionRow {
                ActionButton("Save list") { onSave(JSONArray(ids)); onDismiss() }
                ActionButton("Cancel", onClick = onDismiss)
            }
        }
    }
    if (choosing) NativeMediaPickerDialog(repo, "Hide a title from peers", onDismiss = { choosing = false }) { media ->
        ids = (ids + media.id).distinct(); titles = titles + (media.id to media.title)
    }
}
