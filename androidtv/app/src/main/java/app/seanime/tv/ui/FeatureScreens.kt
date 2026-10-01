package app.seanime.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.data.*
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Native routes pass existing backend episode and stream models to the player coordinator. */
data class PlaybackRequest(
    val mediaId: Long = 0L,
    val episode: Episode? = null,
    val stream: StreamSource? = null,
    val title: String = "",
    val playlistId: Int? = null,
    val media: JSONObject? = null,
    val playlistEpisode: JSONObject? = null,
    val unmatchedFile: app.seanime.tv.platform.NativeUnmatchedFile? = null,
)

@Composable
internal fun FeatureScreen(
    feature: TvFeature,
    repo: SeanimeRepository,
    onPlay: (PlaybackRequest) -> Unit,
    onPlatformAction: (String) -> Unit,
    initialRoute: NativePluginDestination? = null,
) {
    // Keying the route cancels suspended work and drops dialogs when navigating away.
    key(feature) {
        when (feature) {
            TvFeature.MANGA -> MangaScreen(repo, initialMode = initialRoute?.mangaMode ?: "library", initialQuery = initialRoute?.mangaQuery.orEmpty(), initialMediaId = initialRoute?.mangaId ?: 0L,
                initialDiscovery = initialRoute?.discovery, initialDiscoveryPage = initialRoute?.discoveryPage ?: 1)
            TvFeature.OFFLINE -> OfflineScreen(repo)
            TvFeature.PLAYLISTS -> PlaylistsScreen(repo, onPlay)
            TvFeature.EXTENSIONS -> ExtensionsScreen(repo, initialPlayground = initialRoute?.playground == true,
                initialMarketplace = initialRoute?.marketplace == true, initialMarketplaceType = initialRoute?.marketplaceType.orEmpty(),
                initialCustomSources = initialRoute?.customSources == true, initialCustomProvider = initialRoute?.customSourceProvider.orEmpty(), onPlay = onPlay)
            TvFeature.DOWNLOADS -> DownloadsScreen(repo, initialTab = initialRoute?.downloadTab ?: "manga")
            TvFeature.NAKAMA -> NakamaScreen(repo)
            TvFeature.SETTINGS -> SettingsScreen(repo, onPlatformAction, initialSection = initialRoute?.settingsSection)
            TvFeature.LOGS -> LogsScreen(repo, onPlatformAction)
            else -> FeaturePage("Seanime", "Choose a section from the navigation") {}
        }
    }
}

/** One in-flight mutation per screen; failures never turn into successful-looking empty lists. */
@Stable
internal class FeatureAction(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    fun run(success: String? = null, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        message = null
        // Keep all action state and list mutations on Android's UI dispatcher,
        // including when a composition/test scope uses an unconfined dispatcher.
        scope.launch(Dispatchers.Main.immediate) {
            try {
                block()
                if (success != null) message = success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Couldn't complete the request. Please try again."
            } finally {
                busy = false
            }
        }
    }
}

@Composable
internal fun rememberFeatureAction(): FeatureAction {
    val scope = rememberCoroutineScope()
    return remember(scope) { FeatureAction(scope) }
}

@Composable
internal fun FeaturePage(
    title: String,
    subtitle: String = "",
    action: FeatureAction? = null,
    state: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize(),
        // The app shell owns the TV safe area; this inset only clears focused surfaces.
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (action?.busy == true) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                action?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                action?.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
        content()
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
internal fun ActionButton(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier,
        border = ButtonDefaults.border(focusedDisabledBorder = Border(
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape = CircleShape))) { Text(label) }
}

@Composable
internal fun FeaturePanel(
    title: String,
    subtitle: String = "",
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), RoundedCornerShape(14.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
internal fun ActionRow(content: @Composable RowScope.() -> Unit) {
    // Put the space inside the scrolling viewport so focused TV surfaces can grow at its edges.
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(ActionRowContentPadding), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        content = content)
}

internal val ActionRowContentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

@Composable
internal fun EmptyFeature(title: String, detail: String) = FeaturePanel(title, detail)

/** Read-only dialog text needs its own remote target; scroll containers do not handle D-pad arrows. */
@Composable
internal fun TvScrollableText(
    modifier: Modifier = Modifier,
    exitFocus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val overflowing = scroll.viewportSize > 0 && scroll.maxValue > 0 && scroll.maxValue < Int.MAX_VALUE
    var focused by remember { mutableStateOf(false) }
    Column(modifier
        .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionUp, Key.DirectionDown -> {
                    val forward = event.key == Key.DirectionDown
                    if (if (forward) scroll.canScrollForward else scroll.canScrollBackward) {
                        scope.launch {
                            val step = (scroll.viewportSize * .75f).coerceAtLeast(1f)
                            scroll.scrollBy(if (forward) step else -step)
                        }
                        true
                    } else if (forward) exitFocus?.requestFocus() == true else false
                }
                else -> false
            }
        }
        .focusable(overflowing)
        .padding(6.dp)
        .verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
