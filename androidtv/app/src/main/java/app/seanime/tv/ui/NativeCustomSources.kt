package app.seanime.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.data.ExtensionItem
import app.seanime.tv.data.MediaCard
import app.seanime.tv.data.SeanimeJson
import app.seanime.tv.data.SeanimeRepository
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal data class NativeCustomSourcePage(val media: List<MediaCard>, val page: Int, val totalPages: Int)

internal fun nativeCustomSourceTypes(provider: ExtensionItem): List<String> = buildList {
    val settings = provider.raw.optJSONObject("settings")
    if (settings?.optBoolean("supportsAnime") == true) add("anime")
    if (settings?.optBoolean("supportsManga") == true) add("manga")
}

internal suspend fun loadNativeCustomSourcePage(repo: SeanimeRepository, provider: ExtensionItem, type: String, query: String, page: Int): NativeCustomSourcePage {
    require(provider.id.isNotBlank() && type in nativeCustomSourceTypes(provider)) { "This provider does not support the selected media type" }
    require(page > 0) { "Choose a positive page number" }
    val raw = repo.request("POST", "/api/v1/custom-source/provider/list/$type", JSONObject().put("provider", provider.id)
        .put("search", query).put("page", page).put("perPage", 20)) as? JSONObject ?: error("The provider returned an invalid result page")
    // The Go response declares a nullable slice without omitempty: null is a
    // legitimate empty page, while a missing field or another type is invalid.
    check(raw.has("media") && (raw.isNull("media") || raw.optJSONArray("media") != null)) { "The provider did not return a media list" }
    val media = raw.optJSONArray("media").uiObjects().map { SeanimeJson.media(it, type == "manga") }
    check(media.all { it.id > 0 } && media.map { it.id }.distinct().size == media.size) { "The provider returned missing or duplicate media identities" }
    return NativeCustomSourcePage(media, page, raw.optInt("totalPages", 1).coerceAtLeast(page))
}

