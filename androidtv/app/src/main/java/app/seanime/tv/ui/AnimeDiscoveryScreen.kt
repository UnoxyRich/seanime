package app.seanime.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.Text
import app.seanime.tv.data.AnimeDiscoveryFilters
import app.seanime.tv.data.AnimeDiscoveryPage
import app.seanime.tv.data.MediaCard
import app.seanime.tv.data.SeanimeRepository
import app.seanime.tv.data.discoverNativeCatalog
import app.seanime.tv.data.nativeDiscoveryTags
import kotlinx.coroutines.CancellationException

private val DiscoveryFilterSaver = Saver<AnimeDiscoveryFilters, String>(
    save = { it.save() }, restore = { AnimeDiscoveryFilters.restore(it) },
)

/** Discovery stays separate from the user's collection and saves the exact result page for Back. */
@Composable
internal fun AnimeDiscoveryScreen(repo: SeanimeRepository, onDetails: (Long) -> Unit, onBack: () -> Unit,
    initialFilters: AnimeDiscoveryFilters = AnimeDiscoveryFilters(), initialPage: Int = 1, onAiring: (() -> Unit)? = null,
    navigationOwnsFocus: Boolean = LocalNativeNavigationOwnsFocus.current) {
    var filters by rememberSaveable(stateSaver = DiscoveryFilterSaver) { mutableStateOf(initialFilters) }
    var query by rememberSaveable { mutableStateOf(initialFilters.search) }
    var page by rememberSaveable { mutableIntStateOf(initialPage) }
    ReportNativePluginScreen(nativeDiscoveryLocation(filters, page), priority = 2)
    var searchDialog by rememberSaveable { mutableStateOf(false) }
    var filterDialog by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<AnimeDiscoveryPage?>(null) }
    var lastFocused by rememberSaveable { mutableLongStateOf(0L) }
    var restoreCard by rememberSaveable { mutableStateOf(false) }
    var restoreAfterPage by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val backFocus = remember { FocusRequester() }
    val firstFocusGranted = remember { mutableStateOf(restoreCard) }
    val pendingBackGranted = remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val searchFocusGranted = remember { mutableStateOf(true) }
    val filtersFocus = remember { FocusRequester() }
    val filtersFocusGranted = remember { mutableStateOf(true) }
    val previousFocus = remember { FocusRequester() }
    val previousFocusGranted = remember { mutableStateOf(true) }
    val cardFocus = remember { FocusRequester() }
    val cardFocusGranted = remember { mutableStateOf(!restoreCard) }
    val airingFocus = remember { FocusRequester() }
    val airingGranted = rememberSaveable { mutableStateOf(true) }
    fun cancelPendingRestoration() {
        restoreCard = false
        cardFocusGranted.value = true
        restoreAfterPage = false
    }
    LaunchedEffect(navigationOwnsFocus) {
        if (navigationOwnsFocus) {
            cancelPendingRestoration()
            firstFocusGranted.value = true
            pendingBackGranted.value = true
            searchFocusGranted.value = true
            filtersFocusGranted.value = true
            previousFocusGranted.value = true
            airingGranted.value = true
        }
    }
    fun submitSearch(value: String) {
        query = value.trim()
        filters = filters.copy(search = query); page = 1; restoreCard = false; reload++
    }

    BackHandler(onBack = onBack)
    LaunchedEffect(filters, page, reload) {
        loading = true
        error = null
        try {
            result = discoverNativeCatalog(repo, filters, page)
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Couldn't load discovery results" }
        finally { loading = false }
    }
    LaunchedEffect(loading, result, restoreCard) {
        if (!navigationOwnsFocus && !loading && restoreCard) {
            val index = if (error == null) result?.media?.indexOfFirst { it.id == lastFocused } ?: -1 else -1
            if (index < 0) { searchFocusGranted.value = false; restoreCard = false }
        }
    }
    LaunchedEffect(cardFocusGranted.value) {
        if (cardFocusGranted.value && restoreCard) restoreCard = false
    }
    LaunchedEffect(loading, restoreAfterPage) {
        if (!navigationOwnsFocus && !loading && restoreAfterPage) {
            // The Next control may become disabled on the last page. Keep a usable remote target.
            if (page > 1) previousFocusGranted.value = false else searchFocusGranted.value = false
            restoreAfterPage = false
        }
    }
    val loaded = result?.takeIf { !loading && error == null && it.media.isNotEmpty() }
    var gridPlaced by remember(loaded) { mutableStateOf(false) }
    val initialScrollIndex = remember(loaded) {
        if (restoreCard) loaded?.media?.indexOfFirst { it.id == lastFocused }?.coerceAtLeast(0) ?: 0 else 0
    }
    // The grid is absent during loading. Restore only after this result is placed.
    LaunchedEffect(loaded, gridPlaced) {
        if (loaded != null && gridPlaced) grid.scrollToItem(initialScrollIndex)
    }
    NativeDiscoveryContent(filters, page, result, loading, error,
        actions = NativeDiscoveryActions(
            onBack = onBack,
            onSearch = { searchDialog = true },
            onFilters = { filterDialog = true },
            onAiring = onAiring?.let { open -> { airingGranted.value = false; open() } },
            onPrevious = { if (!loading && page > 1) { page--; restoreAfterPage = true } },
            onNext = { if (!loading && error == null && result?.hasNextPage == true) { page++; restoreAfterPage = true } },
            onRetry = { searchFocusGranted.value = false; reload++ },
            onDetails = { media -> lastFocused = media.id; restoreCard = true; onDetails(media.id) },
        ),
        gridState = grid,
        onGridPlaced = { gridPlaced = true },
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (loading && restoreCard && event.type == KeyEventType.KeyDown && event.key in
                setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)) cancelPendingRestoration()
            false
        },
        controlModifier = { control -> if (navigationOwnsFocus) Modifier else when (control) {
            NativeDiscoveryControl.BACK -> Modifier.initialTvFocus(backFocus,
                if (loading && restoreCard) pendingBackGranted else firstFocusGranted)
            NativeDiscoveryControl.SEARCH -> Modifier.initialTvFocus(searchFocus, searchFocusGranted)
            NativeDiscoveryControl.FILTERS -> Modifier.initialTvFocus(filtersFocus, filtersFocusGranted)
            NativeDiscoveryControl.AIRING -> Modifier.initialTvFocus(airingFocus, airingGranted)
            NativeDiscoveryControl.PREVIOUS -> Modifier.initialTvFocus(previousFocus, previousFocusGranted)
            NativeDiscoveryControl.NEXT -> Modifier
        } },
        cardModifier = { media ->
            (if (!navigationOwnsFocus && lastFocused == media.id) Modifier.initialTvFocus(cardFocus, cardFocusGranted) else Modifier)
                .onFocusChanged { if (it.isFocused) lastFocused = media.id }
        },
        cardActions = { media -> NativePluginActions(repo, listOf(NativePluginActionKind.MEDIA_CARD), media = media, label = "Title actions") },
    )
    if (searchDialog) TextEntryDialog(if (filters.manga) "Search manga" else "Search anime", "Title or keyword", query,
        helper = "Leave empty to browse with your filters.", allowEmpty = true, submitLabel = "Search", submitOnIme = true,
        inputModifier = Modifier.testTag("discovery-search-field"),
        onDismiss = { searchDialog = false; searchFocusGranted.value = false }, onSubmit = ::submitSearch)
    if (filterDialog) DiscoveryFiltersDialog(repo, filters.copy(search = query.trim()), onDismiss = {
        filterDialog = false; filtersFocusGranted.value = false
    }) { updated ->
        filters = updated; query = updated.search; page = 1; restoreCard = false; reload++
        filterDialog = false; filtersFocusGranted.value = false
    }
}

