package app.seanime.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import app.seanime.tv.data.ExtensionItem
import app.seanime.tv.data.SeanimeRepository
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal enum class NativePluginSurface(val prefix: String) { TRAY("tray"), COMMANDS("command-palette") }
internal data class NativePluginPresentation(val extensionId: String, val surface: NativePluginSurface)
internal data class NativePluginPresentationRequest(val target: NativePluginPresentation, val open: Boolean)
internal class NativePluginPresentationSession(val target: NativePluginPresentation, val state: NativePluginState)

/** Tray events also identify their owner in the payload; command events use their envelope. */
internal fun nativePluginPresentationRequest(event: JSONObject): NativePluginPresentationRequest? {
    val type = event.text("type")
    val surface = NativePluginSurface.entries.firstOrNull { type == "${it.prefix}:open" || type == "${it.prefix}:close" } ?: return null
    val owner = event.text("extensionId")
    val payloadOwner = if (surface == NativePluginSurface.TRAY) event.optJSONObject("payload")?.text("extensionId").orEmpty() else ""
    require(owner.isBlank() || payloadOwner.isBlank() || owner == payloadOwner) { "The plugin presentation request contains conflicting extension identities" }
    val extensionId = owner.ifBlank { payloadOwner }
    require(extensionId.isNotBlank()) { "The plugin presentation request does not identify its extension" }
    return NativePluginPresentationRequest(NativePluginPresentation(extensionId, surface), type.endsWith(":open"))
}

@Composable
internal fun NativePluginPresentationDialog(repo: SeanimeRepository, session: NativePluginPresentationSession, onClose: () -> Unit) {
    val target = session.target
    var extension by remember(target.extensionId) { mutableStateOf(ExtensionItem(target.extensionId, "Plugin · ${target.extensionId}", "plugin")) }
    LaunchedEffect(repo, target.extensionId) {
        try { repo.extensions().firstOrNull { it.id == target.extensionId }?.let { extension = it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The event's exact identity still permits its native controls. */ }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 40.dp, vertical = 28.dp).testTag("plugin-presentation")) {
            key(session) { NativePluginPanel(repo, extension, onBack = onClose, surface = target.surface, presentationState = session.state) }
        }
    }
}