internal fun ConfirmFeatureDialog(title: String, detail: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    val cancelFocusGranted = remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        TvScrollableText(Modifier.fillMaxWidth().heightIn(max = 250.dp).testTag("feature-confirm-body"), exitFocus = cancelFocus) { Text(detail) }
    },
        confirmButton = { ActionButton("Confirm") { onConfirm(); onDismiss() } },
        dismissButton = { ActionButton("Cancel", modifier = Modifier.initialTvFocus(cancelFocus, cancelFocusGranted), onClick = onDismiss) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TextEntryDialog(
    title: String,
    label: String,
    initial: String = "",
    helper: String = "",
    secret: Boolean = false,
    multiline: Boolean = false,
    allowEmpty: Boolean = false,
    inputModifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    maxLength: Int? = null,
    submitLabel: String = "Save",
    submitOnIme: Boolean = false,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    // Secret drafts stay in process memory and never enter saved-state Bundles.
    val textState = if (secret) remember(title, initial) { mutableStateOf(initial) }
        else rememberSaveable(title, initial) { mutableStateOf(initial) }
    var text by textState
    val entryFocus = remember(title) { FocusRequester() }
    val cancelFocus = remember(title) { FocusRequester() }
    val entryFocusGranted = remember(title) { mutableStateOf(false) }
    // Use the full window so a docked IME can bound the editor and its footer.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Dialog has its own Compose owner and input window. Resolve the
        // controller here so Done hides this editor's IME, not its parent's.
        val keyboard = LocalSoftwareKeyboardController.current
        fun submit() {
            if (allowEmpty || text.isNotBlank()) {
                keyboard?.hide()
                onSubmit(if (secret) text else text.trim())
                onDismiss()
            }
        }
        // Leanback's floating number keyboard reports visibility but no inset.
        // Keep an upper-half editing area in that case; its keyboard window is
        // not measurable by ordinary apps. Docked keyboards use their real inset.
        val imeVisible = WindowInsets.isImeVisible
        val floatingKeyboard = imeVisible && WindowInsets.ime.getBottom(LocalDensity.current) == 0
        val spacing = if (floatingKeyboard) 8.dp else 12.dp
        val helperScroll = rememberScrollState()
        val helperFocus = remember { FocusRequester() }
        val helperScrollable = helper.isNotBlank() && helperScroll.viewportSize > 0 &&
            helperScroll.maxValue > 0 && helperScroll.maxValue < Int.MAX_VALUE
        var helperFocused by remember { mutableStateOf(false) }
        val helperScope = rememberCoroutineScope()
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(if (floatingKeyboard) .5f else 1f)
                .padding(horizontal = 24.dp, vertical = 12.dp).testTag("text-entry-viewport"),
                contentAlignment = if (floatingKeyboard) Alignment.TopCenter else Alignment.Center) {
                Column(Modifier.widthIn(max = 440.dp).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                    .padding(horizontal = 24.dp, vertical = if (floatingKeyboard) 16.dp else 24.dp)
                    .testTag("text-entry-dialog"), verticalArrangement = Arrangement.spacedBy(spacing)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    BoxWithConstraints(Modifier.weight(1f, fill = false).testTag("text-entry-body")) {
                        // Measure helper and editor together in a finite body.
                        // The helper scrolls independently; the focused editor has
                        // no scrolling ancestor competing with its own text scroll.
                        // Let ordinary two-line guidance fit without taking the
                        // labelled editor below one comfortable TV input row.
                        val helperHeight = minOf(maxHeight / 2,
                            (maxHeight - 68.dp - spacing).coerceAtLeast(0.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                            if (helper.isNotBlank()) Text(helper, modifier = Modifier.heightIn(max = helperHeight)
                                .testTag("text-entry-helper")
                                .border(2.dp, if (helperFocused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(4.dp))
                                .focusRequester(helperFocus).onFocusChanged { helperFocused = it.isFocused }
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                        Key.DirectionUp, Key.DirectionDown -> {
                                            if (event.key == Key.DirectionDown && !helperScroll.canScrollForward) entryFocus.requestFocus()
                                            else helperScope.launch {
                                                val step = (helperScroll.viewportSize * .75f).coerceAtLeast(1f)
                                                helperScroll.scrollBy(if (event.key == Key.DirectionUp) -step else step)
                                            }
                                            true
                                        }
                                        Key.DirectionCenter, Key.Enter, Key.DirectionRight -> { entryFocus.requestFocus(); true }
                                        else -> false
                                    }
                                }.focusable(helperScrollable)
                                .padding(if (helperScrollable) 4.dp else 0.dp).verticalScroll(helperScroll))
                            OutlinedTextField(value = text, onValueChange = { text = if (maxLength == null) it else it.take(maxLength) }, label = { Text(label) },
                                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).then(inputModifier)
                                    .onPreviewKeyEvent { event ->
                                        if (imeVisible || event.type != KeyEventType.KeyDown) false
                                        else when (event.key) {
                                            Key.DirectionUp -> if (helperScrollable) { helperFocus.requestFocus(); true } else false
                                            Key.DirectionDown -> { cancelFocus.requestFocus(); true }
                                            else -> false
                                        }
                                    }
                                    .initialTvFocus(entryFocus, entryFocusGranted), singleLine = !multiline, maxLines = if (multiline) 8 else 1,
                                keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType,
                                    imeAction = if (multiline) ImeAction.Default else if (submitOnIme) ImeAction.Search else ImeAction.Done),
                                keyboardActions = KeyboardActions(
                                    onDone = { if (submitOnIme) submit() else keyboard?.hide() },
                                    onSearch = { if (submitOnIme) submit() else keyboard?.hide() },
                                ),
                                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("text-entry-actions"),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                        ActionButton("Cancel", modifier = Modifier.testTag("text-entry-cancel").focusRequester(cancelFocus), onClick = onDismiss)
                        ActionButton(submitLabel, allowEmpty || text.isNotBlank(), Modifier.testTag("text-entry-save"), onClick = ::submit)
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineScreen(repo: SeanimeRepository) {
    val action = rememberFeatureAction()
    var tracked by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var trackedArtwork by remember { mutableStateOf<Map<Long, MediaCard>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var accountStatus by remember { mutableStateOf<ServerStatus?>(null) }
    val offline = accountStatus?.offline == true
    val connectedAccount = accountStatus?.isSimulated == false
    var size by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<JSONObject?>(null) }
    var addType by remember { mutableStateOf<String?>(null) }
    var metadataSync by remember { mutableStateOf(MetadataSyncState()) }
    var metadataQueueError by remember { mutableStateOf<String?>(null) }
    var metadataRevision by remember { mutableLongStateOf(0L) }
    var refreshAfterSync by remember { mutableStateOf(false) }
    val eventConnected by repo.client.connected.collectAsState()
    suspend fun reloadMetadataQueue() {
        val revision = metadataRevision
        try {
            val snapshot = repo.request("GET", "/api/v1/local/queue") as? JSONObject
                ?: error("The server did not return a metadata queue state")
            if (revision == metadataRevision) metadataSync = metadataSync.snapshot(snapshot)
            metadataQueueError = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { metadataQueueError = failure.message ?: "Couldn't refresh metadata queue status" }
    }
    suspend fun reload() {
        tracked = repo.request("GET", "/api/v1/local/track").jsonObjects()
        trackedArtwork = tracked.mapNotNull { entry ->
            (entry.optJSONObject("animeEntry") ?: entry.optJSONObject("mangaEntry"))?.optJSONObject("media")?.let { SeanimeJson.media(it).withLocalArtwork() }
        }.associateBy { it.id }
        size = repo.request("GET", "/api/v1/local/storage/size")?.toString().orEmpty()
        pending = repo.request("GET", "/api/v1/local/updated") == true
        accountStatus = repo.status()
        loaded = true
        reloadMetadataQueue()
    }
    suspend fun requireConnectedAccount() {
        val current = repo.status()
        accountStatus = current
        check(!current.isSimulated) { "Connect AniList in Settings to use offline mode and AniList synchronization. Local files and downloaded manga remain available." }
    }
    LaunchedEffect(repo) { action.run { reload() } }
    LaunchedEffect(repo) {
        repo.client.events.collectOnMain { event ->
            when (event.type) {
                "sync-local-queue-state" -> try {
                    metadataSync = metadataSync.snapshot(event.payload as? JSONObject ?: error("Invalid metadata queue event"))
                    metadataRevision++
                    metadataQueueError = null
                } catch (failure: Exception) { metadataQueueError = failure.message }
                "sync-local-finished" -> {
                    metadataSync = metadataSync.finished()
                    metadataRevision++
                    metadataQueueError = null
                    refreshAfterSync = true
                }
            }
        }
    }
    LaunchedEffect(repo, eventConnected) { if (eventConnected) reloadMetadataQueue() }
    LaunchedEffect(repo, refreshAfterSync, action.busy) {
        if (refreshAfterSync && !action.busy) {
            refreshAfterSync = false
            action.run { reload() }
        }
    }
    FeaturePage("Offline", "Keep your library metadata and reading progress available without a connection", action) {
        item {
            FeaturePanel(if (accountStatus?.isSimulated == true) "Local account" else if (offline) "Offline mode is on" else "Online mode", "${tracked.size} tracked titles${if (size.isNotBlank()) " · $size stored" else ""}") {
                ActionRow {
                    ActionButton(if (offline) "Go online" else "Use offline mode", !action.busy && connectedAccount) {
                        action.run { requireConnectedAccount(); repo.setOffline(!requireNotNull(accountStatus).offline); reload() }
                    }
                    ActionButton("Refresh", !action.busy) { action.run { reload() } }
                }
                Text("Tracking saves metadata. Download manga chapters from Manga to read the pages offline; local anime files stay in your library.")
                if (accountStatus?.isSimulated == true) Text("Your local account keeps local files and downloaded manga available. Connect AniList in Settings to use offline mode and AniList synchronization.")
            }
        }
        item {
            FeaturePanel("Synchronize", if (pending) "Local progress is waiting to be uploaded" else "No unsynced local changes reported") {
                ActionRow {
                    ActionButton("Save latest metadata", !action.busy && !offline && connectedAccount && metadataSync.tasks.isEmpty(), Modifier.testTag("offline-save-metadata")) {
                        action.run {
                            requireConnectedAccount()
                            check(!requireNotNull(accountStatus).offline) { "Go online before refreshing metadata" }
                            val previous = metadataSync
                            metadataSync = metadataSync.requesting()
                            try {
                                check(repo.request("POST", "/api/v1/local/local") == true) { "The server did not accept the metadata request" }
                                metadataSync = metadataSync.accepted()
                            } catch (failure: Exception) {
                                if (metadataSync.phase == MetadataSyncPhase.REQUESTING) metadataSync = previous
                                throw failure
                            }
                            reloadMetadataQueue()
                        }
                    }
                    ActionButton("Upload local progress", !action.busy && !offline && connectedAccount) {
                        action.run("Progress synchronization requested") { requireConnectedAccount(); check(!requireNotNull(accountStatus).offline) { "Go online before uploading progress" }; repo.request("POST", "/api/v1/local/anilist"); reload() }
                    }
                }
                Text(metadataSync.description, Modifier.testTag("offline-metadata-status"))
                metadataSync.tasks.forEach { task -> Text("${humanizeField(task.type)} · ${task.title}") }
                metadataQueueError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                ActionButton(if (metadataQueueError != null) "Retry sync status" else "Refresh sync status", !action.busy,
                    Modifier.testTag("offline-refresh-sync")) { action.run { reloadMetadataQueue() } }
                if (!eventConnected && metadataSync.phase in listOf(MetadataSyncPhase.REQUESTED, MetadataSyncPhase.PROCESSING))
                    Text("Live updates are disconnected. Refresh sync status to check active work; completion needs the server's finish event.")
                ActionRow {
                    ActionButton("Track anime", !action.busy) { addType = "anime" }
                    ActionButton("Track manga", !action.busy) { addType = "manga" }
                }
            }
        }
        if (loaded && tracked.isEmpty()) item { EmptyFeature("Nothing tracked yet", "Add a title above to prepare its metadata for offline use") }
        items(tracked, key = { "${it.optLong("mediaId")}:${it.text("type")}" }) { entry ->
            val media = (entry.optJSONObject("animeEntry") ?: entry.optJSONObject("mangaEntry"))?.optJSONObject("media")
            FeaturePanel(media?.mediaTitle("Title ${entry.optLong("mediaId")}") ?: "Title ${entry.optLong("mediaId")}", humanizeField(entry.text("type"))) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    val artwork = trackedArtwork[media?.optMediaId()] ?: media?.let { SeanimeJson.media(it) }
                    NativeArtwork(artwork ?: MediaCard(0L, "Offline title"), media?.mediaTitle("Offline title"),
                        Modifier.size(64.dp, 90.dp), ContentScale.Crop)
                    ActionButton("Remove offline metadata", !action.busy) { confirm = entry }
                }
            }
        }
    }
    confirm?.let { entry -> ConfirmFeatureDialog("Remove offline metadata?", "The stored metadata for this title will be removed. Local anime files and downloaded chapters are managed separately.",
        onDismiss = { confirm = null }) { action.run { repo.request("DELETE", "/api/v1/local/track", JSONObject().put("mediaId", entry.optLong("mediaId")).put("type", entry.text("type"))); reload() } } }
    addType?.let { type -> NativeMediaPickerDialog(repo, "Track $type", manga = type == "manga", onDismiss = { addType = null }) { media ->
        action.run("${media.title} added for offline metadata") {
            repo.trackOffline(media.id, manga = type == "manga")
            reload()
        }
    } }

}

@Composable
private fun PlaylistsScreen(repo: SeanimeRepository, onPlay: (PlaybackRequest) -> Unit) {
    val action = rememberFeatureAction()
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var nameDialog by remember { mutableStateOf<String?>(null) }
    var addMedia by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<Playlist?>(null) }
    var refreshPending by remember { mutableStateOf(true) }
    suspend fun reload() { playlists = repo.playlists(); loaded = true }
    OnPlaylistScreenResume { refreshPending = true }
    LaunchedEffect(repo, refreshPending, action.busy) {
        if (refreshPending && !action.busy) {
            refreshPending = false
            action.run { reload() }
        }
    }
    LaunchedEffect(repo) {
        repo.client.events.collectOnMain { event ->
            val envelope = event.payload as? JSONObject
            if (event.type == "playlist" && envelope?.optString("type") == "current-playlist") {
                val raw = envelope.optJSONObject("payload")?.optJSONObject("playlist")
                if (raw == null) refreshPending = true
                else {
                    // This event precedes the Go manager's asynchronous database
                    // save, so display its authoritative snapshot directly.
                    val updated = repo.authorizePlaylistArtwork(listOf(SeanimeJson.playlist(raw))).single()
                    playlists = if (playlists.any { it.id == updated.id }) playlists.map { if (it.id == updated.id) updated else it }
                        else playlists + updated
                }
            }
        }
    }
    val selected = playlists.firstOrNull { it.id == selectedId }
    BackHandler(selectedId != null) { selectedId = null }
    FeaturePage(selected?.name ?: "Playlists", "Build a watch queue from your library", action) {
        item {
            ActionRow {
                if (selected != null) ActionButton("All playlists") { selectedId = null }
                ActionButton(if (selected != null) "Rename" else "New playlist", !action.busy) { nameDialog = if (selected != null) "rename" else "create" }
                ActionButton("Refresh", !action.busy) { action.run { reload() } }
                if (selected != null) {
                    ActionButton("Add anime", !action.busy) { addMedia = true }
                    ActionButton("Start playlist", !action.busy && selected.episodes.any { !it.completed }) {
                        onPlay(PlaybackRequest(title = selected.name, playlistId = selected.id))
                    }
                }
            }
        }
        if (selected == null) {
            if (loaded && playlists.isEmpty()) item { EmptyFeature("No playlists yet", "Create a playlist, then choose anime to add its unwatched episodes") }
            items(playlists, key = { it.id }) { playlist ->
                FeaturePanel(playlist.name, "${playlist.episodes.count { !it.completed }} unwatched · ${playlist.episodes.size} episodes") {
                    ActionRow {
                        ActionButton("Open") { selectedId = playlist.id }
                        ActionButton("Delete", !action.busy) { delete = playlist }
                    }
                }
            }
        } else {
            if (selected.episodes.isEmpty()) item { EmptyFeature("Your queue is empty", "Add anime to append its available unwatched episodes") }
            items(selected.episodes.size, key = { it }) { index ->
                val entry = selected.episodes[index]
                val episodeJson = entry.raw.optJSONObject("episode") ?: JSONObject()
                val media = episodeJson.optJSONObject("baseAnime") ?: JSONObject()
                val number = episodeJson.optInt("episodeNumber")
                FeaturePanel(media.mediaTitle("Episode $number"), "Episode $number · ${if (entry.completed) "Watched" else "Unwatched"} · ${entry.watchType.ifBlank { "Choose a playback source in Library" }}") {
                    NativeArtwork(SeanimeJson.media(media).copy(artworkOrigin = entry.artworkOrigin),
                        "${media.mediaTitle("Episode $number")} episode $number thumbnail", Modifier.size(160.dp, 90.dp), ContentScale.Crop,
                        url = entry.episode?.imageUrl ?: SeanimeJson.media(media).imageUrl)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = ActionRowContentPadding) {
                        item { ActionButton("Play", entry.episode != null && !action.busy) {
                            onPlay(PlaybackRequest(mediaId = media.optLong("id"), episode = entry.episode, title = media.mediaTitle(),
                                playlistEpisode = JSONObject(entry.raw.toString()).put("isCompleted", entry.completed)))
                        } }
                        item { ActionButton(if (entry.completed) "Mark unwatched" else "Mark watched", !action.busy) {
                            action.run("Playlist updated") {
                                editCurrentPlaylist(repo, selected.id, PlaylistEdit.Complete(playlistEpisodeIdentity(entry), !entry.completed))
                                reload()
                            }
                        } }
                        item { ActionButton("Move up", index > 0 && !action.busy) {
                            action.run { editCurrentPlaylist(repo, selected.id, PlaylistEdit.Move(playlistEpisodeIdentity(entry), -1)); reload() }
                        } }
                        item { ActionButton("Move down", index < selected.episodes.lastIndex && !action.busy) {
                            action.run { editCurrentPlaylist(repo, selected.id, PlaylistEdit.Move(playlistEpisodeIdentity(entry), 1)); reload() }
                        } }
                        item { ActionButton("Remove", !action.busy) {
                            action.run { editCurrentPlaylist(repo, selected.id, PlaylistEdit.Remove(playlistEpisodeIdentity(entry))); reload() }
                        } }
                    }
                }
            }
        }
    }
    nameDialog?.let { mode -> TextEntryDialog(if (mode == "create") "New playlist" else "Rename playlist", "Name", if (mode == "rename") selected?.name.orEmpty() else "",
        onDismiss = { nameDialog = null }) { name -> action.run("Playlist saved") {
        if (mode == "create") repo.createPlaylist(name) else selected?.let { editCurrentPlaylist(repo, it.id, PlaylistEdit.Rename(name)) }
        reload()
    } } }
    if (addMedia && selected != null) NativeMediaPickerDialog(repo, "Add anime to ${selected.name}", onDismiss = { addMedia = false }) { media ->
        action.run {
            val incoming = repo.playlistEpisodes(media.id)
            editCurrentPlaylist(repo, selected.id, PlaylistEdit.Append(incoming))
            action.message = "Available unwatched episodes from ${media.title} added without duplicates"
            reload()
        }
    }
    delete?.let { playlist -> ConfirmFeatureDialog("Delete ${playlist.name}?", "This removes the playlist. It does not delete any media files.", { delete = null }) {
        action.run("Playlist deleted") { repo.deletePlaylist(playlist.id); if (selectedId == playlist.id) selectedId = null; reload() }
    } }
}

@Composable
private fun ExtensionsScreen(repo: SeanimeRepository, initialPlayground: Boolean = false, initialMarketplace: Boolean = false, initialMarketplaceType: String = "",
    initialCustomSources: Boolean = false, initialCustomProvider: String = "", onPlay: (PlaybackRequest) -> Unit) {
    val action = rememberFeatureAction()
    var entries by remember { mutableStateOf<List<ExtensionItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var installDialog by remember { mutableStateOf(false) }
    var repositoryDialog by remember { mutableStateOf(false) }
    var repositoryPreview by remember { mutableStateOf<JSONObject?>(null) }
    var repositoryUrl by remember { mutableStateOf("") }
    var openPlugin by remember { mutableStateOf<ExtensionItem?>(null) }
    var playground by remember { mutableStateOf(initialPlayground) }
    var marketplace by remember { mutableStateOf(initialMarketplace) }
    var customSources by remember { mutableStateOf(initialCustomSources) }
    if (!marketplace && !customSources) ReportNativePluginScreen(NativeScreenLocation(if (playground) "/extensions/playground" else "/extensions"), priority = 1)
    var playgroundExtension by remember { mutableStateOf<ExtensionItem?>(null) }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    var manifest by remember { mutableStateOf("") }
    var remove by remember { mutableStateOf<ExtensionItem?>(null) }
    var config by remember { mutableStateOf<ExtensionItem?>(null) }
    var permissions by remember { mutableStateOf<ExtensionItem?>(null) }
    var updates by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    suspend fun reload() { entries = repo.extensions(); loaded = true }
    LaunchedEffect(repo) { action.run { reload() } }
    if (marketplace) { NativeExtensionMarketplace(repo, initialMarketplaceType) { marketplace = false; action.run { reload() } }; return }
    if (customSources) { NativeCustomSources(repo, initialCustomProvider, onPlay) { customSources = false }; return }
    openPlugin?.let { NativePluginPanel(repo, it) { openPlugin = null }; return }
    if (playground) { NativeExtensionPlayground(repo, playgroundExtension) { playground = false; playgroundExtension = null }; return }
    val configuring = config
    if (configuring != null) {
        ExtensionConfiguration(repo, configuring, onBack = { config = null; action.run { reload() } })
        return
    }
    FeaturePage("Extensions & plugins", "Manage providers and plugins installed in the Seanime backend", action) {
        item {
            ActionRow {
                ActionButton("Marketplace", !action.busy, Modifier.testTag("extensions-marketplace")) { marketplace = true }
                ActionButton("Custom sources", !action.busy, Modifier.testTag("extensions-custom-sources")) { customSources = true }
                ActionButton("Install from URL", !action.busy) { installDialog = true }
                ActionButton("Install repository", !action.busy) { repositoryDialog = true }
                ActionButton("Playground", !action.busy) { playground = true }
                ActionButton("Check updates", !action.busy) { action.run("Update check complete") {
                    val response = repo.request("POST", "/api/v1/extensions/all", JSONObject().put("withUpdates", true)) as? JSONObject
                    updates = response?.optJSONArray("hasUpdate").uiObjects().associateBy { it.text("extensionID") }
                    reload()
                } }
                ActionButton("Refresh", !action.busy) { action.run { reload() } }
            }
        }
        if (loaded && entries.isEmpty()) item { EmptyFeature("No extensions installed", "Install a provider manifest to add manga, streaming, or torrent sources") }
        items(entries, key = { it.id }) { extension ->
            FeaturePanel(extension.name, "${extension.version} · ${humanizeField(extension.type)} · ${if (extension.disabled) "Disabled" else "Enabled"}") {
                if (extension.description.isNotBlank()) Text(extension.description)
                extension.configurationError?.let { Text("Configuration needs attention: $it", color = MaterialTheme.colorScheme.error) }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = ActionRowContentPadding) {
                    if (extension.manifestUrl != "builtin") item {
                        ActionButton(if (extension.disabled) "Enable" else "Disable", !action.busy) {
                            action.run { repo.setExtensionDisabled(extension.id, !extension.disabled); reload() }
                        }
                    }
                    item { ActionButton("Configure", !action.busy, Modifier.testTag("extension-configure-${extension.id}")) { config = extension } }
                    if (extension.type == "plugin") item { ActionButton("Open plugin", !action.busy && !extension.disabled) { openPlugin = extension } }
                    if (extension.type in setOf("manga-provider", "onlinestream-provider", "anime-torrent-provider")) item {
                        ActionButton("Open in playground", !action.busy) { playgroundExtension = extension; playground = true }
                    }
                    item { ActionButton("Reload", !action.busy) {
                        action.run("Extension reloaded") { repo.request("POST", "/api/v1/extensions/external/reload", JSONObject().put("id", extension.id)); reload() }
                    } }
                    updates[extension.id]?.let { update -> item {
                        ActionButton("Update to ${update.text("version")}", !action.busy) {
                            manifest = update.text("manifestURI")
                            action.run { preview = repo.request("POST", "/api/v1/extensions/external/fetch", JSONObject().put("manifestUri", manifest)) as? JSONObject }
                        }
                    } }
                    if (extension.manifestUrl != "builtin") item { ActionButton("Uninstall", !action.busy) { remove = extension } }
                }
                val plugin = extension.raw.optJSONObject("plugin")
                if (plugin != null) {
                    Text(pluginPermissionSummary(plugin))
                    ActionButton("Review plugin permissions", !action.busy) { permissions = extension }
                }
            }
        }
    }
    if (installDialog) TextEntryDialog("Install extension", "HTTPS manifest URL", helper = "Only install code from an author you trust. Review the manifest before installation.",
        onDismiss = { installDialog = false }) { value ->
        action.run {
            require(value.startsWith("https://") || value.startsWith("http://")) { "Enter an HTTP or HTTPS manifest URL" }
            manifest = value
            preview = repo.request("POST", "/api/v1/extensions/external/fetch", JSONObject().put("manifestUri", value)) as? JSONObject
                ?: error("The server returned no extension manifest")
        }
    }
    if (repositoryDialog) TextEntryDialog("Install extension repository", "Repository URL", helper = "Review the full extension list before installation. Only use a repository you trust.", onDismiss = { repositoryDialog = false }) { url ->
        action.run {
            require(url.startsWith("https://") || url.startsWith("http://")) { "Enter an HTTP or HTTPS repository URL" }
            repositoryUrl = url
            repositoryPreview = repo.request("POST", "/api/v1/extensions/external/install-repository", JSONObject().put("repositoryUri", url).put("install", false)) as? JSONObject
                ?: error("The server returned no repository preview")
        }
    }
    repositoryPreview?.let { result -> ConfirmFeatureDialog("Install repository extensions?",
        "Source: $repositoryUrl\n\n" + result.optJSONArray("extensions").uiObjects().joinToString("\n") { "${it.text("name")} · ${it.text("version")} · ${it.text("author")}" } + "\n\nThese extensions run code in your Seanime backend.",
        onDismiss = { repositoryPreview = null }) { action.run("Repository installation completed") {
            repo.request("POST", "/api/v1/extensions/external/install-repository", JSONObject().put("repositoryUri", repositoryUrl).put("install", true)); reload()
        } } }
    preview?.let { extension -> ConfirmFeatureDialog("Install ${extension.text("name", "extension")}?",
        "Version ${extension.text("version")} · ${extension.text("author")}\n${extension.text("description")}\n\nSource: $manifest\n\nExtensions run code in your Seanime backend. Install only if you trust this source.",
        onDismiss = { preview = null }) { action.run("Extension installed") { repo.installExtension(manifest); reload() } } }
    permissions?.let { extension -> ConfirmFeatureDialog("Grant permissions to ${extension.name}?",
        "This gives the plugin the following access in your Seanime backend:\n\n${pluginPermissionSummary(extension.raw.optJSONObject("plugin") ?: JSONObject())}\n\nOnly grant access to a plugin you trust.",
        onDismiss = { permissions = null }) { action.run("Plugin permissions granted") {
            check(repo.grantPluginPermissions(extension.id)) { "The backend did not confirm permission approval" }
            reload()
        } } }
    remove?.let { extension -> ConfirmFeatureDialog("Uninstall ${extension.name}?", "This provider or plugin will no longer be available. Its dependent playback or reading sources may stop working.",
        onDismiss = { remove = null }) { action.run("Extension removed") { repo.removeExtension(extension.id); reload() } } }
}

@Composable
private fun ExtensionConfiguration(repo: SeanimeRepository, extension: ExtensionItem, onBack: () -> Unit) {
    val action = rememberFeatureAction()
    var definition by remember { mutableStateOf<JSONObject?>(null) }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var configurationError by remember(extension.id) { mutableStateOf(extension.configurationError) }
    var edit by remember { mutableStateOf<JSONObject?>(null) }
    val backFocus = remember { FocusRequester() }
    val backGranted = remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    suspend fun loadConfiguration() {
        val config = repo.extensionConfig(extension.id)
        definition = config.optJSONObject("userConfig") ?: extension.raw.optJSONObject("userConfig")
        val saved = config.optJSONObject("savedUserConfig")?.optJSONObject("values")
        if (!loaded) values = saved?.keys()?.asSequence()?.associateWith { saved.text(it) } ?: emptyMap()
        loaded = true
    }
    LaunchedEffect(extension.id) { action.run { loadConfiguration() } }
    FeaturePage(extension.name, "Provider configuration", action) {
        item { ActionRow {
            ActionButton("Back", modifier = Modifier.testTag("extension-config-back").initialTvFocus(backFocus, backGranted), onClick = onBack)
            if (!loaded) ActionButton("Retry loading", !action.busy, Modifier.testTag("extension-config-retry")) { action.run { loadConfiguration() } }
            ActionButton("Save configuration", !action.busy && definition != null, Modifier.testTag("extension-config-save")) { action.run("Configuration saved") {
                val fields = definition?.optJSONArray("fields").uiObjects()
                val complete = fields.associate { it.text("name") to (values[it.text("name")] ?: it.text("default")) }
                check(repo.saveExtensionConfig(extension.id, definition?.optInt("version") ?: 0, complete) == true) { "The server did not confirm the configuration save" }
                configurationError = repo.extensions().firstOrNull { it.id == extension.id }?.configurationError
            } }
        } }
        configurationError?.let { item { Text("Configuration needs attention: $it", color = MaterialTheme.colorScheme.error) } }
        if (loaded && definition == null) item { EmptyFeature(if (configurationError == null) "No configuration needed" else "Configuration definition unavailable",
            if (configurationError == null) "This extension does not declare configurable fields" else "Reload the extension, then reopen its configuration.") }
        items(definition?.optJSONArray("fields").uiObjects(), key = { it.text("name") }) { field ->
            val name = field.text("name")
            val value = values[name] ?: field.text("default")
            FeaturePanel(field.text("label", humanizeField(name))) {
                when (field.text("type")) {
                    "switch" -> ActionButton(if (value == "true") "On" else "Off", !action.busy) { values = values + (name to (value != "true").toString()) }
                    "select" -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = ActionRowContentPadding) {
                        items(field.optJSONArray("options").uiObjects()) { option ->
                            ActionButton((if (value == option.text("value")) "✓ " else "") + option.text("label", option.text("value")), !action.busy) {
                                values = values + (name to option.text("value"))
                            }
                        }
                    }
                    else -> {
                        Text(if (name.contains("password", true) || name.contains("key", true) || name.contains("token", true)) { if (value.isBlank()) "Not set" else "••••••••" } else value.ifBlank { "Not set" })
                        ActionButton("Edit", !action.busy) { edit = field }
                    }
                }
            }
        }
    }
    edit?.let { field -> TextEntryDialog(field.text("label", field.text("name")), "Value", values[field.text("name")] ?: field.text("default"),
        secret = listOf("password", "key", "token").any { field.text("name").contains(it, true) }, allowEmpty = true, onDismiss = { edit = null }) {
        values = values + (field.text("name") to it)
    } }
}

