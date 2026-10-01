package app.seanime.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import app.seanime.tv.data.NativeMangaFilterEdit
import app.seanime.tv.data.NativeMangaSourceFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NativeMangaFilterDialog(field: String, current: NativeMangaSourceFilter, values: List<String>,
    onDismiss: () -> Unit, onSave: suspend (NativeMangaFilterEdit) -> Unit) {
    val language = field == "language"
    var selected by remember { mutableStateOf(if (language) listOf(current.language).filter(String::isNotBlank) else current.scanlators) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    val firstGranted = remember { mutableStateOf(false) }
    val saveFocus = remember { FocusRequester() }
    val saveGranted = remember { mutableStateOf(true) }
    val choices = listOf("") + values.distinct().sorted()
    val prefix = "manga-filter-$field"
    Dialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Column(Modifier.widthIn(min = 420.dp, max = 640.dp).heightIn(max = 480.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp)).padding(24.dp).testTag(prefix),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (language) "Chapter language" else "Chapter scanlators", style = MaterialTheme.typography.titleLarge)
            Text(if (language) "Choose a language from this source's chapters." else "Choose one or more groups, or include all scanlators.")
            if (selected.any { it !in values }) Text("Saved selection: ${selected.filter { it !in values }.joinToString()}. Choose All to clear it.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("$prefix-error")) }
            LazyColumn(Modifier.weight(1f, fill = false).testTag("$prefix-choices"), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(choices, key = { _, value -> value }) { index, value ->
                    val chosen = if (value.isEmpty()) selected.isEmpty() else value in selected
                    Button(enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("$prefix-choice-$index")
                        .then(if (index == 0 && !saving) Modifier.initialTvFocus(firstFocus, firstGranted) else Modifier), onClick = {
                        selected = when { value.isEmpty() -> emptyList(); language -> listOf(value); value in selected -> selected - value; else -> selected + value }
                        error = null
                    }) {
                        Text((if (chosen) "✓ " else "") + value.ifEmpty { if (language) "All languages" else "All scanlators" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            ActionRow {
                ActionButton(if (saving) "Saving…" else "Save", !saving, Modifier.testTag("$prefix-save")
                    .then(if (!saving) Modifier.initialTvFocus(saveFocus, saveGranted) else Modifier)) {
                    saving = true; error = null
                    scope.launch {
                        try {
                            val edit = if (language) NativeMangaFilterEdit.Language(selected.firstOrNull().orEmpty()) else NativeMangaFilterEdit.Scanlators(selected)
                            onSave(edit); onDismiss()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Couldn't save chapter filters" }
                        finally { saving = false; saveGranted.value = false }
                    }
                }
                ActionButton("Cancel", !saving, Modifier.testTag("$prefix-cancel"), onDismiss)
            }
        }
    }
}
