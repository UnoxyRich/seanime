package app.seanime.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.Card as TvCard
import app.seanime.tv.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Each destination is a native screen. No URL or web router is part of navigation. */
enum class TvFeature(val label: String, val subtitle: String) {
    LIBRARY("Library", "Your collection, ready for the big screen"),
    ANILIST("AniList & MAL", "Discover shows and keep your lists in sync"),
    MANGA("Manga", "Read a chapter at your own pace"),
    OFFLINE("Offline", "Your saved collection"),
    PLAYLISTS("Playlists", "Build your next viewing session"),
    EXTENSIONS("Extensions", "Providers and plugins"),
    STREAMING("Streaming", "Online, torrent and debrid sources"),
    DOWNLOADS("Downloads", "Follow your transfers"),
    NAKAMA("Nakama", "Your shared watching space"),
    SETTINGS("Settings", "Make this TV yours"),
    LOGS("Logs & reports", "Diagnostics you can take with you"),
}

private val Ink = Color(0xFF101723)
private val Panel = Color(0xFF1D293B)
private val Accent = Color(0xFFBDCCFF)

@Composable
fun SeanimeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel,
            onBackground = Color(0xFFF0F3FA), onSurface = Color(0xFFF0F3FA)),
        typography = Typography(
            headlineLarge = androidx.compose.ui.text.TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
            headlineMedium = androidx.compose.ui.text.TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
            headlineSmall = androidx.compose.ui.text.TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
            bodySmall = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            labelSmall = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        ),
    ) {
        androidx.tv.material3.MaterialTheme(
            typography = androidx.tv.material3.Typography(
                displayLarge = MaterialTheme.typography.displayLarge, displayMedium = MaterialTheme.typography.displayMedium,
                displaySmall = MaterialTheme.typography.displaySmall, headlineLarge = MaterialTheme.typography.headlineLarge,
                headlineMedium = MaterialTheme.typography.headlineMedium, headlineSmall = MaterialTheme.typography.headlineSmall,
                titleLarge = MaterialTheme.typography.titleLarge, titleMedium = MaterialTheme.typography.titleMedium,
                titleSmall = MaterialTheme.typography.titleSmall, bodyLarge = MaterialTheme.typography.bodyLarge,
                bodyMedium = MaterialTheme.typography.bodyMedium, bodySmall = MaterialTheme.typography.bodySmall,
                labelLarge = MaterialTheme.typography.labelLarge, labelMedium = MaterialTheme.typography.labelMedium,
                labelSmall = MaterialTheme.typography.labelSmall,
            ),
            colorScheme = androidx.tv.material3.darkColorScheme(primary = Accent, background = Ink, surface = Panel,
                onBackground = Color(0xFFF0F3FA), onSurface = Color(0xFFF0F3FA)),
        ) {
            // MaterialTheme defines a palette but does not apply a foreground to
            // our root Box/Column. Both component families use separate locals;
            // provide both so native text and dialogs remain readable, while TV
            // buttons/cards can still override their own focused foreground.
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                androidx.tv.material3.LocalContentColor provides androidx.tv.material3.MaterialTheme.colorScheme.onSurface,
                content = content,
            )
        }
    }
}

@Composable
fun SeanimeTvApp(
    repo: SeanimeRepository,
    status: ServerStatus,
    onPlay: (PlaybackRequest) -> Unit,
    onPlatformAction: (String) -> Unit,
    onExit: () -> Unit,
) {
    NativeArtworkProvider(repo.client) {
        NativePluginActionProvider(repo) {
        SeanimeTvAppContent(repo, status, onPlay, onPlatformAction, onExit)
        }
    }
}

