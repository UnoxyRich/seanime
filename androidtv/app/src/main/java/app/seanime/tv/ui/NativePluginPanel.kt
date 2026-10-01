package app.seanime.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.data.ExtensionItem
import app.seanime.tv.data.SeanimeRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Declarative Go plugin UI rendered with native TV controls, never an HTML or script renderer. */
@Stable
internal class NativePluginState(val repo: SeanimeRepository, val extensionId: String, val surface: NativePluginSurface?) {
    var components by mutableStateOf<Any?>(null)
    var commands by mutableStateOf<List<JSONObject>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var status by mutableStateOf("Waiting for the plugin to render its controls")
    var commandInput by mutableStateOf("")
    var requestedClose by mutableStateOf(false)
    val fields = mutableStateMapOf<String, Any?>()
    val fieldRefs = mutableStateMapOf<String, Any?>()
    val announced = mutableSetOf<String>()
    val showsTray get() = surface != NativePluginSurface.COMMANDS
    val showsCommands get() = surface != NativePluginSurface.TRAY

    fun send(type: String, payload: JSONObject = JSONObject()): Boolean {
        val sent = repo.client.sendEvent("plugin", pluginEventEnvelope(extensionId, type, payload))
        if (!sent) error = "The server event connection is unavailable. Reconnect and refresh the plugin."
        return sent
    }
    fun refresh() {
        if (showsTray) send("tray:render")
        if (showsCommands) send("command-palette:render")
    }
    fun opened() {
        val prefixes = buildList { if (showsTray) add("tray"); if (showsCommands) add("command-palette") }
        prefixes.forEach { prefix -> if (prefix !in announced && send("$prefix:opened")) announced.add(prefix) }
        if (showsTray) send("tray:list-icons")
        if (showsCommands) send("command-palette:list")
        refresh()
    }
    fun closed() { announced.toList().forEach { send("$it:closed") }; announced.clear() }
    fun handler(name: String, event: JSONObject = JSONObject()) {
        if (name.isNotBlank()) send("handler:triggered", JSONObject().put("handlerName", name).put("event", event))
    }
    fun receive(event: JSONObject) {
        if (event.text("extensionId") != extensionId) return
        val payload = event.optJSONObject("payload") ?: JSONObject()
        when (event.text("type")) {
            "tray:updated" -> { components = payload.opt("components"); status = ""; error = null }
            "tray:close" -> if (showsTray) requestedClose = true
            "command-palette:close" -> if (showsCommands) requestedClose = true
            "tray:icon" -> if (!payload.optBoolean("withContent", true)) status = "This plugin has an action-only tray. Use Activate plugin to run it."
            "command-palette:updated" -> { commands = payload.optJSONArray("items").uiObjects(); if (commands.isNotEmpty()) status = "" }
            "command-palette:set-input" -> commandInput = payload.text("value")
            "command-palette:get-input" -> send("command-palette:input", JSONObject().put("value", commandInput))
            "form:reset" -> {
                val prefix = payload.text("formName") + ":"
                val field = payload.text("fieldToReset")
                fields.keys.filter { if (field.isBlank()) it.startsWith(prefix) else it == prefix + field }.forEach(fields::remove)
            }
            "form:set-values" -> payload.optJSONObject("data")?.let { values -> values.keys().forEach { fields[payload.text("formName") + ":" + it] = values.opt(it) } }
            "field-ref:set-value" -> fieldRefs[payload.text("fieldRef")] = payload.opt("value")
            "fatal-error" -> error = payload.text("error", "The plugin reported an error")
            "dom:get-viewport-size", "dom:clipboard:write", "dom:stop-observe" -> Unit // Global native device bridge owns these.
            "webview:iframe" -> status = "This plugin also requests custom HTML content. Its declarative tray controls are available here; custom HTML needs the browser client."
            else -> if (event.text("type").startsWith("dom:")) status = "This plugin requested a browser DOM operation. Native tray controls remain available; DOM manipulation requires the browser client."
        }
    }
}

