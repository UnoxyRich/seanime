package app.seanime.tv.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.data.*
import app.seanime.tv.platform.NativeMangaPdf
import java.io.File
import kotlinx.coroutines.CancellationException
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.request.ImageRequest
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun MangaScreen(repo: SeanimeRepository, initialMode: String = "library", initialQuery: String = "", initialMediaId: Long = 0L,
    initialDiscovery: AnimeDiscoveryFilters? = null, initialDiscoveryPage: Int = 1) {
    var directMediaId by rememberSaveable { mutableLongStateOf(initialMediaId) }
    if (directMediaId > 0) { NativeMangaEntryRoute(repo, directMediaId) { directMediaId = 0L }; return }
    val action = rememberFeatureAction()
    var catalogTitles by remember { mutableStateOf<List<MediaCard>>(emptyList()) }
    var collection by remember { mutableStateOf(PersonalCollection()) }
    var collectionQuery by rememberSaveable { mutableStateOf("") }
    var collectionStatus by rememberSaveable { mutableStateOf("") }
    var collectionSortName by rememberSaveable { mutableStateOf(PersonalCollectionSort.SERVER_ORDER.name) }
    val collectionSort = PersonalCollectionSort.valueOf(collectionSortName)
    var sortContext by remember { mutableStateOf(PersonalCollectionSortContext()) }
    var options by remember { mutableStateOf(false) }
    val optionsFocus = remember { FocusRequester() }
    val optionsGranted = remember { mutableStateOf(true) }
    val searchFocus = remember { FocusRequester() }
    val searchGranted = remember { mutableStateOf(true) }
    val cardFocus = remember { FocusRequester() }
    val cardGranted = remember { mutableStateOf(true) }
    val discoveryFocus = remember { FocusRequester() }
    val discoveryGranted = remember { mutableStateOf(true) }
    val discoveryStates = rememberSaveableStateHolder()
    var discoveryMediaId by rememberSaveable { mutableLongStateOf(0L) }
    var lastFocused by rememberSaveable { mutableLongStateOf(0L) }
    val collectionListState = rememberLazyListState()
    val catalogListState = rememberLazyListState()
    val downloadedListState = rememberLazyListState()
    var loaded by remember { mutableStateOf(false) }
    var reloadPending by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    val browseListState = when (mode) { "library" -> collectionListState; "downloaded" -> downloadedListState; else -> catalogListState }
    val titles = remember(mode, collection, collectionQuery, collectionStatus, collectionSort, sortContext, catalogTitles) {
        if (mode == "library") filterPersonalCollection(collection, collectionQuery, collectionStatus, collectionSort, sortContext).map { it.media }
        else catalogTitles
    }
    ReportNativePluginScreen(when (mode) {
        "downloaded" -> NativeScreenLocation("/native/manga-downloaded")
        "search" -> if (query.isBlank()) NativeScreenLocation("/discover", mapOf("type" to "manga")) else NativeScreenLocation("/native/manga-search", mapOf("query" to query))
        else -> NativeScreenLocation("/manga")
    }, priority = 1)
    var searchDialog by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<MediaCard?>(null) }
    var refreshJob by remember { mutableStateOf<JSONObject?>(null) }
    suspend fun reload() {
        if (mode == "library") {
            collection = loadPersonalCollection(repo, manga = true)
            sortContext = loadPersonalCollectionSortContext(repo, collectionSort)
        } else catalogTitles = when (mode) {
            "downloaded" -> repo.mangaDownloads().map { item ->
                val media = item.optJSONObject("media") ?: JSONObject().put("id", item.optMediaId("mediaId")).put("title", "Manga ${item.optMediaId("mediaId")}")
                SeanimeJson.media(media, manga = true)
            }
            else -> emptyList()
        }
        loaded = !reloadPending
        refreshJob = repo.request("GET", "/api/v1/manga/source-refresh") as? JSONObject
    }
    LaunchedEffect(repo, mode, query, collectionSort) { if (mode != "search") { loaded = false; reloadPending = true } else reloadPending = false }
    LaunchedEffect(repo, reloadPending, action.busy) {
        if (reloadPending && !action.busy) {
            reloadPending = false
            action.run { reload() }
        }
    }
    LaunchedEffect(repo) {
        repo.client.events.collectOnMain { event ->
            if (event.type == "manga-source-refresh-job-updated") refreshJob = event.payload as? JSONObject
        }
    }
    LaunchedEffect(loaded, titles, cardGranted.value, selected) {
        if (selected == null && loaded && !cardGranted.value) {
            val index = titles.indexOfFirst { it.id == lastFocused }
            if (index >= 0) {
                val preceding = 2 + (if (mode == "library") 1 else 0) + (if (refreshJob != null) 1 else 0) +
                    (if (mode == "search" || mode == "library" && collectionQuery.isNotBlank()) 1 else 0)
                browseListState.scrollToItem(preceding + index)
            } else {
                browseListState.scrollToItem(0)
                cardGranted.value = true
                searchGranted.value = false
            }
        }
    }
    val current = selected
    if (current != null) {
        MangaTitleScreen(repo, current, mode == "downloaded", onBack = { selected = null },
            onListChanged = { action.run { reload() } })
        return
    }
    if (mode == "search") {
        if (discoveryMediaId > 0L) NativeMangaEntryRoute(repo, discoveryMediaId) { discoveryMediaId = 0L }
        else discoveryStates.SaveableStateProvider("manga-catalog") {
            AnimeDiscoveryScreen(repo, onDetails = { discoveryMediaId = it }, onBack = { mode = "library"; discoveryGranted.value = false },
                initialFilters = initialDiscovery ?: AnimeDiscoveryFilters(search = query, manga = true), initialPage = initialDiscoveryPage)
        }
        return
    }
    BackHandler(mode != "library") { mode = "library"; searchGranted.value = false }
    FeaturePage("Manga", "Your collection, downloaded chapters, and new discoveries", action, state = browseListState) {
        item { LazyRow(Modifier.testTag("manga-collection-toolbar"), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = ActionRowContentPadding) {
            item { ActionButton(if (mode == "library") "✓ My collection" else "My collection", !action.busy) { mode = "library" } }
            item { ActionButton(if (mode == "downloaded") "✓ Downloaded" else "Downloaded", !action.busy) { mode = "downloaded" } }
            item { ActionButton(if (mode == "library") "Search my collection" else "Search catalog", !action.busy,
                Modifier.testTag("manga-search").initialTvFocus(searchFocus, searchGranted)) { if (mode == "library") searchDialog = true else mode = "search" } }
            item { ActionButton("Discover", !action.busy, Modifier.testTag("manga-discover").initialTvFocus(discoveryFocus, discoveryGranted)) { mode = "search" } }
            item { if (mode == "library") ActionButton("Lists & sort", !action.busy,
                Modifier.testTag("manga-collection-options").initialTvFocus(optionsFocus, optionsGranted)) { options = true }
                else ActionButton("Refresh", !action.busy) { action.run { reload() } } }
            item { NativePluginActions(repo, listOf(NativePluginActionKind.MANGA_LIBRARY), label = "Library plugin actions") }
        } }
        if (mode == "library") item { ActionRow {
            ActionButton("Refresh and find sources", !action.busy && refreshJob?.text("status") !in setOf("running", "stopping")) {
                action.run { refreshJob = repo.request("POST", "/api/v1/manga/source-refresh", JSONObject().put("mode", "refresh_and_find")) as? JSONObject }
            }
            if (refreshJob != null) ActionButton(if (refreshJob?.text("status") in setOf("running", "stopping")) "Stop source refresh" else "Dismiss result", !action.busy) {
                action.run { refreshJob = repo.request("DELETE", "/api/v1/manga/source-refresh") as? JSONObject }
            }
        } }
        refreshJob?.let { job -> item {
            val result = job.optJSONObject("result")
            FeaturePanel("Source refresh · ${humanizeField(job.text("status"))}", "${job.optInt("current")} / ${job.optInt("total")} titles · ${humanizeField(job.text("stage"))}") {
                if (job.text("error").isNotBlank()) Text(job.text("error"), color = MaterialTheme.colorScheme.error)
                if (result != null) Text("${result.optInt("refreshed")} refreshed · ${result.optInt("found")} found · ${result.optInt("failed")} failed")
            }
        } }
        if (mode == "library" && collectionQuery.isNotBlank()) item { Text("My collection: “$collectionQuery”", Modifier.testTag("manga-personal-query")) }
        if (loaded && titles.isEmpty()) item { EmptyFeature(if (mode == "library" && collection.entries.isNotEmpty()) "No matching titles" else "No manga found", when (mode) {
            "downloaded" -> "Download chapters from a manga title to read them here"
            "search" -> "Try a different title or search term"
            else -> if (collection.entries.isNotEmpty()) "Try a different collection search or choose All statuses in Lists & sort."
                else "Use Discover to find manga and add it to your list, or connect AniList in Settings"
        }) }
        items(titles, key = { it.id }) { manga ->
            FeaturePanel(manga.title, listOf(personalCollectionStatusLabel(manga.status, true), "Chapter ${manga.progress}${manga.totalEpisodes?.let { " / $it" }.orEmpty()}").filter(String::isNotBlank).joinToString(" · ")) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    NativeArtwork(url = manga.imageUrl, contentDescription = "${manga.title} cover", modifier = Modifier.size(86.dp, 124.dp), contentScale = ContentScale.Crop)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                        if (manga.description.isNotBlank()) Text(rememberNativeSynopsis(manga.description), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        ActionButton("Chapters", modifier = Modifier.testTag("manga-collection-${manga.id}")
                            .then(if (lastFocused == manga.id) Modifier.initialTvFocus(cardFocus, cardGranted) else Modifier)) {
                            lastFocused = manga.id; cardGranted.value = false; selected = manga
                        }
                        NativePluginActions(repo, listOf(NativePluginActionKind.MEDIA_CARD), media = manga, label = "Title actions")
                    }
                }
            }
        }

    }
    if (searchDialog) TextEntryDialog(if (mode == "library") "Search my collection" else "Find manga in catalog", "Title",
        if (mode == "library") collectionQuery else query, allowEmpty = true, inputModifier = Modifier.testTag("manga-search-editor"),
        onDismiss = { searchDialog = false; searchGranted.value = false }) {
        if (mode == "library") collectionQuery = it else { query = it; mode = "search" }
    }
    if (options) NativeCollectionOptionsDialog(true, collectionStatus, collectionSort,
        onStatus = { collectionStatus = it }, onSort = { collectionSortName = it.name },
        onRefresh = { action.run { reload() } }, onDismiss = { options = false; optionsGranted.value = false })
}