@Composable
private fun SeanimeTvAppContent(
    repo: SeanimeRepository,
    status: ServerStatus,
    onPlay: (PlaybackRequest) -> Unit,
    onPlatformAction: (String) -> Unit,
    onExit: () -> Unit,
) {
    NativeExtensionPrompts(repo)
    var destinationName by rememberSaveable { mutableStateOf(TvFeature.LIBRARY.name) }
    val destination = TvFeature.valueOf(destinationName)
    var selectedId by rememberSaveable { mutableLongStateOf(0L) }
    var pluginRoutePath by rememberSaveable { mutableStateOf<String?>(null) }
    var pluginRouteGeneration by rememberSaveable { mutableIntStateOf(0) }
    val initialRoute = remember(pluginRoutePath) { pluginRoutePath?.let { runCatching { resolveNativePluginDestination(it) }.getOrNull() } }
    val pluginScreens = remember(repo) { NativePluginScreens() }
    var showExit by remember { mutableStateOf(false) }
    var railHasFocus by remember { mutableStateOf(false) }
    var detailReturnPending by remember { mutableStateOf(false) }
    var detailReturnCancelled by remember { mutableStateOf(false) }
    val contentFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    val railFocusGranted = remember { mutableStateOf(false) }
    val screenStates = rememberSaveableStateHolder()
    val railState = rememberLazyListState()
    val navigationScope = rememberCoroutineScope()
    fun cancelDetailReturn() {
        if (detailReturnPending) { detailReturnPending = false; detailReturnCancelled = true }
    }
    fun closeDetails() {
        // Removing the detail's focused node can temporarily give focus to the rail.
        // That automatic fallback is not a newer remote navigation choice.
        detailReturnPending = true
        detailReturnCancelled = false
        selectedId = 0L
    }
    fun openDetails(id: Long) {
        detailReturnPending = false
        detailReturnCancelled = false
        selectedId = id
    }
    fun navigatePlugin(target: NativePluginDestination) {
        cancelDetailReturn()
        pluginRoutePath = target.location.path
        pluginRouteGeneration++
        destinationName = target.feature.name
        selectedId = target.animeId
        showExit = false
        railFocusGranted.value = false
        navigationScope.launch { railState.scrollToItem(target.feature.ordinal) }
    }
    NativePluginScreenBridge(repo, pluginScreens, ::navigatePlugin) {
        runCatching { resolveNativePluginDestination(pluginScreens.current.path) }
            .onSuccess(::navigatePlugin).onFailure { pluginScreens.notice = it.message ?: "This native screen cannot be reloaded by the plugin" }
    }
    LaunchedEffect(Unit) {
        railState.scrollToItem(destination.ordinal)
    }
    BackHandler {
        when {
            selectedId != 0L -> closeDetails()
            !railHasFocus || detailReturnPending -> {
                cancelDetailReturn()
                railFocusGranted.value = false
                navigationScope.launch {
                    railState.scrollToItem(destination.ordinal)
                }
            }
            else -> showExit = true
        }
    }
    CompositionLocalProvider(LocalNativePluginScreens provides pluginScreens,
        LocalNativeNavigationOwnsFocus provides (railHasFocus && !detailReturnPending)) {
    ReportNativePluginScreen(nativeFeatureLocation(destination), priority = 0)
    NativeTvScaffold(destination, if (status.offline) "Offline mode" else "Server connected", railHasFocus,
        onNavigate = { feature -> cancelDetailReturn(); destinationName = feature.name; selectedId = 0L; pluginRoutePath = null },
        onRailFocusChanged = { railHasFocus = it }, contentFocus = contentFocus, railFocus = railFocus,
        railFocusGranted = railFocusGranted, railState = railState,
        onContentInteraction = ::cancelDetailReturn, onRailInteraction = ::cancelDetailReturn) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(pluginRouteGeneration) {
                screenStates.SaveableStateProvider("$pluginRouteGeneration-" + if (selectedId != 0L) "details-$selectedId" else "destination-${destination.name}") {
                when {
                    selectedId != 0L -> AnimeDetailScreen(selectedId, repo, onPlay, onBack = ::closeDetails,
                        initialSourceMode = initialRoute?.takeIf { it.animeId == selectedId }?.sourceMode,
                        initialEpisode = initialRoute?.takeIf { it.animeId == selectedId }?.episode ?: 1)
                    destination in setOf(TvFeature.LIBRARY, TvFeature.ANILIST, TvFeature.STREAMING) ->
                        BrowseScreen(destination, repo, ::openDetails, onPlatformAction, onPlay, initialRoute, status.userName,
                            railHasFocus && !detailReturnPending, detailReturnPending, detailReturnCancelled,
                            onDetailFocusRestored = { detailReturnPending = false })
                    else -> FeatureScreen(destination, repo, onPlay, onPlatformAction, initialRoute)
                }
                }
                }
            }
        }
    }
    if (showExit) {
        val stayFocus = remember { FocusRequester() }
        val stayFocusGranted = remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { showExit = false }, title = { Text("Leave Seanime?") },
        text = { Text("Playback and transfers pause when the app goes into the background.") },
        confirmButton = { TvButton(onClick = onExit, modifier = Modifier.testTag("exit-confirm")) { Text("Exit") } },
        dismissButton = { TvButton(onClick = { showExit = false }, modifier = Modifier.initialTvFocus(stayFocus, stayFocusGranted)) { Text("Stay") } })
    }
    }
}