@Composable
internal fun NativePluginPanel(repo: SeanimeRepository, extension: ExtensionItem, surface: NativePluginSurface? = null,
    presentationState: NativePluginState? = null, onBack: () -> Unit) {
    val state = remember(repo, extension.id, surface, presentationState) { presentationState ?: NativePluginState(repo, extension.id, surface) }
    val action = rememberFeatureAction()
    val connected by repo.client.connected.collectAsState()
    val backFocus = remember { FocusRequester() }
    val backGranted = remember { mutableStateOf(false) }
    var commandSearch by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    LaunchedEffect(state.requestedClose) { if (state.requestedClose) onBack() }
    LaunchedEffect(state) {
        coroutineScope {
            if (presentationState == null) launch(start = CoroutineStart.UNDISPATCHED) { repo.client.events.collectOnMain { event ->
                if (event.type == "plugin") unpackPluginEvents(event.payload).forEach(state::receive)
            } }
            try { repo.client.awaitEventsReady() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { withContext(Dispatchers.Main.immediate) { state.error = failure.message ?: "The plugin connection is unavailable" } }
        }
    }
    LaunchedEffect(state, connected) {
        withContext(Dispatchers.Main.immediate) { if (connected) state.opened() else state.announced.clear() }
    }
    DisposableEffect(state) { onDispose { state.closed() } }
    FeaturePage(extension.name, when (surface) { NativePluginSurface.TRAY -> "Plugin controls"; NativePluginSurface.COMMANDS -> "Plugin commands"; null -> "Plugin commands and controls" }, action) {
        item { ActionRow {
            ActionButton("Back", modifier = Modifier.testTag("plugin-panel-back").initialTvFocus(backFocus, backGranted), onClick = onBack)
            ActionButton("Refresh plugin", connected && !action.busy) { state.refresh() }
            if (state.showsTray) ActionButton("Activate plugin", connected && !action.busy) { state.send("tray:clicked") }
            if (state.showsCommands) ActionButton("Find command", connected && !action.busy) { commandSearch = true }
        } }
        if (state.status.isNotBlank()) item { Text(state.status) }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (state.showsTray) items(pluginNodes(state.components)) { node -> NativePluginNode(node, state) }
        if (state.showsCommands) items(state.commands.filter {
            val value = it.text("value", it.text("label"))
            when (it.text("filterType")) {
                "startsWith" -> value.startsWith(state.commandInput, true)
                "includes" -> value.contains(state.commandInput, true)
                else -> true
            }
        }, key = { "command:${it.text("id")}" }) { command ->
            FeaturePanel(command.text("heading", "Command")) {
                if (command.has("components")) NativePluginNode(command.opt("components"), state)
                ActionButton(command.text("label").ifBlank { command.text("value", "Run command") }, connected,
                    Modifier.testTag("plugin-command-${command.text("id")}")) {
                    state.send("command-palette:item-selected", JSONObject().put("itemId", command.text("id")))
                }
            }
        }
    }
    if (commandSearch) TextEntryDialog("Find command", "Search", state.commandInput, allowEmpty = true, onDismiss = { commandSearch = false }) {
        state.commandInput = it
        state.send("command-palette:input", JSONObject().put("value", it))
    }
}

@Composable
private fun NativePluginNode(node: Any?, state: NativePluginState, depth: Int = 0, activeTab: String? = null, onTab: ((String) -> Unit)? = null) {
    if (depth > 20) { Text("Plugin layout exceeded the supported nesting depth"); return }
    if (node is JSONArray) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { pluginNodes(node).forEach { NativePluginNode(it, state, depth + 1, activeTab, onTab) } }
        return
    }
    if (node is String || node is Number) { Text(node.toString()); return }
    val component = node as? JSONObject ?: return
    val props = component.optJSONObject("props") ?: JSONObject()
    val type = component.text("type")
    val id = component.text("id", component.text("key", type))
    key(id) {
        when (type) {
            "div", "flex", "stack", "tabs-list" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                pluginNodes(props.opt("items")).forEach { NativePluginNode(it, state, depth + 1, activeTab, onTab) }
                if (props.text("onClick").isNotBlank()) ActionButton("Activate") { state.handler(props.text("onClick")) }
            }
            "text", "p", "span", "badge", "dropdown-menu-label" -> {
                if (props.text("text").isNotBlank()) Text(props.text("text"))
                pluginNodes(props.opt("items")).forEach { NativePluginNode(it, state, depth + 1, activeTab, onTab) }
            }
            "button", "dropdown-menu-item" -> ActionButton(props.text("label").ifBlank { pluginNodeLabel(props.opt("item")) }, !props.optBoolean("disabled") && !props.optBoolean("loading")) {
                state.handler(props.text("onClick"))
            }
            "anchor", "a" -> {
                val uriHandler = LocalUriHandler.current
                val screens = LocalNativePluginScreens.current
                val href = props.text("href")
                ActionButton(nativePluginLinkLabel(props), href.isNotBlank() || props.text("onClick").isNotBlank(), Modifier.testTag("plugin-link-$id")) {
                    if (props.text("onClick").isNotBlank()) state.handler(props.text("onClick"), nativePluginLinkHandlerPayload(type, props))
                    else if (nativePluginExternalLink(href)) runCatching { uriHandler.openUri(href) }.onFailure { state.error = "No app is available to open this link" }
                    else runCatching { checkNotNull(screens) { "Native screen navigation is unavailable here" }.openLink(href) }
                        .onFailure { state.error = it.message ?: "This plugin link has no supported native destination" }
                }
            }
            "input", "select", "checkbox", "switch", "radio-group" -> NativePluginField(id, type, props, state)
            "form" -> NativePluginForm(props, state)
            "alert" -> FeaturePanel(props.text("title", "Notice"), props.text("description"))
            "tooltip" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NativePluginNode(props.opt("item"), state, depth + 1, activeTab, onTab)
                Text(props.text("text"), style = MaterialTheme.typography.bodySmall)
            }
            "img" -> NativeArtwork(url = props.text("src"), contentDescription = props.text("alt", "Plugin image"), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 240.dp), providerResult = true)
            "dropdown-menu-separator" -> Spacer(Modifier.height(12.dp))
            "tabs" -> {
                var selected by remember(id) { mutableStateOf(props.text("defaultValue")) }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    pluginNodes(props.opt("items")).forEach { NativePluginNode(it, state, depth + 1, selected) { value -> selected = value } }
                }
            }
            "tabs-trigger" -> ActionButton((if (activeTab == props.text("value")) "✓ " else "") + pluginNodeLabel(props.opt("item"))) { onTab?.invoke(props.text("value")) }
            "tabs-content" -> if (activeTab == props.text("value")) NativePluginNode(props.opt("items"), state, depth + 1, activeTab, onTab)
            "modal", "popover", "dropdown-menu" -> {
                var open by remember(id) { mutableStateOf(props.optBoolean("open")) }
                LaunchedEffect(props.optBoolean("open")) { if (type == "modal") open = props.optBoolean("open") }
                fun setOpen(value: Boolean) { open = value; state.handler(props.text("onOpenChange"), JSONObject().put("open", value)) }
                ActionButton(pluginNodeLabel(props.opt("trigger"))) { setOpen(true) }
                if (open) AlertDialog(onDismissRequest = { setOpen(false) }, title = { Text(props.text("title", "Plugin controls")) },
                    text = { Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (props.text("description").isNotBlank()) Text(props.text("description"))
                        NativePluginNode(props.opt("items"), state, depth + 1, activeTab, onTab)
                        NativePluginNode(props.opt("footer"), state, depth + 1, activeTab, onTab)
                    } }, confirmButton = { ActionButton("Close") { setOpen(false) } })
            }
            "css" -> Unit // Styling is provided by the native TV design system, never interpreted as code.
            else -> FeaturePanel("Unsupported plugin control", "The plugin requested “${type.ifBlank { "unknown" }}”. Its other supported controls remain available.")
        }
    }
}