@Composable
internal fun NativeMangaEntryRoute(repo: SeanimeRepository, id: Long, onBack: () -> Unit) {
    var media by remember { mutableStateOf<MediaCard?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    ReportNativePluginScreen(NativeScreenLocation("/manga/entry", mapOf("id" to id.toString())), priority = 2)
    BackHandler(onBack = onBack)
    LaunchedEffect(repo, id, retry) {
        error = null
        try { media = repo.mangaDetails(id).media }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Couldn't open this manga" }
    }
    val current = media
    when {
        error != null -> Column { ActionButton("All manga", onClick = onBack); ErrorMessage(error!!) { retry++ } }
        current == null -> Column { ActionButton("All manga", onClick = onBack); LoadingMessage("Opening manga…") }
        else -> MangaTitleScreen(repo, current, downloadedOnly = false, onBack = onBack)
    }
}

@Composable
internal fun MangaTitleScreen(repo: SeanimeRepository, initialMedia: MediaCard, downloadedOnly: Boolean, onBack: () -> Unit,
    onListChanged: () -> Unit = {}) {
    var media by remember(initialMedia.id) { mutableStateOf(initialMedia) }
    ReportNativePluginScreen(NativeScreenLocation("/manga/entry", mapOf("id" to media.id.toString())), priority = 3)
    val action = rememberFeatureAction()
    val entryFocus = remember(initialMedia.id) { FocusRequester() }
    val entryFocusGranted = remember(initialMedia.id) { mutableStateOf(false) }
    var providers by remember { mutableStateOf<List<ExtensionItem>>(emptyList()) }
    var provider by rememberSaveable(media.id) { mutableStateOf(if (downloadedOnly) "__downloaded" else "") }
    var chapters by remember { mutableStateOf<List<MangaChapter>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var selectedChapter by remember { mutableStateOf<MangaChapter?>(null) }
    var mappingDialog by remember { mutableStateOf(false) }
    var mappingResults by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var manualMapping by remember { mutableStateOf<String?>(null) }
    var resetMappingDialog by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    val mappingFocus = remember { FocusRequester() }
    val mappingFocusGranted = remember { mutableStateOf(true) }
    var selectedDownloads by remember { mutableStateOf<Set<String>>(emptySet()) }
    var listDialog by remember { mutableStateOf(false) }
    val listEditFocus = remember { FocusRequester() }
    val listEditFocusGranted = remember { mutableStateOf(true) }
    var descending by rememberSaveable { mutableStateOf(false) }
    var filterDialog by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    var sourcePreferences by remember(media.id) { mutableStateOf(NativeMangaSourcePreferences()) }
    var downloadedFilter by remember(media.id) { mutableStateOf(NativeMangaSourceFilter()) }
    var preferenceRefreshPending by remember { mutableStateOf(false) }
    var filterSetting by remember { mutableStateOf<String?>(null) }
    val languageFocus = remember { FocusRequester() }
    val languageGranted = remember { mutableStateOf(true) }
    val scanlatorFocus = remember { FocusRequester() }
    val scanlatorGranted = remember { mutableStateOf(true) }
    var removeChapter by remember { mutableStateOf<MangaChapter?>(null) }
    var progressDialog by remember { mutableStateOf(false) }
    suspend fun loadChapters(refresh: Boolean = false) {
        if (refresh && !offline && provider != "__downloaded") sourcePreferences = loadNativeMangaPreferences(repo, media.id)
        val nextChapters = if (provider == "__downloaded") repo.downloadedMangaChapters(media.id) else {
            try {
                if (refresh) repo.refreshMangaChapters(media.id, provider) else repo.mangaChapters(media.id, provider)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (refresh) throw error
                val downloaded = try { repo.downloadedMangaChapters(media.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                if (downloaded.isEmpty()) throw error
                provider = "__downloaded"
                action.message = "Source unavailable. Showing downloaded chapters."
                downloaded
            }
        }
        chapters = nextChapters
        selectedDownloads = emptySet()
        loaded = true
        manualMapping = if (provider == "__downloaded") null else repo.mangaMapping(media.id, provider)
    }
    LaunchedEffect(repo, media.id) {
        action.run {
            offline = repo.status().offline
            if (offline || downloadedOnly) provider = "__downloaded"
            // Offline entry must not depend on provider extensions, preferences, or upstream matching.
            providers = if (offline || downloadedOnly) emptyList() else repo.providers("manga-provider")
            if (!offline && provider != "__downloaded") sourcePreferences = loadNativeMangaPreferences(repo, media.id)
            if (provider.isBlank()) {
                val preferred = sourcePreferences.provider
                val default = repo.settings().optJSONObject("manga")?.text("defaultMangaProvider").orEmpty()
                provider = providers.firstOrNull { it.id == preferred }?.id ?: providers.firstOrNull { it.id == default }?.id ?: providers.firstOrNull()?.id ?: "__downloaded"
            }
            loadChapters()
        }
    }
    LaunchedEffect(repo, media.id) {
        repo.client.events.collectOnMain { event ->
            if (event.type == "manga-preferences-updated" && nativeMangaPreferenceEventAffects(event.payload, media.id)) preferenceRefreshPending = true
        }
    }
    LaunchedEffect(repo, preferenceRefreshPending, action.busy, provider, offline, loaded, filterSetting) {
        if (preferenceRefreshPending && loaded && !action.busy && filterSetting == null) {
            preferenceRefreshPending = false
            if (!offline && provider != "__downloaded") action.run {
                sourcePreferences = loadNativeMangaPreferences(repo, media.id)
                selectedDownloads = emptySet()
            }
        }
    }
    BackHandler(onBack = onBack)
    val sourceFilter = if (provider == "__downloaded") downloadedFilter else sourcePreferences.filters[provider] ?: NativeMangaSourceFilter()
    val sorted = remember(chapters, descending, filter, sourceFilter) {
        val filtered = chapters.filter { (filter.isBlank() || it.title.contains(filter, true) || it.number.contains(filter)) && sourceFilter.accepts(it) }
            .sortedWith(compareBy<MangaChapter> { it.number.toDoubleOrNull() ?: Double.MAX_VALUE }.thenBy { it.raw.optInt("index") })
        if (descending) filtered.asReversed() else filtered
    }
    val languages = remember(chapters) { chapters.map { it.raw.text("language") }.filter(String::isNotBlank).distinct() }
    val scanlators = remember(chapters) { chapters.map { it.raw.text("scanlator") }.filter(String::isNotBlank).distinct() }
    FeaturePage(media.title, "Choose a source to browse and read chapters", action) {
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // This navigation target stays enabled while sources and chapters load.
            item { ActionButton("All manga", modifier = Modifier.testTag("manga-title-back")
                .initialTvFocus(entryFocus, entryFocusGranted), onClick = onBack) }
            item { ActionButton("Add / update list", !action.busy, Modifier.testTag("manga-edit-list")
                .then(if (!action.busy) Modifier.initialTvFocus(listEditFocus, listEditFocusGranted) else Modifier)) { listDialog = true } }
            item { ActionButton("Update progress", !action.busy) { progressDialog = true } }
            item { ActionButton("Track offline", !action.busy) { action.run("Title tracked for offline metadata") { repo.trackOffline(media.id, manga = true) } } }
            item { ActionButton("Refresh chapters", !action.busy, Modifier.testTag("manga-refresh-chapters")) { action.run { loadChapters(refresh = true) } } }
            item { NativePluginActions(repo, listOf(NativePluginActionKind.MANGA_PAGE_BUTTON, NativePluginActionKind.MANGA_PAGE_MENU,
                NativePluginActionKind.MEDIA_CARD), media = media) }
        } }
        item { LazyRow(Modifier.testTag("manga-chapter-providers"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ActionButton(if (provider == "__downloaded") "✓ Downloaded chapters" else "Downloaded chapters", !action.busy) {
                provider = "__downloaded"; loaded = false; action.run { loadChapters() }
            } }
            items(providers, key = { it.id }) { source ->
                ActionButton((if (provider == source.id) "✓ " else "") + source.name, !action.busy) {
                    action.run {
                        sourcePreferences = parseNativeMangaPreferences(repo.request("PATCH", "/api/v1/manga/preferences/${media.id}", JSONObject().put("provider", source.id)) as? JSONObject
                            ?: error("Couldn't save the manga source"))
                        provider = source.id; loaded = false
                        loadChapters()
                    }
                }
            }
        } }
        item { LazyRow(Modifier.testTag("manga-chapter-filters"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ActionButton(if (descending) "Newest first" else "Oldest first") { descending = !descending } }
            item { ActionButton("Find chapter") { filterDialog = true } }
            if (filter.isNotBlank()) item { ActionButton("Clear filter") { filter = "" } }
            if (provider != "__downloaded" && provider.isNotBlank()) item { ActionButton("Fix source match", !action.busy,
                Modifier.testTag("manga-fix-match").then(if (!action.busy) Modifier.initialTvFocus(mappingFocus, mappingFocusGranted) else Modifier)) { mappingDialog = true } }
            if (manualMapping != null) item { ActionButton("Reset source match", !action.busy, Modifier.testTag("manga-reset-match")) { resetMappingDialog = true } }
            if (languages.isNotEmpty() || sourceFilter.language.isNotBlank()) item { ActionButton("Language: ${sourceFilter.language.ifBlank { "All" }}", !action.busy,
                Modifier.testTag("manga-language-filter").then(if (!action.busy) Modifier.initialTvFocus(languageFocus, languageGranted) else Modifier)) { filterSetting = "language" } }
            if (scanlators.isNotEmpty() || sourceFilter.scanlators.isNotEmpty()) item { ActionButton("Scanlators: ${sourceFilter.scanlators.joinToString().ifBlank { "All" }}", !action.busy,
                Modifier.testTag("manga-scanlator-filter").then(if (!action.busy) Modifier.initialTvFocus(scanlatorFocus, scanlatorGranted) else Modifier)) { filterSetting = "scanlators" } }
            if (selectedDownloads.isNotEmpty()) item { ActionButton("Download ${selectedDownloads.size} selected", !action.busy) {
                action.run("Chapters added to download queue") {
                    repo.downloadMangaChapters(media.id, provider, selectedDownloads.toList())
                    selectedDownloads = emptySet()
                }
            } }
        } }
        if (offline) item { Text("Offline · downloaded chapters") }
        manualMapping?.let { match -> item { Text("Manual source match: $match", modifier = Modifier.testTag("manga-current-match")) } }
        if (loaded && chapters.isEmpty()) item { EmptyFeature("No chapters from this source", if (provider == "__downloaded") "No downloaded chapters are available for this title" else "Choose another provider, or use Fix source match to select the correct manga") }
        if (loaded && chapters.isNotEmpty() && sorted.isEmpty()) item { EmptyFeature("No matching chapters", "Clear the chapter filter or change the language and scanlator filters") }
        items(mappingResults, key = { "match:${it.text("id")}" }) { result ->
            FeaturePanel(result.text("title", "Source match"), result.text("id")) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    NativeArtwork(result.stringOrNull("image"), "${result.text("title", "Source match")} cover",
                        Modifier.size(64.dp, 90.dp), ContentScale.Crop,
                        headers = result.optJSONObject("imageHeaders")?.stringMap().orEmpty(), providerResult = true)
                    ActionButton("Use this source match", !action.busy) {
                        action.run("Source match saved") {
                            repo.setMangaMapping(media.id, provider, result.text("id"))
                            mappingResults = emptyList(); loadChapters()
                        }
                    }
                }
            }
        }
        items(sorted, key = { "${it.provider}:${it.id}" }) { chapter ->
            FeaturePanel(chapter.title, listOf(chapter.raw.text("language"), chapter.raw.text("scanlator"), chapter.raw.text("updatedAt")).filter(String::isNotBlank).joinToString(" · ")) {
                ActionRow {
                    ActionButton("Read") { selectedChapter = chapter }
                    if (provider == "__downloaded") ActionButton("Delete download", !action.busy) { removeChapter = chapter }
                    if (provider != "__downloaded") {
                        ActionButton(if (chapter.id in selectedDownloads) "✓ Selected" else "Select download", !action.busy) {
                            selectedDownloads = if (chapter.id in selectedDownloads) selectedDownloads - chapter.id else selectedDownloads + chapter.id
                        }
                        ActionButton("Download", !action.busy) { action.run("Chapter queued") { repo.downloadMangaChapters(media.id, chapter.provider, listOf(chapter.id)) } }
                    }
                }
            }
        }
    }
    filterSetting?.let { field -> NativeMangaFilterDialog(field, sourceFilter, if (field == "language") languages else scanlators,
        onDismiss = {
            filterSetting = null
            if (field == "language") languageGranted.value = false else scanlatorGranted.value = false
        }) { edit ->
        if (provider == "__downloaded") downloadedFilter = when (edit) {
            is NativeMangaFilterEdit.Language -> downloadedFilter.copy(language = edit.value)
            is NativeMangaFilterEdit.Scanlators -> downloadedFilter.copy(scanlators = edit.values)
        } else sourcePreferences = saveNativeMangaFilter(repo, media.id, provider, edit)
        selectedDownloads = emptySet()
    } }
    if (mappingDialog) TextEntryDialog("Find source match", "Manga title", media.title, helper = "Search this provider and choose the correct edition to use for this AniList title.", onDismiss = { mappingDialog = false }) { query ->
        action.run { mappingResults = repo.request("POST", "/api/v1/manga/search", JSONObject().put("provider", provider).put("query", query)).jsonObjects() }
    }
    if (resetMappingDialog) ConfirmFeatureDialog("Reset source match?", "Remove the manual match $manualMapping and let this provider find the manga automatically.",
        { resetMappingDialog = false; mappingFocusGranted.value = false }) {
        action.run("Automatic source matching restored") {
            repo.resetMangaMapping(media.id, provider)
            manualMapping = null
            mappingResults = emptyList()
            loadChapters()
        }
    }
    if (filterDialog) TextEntryDialog("Find chapter", "Chapter number or title", filter, onDismiss = { filterDialog = false }) { filter = it }
    if (listDialog) NativeListEntryDialog(repo, media,
        onDismiss = { listDialog = false; listEditFocusGranted.value = false },
        onChanged = { removed ->
            listDialog = false
            if (removed) onBack() else action.run("List updated") {
                media = repo.mangaDetails(media.id).media
                listEditFocusGranted.value = false
            }
            onListChanged()
        })
    if (progressDialog) TextEntryDialog("Reading progress", "Completed chapter number", media.progress.toString(), onDismiss = { progressDialog = false }) { value ->
        action.run("Reading progress updated") {
            val number = value.toIntOrNull()?.takeIf { it >= 0 } ?: error("Enter a nonnegative chapter number")
            repo.updateMangaProgress(media.id, number, media.totalEpisodes ?: 0)
        }
    }
    removeChapter?.let { chapter -> ConfirmFeatureDialog("Delete downloaded chapter?", "${chapter.title} will be removed from local storage. You can download it again if the provider still has it.", { removeChapter = null }) {
        action.run("Downloaded chapter deleted") {
            repo.request("DELETE", "/api/v1/manga/download-chapter", JSONObject().put("downloadIds", JSONArray().put(JSONObject()
                .put("provider", chapter.provider).put("mediaId", media.id).put("chapterId", chapter.id).put("chapterNumber", chapter.number))))
            loadChapters()
        }
    } }
    selectedChapter?.let { chapter ->
        val sequence = chapters.filter { it.provider == chapter.provider && it.raw.text("language") == chapter.raw.text("language") && sourceFilter.accepts(it) }
            .sortedWith(compareBy<MangaChapter> { it.number.toDoubleOrNull() ?: Double.MAX_VALUE }.thenBy { it.raw.optInt("index") })
        val next = sequence.getOrNull(sequence.indexOfFirst { it.id == chapter.id } + 1)
        key(chapter.provider, chapter.id) {
            MangaReader(repo, media, chapter, next, onClose = { selectedChapter = null }, onNextChapter = { selectedChapter = it })
        }
    }
}