@Composable
private fun BrowseScreen(feature: TvFeature, repo: SeanimeRepository, onDetails: (Long) -> Unit, onPlatformAction: (String) -> Unit, onPlay: (PlaybackRequest) -> Unit,
    initialRoute: NativePluginDestination? = null, profileName: String = "", navigationOwnsFocus: Boolean = false,
    detailReturnPending: Boolean = false, detailReturnCancelled: Boolean = false, onDetailFocusRestored: () -> Unit = {}) {
    var tools by rememberSaveable { mutableStateOf(initialRoute?.libraryTab != null) }
    var discovery by rememberSaveable(feature) { mutableStateOf(initialRoute?.discovery != null) }
    var airing by rememberSaveable(feature) { mutableStateOf(initialRoute?.airing != null) }
    val discoveryStates = rememberSaveableStateHolder()
    val discoveryFocus = remember { FocusRequester() }
    val discoveryFocusGranted = rememberSaveable(feature) { mutableStateOf(true) }
    var query by rememberSaveable(feature) { mutableStateOf("") }
    var search by rememberSaveable(feature) { mutableStateOf("") }
    var showSearch by rememberSaveable(feature) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var collection by remember { mutableStateOf(PersonalCollection()) }
    var collectionStatus by rememberSaveable(feature) { mutableStateOf("") }
    var collectionSortName by rememberSaveable(feature) { mutableStateOf(PersonalCollectionSort.SERVER_ORDER.name) }
    val collectionSort = PersonalCollectionSort.valueOf(collectionSortName)
    var sortContext by remember { mutableStateOf(PersonalCollectionSortContext()) }
    var options by remember { mutableStateOf(false) }
    val optionsFocus = remember { FocusRequester() }
    val optionsFocusGranted = rememberSaveable(feature) { mutableStateOf(true) }
    var resetPosition by remember { mutableStateOf(false) }
    val media = remember(collection, search, collectionStatus, collectionSort, sortContext) {
        filterPersonalCollection(collection, search, collectionStatus, collectionSort, sortContext).map { it.media }
    }
    var lastFocused by rememberSaveable(feature) { mutableLongStateOf(0L) }
    val cardFocusGranted = rememberSaveable(feature) { mutableStateOf(true) }
    val searchFocusGranted = rememberSaveable(feature) { mutableStateOf(true) }
    val gridState = rememberLazyGridState()
    val cardFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val manageFocus = remember { FocusRequester() }
    val manageFocusGranted = rememberSaveable(feature) { mutableStateOf(true) }
    val toolsStates = rememberSaveableStateHolder()
    fun submitSearch(value: String) { query = value; search = value.trim(); resetPosition = true }
    // A late collection response must never override a newer choice to return to navigation.
    LaunchedEffect(navigationOwnsFocus) {
        if (navigationOwnsFocus) cardFocusGranted.value = true
    }
    LaunchedEffect(detailReturnCancelled) {
        if (detailReturnCancelled) cardFocusGranted.value = true
    }
    LaunchedEffect(feature, refresh, collectionSort) {
        loading = true; error = null
        try {
            collection = loadPersonalCollection(repo, library = feature == TvFeature.LIBRARY)
            sortContext = loadPersonalCollectionSortContext(repo, collectionSort)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Couldn't load the collection" }
        finally { loading = false }
    }
    LaunchedEffect(loading, media, resetPosition, discovery, airing, tools) {
        if (!tools && !discovery && !airing && !loading && resetPosition && media.isNotEmpty()) {
            gridState.scrollToItem(0)
            resetPosition = false
        }
    }
    LaunchedEffect(loading, media, cardFocusGranted.value, discovery, airing, tools, navigationOwnsFocus) {
        if (!navigationOwnsFocus && !tools && !discovery && !airing && !loading && !cardFocusGranted.value) {
            val index = if (error == null) media.indexOfFirst { it.id == lastFocused } else -1
            if (index >= 0) {
                // The selected card requests focus only once its lazy item is placed.
                gridState.scrollToItem(index)
            } else {
                searchFocusGranted.value = false
                cardFocusGranted.value = true
                onDetailFocusRestored()
            }
        }
    }
    // Keep collection/query/grid state composed while a management page is open.
    // Its own saved state preserves the selected tool and folder between visits.
    if (tools) {
        toolsStates.SaveableStateProvider("library-tools") {
            LibraryTools(repo, onPlay, initialTab = initialRoute?.libraryTab ?: "Files",
                initialDirectory = initialRoute?.libraryDirectory.orEmpty(), onClose = {
                    tools = false; manageFocusGranted.value = false; refresh++
                })
        }
        return
    }
    if (airing) {
        discoveryStates.SaveableStateProvider("airing") {
            RecentAiringScreen(repo, initialRoute?.airing ?: RecentAiringFilters.around(), initialRoute?.discoveryPage ?: 1, onDetails,
                onBack = { airing = false; if (!discovery) discoveryFocusGranted.value = false })
        }
        return
    }
    if (discovery) {
        discoveryStates.SaveableStateProvider("catalog") {
            AnimeDiscoveryScreen(repo, onDetails, onBack = { discovery = false },
                initialFilters = initialRoute?.discovery ?: AnimeDiscoveryFilters(), initialPage = initialRoute?.discoveryPage ?: 1, onAiring = { airing = true })
        }
        return
    }
    ReportNativePluginScreen(nativeFeatureLocation(feature), priority = 1)
    NativeCollectionPage(feature.label, profileName, toolbar = {
        NativeCollectionToolbar(search, feature == TvFeature.LIBRARY,
            searchModifier = Modifier.initialTvFocus(searchFocus, searchFocusGranted),
            optionsModifier = Modifier.initialTvFocus(optionsFocus, optionsFocusGranted),
            discoverModifier = Modifier.initialTvFocus(discoveryFocus, discoveryFocusGranted),
            manageModifier = Modifier.initialTvFocus(manageFocus, manageFocusGranted),
            onFocused = { if (!detailReturnPending) cardFocusGranted.value = true },
            onSearch = { showSearch = true }, onOptions = { options = true },
            onDiscover = { cardFocusGranted.value = true; discoveryFocusGranted.value = false; discovery = true },
            onManage = { tools = true })
    }, pluginActions = {
        NativePluginActions(repo, listOf(NativePluginActionKind.ANIME_LIBRARY), label = "Library plugin actions")
    }) {
        when {
            loading -> LoadingMessage("Loading your collection…")
            error != null -> ErrorMessage(error!!, { searchFocusGranted.value = false; refresh++ })
            media.isEmpty() -> EmptyMessage(if (collection.entries.isEmpty()) "Your collection starts here" else "No matching titles",
                if (collection.entries.isEmpty()) { if (feature == TvFeature.LIBRARY) "Add a media folder in Settings and scan it, or use Discover to find a show."
                    else "Use Discover to find a show or connect your AniList account." }
                else "Try a different collection search or choose All statuses in Lists & sort.")
            else -> NativeCollectionGrid(media, gridState,
                cardModifier = { id -> if (!navigationOwnsFocus && lastFocused == id) Modifier.initialTvFocus(cardFocus, cardFocusGranted) else Modifier },
                onCardFocused = { id ->
                    if (!detailReturnPending || id == lastFocused) {
                        lastFocused = id; cardFocusGranted.value = true; onDetailFocusRestored()
                    }
                },
                cardActions = { card -> NativePluginActions(repo, listOf(NativePluginActionKind.MEDIA_CARD), media = card, label = "Title actions") },
                onDetails = { id -> lastFocused = id; cardFocusGranted.value = false; onDetails(id) })
        }
    }
    if (showSearch) TextEntryDialog("Search my collection", "Title or keyword", initial = query,
        allowEmpty = true, submitLabel = "Search", submitOnIme = true, inputModifier = Modifier.testTag("anime-search-field"),
        onDismiss = { showSearch = false; searchFocusGranted.value = false }, onSubmit = ::submitSearch)
    if (options) NativeCollectionOptionsDialog(false, collectionStatus, collectionSort,
        onStatus = { collectionStatus = it; resetPosition = true },
        onSort = { collectionSortName = it.name; resetPosition = true },
        onRefresh = { refresh++ }, onDismiss = { options = false; optionsFocusGranted.value = false }) {
        if (feature == TvFeature.ANILIST) ActionRow {
            ActionButton("Connect AniList") { onPlatformAction("oauth:anilist") }
            ActionButton("Connect MAL") { onPlatformAction("oauth:mal") }
            ActionButton("Manage accounts") { onPlatformAction("accounts") }
        }
        if (feature == TvFeature.STREAMING) Text("Choose a show, then an episode and source. Provider extensions must be installed and enabled.")
    }
}

@Composable
internal fun AnimeDetailScreen(id: Long, repo: SeanimeRepository, onPlay: (PlaybackRequest) -> Unit, onBack: () -> Unit,
    initialSourceMode: String? = null, initialEpisode: Int = 1,
    navigationOwnsFocus: Boolean = LocalNativeNavigationOwnsFocus.current) {
    var showInformation by rememberSaveable(id) { mutableStateOf(false) }
    var details by remember { mutableStateOf<MediaDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    if (!showInformation || details == null || error != null) ReportNativePluginScreen(NativeScreenLocation("/entry", mapOf("id" to id.toString())), priority = 2)
    var reload by remember { mutableIntStateOf(0) }
    var selectedEpisode by rememberSaveable(id) { mutableStateOf(if (initialSourceMode != null) "stream:$initialEpisode" else "") }
    var selectedSourceMode by rememberSaveable(id) { mutableStateOf(initialSourceMode) }
    val watchFocus = remember { FocusRequester() }
    // Opening a title removes the grid's focused card. Hand the remote to the
    // primary action as soon as the loaded detail header is actually placed.
    val watchFocusGranted = remember(id) { mutableStateOf(false) }
    var editList by remember { mutableStateOf(false) }
    val informationFocus = remember { FocusRequester() }
    val informationFocusGranted = remember { mutableStateOf(true) }
    val listEditFocus = remember { FocusRequester() }
    val listEditFocusGranted = remember { mutableStateOf(true) }
    val detailListState = rememberLazyListState()
    var sourceOpener by rememberSaveable(id) { mutableStateOf("watch") }
    val episodeReturnFocus = remember { FocusRequester() }
    val episodeReturnGranted = remember { mutableStateOf(true) }
    LaunchedEffect(navigationOwnsFocus) {
        if (navigationOwnsFocus) {
            watchFocusGranted.value = true
            episodeReturnGranted.value = true
            listEditFocusGranted.value = true
            informationFocusGranted.value = true
        }
    }
    fun closeSource() {
        selectedEpisode = ""
        if (sourceOpener == "watch") watchFocusGranted.value = false else episodeReturnGranted.value = false
    }
    LaunchedEffect(selectedEpisode, sourceOpener, episodeReturnGranted.value, details) {
        if (!navigationOwnsFocus && selectedEpisode.isBlank() && sourceOpener != "watch" && !episodeReturnGranted.value) {
            val index = details?.episodes?.indexOfFirst { episodeIdentity(it) == sourceOpener } ?: -1
            if (index >= 0 && detailListState.layoutInfo.visibleItemsInfo.none { it.key == sourceOpener }) detailListState.scrollToItem(index + 2)
            else if (index < 0) { episodeReturnGranted.value = true; watchFocusGranted.value = false }
        }
    }
    LaunchedEffect(id, reload) {
        error = null
        try { details = repo.animeDetails(id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Couldn't load this show" }
    }
    BackHandler { if (selectedEpisode.isNotBlank()) closeSource() else onBack() }
    val data = details
    when {
        error != null || data == null -> NativeDetailPendingContent(error, navigationOwnsFocus,
            onRetry = { watchFocusGranted.value = false; reload++ }, onBack = onBack)
        selectedEpisode.isNotBlank() -> SourceScreen(data.media, data.episodes.firstOrNull { episodeIdentity(it) == selectedEpisode }
            ?: data.episodes.firstOrNull { it.number == selectedEpisode.substringAfter("stream:").toIntOrNull() }
            ?: Episode(selectedEpisode.substringAfter("stream:").toIntOrNull() ?: initialEpisode, "Episode ${selectedEpisode.substringAfter("stream:")}"),
            repo, onPlay, ::closeSource, initialMode = selectedSourceMode)
        showInformation -> NativeAnimeMetadataScreen(data.media, repo, onPlay,
            onBack = { showInformation = false; informationFocusGranted.value = navigationOwnsFocus },
            navigationOwnsFocus = navigationOwnsFocus)
        else -> LazyColumn(state = detailListState, verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxSize().testTag("anime-detail-content")) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    NativeArtwork(data.media.imageUrl, data.media.title, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(150.dp).height(215.dp).background(Panel, RoundedCornerShape(12.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(data.media.title, style = MaterialTheme.typography.headlineMedium)
                        Text(rememberNativeSynopsis(data.media.description), maxLines = 4, overflow = TextOverflow.Ellipsis,
                            color = Color(0xFFBBC5D3))
                        ActionRow {
                            TvButton(onClick = { sourceOpener = "watch"; selectedEpisode = data.episodes.firstOrNull { it.progressNumber > data.media.progress }?.let(::episodeIdentity) ?: "stream:1" },
                                modifier = if (!navigationOwnsFocus) Modifier.initialTvFocus(watchFocus, watchFocusGranted) else Modifier) { Text("Watch") }
                            TvButton(onClick = { editList = true }, modifier = Modifier.testTag("anime-edit-list")
                                .then(if (!navigationOwnsFocus) Modifier.initialTvFocus(listEditFocus, listEditFocusGranted) else Modifier)) { Text("Update list") }
                            TvButton(onClick = { showInformation = true }, modifier = Modifier.testTag("anime-more-information")
                                .then(if (!navigationOwnsFocus) Modifier.initialTvFocus(informationFocus, informationFocusGranted) else Modifier)) { Text("More information") }
                            TvButton(onClick = onBack) { Text("Back") }
                        }
                        NativePluginActions(repo, listOf(NativePluginActionKind.ANIME_PAGE_BUTTON, NativePluginActionKind.ANIME_PAGE_MENU,
                            NativePluginActionKind.MEDIA_CARD), media = data.media)
                        NativePluginEpisodeTabButtons(repo, id, onUnavailable = { watchFocusGranted.value = false }) { source -> sourceOpener = "watch"; selectedSourceMode = source; selectedEpisode = "stream:1" }
                    }
                }
            }
            item { Text("Episodes", style = MaterialTheme.typography.titleLarge) }
            if (data.episodes.isEmpty()) item {
                EmptyMessage("Find an episode", "Choose Watch to load episodes from an online, torrent or debrid provider.")
            }
            items(data.episodes, key = ::episodeIdentity) { episode ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TvButton(onClick = { sourceOpener = episodeIdentity(episode); selectedEpisode = sourceOpener },
                    modifier = Modifier.fillMaxWidth().testTag("episode-${episode.number}")
                        .then(if (!navigationOwnsFocus && episodeIdentity(episode) == sourceOpener) Modifier.initialTvFocus(episodeReturnFocus, episodeReturnGranted) else Modifier)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        NativeArtwork(episode.imageUrl ?: data.media.imageUrl, "${episode.title} thumbnail",
                            Modifier.size(112.dp, 63.dp), ContentScale.Crop)
                        Text("${episode.number}. ${episode.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (episode.isNakama) "Shared library" else if (episode.isDownloaded) "On device" else "Choose source", color = Accent, fontSize = 14.sp)
                    }
                }
                NativePluginActions(repo, listOf(NativePluginActionKind.EPISODE_CARD, NativePluginActionKind.EPISODE_GRID), media = data.media,
                    episode = episode, episodeType = if (episode.isDownloaded || episode.localPath != null) "library" else "undownloaded")
                }
            }
        }
    }
    if (editList && data != null) NativeListEntryDialog(repo, data.media,
        onDismiss = { editList = false; listEditFocusGranted.value = false },
        onChanged = { removed ->
            editList = false
            if (removed) onBack() else { listEditFocusGranted.value = false; reload++ }
        })
}

/** The title route always exposes a visible remote exit, including pending/failed reads. */
@Composable
private fun NativeDetailPendingContent(error: String?, navigationOwnsFocus: Boolean, onRetry: () -> Unit, onBack: () -> Unit) {
    val entryFocus = remember { FocusRequester() }
    val entryGranted = remember(error) { mutableStateOf(false) }
    LaunchedEffect(navigationOwnsFocus) { if (navigationOwnsFocus) entryGranted.value = true }
    val entryModifier = if (!navigationOwnsFocus) Modifier.initialTvFocus(entryFocus, entryGranted) else Modifier
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (error == null) "Opening show…" else "Couldn't open this show",
                style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TvButton(onClick = onBack, modifier = Modifier.testTag("detail-pending-back")
                .then(if (error == null) entryModifier else Modifier)) { Text("Back") }
        }
        if (error == null) Box(Modifier.weight(1f)) { LoadingMessage("Loading show information…") }
        else {
            TvScrollableText(Modifier.weight(1f).fillMaxWidth(), exitFocus = entryFocus) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            TvButton(onClick = onRetry, modifier = Modifier.testTag("detail-retry").then(entryModifier)) { Text("Try again") }
        }
    }
}

@Composable
internal fun LoadingMessage(message: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) { CircularProgressIndicator(); Spacer(Modifier.height(18.dp)); Text(message) }
}

@Composable
internal fun ErrorMessage(message: String, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Something needs attention", style = MaterialTheme.typography.titleLarge)
        Text(message, color = MaterialTheme.colorScheme.error)
        TvButton(onClick = retry) { Text("Try again") }
    }
}

@Composable
internal fun EmptyMessage(title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, color = Color(0xFFBBC5D3))
    }
}
