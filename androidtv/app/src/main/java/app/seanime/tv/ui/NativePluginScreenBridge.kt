package app.seanime.tv.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import app.seanime.tv.data.SeanimeRepository
import kotlin.math.roundToInt

@Stable
internal class NativePluginScreens {
    private data class Entry(val location: NativeScreenLocation, val priority: Int)
    private val entries = mutableStateMapOf<Any, Entry>()
    var notice by mutableStateOf<String?>(null)
    var navigate: ((NativePluginDestination) -> Unit)? = null
    val current: NativeScreenLocation get() = entries.values.maxByOrNull { it.priority }?.location ?: NativeScreenLocation("/")
    fun register(key: Any, location: NativeScreenLocation, priority: Int) { entries[key] = Entry(location, priority) }
    fun remove(key: Any) { entries.remove(key) }
    fun openLink(href: String) {
        val destination = nativePluginLinkDestination(href, current)
        checkNotNull(navigate) { "Native screen navigation is unavailable here" }.invoke(destination)
    }
}

internal val LocalNativePluginScreens = staticCompositionLocalOf<NativePluginScreens?> { null }

/** Scoped registrations disappear with their native screen, so Back reports the revealed screen. */
@Composable
internal fun ReportNativePluginScreen(location: NativeScreenLocation, priority: Int = 1) {
    val screens = LocalNativePluginScreens.current ?: return
    val key = remember { Any() }
    DisposableEffect(screens, location, priority) {
        screens.register(key, location, priority)
        onDispose { screens.remove(key) }
    }
}

@Composable
internal fun NativePluginScreenBridge(repo: SeanimeRepository, screens: NativePluginScreens,
    onNavigate: (NativePluginDestination) -> Unit, onReload: () -> Unit) {
    val navigate by rememberUpdatedState(onNavigate)
    val reload by rememberUpdatedState(onReload)
    var presentation by remember(repo) { mutableStateOf<NativePluginPresentationSession?>(null) }
    var clipboardRequest by remember(repo) { mutableStateOf<NativePluginClipboardRequest?>(null) }
    var clipboardFailure by remember(repo) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val view by rememberUpdatedState(LocalView.current)
    val density by rememberUpdatedState(LocalDensity.current.density)
    var viewportSize by remember { mutableStateOf(0 to 0) }
    DisposableEffect(view, density) {
        val root = view.rootView
        fun measure() { viewportSize = (root.width / density).roundToInt() to (root.height / density).roundToInt() }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> measure() }
        root.addOnLayoutChangeListener(listener)
        measure()
        onDispose { root.removeOnLayoutChangeListener(listener) }
    }
    fun closePresentation() { presentation?.state?.closed(); presentation = null }
    DisposableEffect(screens) {
        val open: (NativePluginDestination) -> Unit = { closePresentation(); navigate(it) }
        screens.navigate = open
        onDispose { if (screens.navigate === open) screens.navigate = null }
    }
    val protocol = remember(repo, screens) {
        NativePluginScreenProtocol({ screens.current }, { id, type, payload ->
            if (!repo.client.sendEvent("plugin", pluginEventEnvelope(id, type, payload))) screens.notice = "Couldn't send the current screen to the plugin. Reconnect and try again."
        }, { closePresentation(); navigate(it) }, { closePresentation(); reload() }, { screens.notice = it })
    }
    val deviceProtocol = remember(repo, screens) {
        NativePluginDeviceProtocol({
            (view.rootView.width / density).roundToInt() to (view.rootView.height / density).roundToInt()
        }, { id, type, payload ->
            if (!repo.client.sendEvent("plugin", pluginEventEnvelope(id, type, payload))) screens.notice = "Couldn't reply to the plugin. Reconnect and try again."
        }, { request ->
            if (clipboardRequest == null || clipboardRequest == request) {
                clipboardRequest = request; clipboardFailure = null
            } else clipboardFailure = "Finish this clipboard request, then retry the other plugin action."
        }, { screens.notice = it })
    }
    val connected by repo.client.connected.collectAsState()
    val current = screens.current
    LaunchedEffect(repo, protocol) {
        repo.client.events.collectOnMain { event ->
            if (event.type == "plugin") unpackPluginEvents(event.payload).forEach { pluginEvent ->
                runCatching { nativePluginPresentationRequest(pluginEvent) }.onSuccess { request ->
                    if (request == null) {
                        presentation?.state?.receive(pluginEvent)
                        if (!deviceProtocol.receive(pluginEvent)) protocol.receive(pluginEvent)
                    } else if (request.open && presentation?.target != request.target) {
                        closePresentation()
                        // Create state before the next event, without waiting for composition.
                        presentation = NativePluginPresentationSession(request.target,
                            NativePluginState(repo, request.target.extensionId, request.target.surface))
                    } else if (!request.open && presentation?.target == request.target) closePresentation()
                }.onFailure { screens.notice = it.message ?: "The plugin presentation request is invalid" }
            }
            if (event.type == "plugin-unloaded") {
                if (presentation?.target?.extensionId == (event.payload as? String)) closePresentation()
                if (clipboardRequest?.extensionId == (event.payload as? String)) clipboardRequest = null
            }
            if (event.type == "plugin-loaded") deviceProtocol.viewportChanged(event.payload as? String ?: "")
        }
    }
    LaunchedEffect(current, connected) { if (connected) protocol.changed() }
    LaunchedEffect(viewportSize, connected) { if (connected) deviceProtocol.viewportChanged() }
    LaunchedEffect(connected) { if (!connected) clipboardRequest = null }
    presentation?.let { session -> CompositionLocalProvider(LocalNativePluginScreens provides screens) {
        NativePluginPresentationDialog(repo, session, ::closePresentation)
    } }
    clipboardRequest?.let { request ->
        val cancelFocus = remember(request) { FocusRequester() }
        val cancelGranted = remember(request) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { clipboardRequest = null }, title = { Text("Copy plugin text") }, text = {
            Column {
                Text("Plugin: ${request.extensionId.ifBlank { "Unnamed plugin" }}")
                Text(request.text.take(500).ifEmpty { "Empty text (clears the clipboard)" } + if (request.text.length > 500) "…" else "",
                    maxLines = 6, overflow = TextOverflow.Ellipsis)
                clipboardFailure?.let { Text(it) }
            }
        }, dismissButton = {
            ActionButton("Cancel", modifier = Modifier.testTag("plugin-copy-cancel").initialTvFocus(cancelFocus, cancelGranted)) { clipboardRequest = null }
        }, confirmButton = {
            ActionButton("Copy", modifier = Modifier.testTag("plugin-copy-confirm")) {
                runCatching {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Seanime plugin", request.text))
                }.onSuccess { clipboardRequest = null }.onFailure { clipboardFailure = "Couldn't copy the text. Try again." }
            }
        })
    }
    if (clipboardRequest == null) screens.notice?.let { message ->
        val dismissFocus = remember { FocusRequester() }
        val dismissGranted = remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { screens.notice = null }, title = { Text("Plugin screen request") }, text = { Text(message) },
            confirmButton = { ActionButton("Close", modifier = Modifier.testTag("plugin-screen-notice-close").initialTvFocus(dismissFocus, dismissGranted)) { screens.notice = null } })
    }
}
