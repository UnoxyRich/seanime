package app.seanime.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.data.*
import java.util.Locale

internal fun nativeTorrentBytes(value: Long): String {
    val size = value.coerceAtLeast(0).toDouble()
    return when {
        size >= 1_073_741_824 -> String.format(Locale.ROOT, "%.1f GiB", size / 1_073_741_824)
        size >= 1_048_576 -> String.format(Locale.ROOT, "%.1f MiB", size / 1_048_576)
        size >= 1024 -> String.format(Locale.ROOT, "%.1f KiB", size / 1024)
        else -> "${value.coerceAtLeast(0)} B"
    }
}

/** A source-bound native inspector. File indexes are never substituted for file identity. */
@Composable
internal fun NativeTorrentDetailsScreen(repo: SeanimeRepository, selected: DownloadItem, onBack: () -> Unit) {
    val action = rememberFeatureAction()
    var details by remember(repo, selected.id) { mutableStateOf<NativeTorrentDetails?>(null) }
    var tab by rememberSaveable(selected.id) { mutableStateOf("general") }
    var selectedFile by remember { mutableStateOf<NativeTorrentFile?>(null) }
    var addingTracker by remember { mutableStateOf(false) }
    var trackerDraft by rememberSaveable(selected.id) { mutableStateOf("") }
    var removingTracker by remember { mutableStateOf<String?>(null) }
    var failedCommand by remember { mutableStateOf<NativeTorrentCommand?>(null) }
    var opener by remember { mutableStateOf("back") }
    val returnFocus = remember { FocusRequester() }
    val returnGranted = remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    fun restore() { returnGranted.value = false }
    @Composable fun target(id: String): Modifier = Modifier.testTag("torrent-detail-$id")
        .then(if (opener == id && !action.busy) Modifier.initialTvFocus(returnFocus, returnGranted) else Modifier)
    suspend fun load() {
        requireNativeTorrentClient(repo)
        details = repo.builtInTorrentDetails(selected.id)
    }
    fun execute(command: NativeTorrentCommand) {
        val reviewed = details ?: return
        failedCommand = command
        action.run {
            val result = applyNativeTorrentCommand(repo, reviewed, command)
            details = result.details; action.message = result.message; failedCommand = null
            if (command is NativeTorrentCommand.RemoveTracker) { opener = "add-tracker"; listState.scrollToItem(1) }
            if (command is NativeTorrentCommand.Queue && command.up && result.details.queueIndex == 0) opener = "force"
            restore()
        }
    }
    BackHandler(onBack = onBack)
    ReportNativePluginScreen(NativeScreenLocation("/torrent-client"), priority = 2)
    LaunchedEffect(repo, selected.id) { action.run { load() } }
    FeaturePage(details?.name ?: selected.name, "Built-in torrent details", action, listState) {
        item { ActionRow {
            ActionButton("Downloads", modifier = target("back"), onClick = onBack)
            ActionButton(if (details == null && action.error != null) "Retry details" else "Refresh", !action.busy, target("refresh")) {
                opener = "refresh"; action.run { load(); restore() }
            }
            listOf("general" to "Overview", "files" to "Files", "trackers" to "Trackers", "peers" to "Peers").forEach { (id, label) ->
                ActionButton((if (tab == id) "✓ " else "") + label, !action.busy, target("tab-$id")) { tab = id; opener = "tab-$id" }
            }
        } }
        details?.let { current -> when (tab) {
            "general" -> {
                item { FeaturePanel("Transfer", when { current.paused -> "Paused"; current.queued -> "Queued"; current.length > 0 && current.completed >= current.length -> "Complete"; else -> "Downloading" }) {
                    Text("${nativeTorrentBytes(current.completed)} of ${nativeTorrentBytes(current.length)} · Queue position ${current.queueIndex + 1}")
                    Text("Down ${nativeTorrentBytes(current.downSpeed)}/s · Up ${nativeTorrentBytes(current.upSpeed)}/s")
                    Text("Downloaded ${nativeTorrentBytes(current.downloaded)} · Uploaded ${nativeTorrentBytes(current.uploaded)}")
                    Text("${current.seeds} seeds · ${current.peers.size} connected peers")
                    Text("Folder: ${current.destination}")
                    if (current.addedAt.isNotBlank()) Text("Added: ${current.addedAt}")
                    if (current.error.isNotBlank()) Text(current.error, color = MaterialTheme.colorScheme.error)
                } }
                item { ActionRow {
                    ActionButton(if (current.forceStart) "Disable force start" else "Force start", !action.busy, target("force")) {
                        opener = "force"; execute(NativeTorrentCommand.ForceStart(!current.forceStart))
                    }
                    ActionButton("Move up queue", !action.busy && current.queueIndex > 0, target("queue-up")) { opener = "queue-up"; execute(NativeTorrentCommand.Queue(true)) }
                    ActionButton("Move down queue", !action.busy, target("queue-down")) { opener = "queue-down"; execute(NativeTorrentCommand.Queue(false)) }
                } }
                item { ActionRow {
                    ActionButton(if (current.sequential) "Sequential: On" else "Sequential: Off", !action.busy, target("sequential")) {
                        opener = "sequential"; execute(NativeTorrentCommand.Sequential(!current.sequential))
                    }
                    ActionButton("Recheck files", !action.busy && !current.paused && current.files.isNotEmpty(), target("recheck")) {
                        opener = "recheck"; execute(NativeTorrentCommand.Recheck)
                    }
                    ActionButton("Reannounce trackers", !action.busy && !current.paused, target("reannounce")) {
                        opener = "reannounce"; execute(NativeTorrentCommand.Reannounce)
                    }
                } }
                item { Text("Force start bypasses the download queue. Recheck requests background verification; refresh for updated progress and review server logs if it fails.") }
            }
            "files" -> {
                if (current.files.isEmpty()) item { EmptyFeature("No files available", "Resume the torrent and wait for its metadata, then refresh.") }
                items(current.files, key = { "file:${it.index}:${it.path}" }) { file -> FeaturePanel(file.path,
                    "${nativeTorrentBytes(file.completed)} of ${nativeTorrentBytes(file.length)}") {
                    ActionButton("Priority: ${listOf("Skip", "Normal", "High")[file.priority]}", !action.busy, target("file-${file.index}")) {
                        opener = "file-${file.index}"; selectedFile = file
                    }
                } }
            }
            "trackers" -> {
                item { ActionButton("Add tracker", !action.busy && !current.paused, target("add-tracker")) { opener = "add-tracker"; addingTracker = true } }
                if (current.paused) item { Text("Resume this torrent to add, remove or reannounce trackers.") }
                if (current.trackers.isEmpty()) item { EmptyFeature("No trackers listed", "Trackers appear after torrent metadata is available.") }
                items(current.trackers, key = { "tracker:$it" }) { tracker -> FeaturePanel(tracker) {
                    ActionButton("Remove tracker", !action.busy && !current.paused, target("tracker-${current.trackers.indexOf(tracker)}")) {
                        opener = "tracker-${current.trackers.indexOf(tracker)}"; removingTracker = tracker
                    }
                } }
            }
            "peers" -> {
                if (current.peers.isEmpty()) item { EmptyFeature("No connected peers", "Peer discovery may take time. Refresh to see current connections.") }
                items(current.peers.size, key = { "peer:$it:${current.peers[it].address}" }) { index ->
                    val peer = current.peers[index]
                    FeaturePanel(peer.address.ifBlank { "Unknown address" }, peer.client.ifBlank { "Unknown client" })
                }
            }
        } }
    }
    selectedFile?.let { file -> SettingChoiceDialog("File priority", file.priority.toString(),
        listOf(SettingChoice("0", "Skip"), SettingChoice("1", "Normal"), SettingChoice("2", "High")),
        { selectedFile = null; restore() }) { execute(NativeTorrentCommand.FilePriority(file, it.toInt())) } }
    if (addingTracker) TextEntryDialog("Add tracker", "Tracker URL", trackerDraft, maxLength = 4096,
        inputModifier = Modifier.testTag("torrent-tracker-editor"), helper = "HTTP, HTTPS and UDP tracker URLs are supported.",
        onDismiss = { addingTracker = false; restore() }) { trackerDraft = it; execute(NativeTorrentCommand.AddTracker(it)) }
    removingTracker?.let { tracker -> ConfirmFeatureDialog("Remove tracker?", tracker,
        { removingTracker = null; restore() }) { execute(NativeTorrentCommand.RemoveTracker(tracker)) } }
    val failed = failedCommand
    if (failed != null && action.error != null && !action.busy) {
        val close = remember { FocusRequester() }
        val closeGranted = remember { mutableStateOf(false) }
        val repeatable = failed !is NativeTorrentCommand.Queue && failed != NativeTorrentCommand.Recheck && failed != NativeTorrentCommand.Reannounce
        AlertDialog(onDismissRequest = { failedCommand = null; restore() }, title = { Text("Torrent action needs attention") },
            text = { Text(action.error.orEmpty(), Modifier.testTag("torrent-action-error")) },
            dismissButton = { ActionButton("Close", modifier = Modifier.testTag("torrent-action-close").initialTvFocus(close, closeGranted)) { failedCommand = null; restore() } },
            confirmButton = { ActionButton(if (repeatable) "Retry" else "Refresh details", modifier = Modifier.testTag("torrent-action-retry")) {
                if (repeatable) execute(failed) else { failedCommand = null; action.run { load(); restore() } }
            } })
    }
}

