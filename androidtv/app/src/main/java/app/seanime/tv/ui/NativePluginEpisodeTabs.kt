package app.seanime.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.seanime.tv.data.Episode
import app.seanime.tv.data.SeanimeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Stable
internal class NativePluginEpisodeTabs(val repo: SeanimeRepository, val mediaId: Long) {
    private val visible = mutableStateMapOf<String, NativePluginEpisodeTab>()
    private val rendered = mutableStateMapOf<String, Boolean>()
    val tabs get() = visible.values.sortedWith(compareBy<NativePluginEpisodeTab> { it.extensionId }.thenBy { it.name })
    var registeredIds by mutableStateOf<Set<String>>(emptySet())
    var registryLoaded by mutableStateOf(false)
    var registryRevision by mutableIntStateOf(0)
    var active by mutableStateOf<String?>(null)
        private set
    var episodes by mutableStateOf<List<Episode>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var hasMappingError by mutableStateOf(false)
    var requestRevision by mutableIntStateOf(0)
        private set
    var ready = false
    private var opened: String? = null
    private fun send(id: String, type: String, payload: JSONObject = JSONObject()): Boolean {
        if (!repo.client.sendEvent("plugin", pluginEventEnvelope(id, type, payload))) {
            error = "The plugin connection is unavailable. Reconnect and refresh the source."
            return false
        }
        return true
    }
    fun render() {
        if (!ready || !repo.client.connected.value) return
        send("", "anime:entry-episode-tabs:render", JSONObject().put("mediaId", mediaId))
    }
    fun select(extensionId: String?) {
        if (active == extensionId) return
        active?.let { send(it, "anime:entry-episode-tab:state-changed", JSONObject().put("isOpen", false)) }
        active = extensionId; opened = null; episodes = null; error = null; message = null; hasMappingError = false; requestRevision++
        if (extensionId != null) openIfAvailable()
    }
    private fun openIfAvailable() {
        val id = active ?: return
        if (!ready || !repo.client.connected.value || opened == id) return
        if (id !in visible) {
            if (rendered[id] == true) error = "This plugin source is unavailable for this title. Choose another source."
            return
        }
        error = null; episodes = null; requestRevision++
        send(id, "anime:entry-episode-tab:state-changed", JSONObject().put("isOpen", true))
        if (send(id, "anime:entry-episode-tab:open", JSONObject().put("mediaId", mediaId))) opened = id
    }
    fun refresh() { opened = null; episodes = null; error = null; message = null; requestRevision++; render(); openIfAvailable() }
    fun reconnect() { opened = null; episodes = null; rendered.clear(); visible.clear(); requestRevision++; render() }
    fun unload(id: String) {
        visible.remove(id); rendered.remove(id); registeredIds = registeredIds - id
        if (active == id) { opened = null; episodes = null; error = "This plugin was unloaded. Choose another source." }
    }
    fun receive(event: JSONObject) {
        val id = event.text("extensionId").takeIf(String::isNotBlank) ?: return
        val payload = event.optJSONObject("payload") ?: JSONObject()
        when (event.text("type")) {
            "anime:entry-episode-tabs:updated" -> {
                rendered[id] = true
                val tab = payload.optJSONArray("tabs").uiObjects().firstOrNull { it.text("name").isNotBlank() }
                if (tab == null) visible.remove(id) else visible[id] = NativePluginEpisodeTab(id, tab.text("name"))
                if (active != id) send(id, "anime:entry-episode-tab:state-changed", JSONObject().put("isOpen", false))
                else if (tab == null) { opened = null; episodes = null; error = "This plugin source is unavailable for this title. Choose another source." }
                else openIfAvailable()
            }
            "anime:entry-episode-tab:episode-collection" -> if (active == id && opened == id) {
                runCatching {
                    val collection = requireNotNull(payload.optJSONObject("episodeCollection")) { "The plugin did not return an episode collection" }
                    nativePluginEpisodes(collection, mediaId).also { hasMappingError = collection.optBoolean("hasMappingError") }
                }.onSuccess { episodes = it; error = null }.onFailure {
                    episodes = null; hasMappingError = false; message = null
                    error = it.message ?: "The plugin returned an invalid episode collection"
                }
            }
            "fatal-error" -> if (active == id) error = payload.text("error", "The plugin source reported an error")
        }
    }
    fun selectEpisode(episode: Episode) {
        val id = active ?: return
        if (episodes?.any { it.raw === episode.raw } != true || error != null) return
        if (send(id, "anime:entry-episode-tab:select-episode", nativePluginEpisodeSelection(mediaId, episode)))
            message = "Episode ${episode.number} sent to ${visible[id]?.name ?: "the plugin"}"
    }
    fun close() { active?.let { send(it, "anime:entry-episode-tab:state-changed", JSONObject().put("isOpen", false)) } }
}

@Composable
internal fun rememberNativePluginEpisodeTabs(repo: SeanimeRepository, mediaId: Long): NativePluginEpisodeTabs {
    val state = remember(repo, mediaId) { NativePluginEpisodeTabs(repo, mediaId) }
    val connected by repo.client.connected.collectAsState()
    LaunchedEffect(state) {
        coroutineScope {
            launch(start = CoroutineStart.UNDISPATCHED) { repo.client.events.collectOnMain { event -> when (event.type) {
                "plugin" -> unpackPluginEvents(event.payload).forEach(state::receive)
                "plugin-loaded" -> { state.registryRevision++; state.render() }
                "plugin-unloaded" -> state.unload(event.payload as? String ?: "")
            } } }
            state.ready = true
            state.render()
        }
    }
    LaunchedEffect(state, state.registryRevision) {
        try {
            state.registeredIds = repo.request("GET", "/api/v1/extensions/list/anime-entry-episode-tabs").jsonObjects().map { it.text("id") }.filter(String::isNotBlank).toSet()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { state.error = "Couldn't load registered plugin sources" }
        finally { state.registryLoaded = true }
    }
    LaunchedEffect(state, connected) { if (connected && state.ready) state.reconnect() }
    LaunchedEffect(state, state.active, state.requestRevision) {
        if (state.active != null) {
            delay(20_000)
            if (state.episodes == null && state.error == null) state.error = "The plugin source did not return episodes. Refresh or choose another source."
        }
    }
    DisposableEffect(state) { onDispose { state.close() } }
    return state
}

@Composable
internal fun NativePluginEpisodeTabButtons(repo: SeanimeRepository, mediaId: Long, onUnavailable: () -> Unit = {}, onOpen: (String) -> Unit) {
    val state = rememberNativePluginEpisodeTabs(repo, mediaId)
    var focused by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.tabs.map { it.extensionId }) {
        if (focused != null && state.tabs.none { it.extensionId == focused }) { onUnavailable(); focused = null }
    }
    if (state.tabs.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.tabs, key = { it.extensionId }) { tab ->
            ActionButton(tab.name, modifier = Modifier.testTag("plugin-episode-tab-${tab.extensionId}")
                .onFocusChanged { if (it.isFocused) focused = tab.extensionId }) { onOpen("episodeTab:${tab.extensionId}") }
        }
    }
}