/** Provider-owned catalogs reuse only this rewrite's native detail/reader routes. */
@Composable
internal fun NativeCustomSources(repo: SeanimeRepository, initialProvider: String = "", onPlay: (PlaybackRequest) -> Unit, onBack: () -> Unit) {
    var providers by remember { mutableStateOf<List<ExtensionItem>>(emptyList()) }
    var providerId by rememberSaveable { mutableStateOf(initialProvider) }
    var type by rememberSaveable { mutableStateOf("anime") }
    var query by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(1) }
    var retry by remember { mutableIntStateOf(0) }
    var providersReady by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf(NativeCustomSourcePage(emptyList(), 1, 1)) }
    var chooseProvider by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableLongStateOf(0L) }
    var lastFocused by rememberSaveable { mutableLongStateOf(0L) }
    val cardFocus = remember { FocusRequester() }
    val cardGranted = remember { mutableStateOf(true) }
    val backFocus = remember { FocusRequester() }
    val backGranted = remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val provider = providers.firstOrNull { it.id == providerId }
    LaunchedEffect(repo, retry) {
        if (providersReady) return@LaunchedEffect
        loading = true; error = null
        try {
            providers = repo.providers("custom-source")
            val selected = providers.firstOrNull { it.id == providerId } ?: providers.firstOrNull()
            providerId = selected?.id.orEmpty()
            selected?.let { if (type !in nativeCustomSourceTypes(it)) type = nativeCustomSourceTypes(it).firstOrNull() ?: "anime" }
            providersReady = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Couldn't load custom sources" }
        finally { loading = false }
    }
    LaunchedEffect(repo, providersReady, providerId, type, page, query, retry) {
        val selected = providers.firstOrNull { it.id == providerId }
        if (!providersReady || selected == null) return@LaunchedEffect
        loading = true; error = null; result = NativeCustomSourcePage(emptyList(), page, page)
        listState.scrollToItem(0)
        backGranted.value = false
        try { result = loadNativeCustomSourcePage(repo, selected, type, query, page) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Couldn't load this source" }
        finally { loading = false }
    }
    fun returnFromDetail() { selectedId = 0L; cardGranted.value = false }
    if (selectedId > 0L) {
        if (type == "manga") NativeMangaEntryRoute(repo, selectedId, ::returnFromDetail)
        else AnimeDetailScreen(selectedId, repo, onPlay, ::returnFromDetail)
        return
    }
    BackHandler(onBack = onBack)
    ReportNativePluginScreen(NativeScreenLocation("/custom-sources", if (providerId.isBlank()) emptyMap() else mapOf("provider" to providerId)))
    FeaturePage("Custom sources", "${provider?.name ?: "Choose a provider"} · ${if (type == "manga") "Manga" else "Anime"} · Page $page", state = listState,
        modifier = Modifier.testTag("custom-source-list")) {
        item { ActionRow {
            ActionButton("Extensions", modifier = Modifier.testTag("custom-source-back").initialTvFocus(backFocus, backGranted), onClick = onBack)
            ActionButton("Provider", !loading && providers.isNotEmpty(), Modifier.testTag("custom-source-provider")) { chooseProvider = true }
            ActionButton("Search", !loading && provider != null, Modifier.testTag("custom-source-search")) { search = true }
            ActionButton("Refresh", !loading, Modifier.testTag("custom-source-refresh")) { providersReady = false; retry++ }
            provider?.let { selected -> nativeCustomSourceTypes(selected).forEach { value ->
                ActionButton((if (value == type) "✓ " else "") + if (value == "anime") "Anime" else "Manga", !loading, Modifier.testTag("custom-source-type-$value")) {
                    type = value; page = 1; lastFocused = 0L
                }
            } }
        } }
        if (query.isNotBlank()) item { Text("Search: $query") }
        if (loading) item { LoadingMessage("Loading source…") }
        error?.let { message -> item { ErrorMessage(message) { retry++ } } }
        if (providersReady && providers.isEmpty()) item { EmptyFeature("No custom sources installed", "Install and enable a custom-source provider in Extensions. These providers supply catalog entries; playback still uses your configured media sources.") }
        if (!loading && error == null && provider != null && result.media.isEmpty()) item { EmptyFeature("No titles found", "Try a different search, provider or media type") }
        if (!loading && error == null) items(result.media.size, key = { result.media[it].id }) { index ->
            val media = result.media[index]
            FeaturePanel(media.title) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    NativeArtwork(media.imageUrl, media.title, Modifier.size(84.dp, 120.dp), ContentScale.Crop, providerResult = true)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (media.description.isNotBlank()) Text(rememberNativeSynopsis(media.description), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        ActionButton(if (type == "manga") "Open manga" else "Open anime", modifier = Modifier.testTag("custom-source-media-${media.id}")
                            .then(if (lastFocused == media.id) Modifier.initialTvFocus(cardFocus, cardGranted) else Modifier)) {
                            lastFocused = media.id; cardGranted.value = true; selectedId = media.id
                        }
                    }
                }
            }
        }
        item { ActionRow {
            ActionButton("Previous page", !loading && error == null && page > 1, Modifier.testTag("custom-source-previous")) { page--; lastFocused = 0L }
            ActionButton("Next page", !loading && error == null && page < result.totalPages, Modifier.testTag("custom-source-next")) { page++; lastFocused = 0L }
        } }
    }
    if (chooseProvider) SettingChoiceDialog("Custom-source provider", providerId, providers.map { SettingChoice(it.id, it.name) }, { chooseProvider = false }) { id ->
        providerId = id; page = 1; lastFocused = 0L
        providers.firstOrNull { it.id == id }?.let { if (type !in nativeCustomSourceTypes(it)) type = nativeCustomSourceTypes(it).firstOrNull() ?: "anime" }
    }
    if (search) TextEntryDialog("Search custom source", "Title", query, allowEmpty = true, inputModifier = Modifier.testTag("custom-source-search-editor"),
        onDismiss = { search = false }) { query = it; page = 1; lastFocused = 0L }
}