@Composable
private fun NativePluginField(id: String, type: String, props: JSONObject, state: NativePluginState, formName: String? = null) {
    val name = props.text("name", id)
    val key = if (formName == null) "field:$id" else "$formName:$name"
    val fieldRef = props.optJSONObject("fieldRef")
    val refId = fieldRef?.text("__ID").orEmpty()
    LaunchedEffect(id, props.opt("value")) {
        if (formName == null && refId.isBlank() && props.has("value")) state.fields[key] = props.opt("value")
    }
    val value = if (refId.isNotBlank() && state.fieldRefs.containsKey(refId)) state.fieldRefs[refId]
        else state.fields[key] ?: fieldRef?.opt("current") ?: props.opt("value")?.takeUnless { it == JSONObject.NULL }
    var editing by remember { mutableStateOf(false) }
    val disabled = props.optBoolean("disabled")
    val label = props.text("label").ifBlank { name.ifBlank { "Value" } }
    fun change(next: Any) {
        state.fields[key] = next
        if (refId.isNotBlank()) {
            state.fieldRefs[refId] = next
            state.send("field-ref:send-value", JSONObject().put("fieldRef", refId).put("value", next))
        }
        if (formName == null) state.handler(props.text("onChange"), JSONObject().put("value", next))
    }
    FeaturePanel(label) {
        when (type) {
            "checkbox", "switch" -> ActionButton(if (value == true) "✓ On" else "Off", !disabled) { change(value != true) }
            "select", "radio", "radio-group" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                props.optJSONArray("options").uiObjects().forEach { option ->
                    val optionValue = option.opt("value") ?: ""
                    ActionButton((if (value == optionValue) "✓ " else "") + option.text("label", optionValue.toString()), !disabled) { change(optionValue) }
                }
            }
            else -> {
                val secret = props.text("type") == "password" || settingIsSecret(name)
                Text(if (secret && value?.toString()?.isNotBlank() == true) "••••••••" else value?.toString().orEmpty().ifBlank { props.text("placeholder", "Not set") })
                ActionButton("Edit", !disabled) { editing = true }
            }
        }
    }
    if (editing) TextEntryDialog(label, if (type == "date") "Date (YYYY-MM-DD)" else "Value", value?.toString().orEmpty(),
        secret = props.text("type") == "password" || settingIsSecret(name), multiline = props.optBoolean("textarea"), allowEmpty = type != "number", onDismiss = { editing = false }) { text ->
        if (type == "number") {
            val number = text.toDoubleOrNull()?.takeIf { it.isFinite() }
            if (number == null) state.error = "Enter a valid number for $label" else change(number)
        } else change(text)
    }
}