@Composable
internal fun NativeTorrentLimitsDialog(repo: SeanimeRepository, onClose: () -> Unit) {
    val action = rememberFeatureAction()
    var download by rememberSaveable { mutableStateOf("") }
    var upload by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    var opener by remember { mutableStateOf("cancel") }
    val focus = remember { FocusRequester() }
    val granted = remember { mutableStateOf(false) }
    @Composable fun target(id: String) = Modifier.testTag("torrent-limits-$id").then(if (opener == id && !action.busy) Modifier.initialTvFocus(focus, granted) else Modifier)
    fun load() = action.run {
        val settings = requireNativeTorrentClient(repo)
        download = settings.optInt("seanimeDownloadLimit").toString(); upload = settings.optInt("seanimeUploadLimit").toString(); loaded = true
    }
    LaunchedEffect(repo) { load() }
    AlertDialog(onDismissRequest = { if (!action.busy) onClose() }, title = { Text("Global session speed limits") }, text = {
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            Text("Limits apply to all built-in torrents for this server session. Initial values are saved startup defaults; current session limits cannot be read back. 0 means unlimited.")
            if (loaded) {
                ActionButton("Download: $download KB/s", !action.busy, target("download")) { opener = "download"; editing = "download" }
                ActionButton("Upload: $upload KB/s", !action.busy, target("upload")) { opener = "upload"; editing = "upload" }
            }
            action.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("torrent-limits-error")) }
            if (accepted) Text("Session limit request accepted. These values are not saved as startup defaults.", Modifier.testTag("torrent-limits-accepted"))
        }
    }, dismissButton = { ActionButton(if (accepted) "Close" else "Cancel", !action.busy, target("cancel"), onClose) },
        confirmButton = { ActionButton(if (!loaded) "Retry settings" else "Apply limits", !action.busy, target("apply")) {
            opener = "apply"
            if (!loaded) load() else action.run { applyNativeTorrentLimits(repo, download, upload); accepted = true; granted.value = false }
        } })
    editing?.let { field -> TextEntryDialog(if (field == "download") "Download limit" else "Upload limit", "KB/s", if (field == "download") download else upload,
        keyboardType = KeyboardType.Number, maxLength = 7, inputModifier = Modifier.testTag("torrent-limit-editor"),
        onDismiss = { editing = null; granted.value = false }) { if (field == "download") download = it else upload = it; accepted = false } }
}