@Composable
private fun DownloadsScreen(repo: SeanimeRepository, initialTab: String = "manga") {
    val action = rememberFeatureAction()
    var torrents by remember { mutableStateOf<List<DownloadItem>>(emptyList()) }
    var chapters by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    var autoReturnTab by rememberSaveable { mutableStateOf(if (initialTab == "auto") "manga" else initialTab) }
    val autoReturnFocus = remember { FocusRequester() }
    val autoReturnGranted = remember { mutableStateOf(true) }
    ReportNativePluginScreen(NativeScreenLocation(when (selectedTab) { "torrent" -> "/torrent-list"; "debrid" -> "/debrid"; "auto" -> "/auto-downloader"; else -> "/native/manga-downloads" }), priority = 1)
    var confirm by remember { mutableStateOf<DownloadItem?>(null) }
    var clearQueue by remember { mutableStateOf(false) }
    var queueRefreshPending by remember { mutableStateOf(false) }
    val queueEventsConnected by repo.client.connected.collectAsState()
    var magnetDialog by remember { mutableStateOf(false) }
    var torrentClient by remember { mutableStateOf("") }
    var movingTorrent by remember { mutableStateOf<DownloadItem?>(null) }
    var renamingTorrent by remember { mutableStateOf<DownloadItem?>(null) }
    var inspectingTorrent by remember { mutableStateOf<DownloadItem?>(null) }
    var torrentLimits by remember { mutableStateOf(false) }
    val downloadListState = rememberLazyListState()
    var torrentChangeOpener by remember { mutableStateOf<String?>(null) }
    val torrentChangeFocus = remember { FocusRequester() }
    val torrentChangeGranted = remember { mutableStateOf(true) }
    var debridDestination by remember { mutableStateOf<DownloadItem?>(null) }
    var debridOpener by remember { mutableStateOf<String?>(null) }
    val debridReturnFocus = remember { FocusRequester() }
    val debridReturnGranted = remember { mutableStateOf(true) }
    suspend fun reload() {
        if (selectedTab == "manga") chapters = repo.request("GET", "/api/v1/manga/download-queue").jsonObjects()
        else {
            if (selectedTab == "torrent") torrentClient = repo.settings().optJSONObject("torrent")?.text("defaultTorrentClient").orEmpty()
            torrents = if (selectedTab == "debrid") repo.debridDownloads() else repo.downloads()
        }
        loaded = true
    }
    suspend fun requireBuiltInClient() {
        torrentClient = repo.settings().optJSONObject("torrent")?.text("defaultTorrentClient").orEmpty()
        check(torrentClient == "seanime") { "This action needs the built-in Seanime torrent client. Refresh to see the current client." }
    }
    LaunchedEffect(repo, selectedTab) { if (selectedTab != "auto") { loaded = false; action.run { reload() } } }
    LaunchedEffect(repo) {
        repo.client.events.collectOnMain { event ->
            if (event.type == "chapter-download-queue-updated") queueRefreshPending = true
        }
    }
    LaunchedEffect(repo, queueEventsConnected) { if (queueEventsConnected) queueRefreshPending = true }
    LaunchedEffect(repo, selectedTab, queueRefreshPending, action.busy) {
        if (selectedTab == "manga" && queueRefreshPending && !action.busy) {
            queueRefreshPending = false
            action.run { reload() }
        }
    }
    if (selectedTab == "auto") {
        AutoDownloaderScreen(repo) { selectedTab = autoReturnTab; autoReturnGranted.value = false }
        return
    }
    inspectingTorrent?.let { selected ->
        key(repo, selected.id) { NativeTorrentDetailsScreen(repo, selected) {
            inspectingTorrent = null; torrentChangeGranted.value = false
            action.run {
                reload()
                if (torrentClient != "seanime" || torrents.none { it.id == selected.id }) downloadListState.scrollToItem(0)
            }
        } }
        return
    }
    FeaturePage("Downloads", "Manage ongoing torrent transfers and manga chapter downloads", action, downloadListState) {
        item { ActionRow {
            ActionButton(if (selectedTab == "manga") "✓ Manga" else "Manga", !action.busy) { selectedTab = "manga" }
            ActionButton(if (selectedTab == "torrent") "✓ Torrents" else "Torrents", !action.busy) { selectedTab = "torrent" }
            ActionButton(if (selectedTab == "debrid") "✓ Debrid" else "Debrid", !action.busy) { selectedTab = "debrid" }
            // Navigation remains enabled during refresh so a restored opener keeps focus.
            ActionButton("Auto downloader", modifier = Modifier.testTag("downloads-auto-downloader")
                .initialTvFocus(autoReturnFocus, autoReturnGranted)) {
                autoReturnTab = selectedTab; selectedTab = "auto"
            }
            ActionButton("Refresh", !action.busy, Modifier.testTag("downloads-refresh")
                .then(if (!action.busy && debridOpener != null && torrents.none { it.id == debridOpener })
                    Modifier.initialTvFocus(debridReturnFocus, debridReturnGranted) else Modifier)
                .then(if (!action.busy && torrentChangeOpener?.startsWith("details:") == true && (torrentClient != "seanime" || torrents.none { "details:${it.id}" == torrentChangeOpener }))
                    Modifier.initialTvFocus(torrentChangeFocus, torrentChangeGranted) else Modifier)) { action.run { reload() } }
        } }
        if (selectedTab == "manga") {
            item { LazyRow(modifier = Modifier.testTag("manga-queue-actions"), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = ActionRowContentPadding) {
                item { ActionButton("Start queue", !action.busy) { action.run("Queue start requested") { check(repo.request("POST", "/api/v1/manga/download-queue/start") == true) { "The server did not accept the queue start request" }; reload() } } }
                item { ActionButton("Pause queue", !action.busy, Modifier.testTag("manga-queue-pause")) { action.run("Queue cancellation requested. Downloads may still be stopping.") { check(repo.request("POST", "/api/v1/manga/download-queue/stop") == true) { "The server did not accept the queue pause request" }; reload() } } }
                item { ActionButton("Retry failed", !action.busy) { action.run("Failed chapters reset") { check(repo.request("POST", "/api/v1/manga/download-queue/reset-errored") == true) { "The server did not reset failed chapters" }; reload() } } }
                item { ActionButton("Clear queue", !action.busy && mangaQueueClearBlocker(chapters, loaded) == null, Modifier.testTag("manga-queue-clear")) { clearQueue = true } }
            } }
            if (chapters.isNotEmpty()) item { Text(mangaQueueClearBlocker(chapters, loaded)
                ?: "Every queued chapter has failed. Clear removes these failed queue records; downloaded files stay on disk.", Modifier.testTag("manga-queue-clear-status")) }
            if (loaded && chapters.isEmpty()) item { EmptyFeature("No queued chapters", "Open a title in Manga, choose a source, and download chapters") }
            items(chapters) { chapter -> FeaturePanel("Chapter ${chapter.text("chapterNumber", chapter.text("chapterId"))}", "Title ${chapter.optLong("mediaId")} · ${chapter.text("provider")} · ${humanizeField(chapter.text("status", "queued"))}") }
        } else {
            if (selectedTab == "torrent" && torrentClient == "seanime") item { ActionRow {
                ActionButton("Add magnet", !action.busy) { magnetDialog = true }
                ActionButton("Pause all", !action.busy) { action.run { requireBuiltInClient(); repo.torrentAction("", "pause-all"); reload() } }
                ActionButton("Resume all", !action.busy) { action.run { requireBuiltInClient(); repo.torrentAction("", "resume-all"); reload() } }
                ActionButton("Speed limits", !action.busy, Modifier.testTag("torrent-global-limits")
                    .then(if (torrentChangeOpener == "limits") Modifier.initialTvFocus(torrentChangeFocus, torrentChangeGranted) else Modifier)) {
                    torrentChangeOpener = "limits"; torrentLimits = true
                }
            } }
            if (loaded && torrents.isEmpty()) item { EmptyFeature("No active torrents", "Choose an anime, find a release under Torrent or Debrid, then select Download. Manage your client or provider in Settings.") }
            items(torrents, key = { it.id }) { torrent ->
                FeaturePanel(torrent.name, "${humanizeField(torrent.status)} · ${"%.0f".format(torrent.progress.coerceIn(0.0, 1.0) * 100)}% · ${torrent.speed}") {
                    LinearProgressIndicator(progress = { torrent.progress.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    ActionRow {
                        if (selectedTab == "debrid") {
                            ActionButton("Download files", !action.busy, Modifier.testTag("downloads-debrid-files-${torrent.id}")
                                .then(if (!action.busy && debridOpener == torrent.id) Modifier.initialTvFocus(debridReturnFocus, debridReturnGranted) else Modifier)) {
                                debridOpener = torrent.id; debridDestination = torrent
                            }
                            ActionButton("Cancel download", !action.busy) { action.run { repo.cancelDebridDownload(torrent.id); reload() } }
                        } else {
                            ActionButton("Pause", !action.busy) { action.run { repo.torrentAction(torrent.id, "pause"); reload() } }
                            ActionButton("Resume", !action.busy) { action.run { repo.torrentAction(torrent.id, "resume"); reload() } }
                            if (torrentClient == "seanime") {
                                ActionButton("Details", !action.busy, Modifier.testTag("torrent-details-${torrent.id}")
                                    .then(if (!action.busy && torrentChangeOpener == "details:${torrent.id}") Modifier.initialTvFocus(torrentChangeFocus, torrentChangeGranted) else Modifier)) {
                                    torrentChangeOpener = "details:${torrent.id}"; inspectingTorrent = torrent
                                }
                                ActionButton("Move files", !action.busy, Modifier.testTag("torrent-move-${torrent.id}")
                                    .then(if (torrentChangeOpener == "move:${torrent.id}") Modifier.initialTvFocus(torrentChangeFocus, torrentChangeGranted) else Modifier)) { torrentChangeOpener = "move:${torrent.id}"; movingTorrent = torrent }
                                ActionButton("Rename", !action.busy, Modifier.testTag("torrent-rename-${torrent.id}")
                                    .then(if (torrentChangeOpener == "rename:${torrent.id}") Modifier.initialTvFocus(torrentChangeFocus, torrentChangeGranted) else Modifier)) { torrentChangeOpener = "rename:${torrent.id}"; renamingTorrent = torrent }
                            }
                        }
                        ActionButton("Remove", !action.busy) { confirm = torrent }
                    }
                }
            }
        }
    }
    if (torrentLimits) NativeTorrentLimitsDialog(repo) { torrentLimits = false; torrentChangeGranted.value = false }
    movingTorrent?.let { torrent -> NativeDownloadDestinationDialog(repo, "Move torrent files", torrent.name, "torrent-move",
        onClose = { movingTorrent = null; torrentChangeGranted.value = false }, onDownload = { destination -> changeNativeTorrent(repo, torrent, directory = destination) },
        onRequested = { movingTorrent = null; torrentChangeGranted.value = false; action.run("Torrent files moved. The server will verify the data.") { reload() } },
        confirmLabel = "Move files here", confirmation = { destination -> "Move ${torrent.name} from ${torrent.raw.text("contentPath", "its current folder")} to $destination? Seanime pauses this torrent, moves its files, and verifies the data." }) }
    renamingTorrent?.let { torrent -> NativeTorrentRenameDialog(repo, torrent, onClose = { renamingTorrent = null; torrentChangeGranted.value = false },
        onRenamed = { renamingTorrent = null; torrentChangeGranted.value = false; action.run("Torrent renamed") { reload() } }) }
    confirm?.let { torrent -> ConfirmFeatureDialog("Remove torrent?", torrent.name + "\nThe configured torrent client will remove this transfer.", { confirm = null }) {
        action.run("Torrent removed") { if (torrent.kind == "debrid") repo.deleteDebridTorrent(torrent) else repo.torrentAction(torrent.id, "remove"); reload() }
    } }
    if (clearQueue) ConfirmFeatureDialog("Clear failed manga queue?", "All queued chapters must still be failed. Their queue records will be removed; downloaded files stay on disk.", { clearQueue = false }) {
        action.run("Failed queue records cleared") {
            chapters = repo.request("GET", "/api/v1/manga/download-queue").jsonObjects()
            loaded = true
            check(mangaQueueClearBlocker(chapters, loaded) == null) { mangaQueueClearBlocker(chapters, loaded).orEmpty() }
            check(repo.request("DELETE", "/api/v1/manga/download-queue") == true) { "The server did not confirm that the queue was cleared" }
            reload()
        }
    }
    debridDestination?.let { torrent -> NativeDebridDownloadDialog(repo, torrent, onClose = {
        debridDestination = null; debridReturnGranted.value = false
    }, onRequested = {
        debridDestination = null; debridReturnGranted.value = false
        action.run("Debrid download requested") { reload() }
    }) }
    if (magnetDialog) TextEntryDialog("Add torrent", "Magnet URI", helper = "The torrent uses the download directory configured in Seanime.", onDismiss = { magnetDialog = false }) { magnet ->
        action.run("Torrent added") {
            require(magnet.startsWith("magnet:?")) { "Enter a magnet URI" }
            requireBuiltInClient()
            repo.request("POST", "/api/v1/torrent-client/action", JSONObject().put("action", "add-magnet").put("magnet", magnet))
            reload()
        }
    }
}

@Composable
private fun NakamaScreen(repo: SeanimeRepository) {
    val action = rememberFeatureAction()
    var configuration by remember { mutableStateOf(JSONObject()) }
    var live by remember { mutableStateOf<JSONObject?>(null) }
    var party by remember { mutableStateOf<JSONObject?>(null) }
    var chat by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var edit by remember { mutableStateOf<String?>(null) }
    var messageDialog by remember { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf(false) }
    val connected by repo.client.connected.collectAsState()
    suspend fun reload() {
        configuration = repo.settings().optJSONObject("nakama") ?: JSONObject()
        repo.client.sendEvent("nakama-status-requested", JSONObject().put("clientId", repo.client.clientId).put("useDenshiPlayer", true))
    }
    LaunchedEffect(repo) { action.run { reload() } }
    LaunchedEffect(repo) {
        repo.client.events.collectOnMain { event ->
            when (event.type) {
                "nakama-status" -> {
                    live = event.payload as? JSONObject
                    party = live?.optJSONObject("currentWatchPartySession")
                }
                "nakama-watch-party-state" -> party = event.payload as? JSONObject
                "nakama-watch-party-chat-message" -> (event.payload as? JSONObject)?.let { item ->
                    chat = (chat + item).distinctBy { it.text("messageId", it.toString()) }.takeLast(100)
                }
            }
        }
    }
    LaunchedEffect(connected) { if (connected) repo.client.sendEvent("nakama-status-requested", JSONObject().put("clientId", repo.client.clientId).put("useDenshiPlayer", true)) }
    val host = live?.optBoolean("isHost") ?: configuration.optBoolean("isHost")
    FeaturePage("Nakama", "Watch together and connect to a shared Seanime library", action) {
        item {
            val statusText = when {
                !configuration.optBoolean("enabled") -> "Nakama is disabled"
                !connected -> "Waiting for the server event connection"
                live == null -> "Waiting for live Nakama status"
                host -> "Hosting · ${live?.optJSONArray("connectedPeers")?.length() ?: 0} connected peers"
                live?.optBoolean("isConnectedToHost") == true -> "Connected to host"
                else -> "Not connected to a host"
            }
            FeaturePanel(statusText, configuration.text("username", "Choose a username in Settings")) {
                ActionRow {
                    ActionButton("Refresh status", !action.busy) { action.run { reload() } }
                    ActionButton("Reconnect", !action.busy && configuration.optBoolean("enabled")) { action.run("Reconnection requested") { repo.nakamaReconnect(); reload() } }
                    ActionButton("Host address", !action.busy) { edit = "remoteServerURL" }
                    ActionButton("Host password", !action.busy) { edit = "remoteServerPassword" }
                }
                Text("Enable Nakama, set your username, and choose host or peer mode in Settings → Nakama.")
            }
        }
        if (host) item {
            val room = live?.optJSONObject("currentRoom")
            FeaturePanel("Room", room?.text("peerJoinUrl").orEmpty().ifBlank { "Create a relay room for peers to connect to this host" }) {
                ActionRow {
                    ActionButton("Create room", !action.busy && configuration.optBoolean("enabled")) { action.run("Room creation requested") { repo.nakamaCreateRoom(); reload() } }
                    ActionButton("Disconnect room", !action.busy && room != null) { disconnect = true }
                }
            }
        }
        item {
            val participants = party?.optJSONObject("participants")
            val count = participants?.length() ?: 0
            val media = party?.optJSONObject("currentMediaInfo")
            FeaturePanel(if (party != null) "Watch party · $count participants" else "Watch party", media?.optJSONObject("media")?.mediaTitle().orEmpty()) {
                ActionRow {
                    if (host) ActionButton("Create watch party", !action.busy && party == null && configuration.optBoolean("enabled")) {
                        action.run("Watch party created") { repo.request("POST", "/api/v1/nakama/watch-party/create", JSONObject()); reload() }
                    } else ActionButton("Join watch party", !action.busy && live?.optBoolean("isConnectedToHost") == true) {
                        action.run("Join request sent") { repo.joinWatchParty(); reload() }
                    }
                    ActionButton(if (host) "End watch party" else "Leave watch party", !action.busy && party != null) { action.run("Watch party left") { repo.leaveWatchParty(); reload() } }
                    ActionButton("Send message", !action.busy && party != null) { messageDialog = true }
                }
                participants?.keys()?.asSequence()?.forEach { id ->
                    val participant = participants.optJSONObject(id) ?: JSONObject()
                    Text("${participant.text("username", id)} · ${if (participant.optBoolean("isBuffering")) "Buffering" else if (participant.optBoolean("isReady")) "Ready" else "Not ready"}${if (participant.optBoolean("isHost")) " · Host" else ""}")
                }
            }
        }
        items(chat.asReversed()) { item -> FeaturePanel(item.text("username", "Participant"), item.text("message")) }
    }
    edit?.let { field -> TextEntryDialog(if (field == "remoteServerURL") "Host connection" else "Host password", humanizeField(field), configuration.text(field), secret = field.endsWith("Password"), allowEmpty = true, onDismiss = { edit = null }) { value ->
        action.run("Nakama settings saved") {
            repo.patchSetting("nakama.$field", value)
            reload()
        }
    } }
    if (messageDialog) TextEntryDialog("Watch party message", "Message", onDismiss = { messageDialog = false }) { message -> action.run("Message sent") { repo.sendNakamaChat(message) } }
    if (disconnect) ConfirmFeatureDialog("Disconnect room?", "Connected peers will lose this relay connection.", { disconnect = false }) { action.run { repo.nakamaDisconnect(); reload() } }
}

private val settingsSections = linkedMapOf(
    "library" to "Library & playback",
    "manga" to "Manga",
    "anilist" to "AniList",
    "torrent" to "Torrent client",
    "mediaPlayer" to "Media player",
    "nakama" to "Nakama",
    "notifications" to "Notifications",
    "discord" to "Discord",
    "autoDownloader" to "Auto downloader",
    "listSync" to "List synchronization",
    "debrid" to "Debrid provider",
    "torrentstream" to "Torrent streaming",
    "mediastream" to "Media streaming & transcoding",
)
private val secondarySettingsEndpoints = mapOf(
    "debrid" to "/api/v1/debrid/settings",
    "torrentstream" to "/api/v1/torrentstream/settings",
    "mediastream" to "/api/v1/mediastream/settings",
)
@Composable
private fun SettingsScreen(repo: SeanimeRepository, onPlatformAction: (String) -> Unit, initialSection: String? = null) {
    var indexTools by rememberSaveable { mutableStateOf(false) }
    var devicePage by rememberSaveable { mutableStateOf(false) }
    val pageStates = rememberSaveableStateHolder()
    val action = rememberFeatureAction()
    var settings by remember { mutableStateOf<JSONObject?>(null) }
    var selected by rememberSaveable { mutableStateOf(initialSection) }
    var restorePage by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreRow by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreGeneration by rememberSaveable { mutableIntStateOf(0) }
    fun requestRowFocus(page: String, row: String? = null) {
        restorePage = page; restoreRow = row; restoreGeneration++
    }
    fun returnToRoot() {
        val origin = if (devicePage) "device" else selected?.let { "section:$it" }
        selected = null; devicePage = false
        requestRowFocus("root", origin)
    }
    ReportNativePluginScreen(NativeScreenLocation("/settings", selected?.let { section ->
        mapOf("tab" to (nativeSettingsTabs.entries.firstOrNull { it.value == section }?.key ?: section))
    } ?: emptyMap()), priority = 1)
    var editing by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf<String?>(null) }
    var editingList by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var advancedOrigin by remember { mutableStateOf("advanced") }
    var settingProviders by remember { mutableStateOf<List<ExtensionItem>>(emptyList()) }
    var settingProviderError by remember { mutableStateOf<String?>(null) }
    var playbackSourceTabs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var playbackSourceError by remember { mutableStateOf<String?>(null) }
    fun fieldChoices(section: String, field: String, current: String): List<SettingChoice> =
        if (section == "library" && field == "defaultPlaybackSource") settingPlaybackSourceChoices(current, playbackSourceTabs)
        else if (settingProviderType(section, field) != null) settingProviderChoices(current, settingProviders)
        else settingChoices(section, field, current)
    suspend fun reload() {
        val current = repo.settings()
        selected?.let { section -> secondarySettingsEndpoints[section]?.let { endpoint ->
            current.put(section, repo.request("GET", endpoint) as? JSONObject ?: error("The backend did not return this configuration"))
        } }
        settings = current
        settingProviders = emptyList(); settingProviderError = null
        playbackSourceTabs = emptyList(); playbackSourceError = null
        if (selected == "library") {
            try { playbackSourceTabs = repo.request("GET", "/api/v1/extensions/list/anime-entry-episode-tabs").jsonObjects() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { playbackSourceError = failure.message ?: "Couldn't load plugin episode sources" }
        }
        val type = when (selected) { "library", "autoDownloader" -> "anime-torrent-provider"; "manga" -> "manga-provider"; else -> null }
        if (type != null) {
            try { settingProviders = repo.providers(type) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { settingProviderError = failure.message ?: "Couldn't load installed providers" }
        }
    }
    suspend fun saveField(section: String, name: String, value: Any) {
        val endpoint = secondarySettingsEndpoints[section]
        if (endpoint != null) {
            val current = repo.request("GET", endpoint) as? JSONObject ?: error("Unable to read current settings")
            current.put(name, value)
            repo.request("PATCH", endpoint, JSONObject().put("settings", current))
        } else {
            // The server patches one path atomically, preserving unrelated concurrent settings edits.
            repo.patchSetting("$section.$name", value)
        }
        reload()
    }
    LaunchedEffect(repo, selected) { action.run { reload() } }
    if (indexTools) {
        NativeLibraryIndexScreen(repo, onPlatformAction) {
            indexTools = false; requestRowFocus("root", "library-index")
        }
        return
    }
    BackHandler(selected != null || devicePage, onBack = ::returnToRoot)
    val selectedSection = selected
    val pageKey = if (devicePage) "device" else selectedSection?.let { "section:$it" } ?: "root"
    val category = selectedSection?.let { settings?.optJSONObject(it) }
    val deviceActions = listOf(
        Triple("anime-folder", "Anime folder", "storage:library-main"),
        Triple("manga-folder", "Manga folder", "storage:manga-local"),
        Triple("additional-anime-folder", "Additional anime folder", "storage:library-additional"),
        Triple("screenshot-folder", "Screenshot folder", "storage:screenshot"),
        Triple("torrent-folder", "Torrent download folder", "storage:torrent-stream"),
        Triple("folder-access", "Manage folder access", "storage:manage"),
        Triple("anilist", "Connect AniList", "oauth:anilist"),
        Triple("mal", "Connect MyAnimeList", "oauth:mal"),
        Triple("accounts", "Manage accounts", "accounts"),
        Triple("update", "Check app update", "update"),
    )
    fun settingValue(field: String, value: Any?): String = when {
        settingIsSecret(field) -> if (category?.text(field).isNullOrBlank()) "Not set" else "Saved ••••••••"
        value is Boolean -> if (value) "Enabled" else "Disabled"
        value == null || value == JSONObject.NULL -> "Not set"
        value is JSONArray -> "${value.length()} " + when (field) {
            "libraryPaths" -> if (value.length() == 1) "folder" else "folders"
            "hostUnsharedAnimeIds" -> if (value.length() == 1) "hidden title" else "hidden titles"
            else -> if (value.length() == 1) "entry" else "entries"
        }
        value is JSONObject -> "Advanced options"
        else -> {
            val current = value.toString()
            val choice = selectedSection?.let { fieldChoices(it, field, current).firstOrNull { item -> item.value == current } }
            when {
                choice != null && choice.label.startsWith("Current provider:") -> "Saved provider unavailable"
                choice != null && choice.label.startsWith("Current source:") -> "Saved source unavailable"
                choice != null && choice.label.startsWith("Current:") -> "Saved custom value"
                choice != null -> choice.label
                current.startsWith("content://") -> "Android folder selected"
                else -> current.ifBlank { "Not set" }
            }
        }
    }
    val fields = category?.keys()?.asSequence()?.filter {
        it !in setOf("id", "createdAt", "updatedAt", "deletedAt", "dohProvider", "autoSelectTorrentProvider", "hideTorrentList", "enableEnhancedQueries")
    }?.toList()?.sorted().orEmpty()
    val rows = when {
        devicePage -> deviceActions.map { (id, label, _) -> NativeSettingsRow("device:$id", label) }
        selectedSection == null -> listOf(
            NativeSettingsRow("device", "Device & accounts", detail = "Folders, accounts and app updates"),
            NativeSettingsRow("library-index", "Library index backup", detail = "Back up or restore file matches, locks and ignored flags",
                testTag = "settings-library-index"),
        ) + settingsSections.map { (key, label) -> NativeSettingsRow("section:$key", label, enabled = settings != null && !action.busy) }
        category != null -> fields.map { field ->
            val detail = when {
                selectedSection == "library" && field == "defaultPlaybackSource" -> playbackSourceError.orEmpty()
                settingProviderType(selectedSection, field) != null -> settingProviderError
                    ?: if (settingProviders.isEmpty()) "Install a provider from Extensions to select it here." else ""
                field in setOf("libraryPaths", "hostUnsharedAnimeIds") -> "Open to review or edit the full list"
                category.opt(field) is JSONObject || category.opt(field) is JSONArray -> "Advanced configuration"
                else -> ""
            }
            NativeSettingsRow("field:$field", settingLabel(field), settingValue(field, category.opt(field)), detail, enabled = !action.busy)
        } + NativeSettingsRow("advanced", "Advanced JSON editor", detail = "Review this section's advanced configuration", enabled = !action.busy)
        else -> emptyList()
    }
    fun openAdvanced(origin: String) { advancedOrigin = origin; advanced = true }
    fun activateRow(id: String) {
        when {
            devicePage -> deviceActions.firstOrNull { "device:${it.first}" == id }?.let { onPlatformAction(it.third) }
            id == "device" -> { devicePage = true; requestRowFocus("device") }
            id == "library-index" -> indexTools = true
            id.startsWith("section:") -> {
                selected = id.removePrefix("section:"); requestRowFocus(id)
            }
            id == "advanced" -> openAdvanced(id)
            id.startsWith("field:") && selectedSection != null && category != null -> {
                val field = id.removePrefix("field:")
                val value = category.opt(field)
                when {
                    field == "libraryPath" -> onPlatformAction("storage:library-main")
                    field == "mangaLocalSourceDirectory" -> onPlatformAction("storage:manga-local")
                    selectedSection == "torrentstream" && field == "downloadDir" -> onPlatformAction("storage:torrent-stream")
                    field in setOf("libraryPaths", "hostUnsharedAnimeIds") -> editingList = field
                    value is Boolean -> {
                        requestRowFocus(pageKey, id)
                        action.run("Setting saved") { saveField(selectedSection, field, !value) }
                    }
                    fieldChoices(selectedSection, field, category.text(field)).isNotEmpty() -> choosing = field
                    value !is JSONObject && value !is JSONArray -> editing = field
                    else -> openAdvanced(id)
                }
            }
        }
    }
    pageStates.SaveableStateProvider(pageKey) {
        val listState = rememberLazyListState()
        var lastFocusedRow by rememberSaveable { mutableStateOf<String?>(null) }
        val rowFocus = remember { FocusRequester() }
        val rowFocusGranted = remember { mutableStateOf(true) }
        val requestedRow = if (restorePage == pageKey) {
            // A TV row remains focusable while its mutation is disabled. Keep
            // the remote on that row during reloads instead of waiting unfocused.
            rows.firstOrNull { it.id == restoreRow }?.id
                ?: rows.firstOrNull { it.id == lastFocusedRow }?.id
                ?: rows.firstOrNull()?.id
        } else null
        LaunchedEffect(restoreGeneration, requestedRow, action.busy) {
            if (requestedRow != null) {
                val index = rows.indexOfFirst { it.id == requestedRow }
                if (index >= 0) {
                    // Keep the saved position when the opener is already visible.
                    if (listState.layoutInfo.visibleItemsInfo.none { it.key == requestedRow }) listState.scrollToItem(index)
                    rowFocusGranted.value = false
                }
            }
        }
        NativeSettingsList(
            title = if (devicePage) "Device & accounts" else selectedSection?.let { settingsSections[it] } ?: "Settings",
            rows = rows, onActivate = ::activateRow, state = listState,
            subtitle = if (devicePage) "Folders, accounts and app updates" else "",
            busy = action.busy, error = action.error ?: if (selectedSection != null && category == null && !action.busy && settings != null) "The backend did not return this configuration section" else null,
            message = action.message,
            onBack = if (selectedSection != null || devicePage) ::returnToRoot else null,
            onRefresh = { action.run { reload() } },
            rowModifier = { id -> if (id == requestedRow) Modifier.initialTvFocus(rowFocus, rowFocusGranted) else Modifier },
            onRowFocused = { id ->
                // Reattaching a page may briefly focus another visible row. That
                // fallback must not replace the saved row we are still restoring.
                // A departing page can still receive Android fallback focus before
                // its nodes are disposed. It no longer owns its saved focus state.
                val activePage = if (devicePage) "device" else selected?.let { "section:$it" } ?: "root"
                if (!indexTools && activePage == pageKey && (restorePage != pageKey || requestedRow == id)) {
                    lastFocusedRow = id
                    if (restorePage == pageKey) { restorePage = null; restoreRow = null }
                }
            },
            modifier = Modifier.testTag("settings-page-$pageKey").onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key in listOf(
                        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
                    ) && restorePage == pageKey) {
                    // An actual newer remote action owns focus. A pending restore
                    // must not steal it back after layout or a refresh completes.
                    restorePage = null; restoreRow = null; rowFocusGranted.value = true
                }
                false
            },
        )
    }
    fun restoreField(field: String) { requestRowFocus(pageKey, "field:$field") }
    editing?.let { field -> if (selectedSection != null) TextEntryDialog(settingLabel(field), "Value", category?.text(field).orEmpty(),
        secret = settingIsSecret(field), allowEmpty = category?.opt(field) !is Number,
        onDismiss = { editing = null; restoreField(field) }) { value ->
        action.run("Setting saved") {
            val previous = category?.opt(field)
            val parsed: Any = when (previous) {
                is Int, is Long -> validateSettingNumber(field, value, integral = true)
                is Number -> validateSettingNumber(field, value, integral = false)
                else -> value
            }
            saveField(selectedSection, field, parsed)
        }
    } }
    choosing?.let { field -> if (selectedSection != null && category != null) SettingChoiceDialog(settingLabel(field), category.text(field),
        fieldChoices(selectedSection, field, category.text(field)), onDismiss = { choosing = null; restoreField(field) }) { value ->
        action.run("Setting saved") { saveField(selectedSection, field, value) }
    } }
    editingList?.let { field -> if (selectedSection != null && category != null) {
        val save: (JSONArray) -> Unit = { value -> action.run("Setting saved") { saveField(selectedSection, field, value) } }
        val dismiss = { editingList = null; restoreField(field) }
        if (field == "hostUnsharedAnimeIds") HiddenTitlesDialog(repo, category.optJSONArray(field) ?: JSONArray(), dismiss, save)
        else SettingListDialog(field, category.optJSONArray(field) ?: JSONArray(), dismiss, save)
    } }
    if (advanced && selectedSection != null && category != null) TextEntryDialog("Advanced JSON: ${settingsSections[selectedSection]}", "Section JSON", redactSettingsForEditor(category).toString(2),
        helper = "Advanced configuration. Only this section is replaced. Sensitive values are hidden and preserved; use their masked fields to change them. Malformed JSON is rejected.", multiline = true,
        onDismiss = { advanced = false; requestRowFocus(pageKey, advancedOrigin) }) { value -> action.run("Settings saved") {
        val endpoint = secondarySettingsEndpoints[selectedSection]
        val current = if (endpoint == null) repo.settings().optJSONObject(selectedSection) ?: JSONObject()
            else repo.request("GET", endpoint) as? JSONObject ?: error("Unable to read current settings")
        val replacement = preserveSettingSecrets(JSONObject(value), current)
        if (endpoint == null) repo.patchSetting(selectedSection, replacement)
        else repo.request("PATCH", endpoint, JSONObject().put("settings", replacement))
        reload()
    } }
}

@Composable
private fun LogsScreen(repo: SeanimeRepository, onPlatformAction: (String) -> Unit) {
    val action = rememberFeatureAction()
    var logs by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filterDialog by remember { mutableStateOf(false) }
    var reportDialog by remember { mutableStateOf(false) }
    var reportReady by remember { mutableStateOf(false) }
    var exportKind by remember { mutableStateOf<NativeExportKind?>(null) }
    var exportOpener by remember { mutableStateOf<NativeExportKind?>(null) }
    val exportFocus = remember { FocusRequester() }
    val exportGranted = remember { mutableStateOf(true) }
    suspend fun reload() { logs = repo.logs(); loaded = true }
    LaunchedEffect(repo) { action.run { reload() } }
    val lines = remember(logs, query) { logs.lineSequence().filter { it.isNotBlank() && (query.isBlank() || it.contains(query, true)) }.toList().takeLast(500).asReversed() }
    FeaturePage("Logs & diagnostics", "Latest server log · newest first · up to 500 matching lines", action) {
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = ActionRowContentPadding) {
            item { ActionButton("Refresh", !action.busy) { action.run { reload() } } }
            item { ActionButton("Filter") { filterDialog = true } }
            if (query.isNotBlank()) item { ActionButton("Clear filter") { query = "" } }
            item { ActionButton("Create issue report", !action.busy) { reportReady = false; reportDialog = true } }
            if (reportReady) item { ActionButton("Export report", !action.busy) { onPlatformAction("report") } }
        } }
        if (query.isNotBlank()) item { Text("Filter: $query") }
        item { FeaturePanel("Server profiles", "Diagnostic captures for memory and performance analysis") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = ActionRowContentPadding) {
                items(NativeExportKind.entries.filter { it != NativeExportKind.LIBRARY_INDEX }, key = { it.name }) { kind ->
                    ActionButton("Export ${kind.label.lowercase()}", !action.busy, Modifier.testTag("profile-export-${kind.name.lowercase()}")
                        .then(if (exportOpener == kind) Modifier.initialTvFocus(exportFocus, exportGranted) else Modifier)) { exportOpener = kind; exportKind = kind }
                }
            }
        } }
        if (loaded && lines.isEmpty()) item { EmptyFeature("No log lines", if (query.isBlank()) "The backend has no recent log content" else "No lines match this filter") }
        items(lines.size, key = { it }) { index ->
            var focused by remember { mutableStateOf(false) }
            Text(lines[index], fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.focusable()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f), RoundedCornerShape(8.dp))
                    .border(if (focused) 2.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp)).padding(14.dp))
        }
    }
    if (filterDialog) TextEntryDialog("Filter logs", "Search text", query, onDismiss = { filterDialog = false }) { query = it }
    exportKind?.let { kind -> NativeExportDialog(repo, kind) { exportKind = null; exportGranted.value = false } }
    if (reportDialog) NativeIssueReportDialog(repo, onClose = { reportDialog = false }, onCreated = {
        reportDialog = false; reportReady = true; action.message = "Report prepared. Export it to save a copy."
    })
}