internal enum class NativeDiscoveryControl { BACK, SEARCH, FILTERS, AIRING, PREVIOUS, NEXT }

internal data class NativeDiscoveryActions(
    val onBack: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onFilters: () -> Unit = {},
    val onAiring: (() -> Unit)? = null,
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDetails: (MediaCard) -> Unit = {},
)

/** The production layout accepts already-loaded data; repository and restoration effects stay in the screen. */
@Composable
internal fun NativeDiscoveryContent(
    filters: AnimeDiscoveryFilters,
    page: Int,
    result: AnimeDiscoveryPage?,
    loading: Boolean = false,
    error: String? = null,
    actions: NativeDiscoveryActions = NativeDiscoveryActions(),
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    onGridPlaced: () -> Unit = {},
    controlModifier: @Composable (NativeDiscoveryControl) -> Modifier = { Modifier },
    cardModifier: @Composable (MediaCard) -> Modifier = { Modifier },
    cardActions: @Composable (MediaCard) -> Unit = {},
    artwork: @Composable (MediaCard, Modifier) -> Unit = { media, imageModifier ->
        NativeArtwork(media, media.title, imageModifier, ContentScale.Crop)
    },
) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (filters.manga) "Discover manga" else "Discover anime", style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(discoverySortLabel(filters), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            Button(onClick = actions.onBack, modifier = Modifier.testTag("discovery-back").then(controlModifier(NativeDiscoveryControl.BACK))) { Text("Back") }
        }
        Box(Modifier.fillMaxWidth().testTag("discovery-toolbar")) {
            ActionRow {
                Button(onClick = actions.onSearch, modifier = Modifier.testTag("discovery-search-submit").then(controlModifier(NativeDiscoveryControl.SEARCH))) {
                    Text(if (filters.search.isBlank()) "Search" else "Search: ${filters.search}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp))
                }
                Button(onClick = actions.onFilters, modifier = Modifier.testTag("discovery-filters").then(controlModifier(NativeDiscoveryControl.FILTERS))) {
                    val count = listOf(filters.statuses.isNotEmpty(), filters.genres.isNotEmpty(), filters.tags.isNotEmpty(),
                        filters.averageScoreGreater != null, filters.season != null, filters.seasonYear != null, filters.format != null, filters.countryOfOrigin != null, filters.isAdult).count { it }
                    Text(if (count == 0) "Filters" else "Filters ($count)")
                }
                if (!filters.manga && actions.onAiring != null) Button(onClick = actions.onAiring,
                    modifier = Modifier.testTag("discovery-airing").then(controlModifier(NativeDiscoveryControl.AIRING))) { Text("Airing") }
                Spacer(Modifier.width(8.dp))
                Text("Page $page" + (result?.takeIf { !loading && error == null }?.lastPage?.let { " of $it" } ?: ""),
                    fontSize = 16.sp, maxLines = 1, modifier = Modifier.testTag("discovery-page"))
                Button(onClick = actions.onPrevious, enabled = page > 1,
                    modifier = Modifier.testTag("discovery-previous").then(controlModifier(NativeDiscoveryControl.PREVIOUS))) { Text("Previous") }
                Button(onClick = actions.onNext, enabled = result?.hasNextPage == true && error == null,
                    modifier = Modifier.testTag("discovery-next").then(controlModifier(NativeDiscoveryControl.NEXT))) { Text("Next") }
            }
        }
        when {
            loading -> LoadingMessage(if (filters.manga) "Discovering manga…" else "Discovering anime…")
            error != null -> ErrorMessage(error, actions.onRetry)
            result?.media.isNullOrEmpty() -> EmptyMessage(if (filters.manga) "No manga found" else "No anime found", "Try another title or adjust the filters. Leave the title empty to browse with your filters.")
            else -> {
                val loaded = result!!
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // Width chooses the column count, while the actual remaining height bounds
                    // each poster. Expanding the rail must never make a focused card taller
                    // than the grid; titles retain two readable lines at either width.
                    val gridPadding = 8.dp
                    val columnGap = 16.dp
                    val columns = ((maxWidth - gridPadding * 2 + columnGap) / (136.dp + columnGap)).toInt().coerceAtLeast(1)
                    val cardWidth = (maxWidth - gridPadding * 2 - columnGap * (columns - 1)) / columns
                    val titleHeight = with(LocalDensity.current) { 44.sp.toDp() } + 20.dp
                    val posterHeight = minOf(cardWidth / .7f, 216.dp, (maxHeight - titleHeight - gridPadding * 2).coerceAtLeast(0.dp))
                    LazyVerticalGrid(columns = GridCells.Fixed(columns), state = gridState,
                        modifier = Modifier.fillMaxSize().testTag("discovery-grid").onGloballyPositioned { if (it.isAttached) onGridPlaced() },
                        contentPadding = PaddingValues(gridPadding), horizontalArrangement = Arrangement.spacedBy(columnGap), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(loaded.media, key = { it.id }) { media ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Card(onClick = { actions.onDetails(media) },
                                    scale = CardDefaults.scale(focusedScale = 1f),
                                    border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(12.dp))),
                                    modifier = Modifier.fillMaxWidth().testTag("discovery-media-${media.id}")
                                        .then(cardModifier(media))) {
                                    Column {
                                        artwork(media, Modifier.fillMaxWidth().height(posterHeight).background(MaterialTheme.colorScheme.surface))
                                        Text(media.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 17.sp, lineHeight = 22.sp,
                                            fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().height(titleHeight).padding(10.dp))
                                    }
                                }
                                cardActions(media)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val DiscoverySorts = listOf(
    "AUTO" to "Automatic", "TRENDING_DESC" to "Trending", "POPULARITY_DESC" to "Most popular",
    "SCORE_DESC" to "Highest rated", "START_DATE_DESC" to "Newest releases", "FAVOURITES_DESC" to "Most favourited",
    "TITLE_ROMAJI" to "Title A–Z", "SEARCH_MATCH" to "Best matches",
)
private val MangaDiscoveryFormats = listOf("" to "Any format", "MANGA" to "Manga", "ONE_SHOT" to "One shot")
private val MangaDiscoveryCountries = listOf("" to "Any country", "JP" to "Japan", "KR" to "South Korea", "CN" to "China", "TW" to "Taiwan")
private fun discoverySorts(manga: Boolean) = DiscoverySorts + if (manga) listOf("CHAPTERS_DESC" to "Most chapters") else listOf("EPISODES_DESC" to "Most episodes")
private val DiscoveryStatuses = listOf("RELEASING" to "Airing", "FINISHED" to "Finished", "NOT_YET_RELEASED" to "Upcoming", "HIATUS" to "On hiatus", "CANCELLED" to "Cancelled")
private val DiscoverySeasons = listOf("" to "Any season", "WINTER" to "Winter", "SPRING" to "Spring", "SUMMER" to "Summer", "FALL" to "Fall")
private val DiscoveryFormats = listOf("" to "Any format", "TV" to "TV series", "TV_SHORT" to "TV short", "MOVIE" to "Movie", "SPECIAL" to "Special", "OVA" to "OVA", "ONA" to "ONA")
private val DiscoveryGenres = listOf("Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Hentai", "Horror", "Mahou Shoujo", "Mecha", "Music", "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller").map { it to it }

private fun discoverySortLabel(filters: AnimeDiscoveryFilters): String =
    if (filters.sort == "AUTO") if (filters.search.isBlank()) "Trending now" else "Best matches"
    else discoverySorts(filters.manga).firstOrNull { it.first == filters.sort }?.second ?: "Discover anime"

@Composable
private fun DiscoveryFiltersDialog(repo: SeanimeRepository, initial: AnimeDiscoveryFilters, onDismiss: () -> Unit, onApply: (AnimeDiscoveryFilters) -> Unit) {
    var draft by rememberSaveable(stateSaver = DiscoveryFilterSaver) { mutableStateOf(initial) }
    val sorts = discoverySorts(initial.manga)
    val formats = if (initial.manga) MangaDiscoveryFormats else DiscoveryFormats
    val maximumScore = if (initial.manga) 9 else 99
    var year by rememberSaveable { mutableStateOf(initial.seasonYear?.toString().orEmpty()) }
    var score by rememberSaveable { mutableStateOf(initial.averageScoreGreater?.toString().orEmpty()) }
    var tags by rememberSaveable { mutableStateOf(initial.tags.joinToString(", ")) }
    var chooser by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreChoice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tagSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var tagsLoading by remember { mutableStateOf(true) }
    var tagsError by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val firstFocusGranted = remember { mutableStateOf(false) }
    val choiceFocus = remember { FocusRequester() }
    val choiceFocusGranted = remember { mutableStateOf(true) }
    fun closeChoice() { restoreChoice = chooser; chooser = null; choiceFocusGranted.value = false }
    fun closeEditor() { restoreChoice = editor; editor = null; choiceFocusGranted.value = false }
    LaunchedEffect(choiceFocusGranted.value) {
        if (choiceFocusGranted.value) restoreChoice = null
    }
    LaunchedEffect(repo) {
        try { tagSuggestions = nativeDiscoveryTags(repo, initial.manga) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { tagsError = "Collection tags aren't available. You can enter tag names below." }
        finally { tagsLoading = false }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(min = 480.dp, max = 700.dp).heightIn(max = 480.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Discovery filters", style = MaterialTheme.typography.titleLarge)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f).testTag("discovery-filter-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    FilterChoiceButton("Sort", sorts.first { it.first == draft.sort }.second, "sort", restoreChoice, choiceFocus, choiceFocusGranted,
                        Modifier.initialTvFocus(firstFocus, firstFocusGranted)) { chooser = "sort" }
                }
                item { FilterChoiceButton("Release status", DiscoveryStatuses.filter { it.first in draft.statuses }.joinToString { it.second }.ifBlank { "Any" }, "status", restoreChoice, choiceFocus, choiceFocusGranted) { chooser = "status" } }
                if (!initial.manga) item { FilterChoiceButton("Season", DiscoverySeasons.first { it.first == draft.season.orEmpty() }.second, "season", restoreChoice, choiceFocus, choiceFocusGranted) { chooser = "season" } }
                item { FilterChoiceButton("Release year", year.ifBlank { "Any" }, "year", restoreChoice, choiceFocus, choiceFocusGranted) { editor = "year" } }
                item { FilterChoiceButton("Format", formats.first { it.first == draft.format.orEmpty() }.second, "format", restoreChoice, choiceFocus, choiceFocusGranted) { chooser = "format" } }
                if (initial.manga) item { FilterChoiceButton("Country", MangaDiscoveryCountries.first { it.first == draft.countryOfOrigin.orEmpty() }.second, "country", restoreChoice, choiceFocus, choiceFocusGranted) { chooser = "country" } }
                item { FilterChoiceButton("Genres", draft.genres.joinToString().ifBlank { "Any" }, "genres", restoreChoice, choiceFocus, choiceFocusGranted) { chooser = "genres" } }
                item { FilterChoiceButton("Tags from your AniList collection", tags.ifBlank { "Any" }, "tags", restoreChoice, choiceFocus, choiceFocusGranted) { chooser = "tags" } }
                item { FilterChoiceButton("Additional tag names", tags.ifBlank { "Any" }, "custom-tags", restoreChoice, choiceFocus, choiceFocusGranted) { editor = "custom-tags" } }
                item { FilterChoiceButton("Score above", score.ifBlank { "Any" }, "score", restoreChoice, choiceFocus, choiceFocusGranted) { editor = "score" } }
                item {
                    Button(onClick = { draft = draft.copy(isAdult = !draft.isAdult) }, modifier = Modifier.fillMaxWidth().testTag("discovery-filter-adult")) {
                        Text(if (draft.isAdult) "Adult titles: Only (requires server setting)" else "Adult titles: Exclude")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val yearValue = year.takeIf(String::isNotBlank)?.toIntOrNull()
                    val scoreValue = score.takeIf(String::isNotBlank)?.toIntOrNull()
                    when {
                        year.isNotBlank() && (yearValue == null || yearValue !in 1..9999) -> error = "Enter a valid release year"
                        score.isNotBlank() && (scoreValue == null || scoreValue !in 0..maximumScore) -> error = "Score must be between 0 and $maximumScore"
                        else -> onApply(draft.copy(seasonYear = yearValue, averageScoreGreater = scoreValue, tags = AnimeDiscoveryFilters.parseTags(tags)))
                    }
                }, modifier = Modifier.testTag("discovery-filter-apply")) { Text("Apply") }
                Button(onClick = {
                    draft = AnimeDiscoveryFilters(search = initial.search, manga = initial.manga); year = ""; score = ""; tags = ""; error = null
                }, modifier = Modifier.testTag("discovery-filter-reset")) { Text("Reset") }
                Button(onClick = onDismiss, modifier = Modifier.testTag("discovery-filter-cancel")) { Text("Cancel") }
            }
        }
    }
    editor?.let { field ->
        val title = when (field) { "year" -> "Release year"; "score" -> "Score above"; else -> "Additional tag names" }
        val value = when (field) { "year" -> year; "score" -> score; else -> tags }
        TextEntryDialog(title, when (field) { "year" -> "Year (1–9999)"; "score" -> "Score (0–$maximumScore)"; else -> "Tag names, separated by commas" }, value,
            helper = if (field == "custom-tags") "Enter AniList tags absent from your collection. Leave empty to remove the tag filter." else "Leave empty for any value.",
            allowEmpty = true, inputModifier = Modifier.testTag("discovery-editor-$field"),
            keyboardType = if (field == "custom-tags") KeyboardType.Text else KeyboardType.Number, onDismiss = ::closeEditor) { updated ->
            when (field) { "year" -> year = updated; "score" -> score = updated; else -> tags = updated }
            error = null
        }
    }
    chooser?.let { field ->
        val choices = when (field) { "sort" -> sorts; "status" -> DiscoveryStatuses; "season" -> DiscoverySeasons; "format" -> formats; "country" -> MangaDiscoveryCountries;
            "tags" -> (tagSuggestions + AnimeDiscoveryFilters.parseTags(tags)).distinct().sorted().map { it to it }; else -> DiscoveryGenres }
        val selected = when (field) { "sort" -> listOf(draft.sort); "status" -> draft.statuses; "season" -> listOf(draft.season.orEmpty()); "format" -> listOf(draft.format.orEmpty()); "country" -> listOf(draft.countryOfOrigin.orEmpty()); "tags" -> AnimeDiscoveryFilters.parseTags(tags); else -> draft.genres }
        val multiple = field in setOf("status", "genres", "tags")
        DiscoveryChoiceDialog(field, choices, selected, multiple = multiple, onDismiss = ::closeChoice,
            message = if (field == "tags") when { tagsLoading -> "Loading collection tags…"; tagsError != null -> tagsError;
                choices.isEmpty() -> "No collection tags yet. Connect AniList or enter tag names in the filters."; else -> "Suggestions from titles in your AniList collection" } else null) { value ->
            fun toggle(list: List<String>) = if (value in list) list - value else list + value
            draft = when (field) {
                "sort" -> draft.copy(sort = value)
                "status" -> draft.copy(statuses = toggle(draft.statuses))
                "season" -> draft.copy(season = value.takeIf(String::isNotBlank))
                "format" -> draft.copy(format = value.takeIf(String::isNotBlank))
                "country" -> draft.copy(countryOfOrigin = value.takeIf(String::isNotBlank))
                "tags" -> { tags = toggle(AnimeDiscoveryFilters.parseTags(tags)).joinToString(", "); draft }
                else -> draft.copy(genres = toggle(draft.genres))
            }
            if (!multiple) closeChoice()
        }
    }
}

@Composable
private fun FilterChoiceButton(label: String, value: String, field: String, restoring: String?, focus: FocusRequester, granted: MutableState<Boolean>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick, modifier = Modifier.fillMaxWidth().testTag("discovery-filter-$field")
        .then(if (restoring == field) Modifier.initialTvFocus(focus, granted) else Modifier).then(modifier)) {
        Text("$label: $value", maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DiscoveryChoiceDialog(field: String, choices: List<Pair<String, String>>, selected: List<String>, multiple: Boolean, onDismiss: () -> Unit, message: String? = null, onSelect: (String) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    val firstFocusGranted = remember { mutableStateOf(false) }
    val selectedIndex = choices.indexOfFirst { it.first in selected }.coerceAtLeast(0)
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(min = 380.dp, max = 600.dp).heightIn(max = 450.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose $field", style = MaterialTheme.typography.titleLarge)
            message?.let { Text(it, fontSize = 15.sp) }
            LazyColumn(Modifier.weight(1f, fill = false).testTag("discovery-choices"), state = scroll, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(choices, key = { it.first }) { (value, label) ->
                    Button(onClick = { onSelect(value) }, modifier = Modifier.fillMaxWidth().testTag("discovery-choice-$value")
                        .then(if (choices[selectedIndex].first == value) Modifier.initialTvFocus(firstFocus, firstFocusGranted) else Modifier)) {
                        Text((if (value in selected) "✓ " else "") + label)
                    }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.testTag("discovery-choice-close")
                .then(if (choices.isEmpty()) Modifier.initialTvFocus(firstFocus, firstFocusGranted) else Modifier)) { Text(if (multiple) "Done" else "Cancel") }
        }
    }
}