@Composable
internal fun MangaReader(
    repo: SeanimeRepository,
    media: MediaCard,
    chapter: MangaChapter,
    nextChapter: MangaChapter?,
    onClose: () -> Unit,
    onNextChapter: (MangaChapter) -> Unit,
) {
    val context = LocalContext.current
    val imageContext = context.applicationContext
    val imageTransport = remember(repo.client) { NativeImageTransport(repo.client) }
    val imageLoader = remember(imageContext, imageTransport) {
        ImageLoader.Builder(imageContext).callFactory(imageTransport)
            .memoryCache { MemoryCache.Builder(imageContext).maxSizePercent(0.08).weakReferencesEnabled(false).build() }
            .diskCache(null).bitmapFactoryMaxParallelism(2).build()
    }
    DisposableEffect(imageLoader, imageTransport) {
        onDispose { imageLoader.shutdown(); imageTransport.close() }
    }
    val preferences = remember { context.getSharedPreferences("native_manga_reader", Context.MODE_PRIVATE) }
    val readerSettings = remember(media.id) { loadNativeMangaReaderSettings(media.id) { key -> if (preferences.contains(key)) preferences.getBoolean(key, false) else null } }
    val resumeKey = "${media.id}:${chapter.provider}:${chapter.id}"
    val action = rememberFeatureAction()
    var pages by remember { mutableStateOf<List<MangaPage>>(emptyList()) }
    var pageDimensions by remember(resumeKey) { mutableStateOf<Map<Int, MangaPageDimensions>>(emptyMap()) }
    var requestedDimensions by remember(resumeKey) { mutableStateOf(false) }
    var pdfDocument by remember(resumeKey) { mutableStateOf<NativeMangaPdf?>(null) }
    DisposableEffect(pdfDocument) {
        val documentToClose = pdfDocument
        onDispose { documentToClose?.close() }
    }
    var page by rememberSaveable(resumeKey) { mutableIntStateOf(preferences.getInt(resumeKey, 0)) }
    var rightToLeft by rememberSaveable(media.id) { mutableStateOf(readerSettings.rtl) }
    var doublePage by rememberSaveable(media.id) { mutableStateOf(readerSettings.doublePage) }
    var coverAlone by rememberSaveable(media.id) { mutableStateOf(readerSettings.coverAlone) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var jumpDialog by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var automaticProgress by remember(resumeKey) { mutableStateOf(false) }
    var automaticProgressError by remember(resumeKey) { mutableStateOf<String?>(null) }
    var automaticProgressAttempted by remember(resumeKey) { mutableStateOf(false) }
    var renderedPages by remember(resumeKey) { mutableStateOf<Set<Int>>(emptySet()) }
    var knownProgress by remember(resumeKey) { mutableIntStateOf(media.progress) }
    val imageFocus = remember { FocusRequester() }
    val imageFocusGranted = remember(resumeKey) { mutableStateOf(false) }
    suspend fun loadPages(withDimensions: Boolean = doublePage) {
        if (chapter.raw.optBoolean("localIsPDF")) {
            val document = pdfDocument ?: run {
                val root = repo.settings().optJSONObject("manga")?.optString("mangaLocalSourceDirectory").orEmpty()
                val source = LocalMangaPdfSource.resolve(repo.client.baseUrl, root, media.id, chapter)
                NativeMangaPdf.open(context, source).also { pdfDocument = it }
            }
            pages = List(document.pageCount) { index -> MangaPage(index, document.pageUrl(index)) }
            if (withDimensions) {
                pageDimensions = document.pageDimensions()
                requestedDimensions = true
            }
        } else {
            val collection = repo.mangaPageCollection(media.id, chapter.id, chapter.provider, doublePage = withDimensions)
            val anchorIndex = pages.getOrNull(page)?.index
            val previous = pages
            pages = collection.pages
            // Only retain decoded-page evidence for the same resource at the same list position.
            renderedPages = renderedPages.filterTo(mutableSetOf()) { index ->
                previous.getOrNull(index)?.let { it.index == pages.getOrNull(index)?.index && it.url == pages.getOrNull(index)?.url && it.headers == pages.getOrNull(index)?.headers } == true
            }
            val previousByIndex = previous.associateBy { it.index }
            val currentByIndex = pages.associateBy { it.index }
            pageDimensions = pageDimensions.filterKeys { index ->
                val before = previousByIndex[index]
                val after = currentByIndex[index]
                before != null && after != null && before.url == after.url && before.headers == after.headers
            } + collection.dimensions
            requestedDimensions = withDimensions
            anchorIndex?.let { index -> pages.indexOfFirst { it.index == index }.takeIf { it >= 0 }?.let { page = it } }
        }
        page = MangaPagination.clamp(page, pages.size)
        loaded = true
    }
    LaunchedEffect(resumeKey) { action.run { loadPages() } }
    LaunchedEffect(repo, resumeKey) {
        try { automaticProgress = repo.settings().optJSONObject("manga")?.optBoolean("mangaAutoUpdateProgress") == true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { automaticProgressError = "Automatic reading progress is unavailable. You can still mark the chapter read." }
    }
    suspend fun markRead() {
        val result = markMangaChapterRead(repo, media.id, chapter.number, media.totalEpisodes ?: 0,
            media.raw.optInt("idMal").takeIf { it > 0 })
        knownProgress = result.progress
        action.message = if (result.updated) "Reading progress updated" else "Chapter already marked read"
    }
    val spreads = remember(pages, pageDimensions, doublePage, coverAlone) { MangaPagination.spreads(pages, pageDimensions, doublePage, coverAlone) }
    val visiblePages = MangaPagination.visible(page, spreads)
    LaunchedEffect(automaticProgress, loaded, visiblePages, pages.size, renderedPages, action.busy) {
        if (automaticProgress && loaded && !automaticProgressAttempted && !action.busy && mangaChapterProgress(chapter.number) != null &&
            mangaEndPageRendered(visiblePages, pages.size, renderedPages)) {
            automaticProgressAttempted = true
            action.run { markRead() }
        }
    }
    LaunchedEffect(page, rightToLeft, doublePage, coverAlone, loaded) {
        if (loaded) preferences.edit().putInt(resumeKey, page).apply {
            NativeMangaReaderSettings(rightToLeft, doublePage, coverAlone).storedValues(media.id).forEach { (key, value) -> putBoolean(key, value) }
        }.apply()
        panX = 0f; panY = 0f
    }
    fun move(delta: Int) { page = MangaPagination.move(page, delta, spreads) }
    fun recordDimensions(index: Int, width: Int, height: Int) {
        val providerIndex = pages.getOrNull(index)?.index ?: return
        if (providerIndex !in pageDimensions && width > 0 && height > 0)
            pageDimensions = pageDimensions + (providerIndex to MangaPageDimensions(width, height))
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().testTag("manga-reader").background(Color(0xFF080B10)).padding(horizontal = 36.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(media.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(chapter.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ActionButton("Close reader", modifier = Modifier.testTag("manga-close"), onClick = onClose)
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("manga-page")
                    .then(if (loaded && pages.isNotEmpty()) Modifier.initialTvFocus(imageFocus, imageFocusGranted) else Modifier)
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                            Key.DirectionLeft -> { if (zoom > 1f) panX = (panX + 100f).coerceIn(-1600f, 1600f) else move(MangaPagination.keyDelta(false, rightToLeft)); true }
                            Key.DirectionRight -> { if (zoom > 1f) panX = (panX - 100f).coerceIn(-1600f, 1600f) else move(MangaPagination.keyDelta(true, rightToLeft)); true }
                            Key.DirectionUp -> if (zoom > 1f) { panY = (panY + 100f).coerceIn(-2500f, 2500f); true } else false
                            // Down always releases focus at normal zoom. At zoom, Center resets the image so controls remain reachable.
                            Key.DirectionDown -> if (zoom > 1f) { panY = (panY - 100f).coerceIn(-2500f, 2500f); true } else false
                            Key.DirectionCenter, Key.Enter -> { zoom = 1f; panX = 0f; panY = 0f; false }
                            else -> false
                        }
                    }.focusable(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    action.busy && pages.isEmpty() -> CircularProgressIndicator()
                    action.error != null && pages.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(action.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        ActionButton("Retry", modifier = Modifier.testTag("manga-retry-pages")) { action.run { loadPages() } }
                    }
                    loaded && pages.isEmpty() -> Text("No pages were returned for this chapter")
                    else -> {
                        val visible = visiblePages.let { if (rightToLeft) it.asReversed() else it }
                        Row(Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = panX, translationY = panY), horizontalArrangement = Arrangement.Center) {
                            visible.forEach { index -> key(pages[index].index, pages[index].url) {
                                val mangaPage = pages[index]
                                var imageFailed by remember(mangaPage.url) { mutableStateOf(false) }
                                var imageLoading by remember(mangaPage.url) { mutableStateOf(true) }
                                var retry by remember(mangaPage.url) { mutableIntStateOf(0) }
                                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                    val pdf = pdfDocument
                                    if (pdf != null) {
                                        NativePdfPage(pdf, index, onRendered = { width, height -> recordDimensions(index, width, height); renderedPages = renderedPages + index },
                                            onUnavailable = { renderedPages = renderedPages - index })
                                    } else {
                                    val request = remember(mangaPage.url, mangaPage.headers, mangaPage.providerResult, retry) {
                                        ImageRequest.Builder(context).data(mangaPage.url)
                                            .tag(NativeImageTransport.SourceHeaders::class.java, NativeImageTransport.SourceHeaders(mangaPage.headers, mangaPage.providerResult))
                                            .crossfade(false).build()
                                    }
                                    AsyncImage(model = request, imageLoader = imageLoader, contentDescription = "Page ${index + 1}", contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize(), onLoading = { imageLoading = true; imageFailed = false; renderedPages = renderedPages - index },
                                        onSuccess = { result -> recordDimensions(index, result.result.drawable.intrinsicWidth, result.result.drawable.intrinsicHeight)
                                            imageLoading = false; imageFailed = false; renderedPages = renderedPages + index },
                                        onError = { imageLoading = false; imageFailed = true; renderedPages = renderedPages - index })
                                    if (imageLoading) CircularProgressIndicator()
                                    if (imageFailed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Page ${index + 1} couldn't load")
                                        ActionButton("Retry image", modifier = Modifier.testTag("manga-retry-image-$index")) { retry += 1 }
                                    }
                                    }
                                }
                            } }
                        }
                    }
                }
            }
            Text(if (pages.isNotEmpty()) "Page ${page + 1} of ${pages.size} · ${if (zoom > 1) "D-pad pans · OK resets zoom" else "Focus the page, then use Left / Right to turn pages"}" else "Preparing chapter…",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("manga-page-status"))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.testTag("manga-controls")) {
                item { ActionButton("Previous", visiblePages.firstOrNull()?.let { it > 0 } == true && !action.busy, modifier = Modifier.testTag("manga-previous")) { move(-1) } }
                item { ActionButton("Next", visiblePages.lastOrNull()?.let { it < pages.lastIndex } == true && !action.busy, modifier = Modifier.testTag("manga-next")) { move(1) } }
                item { ActionButton("Go to page", pages.isNotEmpty(), modifier = Modifier.testTag("manga-jump")) { jumpDialog = true } }
                item { ActionButton(if (rightToLeft) "Right to left" else "Left to right", modifier = Modifier.testTag("manga-direction")) { rightToLeft = !rightToLeft } }
                item { ActionButton(if (doublePage) "Two pages" else "Single page", !action.busy, modifier = Modifier.testTag("manga-spread")) {
                    if (doublePage) doublePage = false else action.run {
                        if (!requestedDimensions) loadPages(withDimensions = true)
                        doublePage = true
                    }
                } }
                if (doublePage) item { ActionButton(if (coverAlone) "Cover alone" else "Cover paired", modifier = Modifier.testTag("manga-cover")) { coverAlone = !coverAlone } }
                item { ActionButton(if (zoom > 1f) "Fit page" else "Zoom 2×", pages.isNotEmpty(), modifier = Modifier.testTag("manga-zoom")) { zoom = if (zoom > 1f) 1f else 2f; imageFocusGranted.value = false } }
                item { ActionButton(if ((mangaChapterProgress(chapter.number) ?: Int.MAX_VALUE) <= knownProgress) "Chapter already read" else "Mark chapter read",
                    !action.busy && mangaChapterProgress(chapter.number) != null, modifier = Modifier.testTag("manga-mark-read")) {
                    automaticProgressAttempted = true
                    action.run { markRead() }
                } }
                if (nextChapter != null) item { ActionButton("Next chapter", !action.busy) { onNextChapter(nextChapter) } }
            }
            action.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (pages.isNotEmpty()) action.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            automaticProgressError?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (jumpDialog) TextEntryDialog("Go to page", "Page number (1–${pages.size})", (page + 1).toString(),
        onDismiss = { jumpDialog = false; imageFocusGranted.value = false }) { value ->
        action.run {
            val target = value.toIntOrNull() ?: error("Enter a page number")
            require(target in 1..pages.size) { "Choose a page from 1 to ${pages.size}" }
            page = target - 1
            imageFocusGranted.value = false
        }
    }
}


@Composable
private fun NativePdfPage(document: NativeMangaPdf, index: Int, onRendered: (Int, Int) -> Unit, onUnavailable: () -> Unit) {
    var file by remember(document, index) { mutableStateOf<File?>(null) }
    var failure by remember(document, index) { mutableStateOf<String?>(null) }
    var retry by remember(document, index) { mutableIntStateOf(0) }
    LaunchedEffect(document, index, retry) {
        failure = null
        onUnavailable()
        try { file = document.renderPage(index) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message ?: "This PDF page could not be opened" }
    }
    when {
        failure != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(failure.orEmpty(), color = MaterialTheme.colorScheme.error)
            ActionButton("Retry PDF page") { retry += 1 }
        }
        file == null -> CircularProgressIndicator()
        else -> AsyncImage(file, "PDF page ${index + 1}", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(), onSuccess = { onRendered(it.result.drawable.intrinsicWidth, it.result.drawable.intrinsicHeight) },
            onError = { onUnavailable(); failure = "PDF page ${index + 1} could not be displayed" })
    }
}