@Composable
private fun NativePluginForm(props: JSONObject, state: NativePluginState) {
    val name = props.text("name")
    val fields = props.optJSONArray("fields").uiObjects()
    fun submit() {
        val data = pluginFormData(name, fields, state.fields)
        state.send("form:submitted", JSONObject().put("formName", name).put("data", data))
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        fields.forEach { field ->
            key(field.text("id", field.text("name"))) {
                if (field.text("type") == "submit") ActionButton(field.text("label").ifBlank { "Submit" }, !field.optJSONObject("props").let { it?.optBoolean("disabled") ?: false }) { submit() }
                else {
                    val combined = JSONObject(field.optJSONObject("props")?.toString() ?: "{}")
                    field.keys().forEach { if (it != "props") combined.put(it, field.opt(it)) }
                    NativePluginField(field.text("id", field.text("name")), field.text("type"), combined, state, name)
                }
            }
        }
        if (fields.none { it.text("type") == "submit" }) ActionButton("Submit") { submit() }
    }
}

/** Native action-time approval for the backend extension permission prompt protocol. */
@Composable
internal fun NativeExtensionPrompts(repo: SeanimeRepository) {
    var prompts by remember(repo) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var deliveryError by remember { mutableStateOf<String?>(null) }
    val connected by repo.client.connected.collectAsState()
    LaunchedEffect(repo) {
        repo.client.events.collectOnMain { event ->
            if (event.type == "extension-prompt") (event.payload as? JSONObject)?.let { incoming ->
                val id = incoming.text("id")
                if (id.isNotBlank()) prompts = if (incoming.optBoolean("expired")) prompts.filterNot { it.text("id") == id }
                    else if (prompts.any { it.text("id") == id }) prompts else prompts + incoming
            }
        }
    }
    LaunchedEffect(connected) { if (connected) repo.client.sendEvent("extension-prompt-sync", JSONObject()) }
    val prompt = prompts.firstOrNull() ?: return
    val denyFocus = remember(prompt.text("id")) { FocusRequester() }
    val denyFocusGranted = remember(prompt.text("id")) { mutableStateOf(false) }
    fun respond(allowed: Boolean) {
        if (repo.client.sendEvent("extension-prompt-response", JSONObject().put("id", prompt.text("id")).put("allowed", allowed))) {
            prompts = prompts.drop(1)
            deliveryError = null
        } else deliveryError = "Couldn't send your response. Reconnect before trying again."
    }
    AlertDialog(onDismissRequest = { respond(false) },
        title = { Text(prompt.optJSONObject("extension")?.text("name", "Extension") ?: "Extension permission") },
        text = { TvScrollableText(Modifier.fillMaxWidth().heightIn(max = 260.dp).testTag("extension-permission-body"), exitFocus = denyFocus) {
            Text(prompt.text("message", "Allow this extension action?"))
            Text("Action: ${prompt.text("action")}\nResource: ${prompt.text("resource", "Not specified")}")
            prompt.optJSONArray("details")?.let { details -> (0 until details.length()).forEach { Text(details.optString(it)) } }
            deliveryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { ActionButton(prompt.text("allowLabel", "Allow"), connected) { respond(true) } },
        dismissButton = { ActionButton(prompt.text("denyLabel", "Don't allow"), modifier = Modifier.initialTvFocus(denyFocus, denyFocusGranted)) { respond(false) } })
}