@Composable
private fun NativeExtensionPlayground(repo: SeanimeRepository, extension: ExtensionItem?, onBack: () -> Unit) {
    val action = rememberFeatureAction()
    var type by remember { mutableStateOf(extension?.type ?: "manga-provider") }
    var language by remember { mutableStateOf(extension?.raw?.text("language")?.takeIf { it in setOf("javascript", "typescript") } ?: "typescript") }
    var code by remember { mutableStateOf("") }
    var function by remember { mutableStateOf("search") }
    var mediaId by rememberSaveable { mutableStateOf("") }
    var inputs by remember { mutableStateOf(JSONObject().put("id", "").put("query", "").put("dub", false).put("server", "")) }
    var output by remember { mutableStateOf<JSONObject?>(null) }
    var edit by remember { mutableStateOf<String?>(null) }
    var runConfirmation by remember { mutableStateOf(false) }
    var replaceConfirmation by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    LaunchedEffect(extension?.id) { if (extension != null) action.run {
        code = repo.request("GET", "/api/v1/extensions/payload/${SeanimeRepository.encodePathSegment(extension.id)}") as? String ?: ""
    } }
    val functions = when (type) {
        "manga-provider" -> listOf("search", "findChapters", "findChapterPages")
        "onlinestream-provider" -> listOf("search", "findEpisodes", "findEpisodeServer")
        else -> listOf("search", "smartSearch", "getTorrentInfoHash", "getTorrentMagnetLink", "getLatest", "getSettings")
    }
    FeaturePage("Extension playground", extension?.name ?: "Develop and test a provider against the existing backend", action) {
        item { ActionRow {
            ActionButton("Back", onClick = onBack)
            ActionButton("Edit source", !action.busy) { edit = "code" }
            ActionButton("Run provider", !action.busy && code.isNotBlank() && parseNativeMediaId(mediaId) != null) { runConfirmation = true }
            if (extension != null && extension.manifestUrl != "builtin") ActionButton("Replace installed code", !action.busy && code.isNotBlank()) { replaceConfirmation = true }
        } }
        item { FeaturePanel("Provider", "Code stays in this editor until you run it or replace installed code") {
            ActionRow {
                ActionButton(humanizeField(type)) {
                    val types = listOf("manga-provider", "onlinestream-provider", "anime-torrent-provider")
                    type = types[(types.indexOf(type) + 1) % types.size]; function = "search"
                }
                ActionButton(language) { language = if (language == "typescript") "javascript" else "typescript" }
                ActionButton("AniList media ID: ${mediaId.ifBlank { "Not set" }}") { edit = "mediaId" }
            }
            Text("${code.lineSequence().count()} lines · ${code.length} characters")
        } }
        item { FeaturePanel("Function") { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = ActionRowContentPadding) {
            items(functions) { name -> ActionButton((if (function == name) "✓ " else "") + name) { function = name } }
        } } }
        item { FeaturePanel("Function inputs") {
            when (function) {
                "search" -> if (type == "anime-torrent-provider") ActionButton("Query: ${inputs.text("query").ifBlank { "Not set" }}") { edit = "query" }
                    else if (type == "onlinestream-provider") ActionButton(if (inputs.optBoolean("dub")) "Dubbed" else "Subtitled") { inputs = JSONObject(inputs.toString()).put("dub", !inputs.optBoolean("dub")) }
                "findChapters", "findChapterPages", "findEpisodes" -> ActionButton("Provider ID: ${inputs.text("id").ifBlank { "Not set" }}") { edit = "id" }
                "findEpisodeServer" -> {
                    ActionButton("Episode object (advanced JSON)") { edit = "episode" }
                    ActionButton("Server: ${inputs.text("server").ifBlank { "Not set" }}") { edit = "server" }
                }
                "getTorrentInfoHash", "getTorrentMagnetLink" -> ActionButton("Torrent object (advanced JSON)") { edit = "torrent" }
                "smartSearch" -> ActionButton("Smart-search options (advanced JSON)") { edit = "options" }
            }
        } }
        output?.let { result ->
            item { FeaturePanel("Execution log", result.text("logs").ifBlank { "No log output" }) }
            item { FeaturePanel("Return value · developer output") { Text(result.text("value", "No return value"), fontFamily = FontFamily.Monospace) } }
        }
    }
    edit?.let { field -> TextEntryDialog(when (field) { "code" -> "Provider source"; "mediaId" -> "AniList media ID"; else -> humanizeField(field) },
        if (field in setOf("options", "episode", "torrent")) "Advanced JSON" else "Value",
        when (field) { "code" -> code; "mediaId" -> mediaId; "options" -> inputs.optJSONObject("options")?.toString(2) ?: "{\"query\":\"\",\"batch\":false,\"episodeNumber\":1,\"resolution\":\"1080p\",\"bestReleases\":false}"; else -> inputs.text(field) },
        multiline = field in setOf("code", "options", "episode", "torrent"), allowEmpty = field !in setOf("mediaId", "options", "episode", "torrent"),
        onDismiss = { edit = null }) { value ->
        action.run {
            when (field) {
                "code" -> code = value
                "mediaId" -> { require(parseNativeMediaId(value) != null) { "Enter a positive media ID" }; mediaId = value }
                "options" -> inputs = JSONObject(inputs.toString()).put(field, JSONObject(value))
                "episode", "torrent" -> { JSONObject(value); inputs = JSONObject(inputs.toString()).put(field, value) }
                else -> inputs = JSONObject(inputs.toString()).put(field, value)
            }
        }
    } }
    if (runConfirmation) ConfirmFeatureDialog("Run provider code?", "The source in this editor will execute in your Seanime backend and can make network requests. Run only code you trust.", { runConfirmation = false }) {
        action.run("Provider run completed") {
            val params = JSONObject().put("type", type).put("language", language).put("code", code).put("function", function)
                .put("inputs", JSONObject(inputs.toString()).put("mediaId", mediaId.toLong()))
            output = repo.request("POST", "/api/v1/extensions/playground/run", JSONObject().put("params", params)) as? JSONObject
        }
    }
    if (replaceConfirmation && extension != null) ConfirmFeatureDialog("Replace ${extension.name} code?", "This changes the installed provider payload and reloads it. Keep a copy of the original source if you need to restore it.", { replaceConfirmation = false }) {
        action.run("Installed provider code updated") { repo.request("POST", "/api/v1/extensions/external/edit-payload", JSONObject().put("id", extension.id).put("payload", code)) }
    }
}
