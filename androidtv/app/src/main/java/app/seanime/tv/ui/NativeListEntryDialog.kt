package app.seanime.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A native editor shared by anime and manga. The server's existing sparse mutation preserves unedited fields. */
@Composable
internal fun NativeListEntryDialog(repo: SeanimeRepository, media: MediaCard, onDismiss: () -> Unit, onChanged: (removed: Boolean) -> Unit) {
    var snapshot by remember(media.id, media.isManga) { mutableStateOf<NativeListEntrySnapshot?>(null) }
    var draft by remember(media.id, media.isManga) { mutableStateOf<NativeListEntryDraft?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var choosingStatus by remember { mutableStateOf(false) }
    var editingNumber by remember { mutableStateOf<String?>(null) }
    var editingDate by remember { mutableStateOf<String?>(null) }
    var confirmingRemoval by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val statusFocus = remember { FocusRequester() }
    val statusGranted = remember { mutableStateOf(false) }
    val progressFocus = remember { FocusRequester() }
    val progressGranted = remember { mutableStateOf(true) }
    val scoreFocus = remember { FocusRequester() }
    val scoreGranted = remember { mutableStateOf(true) }
    val startedFocus = remember { FocusRequester() }
    val startedGranted = remember { mutableStateOf(true) }
    val completedFocus = remember { FocusRequester() }
    val completedGranted = remember { mutableStateOf(true) }
    val cancelFocus = remember { FocusRequester() }
    val cancelGranted = remember { mutableStateOf(false) }
    LaunchedEffect(repo, media.id, media.isManga, retry) {
        loading = true; error = null
        try {
            val current = loadNativeListEntry(repo, media.id, media.isManga)
            snapshot = current; draft = NativeListEntryDraft.from(current)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Couldn't load the current list entry" }
        finally { loading = false }
    }
    fun save(remove: Boolean = false) {
        val current = snapshot ?: return
        val edited = draft ?: return
        if (saving) return
        saving = true; error = null
        scope.launch {
            try {
                if (remove) { removeNativeListEntry(repo, media.id, media.isManga); onChanged(true) }
                else if (saveNativeListEntry(repo, current, edited)) onChanged(false)
                else onDismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "The list entry could not be changed" }
            finally { saving = false; statusGranted.value = false }
        }
    }
    Dialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Column(Modifier.widthIn(min = 480.dp, max = 680.dp).heightIn(max = 480.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp)).padding(24.dp)
            .testTag("list-entry-dialog"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(media.title, style = MaterialTheme.typography.titleLarge)
            val current = snapshot
            val edited = draft
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading) Text("Loading your current list entry…")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("list-entry-error")) }
                if (!loading && current == null) ActionButton("Retry", modifier = Modifier.testTag("list-entry-retry")) { retry++ }
                if (current != null && edited != null) {
                    ActionButton("Status: ${listStatusLabel(edited.status, media.isManga)}", !saving,
                        Modifier.fillMaxWidth().testTag("list-entry-status")
                            .then(if (!saving) Modifier.initialTvFocus(statusFocus, statusGranted) else Modifier)) { choosingStatus = true }
                    ActionButton("${if (media.isManga) "Chapters read" else "Episodes watched"}: ${edited.progress}", !saving,
                        Modifier.fillMaxWidth().testTag("list-entry-progress")
                            .then(if (!saving) Modifier.initialTvFocus(progressFocus, progressGranted) else Modifier)) { editingNumber = "progress" }
                    ActionButton("Rating (0–10): ${edited.score.ifBlank { "Not rated" }}", !saving,
                        Modifier.fillMaxWidth().testTag("list-entry-score")
                            .then(if (!saving) Modifier.initialTvFocus(scoreFocus, scoreGranted) else Modifier)) { editingNumber = "score" }
                    ActionButton("Start date: ${edited.startedAt.label}", !saving,
                        Modifier.fillMaxWidth().testTag("list-entry-startedAt")
                            .then(if (!saving) Modifier.initialTvFocus(startedFocus, startedGranted) else Modifier)) { editingDate = "startedAt" }
                    ActionButton("Completion date: ${edited.completedAt.label}", !saving,
                        Modifier.fillMaxWidth().testTag("list-entry-completedAt")
                            .then(if (!saving) Modifier.initialTvFocus(completedFocus, completedGranted) else Modifier)) { editingDate = "completedAt" }
                    if (current.exists) {
                        ActionButton("Remove from list", !saving && !current.offline, Modifier.testTag("list-entry-remove")) { confirmingRemoval = true }
                        if (current.offline) Text("Go online to remove this title from your list")
                    }
                }
            }
            ActionRow {
                ActionButton(if (saving) "Saving…" else if (current?.exists == false) "Add to list" else "Save",
                    !saving && !loading && current != null && edited != null, Modifier.testTag("list-entry-save")) { save() }
                ActionButton("Cancel", !saving, Modifier.testTag("list-entry-cancel")
                    .then(if (current == null) Modifier.initialTvFocus(cancelFocus, cancelGranted) else Modifier), onDismiss)
            }
        }
    }
    if (choosingStatus) SettingChoiceDialog("List status", draft?.status.orEmpty(), nativeListStatuses.map { SettingChoice(it, listStatusLabel(it, media.isManga)) },
        onDismiss = { choosingStatus = false; statusGranted.value = false }) { selected ->
        draft = draft?.let { old -> old.copy(status = selected,
            progress = if (selected == "COMPLETED") snapshot?.media?.totalEpisodes?.toString() ?: old.progress else old.progress) }
        choosingStatus = false; statusGranted.value = false
    }
    editingNumber?.let { field ->
        val rating = field == "score"
        val title = if (rating) "Rating" else if (media.isManga) "Chapters read" else "Episodes watched"
        TextEntryDialog(title, if (rating) "Rating (0–10)" else "Progress", if (rating) draft?.score.orEmpty() else draft?.progress.orEmpty(),
            helper = if (rating) "Use one decimal place. Leave empty to remove your rating." else "Enter a nonnegative whole number.",
            allowEmpty = rating, keyboardType = if (rating) KeyboardType.Decimal else KeyboardType.Number,
            inputModifier = Modifier.testTag("list-entry-editor-$field"), onDismiss = {
                editingNumber = null
                if (rating) scoreGranted.value = false else progressGranted.value = false
            }) { value ->
            draft = draft?.let { if (rating) it.copy(score = value) else it.copy(progress = value) }
            error = null
        }
    }
    editingDate?.let { field ->
        val current = snapshot
        val edited = draft
        if (current != null && edited != null) NativeListDateDialog(field,
            initial = if (field == "startedAt") edited.startedAt else edited.completedAt,
            allowPartial = current.allowsPartialDates, onDismiss = {
                editingDate = null
                if (field == "startedAt") startedGranted.value = false else completedGranted.value = false
            }) { date ->
            draft = draft?.let { if (field == "startedAt") it.copy(startedAt = date) else it.copy(completedAt = date) }
            error = null
        }
    }
    if (confirmingRemoval) {
        val dismissFocus = remember { FocusRequester() }
        val dismissGranted = remember { mutableStateOf(false) }
        fun closeConfirmation() { confirmingRemoval = false; statusGranted.value = false }
        AlertDialog(onDismissRequest = ::closeConfirmation, modifier = Modifier.testTag("list-entry-remove-confirmation"),
            title = { Text("Remove ${media.title} from your list?") },
            text = { Text("This removes its saved status, progress and rating from your list. Your local media files and downloaded chapters stay on this TV.") },
            confirmButton = { ActionButton("Remove", modifier = Modifier.testTag("list-entry-confirm-remove")) {
                confirmingRemoval = false; save(remove = true)
            } },
            dismissButton = { ActionButton("Cancel", modifier = Modifier.testTag("list-entry-cancel-remove")
                .initialTvFocus(dismissFocus, dismissGranted), onClick = ::closeConfirmation) })
    }
}

