package app.seanime.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import app.seanime.tv.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Source selection uses the existing provider contracts; Go remains the owner of source resolution. */
@Composable
internal fun SourceScreen(media: MediaCard, episode: Episode, repo: SeanimeRepository,
    onPlay: (PlaybackRequest) -> Unit, onBack: () -> Unit, initialMode: String? = null,
    listState: LazyListState = rememberLazyListState(), onProviderDiagnostic: ((String) -> Unit)? = null) {
    var mode by rememberSaveable(media.id) { mutableStateOf(initialMode ?: "") }
    var initialized by rememberSaveable(media.id) { mutableStateOf(initialMode != null && nativePluginSourceId(initialMode) == null) }
    val pluginTabs = rememberNativePluginEpisodeTabs(repo, media.id)
    val connected by repo.client.connected.collectAsState()
    var focusedPlugin by remember { mutableStateOf<String?>(null) }
    val deviceFocus = remember { FocusRequester() }
    val deviceFocusGranted = remember { mutableStateOf(true) }
    val entryFocus = remember { FocusRequester() }
    val entryGranted = remember(media.id) { mutableStateOf(false) }
    LaunchedEffect(pluginTabs.tabs.map { it.extensionId }) {
        if (focusedPlugin != null && pluginTabs.tabs.none { it.extensionId == focusedPlugin }) { deviceFocusGranted.value = false; focusedPlugin = null }
    }
    var providers by remember(mode) { mutableStateOf<List<ExtensionItem>>(emptyList()) }
    var providerError by remember(mode) { mutableStateOf<String?>(null) }
    var providerId by rememberSaveable(mode) { mutableStateOf("") }
    var query by rememberSaveable(media.id) { mutableStateOf(media.title) }
    var episodeText by rememberSaveable(media.id) { mutableStateOf(episode.number.coerceAtLeast(1).toString()) }
    var episodeError by remember(media.id) { mutableStateOf<String?>(null) }
    var sourceEditor by rememberSaveable(media.id) { mutableStateOf<String?>(null) }
    val episodeFocus = remember { FocusRequester() }
    val episodeFocusGranted = remember { mutableStateOf(true) }
    val queryFocus = remember { FocusRequester() }
    val queryFocusGranted = remember { mutableStateOf(true) }
    ReportNativePluginScreen(NativeScreenLocation("/entry", if (initialized) mapOf("id" to media.id.toString(),
        "tab" to (mode.takeIf { nativePluginSourceId(it) != null } ?: nativeSourceTabs.entries.firstOrNull { it.value == mode }?.key ?: "library"),
        "episode" to (episodeText.toIntOrNull()?.takeIf { it > 0 } ?: episode.number.coerceAtLeast(1)).toString()) else mapOf("id" to media.id.toString())), priority = 3)
    var dubbed by rememberSaveable { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<StreamSource>>(emptyList()) }
    var torrents by remember { mutableStateOf<List<TorrentItem>>(emptyList()) }
    var onlineEpisodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var selectedTorrent by remember { mutableStateOf<TorrentItem?>(null) }
    var downloadTorrent by remember { mutableStateOf<TorrentItem?>(null) }
    var downloadOpener by remember { mutableStateOf<String?>(null) }
    val downloadFocus = remember { FocusRequester() }
    val downloadFocusGranted = remember { mutableStateOf(true) }
    var torrentFiles by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }
    LaunchedEffect(repo, media.id, pluginTabs.registryLoaded) {
        if (!initialized && pluginTabs.registryLoaded) {
            try {
                val status = repo.status()
                val library = status.settings.optJSONObject("library")
                if (!initialized) {
                    mode = initialNativeSourceMode(initialMode, library?.text("defaultPlaybackSource").orEmpty(), episode.localPath != null,
                        library?.optBoolean("enableOnlinestream") == true,
                        status.raw.optJSONObject("torrentstreamSettings")?.optBoolean("enabled") == true,
                        status.raw.optJSONObject("debridSettings")?.optBoolean("enabled") == true, pluginTabs.registeredIds)
                    initialized = true
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!initialized) {
                    mode = initialMode?.takeIf { nativePluginSourceId(it) == null } ?: if (episode.localPath != null) "Device" else "Online"
                    initialized = true
                    message = "Couldn't load your default source. Choose a source below."
                }
            }
        }
    }
    fun clearResolvedSources() {
        sources = emptyList(); torrents = emptyList(); selectedTorrent = null; torrentFiles = emptyList()
    }
    fun closeSourceEditor() {
        when (sourceEditor) {
            "episode" -> episodeFocusGranted.value = false
            "query" -> queryFocusGranted.value = false
        }
        sourceEditor = null
    }
    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Couldn't open this source" }
            finally { busy = false }
        }
    }
    LaunchedEffect(mode, refresh, initialized) {
        if (!initialized) return@LaunchedEffect
        sources = emptyList(); torrents = emptyList(); onlineEpisodes = emptyList(); selectedTorrent = null; torrentFiles = emptyList(); message = null
        providers = emptyList(); providerError = null
        pluginTabs.select(nativePluginSourceId(mode))
        if (mode == "Device" || nativePluginSourceId(mode) != null) { busy = false; return@LaunchedEffect }
        busy = true
        try {
            providers = repo.providers(if (mode == "Online") "onlinestream-provider" else "anime-torrent-provider")
                .filter { !it.disabled }
            onProviderDiagnostic?.invoke("loaded count=${providers.size}")
            if (providers.none { it.id == providerId }) {
                val configured = if (mode == "Online") "" else try {
                    repo.settings().optJSONObject("library")?.text("torrentProvider").orEmpty()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "Couldn't load the saved provider. Choose a provider below."; "" }
                providerId = selectSourceProvider(providers, providerId, configured)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { providerError = e.message ?: "Couldn't load providers. Try again." }
        finally { busy = false }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // A full-width TV button grows by 5% on each side at the default focused scale.
    // Keep that space inside the scrolling viewport, including on wider TV layouts.
    val focusInset = maxOf(24.dp, maxWidth * .05f)
    LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = focusInset, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp), modifier = Modifier.fillMaxSize().testTag("source-content")
            .onFocusChanged { if (it.hasFocus) entryGranted.value = true }) {
        item { Text(media.title, style = MaterialTheme.typography.titleLarge) }
        item {
            ActionRow {
                listOf("Device", "Online", "Torrent", "Debrid").forEach { value ->
                    Button(onClick = { initialized = true; mode = value }, enabled = !busy, modifier = Modifier.testTag("source-mode-$value")
                        .then(if (value == "Device") Modifier.initialTvFocus(deviceFocus, deviceFocusGranted) else Modifier)
                        .then(if (initialized && value == mode) Modifier.initialTvFocus(entryFocus, entryGranted) else Modifier)) { Text(if (mode == value) "✓ $value" else value) }
                }
                Button(onClick = onBack, modifier = Modifier.testTag("source-back")
                    // Default-source lookup can be slow. Back is immediately actionable,
                    // and the list's focus observer prevents a late lookup from stealing it.
                    .then(if (!initialized) Modifier.initialTvFocus(entryFocus, entryGranted) else Modifier)) { Text("Back") }
            }
        }
        if (pluginTabs.tabs.isNotEmpty()) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(pluginTabs.tabs, key = { it.extensionId }) { tab ->
                    Button(onClick = { initialized = true; mode = "episodeTab:${tab.extensionId}" }, modifier = Modifier.testTag("source-plugin-${tab.extensionId}")
                        .then(if (initialized && mode == "episodeTab:${tab.extensionId}") Modifier.initialTvFocus(entryFocus, entryGranted) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedPlugin = tab.extensionId }) {
                        Text((if (mode == "episodeTab:${tab.extensionId}") "✓ " else "") + tab.name)
                    }
                }
            }
        }
        if (!initialized) item { LoadingMessage("Preparing your sources…") }
        else if (nativePluginSourceId(mode) != null) {
            item {
                ActionButton("Refresh plugin episodes", connected, Modifier.testTag("plugin-episodes-refresh")) { pluginTabs.refresh() }
                pluginTabs.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                pluginTabs.message?.let { Text(it) }
                if (pluginTabs.hasMappingError) Text("The plugin reported incomplete episode mapping. Check the episode title before selecting it.")
                if (pluginTabs.episodes == null && pluginTabs.error == null) LoadingMessage("Loading plugin episodes…")
                if (pluginTabs.episodes?.isEmpty() == true) EmptyMessage("No plugin episodes", "Refresh or choose another source.")
            }
            itemsIndexed(pluginTabs.episodes.orEmpty(), key = { index, item -> "$index:${item.aniDbEpisode}" }) { _, item ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionButton("${item.number}. ${item.title}", connected && pluginTabs.error == null,
                        Modifier.fillMaxWidth().testTag("plugin-episode-${item.number}")) { pluginTabs.selectEpisode(item) }
                    NativePluginActions(repo, listOf(NativePluginActionKind.EPISODE_CARD, NativePluginActionKind.EPISODE_GRID),
                        media = media, episode = item, episodeType = mode)
                }
            }
        } else if (mode == "Device") {
            item {
                if (episode.localPath != null) {
                    Text("Episode ${episode.number} · ${episode.title}")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { onPlay(PlaybackRequest(media.id, episode, title = "${media.title} · ${episode.title}")) }) { Text("Play on this TV") }
                } else EmptyMessage("No local file for this episode", "Choose another source, or add your media folder in Settings and scan the library.")
                if (episode.raw.length() > 0) NativePluginActions(repo, listOf(NativePluginActionKind.EPISODE_CARD, NativePluginActionKind.EPISODE_GRID),
                    media = media, episode = episode, episodeType = "library")
            }
        } else {
            val providerEpisode = onlineEpisodes.firstOrNull { OnlineEpisodeIdentity.sourceNumber(it.raw) == episodeText.toIntOrNull() }
            val contextEpisode = if (providerEpisode != null) OnlineEpisodeIdentity.canonical(providerEpisode.raw)?.let { providerEpisode.copy(raw = it) }
                else episode.takeIf { it.raw.length() > 0 && it.number == episodeText.toIntOrNull() }
            if (contextEpisode != null) item {
                NativePluginActions(repo, listOf(NativePluginActionKind.EPISODE_CARD, NativePluginActionKind.EPISODE_GRID), media = media,
                    episode = contextEpisode, episodeType = nativeSourceTabs.entries.firstOrNull { it.value == mode }?.key)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { sourceEditor = "episode" }, enabled = !busy,
                            modifier = Modifier.width(190.dp).testTag("source-episode-edit").initialTvFocus(episodeFocus, episodeFocusGranted)) {
                            Text("Episode: $episodeText", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (mode == "Online") Button(onClick = { dubbed = !dubbed; clearResolvedSources(); onlineEpisodes = emptyList() }, enabled = !busy) { Text(if (dubbed) "Audio: Dubbed" else "Audio: Subbed") }
                        else Button(onClick = { sourceEditor = "query" }, enabled = !busy,
                            modifier = Modifier.weight(1f).testTag("source-query-edit").initialTvFocus(queryFocus, queryFocusGranted)) {
                            Text("Torrent search: ${query.ifBlank { "Automatic" }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    episodeError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("source-episode-error"))
                    }
                }
            }
            item { Text("Provider", style = MaterialTheme.typography.titleMedium) }
            val providerRows = providers
            val providerAvailable = providerRows.any { it.id == providerId }
            providerError?.let { error -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("source-provider-error"))
                    ActionButton("Retry providers", !busy, Modifier.testTag("source-provider-retry")) { refresh++ }
                }
            } }
            onProviderDiagnostic?.invoke("interval count=${providerRows.size} keys=${providerRows.map { it.id }}")
            items(providerRows, key = { it.id }) { provider ->
                onProviderDiagnostic?.invoke("body key=${provider.id}")
                Button(onClick = { providerId = provider.id; clearResolvedSources(); onlineEpisodes = emptyList() }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("source-provider-${provider.id}").then(if (onProviderDiagnostic != null) Modifier.onSizeChanged {
                        onProviderDiagnostic("size key=${provider.id} width=${it.width} height=${it.height}")
                    } else Modifier)) { Text((if (provider.id == providerId) "✓ " else "") + provider.name) }
            }
            if (!busy && providerError == null && providers.isEmpty()) item {
                EmptyMessage("No enabled provider", "Install an ${if (mode == "Online") "online streaming" else "anime torrent"} provider in Extensions, then return here.")
                Button(onClick = { refresh++ }) { Text("Refresh providers") }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (mode == "Online") Button(enabled = !busy && providerAvailable, onClick = {
                        runAction { onlineEpisodes = repo.onlineEpisodes(media.id, providerId, dubbed); message = if (onlineEpisodes.isEmpty()) "This provider returned no episodes" else null }
                    }) { Text("Episode list") }
                    Button(enabled = !busy && providerAvailable, modifier = Modifier.testTag("source-find-sources"), onClick = {
                        val number = episodeText.toIntOrNull()
                        if (number == null || number < 1) episodeError = "Choose a positive episode number" else runAction {
                            clearResolvedSources()
                            if (mode == "Online") {
                                sources = repo.onlineSources(media.id, number, providerId, dubbed)
                                if (sources.isEmpty()) message = "No playable sources returned. Try another provider or audio option."
                            } else {
                                torrents = repo.searchTorrents(media, number, providerId, query)
                                if (torrents.isEmpty()) message = "No torrents found. Try another query or provider."
                            }
                        }
                    }) { Text(if (busy) "Finding…" else "Find sources") }
                }
            }
            items(onlineEpisodes, key = { "online-${OnlineEpisodeIdentity.sourceNumber(it.raw)}-${it.aniDbEpisode}" }) { found ->
                Button(enabled = !busy, onClick = {
                    val number = OnlineEpisodeIdentity.sourceNumber(found.raw)
                    episodeText = number.toString()
                    episodeError = null
                    clearResolvedSources()
                    runAction { sources = repo.onlineSources(media.id, number, providerId, dubbed) }
                }, modifier = Modifier.fillMaxWidth()) { Text("${OnlineEpisodeIdentity.sourceNumber(found.raw)}. ${found.title}") }
            }
            items(sources, key = { "${it.url}-${it.label}" }) { source ->
                Button(enabled = !busy, onClick = {
                    runAction {
                        val number = episodeText.toIntOrNull() ?: error("Choose a positive episode number")
                        val selected = resolveOnlineEpisode(media.id, number, onlineEpisodes) {
                            repo.onlineEpisodes(media.id, providerId, dubbed).also { onlineEpisodes = it }
                        }
                        val params = JSONObject().put("mediaId", media.id).put("episodeNumber", number).put("provider", providerId).put("dubbed", dubbed)
                            .put("quality", source.quality).put("server", source.raw.optString("server"))
                        val raw = OnlineEpisodeIdentity.withParams(selected.raw, params)
                        onPlay(PlaybackRequest(media.id, selected.copy(raw = raw), source, "${media.title} · ${selected.title}", media = media.raw))
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Play ${source.label} ${source.quality}".trim()) }
            }
            items(torrents, key = { it.infoHash.ifBlank { it.magnetUrl.ifBlank { it.name } } }) { torrent ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(torrent.name, style = MaterialTheme.typography.titleMedium)
                    Text("${torrent.size} · ${torrent.seeders} seeders")
                    Button(enabled = !busy, onClick = {
                        runAction {
                            val number = episodeText.toIntOrNull() ?: episode.number
                            selectedTorrent = null; torrentFiles = emptyList()
                            val previews = repo.torrentFilePreviews(media, number, torrent, mode == "Debrid").objects("files")
                            selectedTorrent = torrent; torrentFiles = previews
                            message = if (torrentFiles.isEmpty()) "This torrent returned no playable files" else "Choose the matching episode below"
                        }
                    }) { Text("Choose torrent file") }
                    val downloadKey = torrent.infoHash.ifBlank { torrent.magnetUrl.ifBlank { torrent.name } }
                    ActionButton("Download release", !busy, Modifier.testTag("source-download-$downloadKey")
                        .then(if (downloadOpener == downloadKey) Modifier.initialTvFocus(downloadFocus, downloadFocusGranted) else Modifier)) {
                        downloadOpener = downloadKey; downloadTorrent = torrent
                    }
                }
            }
        }
        items(torrentFiles, key = { "file-${it.optInt("index")}-${it.optString("fileId")}" }) { file ->
            Button(enabled = !busy, onClick = {
                val torrent = selectedTorrent ?: return@Button
                runAction {
                    val number = episodeText.toIntOrNull() ?: error("Choose a positive episode number")
                    val selected = resolveStreamEpisode(media.id, episode, number) { repo.episodeCollection(media.id) }
                    if (mode == "Debrid") repo.startDebridStream(media.id, selected, torrent,
                        fileId = file.optString("fileId"), fileIndex = file.optInt("index"), autoSelect = false)
                    else repo.startTorrentStream(media.id, selected, torrent, fileIndex = file.optInt("index"), autoSelect = false)
                    message = "Source requested. Playback opens when the server has prepared the stream."
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Play " + file.optString("displayTitle").ifBlank { file.optString("displayPath", "File ${file.optInt("index")}") })
            }
        }
        message?.let { value -> item { Text(value, color = MaterialTheme.colorScheme.primary) } }
        if (busy) item { Text("Working with the server…", color = MaterialTheme.colorScheme.primary) }
    }
    }
    downloadTorrent?.let { selected ->
        SourceDownloadDialog(repo, media, selected, mode == "Debrid", onClose = {
            downloadTorrent = null; downloadFocusGranted.value = false
        }, onRequested = { result ->
            downloadTorrent = null; downloadFocusGranted.value = false; message = result
        })
    }
    sourceEditor?.let { field ->
        TextEntryDialog(if (field == "episode") "Choose episode" else "Torrent search", if (field == "episode") "Episode number" else "Search text",
            initial = if (field == "episode") episodeText else query, allowEmpty = field == "query",
            inputModifier = Modifier.testTag("source-editor-$field"),
            keyboardType = if (field == "episode") KeyboardType.Number else KeyboardType.Text, onDismiss = ::closeSourceEditor) { value ->
            if (field == "episode") {
                val number = value.toIntOrNull()
                if (number == null || number <= 0) episodeError = "Choose a positive episode number"
                else { episodeText = number.toString(); episodeError = null; clearResolvedSources() }
            } else { query = value; clearResolvedSources() }
        }
    }
}
