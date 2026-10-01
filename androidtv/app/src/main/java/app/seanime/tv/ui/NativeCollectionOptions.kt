package app.seanime.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.data.*

/** A compact toolbar opens two bounded native pickers instead of taking poster space. */
@Composable
internal fun NativeCollectionOptionsDialog(manga: Boolean, status: String, sort: PersonalCollectionSort,
    onStatus: (String) -> Unit, onSort: (PersonalCollectionSort) -> Unit, onRefresh: () -> Unit, onDismiss: () -> Unit,
    additionalContent: @Composable () -> Unit = {}) {
    var chooser by remember { mutableStateOf<String?>(null) }
    var returning by remember { mutableStateOf("status") }
    val focus = remember { FocusRequester() }
    val granted = remember { mutableStateOf(false) }
    fun closeChooser() { returning = chooser ?: "status"; chooser = null; granted.value = false }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(460.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Collection options", style = MaterialTheme.typography.titleLarge)
            ActionButton("Status: ${personalCollectionStatusLabel(status, manga)}", modifier = Modifier.testTag("collection-status")
                .then(if (returning == "status") Modifier.initialTvFocus(focus, granted) else Modifier)) { chooser = "status" }
            ActionButton("Sort: ${sort.label}", modifier = Modifier.testTag("collection-sort")
                .then(if (returning == "sort") Modifier.initialTvFocus(focus, granted) else Modifier)) { chooser = "sort" }
            Text("Search stays within your collection. Use Discover to browse the catalog.")
            additionalContent()
            ActionRow {
                ActionButton("Refresh collection", modifier = Modifier.testTag("collection-refresh")) { onRefresh(); onDismiss() }
                ActionButton("Back", modifier = Modifier.testTag("collection-options-back"), onClick = onDismiss)
            }
        }
    }
    when (chooser) {
        "status" -> SettingChoiceDialog("List status", status,
            personalCollectionStatuses.map { SettingChoice(it, personalCollectionStatusLabel(it, manga)) }, ::closeChooser, onStatus)
        "sort" -> SettingChoiceDialog("Collection sort", sort.name,
            personalCollectionSorts(manga).map { SettingChoice(it.name, it.label) }, ::closeChooser) { onSort(PersonalCollectionSort.valueOf(it)) }
    }
}
