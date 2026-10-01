package app.seanime.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.data.Episode
import app.seanime.tv.data.MediaCard
import app.seanime.tv.data.SeanimeRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

@Stable
internal class NativePluginActionStore(val repo: SeanimeRepository) {
    private val catalog = NativePluginActionCatalog()
    private val attached = mutableMapOf<NativePluginActionKind, Int>()
    private var revision by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    var ready = false
    fun attach(kinds: List<NativePluginActionKind>) { kinds.distinct().forEach { kind ->
        val count = attached[kind] ?: 0; attached[kind] = count + 1
        if (count == 0) request(kind)
    } }
    fun detach(kinds: List<NativePluginActionKind>) { kinds.distinct().forEach { kind ->
        val count = (attached[kind] ?: 1) - 1
        if (count == 0) attached.remove(kind) else attached[kind] = count
    } }
    private fun request(kind: NativePluginActionKind, extension: String = "") {
        if (ready && repo.client.connected.value) repo.client.sendEvent("plugin", pluginEventEnvelope(extension, kind.request))
    }
    fun refresh(extension: String = "") { attached.keys.toList().forEach { request(it, extension) } }
    fun reconnect() { catalog.clear(); revision++; error = null; refresh() }
    fun receive(event: JSONObject) { if (catalog.receive(event)) revision++ }
    fun unload(id: String) { catalog.remove(id); revision++ }
    fun actions(kinds: List<NativePluginActionKind>, media: MediaCard?, episodeType: String?): List<NativePluginAction> {
        revision
        return catalog.visible(kinds, media?.let { if (it.isManga) "manga" else "anime" }, episodeType)
    }
    fun click(action: NativePluginAction, media: MediaCard?, episode: Episode?, episodeType: String?) {
        val current = catalog.current(action) ?: return
        if (current.disabled || current.loading) return
        val payload = nativePluginActionPayload(current, media?.raw, episode?.raw, episodeType)
        if (!repo.client.sendEvent("plugin", pluginEventEnvelope(current.extensionId, "action:clicked", payload)))
            error = "The plugin connection is unavailable. Reconnect and try again."
        else error = null
    }
}

private val LocalNativePluginActions = staticCompositionLocalOf<NativePluginActionStore?> { null }

@Composable
internal fun NativePluginActionProvider(repo: SeanimeRepository, content: @Composable () -> Unit) {
    val store = remember(repo) { NativePluginActionStore(repo) }
    val connected by repo.client.connected.collectAsState()
    LaunchedEffect(repo) {
        coroutineScope {
            launch(start = CoroutineStart.UNDISPATCHED) {
                repo.client.events.collectOnMain { event -> when (event.type) {
                    "plugin" -> unpackPluginEvents(event.payload).forEach(store::receive)
                    "plugin-loaded" -> store.refresh(event.payload as? String ?: "")
                    "plugin-unloaded" -> store.unload(event.payload as? String ?: "")
                } }
            }
            store.ready = true
            store.refresh()
        }
    }
    LaunchedEffect(connected) { if (connected && store.ready) store.reconnect() }
    CompositionLocalProvider(LocalNativePluginActions provides store, content = content)
}

/** Native menu for page buttons and contextual actions; server styling is intentionally not interpreted. */
@Composable
internal fun NativePluginActions(repo: SeanimeRepository, families: List<NativePluginActionKind>, media: MediaCard? = null,
    episode: Episode? = null, episodeType: String? = null, label: String = "Plugin actions") {
    val store = LocalNativePluginActions.current ?: return
    val connected by repo.client.connected.collectAsState()
    DisposableEffect(store, families) { store.attach(families); onDispose { store.detach(families) } }
    val actions = store.actions(families, media, episodeType)
    var open by remember { mutableStateOf(false) }
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(actions.isNotEmpty()) { if (actions.isNotEmpty()) appeared = true }
    if (actions.isEmpty() && !appeared && !open) return
    val triggerFocus = remember { FocusRequester() }
    val triggerGranted = remember { mutableStateOf(true) }
    ActionButton(label, modifier = Modifier.testTag("plugin-actions-${families.first().name}-${episode?.number ?: media?.id ?: 0}")
        .initialTvFocus(triggerFocus, triggerGranted)) { open = true }
    if (open) {
        val closeFocus = remember { FocusRequester() }
        val closeGranted = remember { mutableStateOf(false) }
        var focusedAction by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(actions.map { Triple(it.key, it.disabled, it.loading) }) {
            if (focusedAction != null && actions.none { it.key == focusedAction && !it.disabled && !it.loading }) closeGranted.value = false
        }
        fun close() { open = false; triggerGranted.value = false }
        Dialog(onDismissRequest = ::close) {
            Column(Modifier.widthIn(min = 420.dp, max = 650.dp).heightIn(max = 450.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(episode?.let { "Episode ${it.number} · Plugin actions" } ?: media?.title ?: label, style = MaterialTheme.typography.titleLarge)
                store.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.weight(1f, fill = false).testTag("plugin-action-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (actions.isEmpty()) item { Text("No plugin actions are currently available for this item.") }
                    items(actions, key = { it.key }) { action ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(action.extensionId, style = MaterialTheme.typography.labelSmall)
                            ActionButton(if (action.loading) "${action.label}…" else action.label,
                                !action.disabled && !action.loading && connected,
                                Modifier.fillMaxWidth().testTag("plugin-action-${action.extensionId}-${action.id}")
                                    .onFocusChanged { if (it.isFocused) focusedAction = action.key }) {
                                store.click(action, media, episode, episodeType)
                            }
                            action.raw.text("tooltipText").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                ActionButton("Close", modifier = Modifier.testTag("plugin-action-close").initialTvFocus(closeFocus, closeGranted), onClick = ::close)
            }
        }
    }
}