/** A date remains a draft until Apply, and the outer list editor still owns the server mutation. */
@Composable
private fun NativeListDateDialog(field: String, initial: NativeListDate, allowPartial: Boolean,
    onDismiss: () -> Unit, onApply: (NativeListDate) -> Unit) {
    var year by remember { mutableStateOf(initial.year?.toString().orEmpty()) }
    var month by remember { mutableStateOf(initial.month) }
    var day by remember { mutableStateOf(initial.day) }
    var error by remember { mutableStateOf<String?>(null) }
    var component by remember { mutableStateOf<String?>(null) }
    val names = listOf("year", "month", "day")
    val focus = remember { List(3) { FocusRequester() } }
    val granted = remember { List(3) { mutableStateOf(it != 0) } }
    val prefix = "list-entry-date-$field"
    fun closeComponent() {
        component?.let { granted[names.indexOf(it)].value = false }
        component = null
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(min = 440.dp, max = 620.dp).heightIn(max = 480.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp)).padding(24.dp).testTag(prefix),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (field == "startedAt") "Start date" else "Completion date", style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (allowPartial) "Unknown components can stay Not set. Clear date removes the locally stored date."
                    else "AniList changes need a complete date. Existing partial dates stay unchanged unless you replace them. Clearing an AniList date is unavailable here.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("$prefix-error")) }
                names.forEachIndexed { index, name ->
                    val value = when (name) { "year" -> year.ifBlank { "Not set" }; "month" -> month?.toString() ?: "Not set"; else -> day?.toString() ?: "Not set" }
                    ActionButton("${name.replaceFirstChar(Char::uppercase)}: $value", modifier = Modifier.fillMaxWidth()
                        .testTag("$prefix-$name").initialTvFocus(focus[index], granted[index])) { component = name }
                }
                if (allowPartial) ActionButton("Clear date", modifier = Modifier.testTag("$prefix-clear")) {
                    year = ""; month = null; day = null; error = null
                }
            }
            ActionRow {
                ActionButton("Apply date", modifier = Modifier.testTag("$prefix-apply")) {
                    try {
                        val parsedYear = if (year.isBlank()) null else year.toIntOrNull() ?: throw IllegalArgumentException("Year must be between 1 and 9999")
                        val date = NativeListDate(parsedYear, month, day)
                        // An unchanged partial value is valid to preserve, including for a live account.
                        if (date != initial) validateNativeListDate(date, allowPartial)
                        onApply(date); onDismiss()
                    } catch (failure: IllegalArgumentException) { error = failure.message }
                    catch (failure: IllegalStateException) { error = failure.message }
                }
                ActionButton("Cancel", modifier = Modifier.testTag("$prefix-cancel"), onClick = onDismiss)
            }
        }
    }
    when (component) {
        "year" -> TextEntryDialog("Year", "Year (1–9999)", year, allowEmpty = allowPartial,
            helper = if (allowPartial) "Leave empty for an unknown year." else "Choose a year from 1 to 9999.",
            inputModifier = Modifier.testTag("$prefix-editor-year"), keyboardType = KeyboardType.Number,
            onDismiss = ::closeComponent) { year = it; error = null }
        "month", "day" -> {
            val name = requireNotNull(component)
            val count = if (name == "month") 12 else 31
            val choices = (if (allowPartial) listOf(SettingChoice("", "Not set")) else emptyList()) +
                (1..count).map { SettingChoice(it.toString(), "${name.replaceFirstChar(Char::uppercase)} $it") }
            SettingChoiceDialog(if (name == "month") "Month" else "Day", (if (name == "month") month else day)?.toString().orEmpty(),
                choices, onDismiss = ::closeComponent) {
                if (name == "month") month = it.toIntOrNull() else day = it.toIntOrNull()
                error = null; closeComponent()
            }
        }
    }
}

private fun listStatusLabel(status: String, manga: Boolean): String = when (status) {
    "CURRENT" -> if (manga) "Reading" else "Watching"
    "PLANNING" -> if (manga) "Plan to read" else "Plan to watch"
    "COMPLETED" -> "Completed"
    "PAUSED" -> "Paused"
    "DROPPED" -> "Dropped"
    "REPEATING" -> if (manga) "Rereading" else "Rewatching"
    else -> status
}
