package app.seanime.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.data.MediaCard
import app.seanime.tv.data.MediaArtworkOrigin
import app.seanime.tv.data.SeanimeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** On-demand metadata never blocks the entry's Watch action or its base AniList facts. */
@Composable
internal fun NativeAnimeMetadataScreen(media: MediaCard, repo: SeanimeRepository,
    onPlay: (PlaybackRequest) -> Unit, onBack: () -> Unit, navigationOwnsFocus: Boolean = false) {
    var supplement by remember(media.id) { mutableStateOf<NativeAnimeSupplement?>(null) }
    var loading by remember(media.id) { mutableStateOf(true) }
    var failed by remember(media.id) { mutableStateOf(false) }
    var retry by remember(media.id) { mutableIntStateOf(0) }
    var hideScore by remember(media.id) { mutableStateOf(true) }
    var scoreVisibilityOverride by rememberSaveable(media.id) { mutableStateOf<Boolean?>(null) }
    var selectedId by rememberSaveable(media.id) { mutableLongStateOf(0L) }
    var selectedManga by rememberSaveable(media.id) { mutableStateOf(false) }
    var returnKey by rememberSaveable(media.id) { mutableStateOf("") }
    val list = rememberLazyListState()
    val backFocus = remember { FocusRequester() }
    val backGranted = remember { mutableStateOf(navigationOwnsFocus) }
    val returnFocus = remember { FocusRequester() }
    val returnGranted = remember { mutableStateOf(true) }
    val uriHandler = LocalUriHandler.current
    var linkError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(navigationOwnsFocus) {
        if (navigationOwnsFocus) { backGranted.value = true; returnGranted.value = true }
    }
    LaunchedEffect(repo, media.id) {
        try { hideScore = repo.settings().optJSONObject("anilist")?.optBoolean("hideAudienceScore") == true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { hideScore = true }
    }
    LaunchedEffect(repo, media.id, retry) {
        loading = true; failed = false
        try { supplement = parseNativeAnimeSupplement(repo.animeMetadata(media.id), media.id,
            providerResult = media.artworkOrigin != MediaArtworkOrigin.SERVER) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    fun closeRelated() { selectedId = 0; returnGranted.value = navigationOwnsFocus }
    if (selectedId > 0) {
        key(selectedId, selectedManga) {
            if (selectedManga) NativeMangaEntryRoute(repo, selectedId, ::closeRelated)
            else AnimeDetailScreen(selectedId, repo, onPlay, ::closeRelated, navigationOwnsFocus = navigationOwnsFocus)
        }
        return
    }
    // Parent Detail removes its registration while this view is open; a related route
    // removes this registration in turn, so plugins always observe the visible title.
    ReportNativePluginScreen(NativeScreenLocation("/entry", mapOf("id" to media.id.toString())), priority = 2)
    BackHandler(onBack = onBack)
    fun openRelated(item: NativeAnimeRelated) {
        returnKey = item.key; returnGranted.value = true
        selectedManga = item.media.isManga; selectedId = item.media.id
    }
    val showScore = scoreVisibilityOverride ?: !hideScore
    val facts = remember(media, showScore) { nativeAnimeFacts(media, showScore) }
    val links = remember(media) { nativeAnimeLinks(media) }
    Column(Modifier.fillMaxSize().testTag("anime-information"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f)) {
                Text("More information", style = MaterialTheme.typography.titleLarge)
                Text(media.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            ActionButton("Back", modifier = Modifier.testTag("anime-information-back")
                .then(if (!navigationOwnsFocus) Modifier.initialTvFocus(backFocus, backGranted) else Modifier), onClick = onBack)
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("anime-information-list"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            media.bannerUrl?.let { banner -> item(key = "banner") {
                NativeArtwork(media, "${media.title} banner", Modifier.fillMaxWidth().height(120.dp), ContentScale.Crop, url = banner)
            } }
            items(facts, key = { "fact:${it.label}" }) { fact -> NativeAnimeFactRow(fact) }
            if (nativeAnimeScore(media) != null) item(key = "score-reveal") {
                ActionButton(if (showScore) "Hide audience score" else "Show audience score", modifier = Modifier.testTag("anime-information-score")) {
                    scoreVisibilityOverride = !showScore
                }
            }
            if (media.description.isNotBlank()) item(key = "synopsis") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Synopsis", style = MaterialTheme.typography.titleMedium)
                    NativeAnimeSynopsis(media.description)
                }
            }
            if (links.isNotEmpty()) item(key = "external-links") {
                ActionRow {
                    links.forEach { link -> ActionButton(link.label, modifier = Modifier.testTag("anime-information-link-${link.label}")) {
                        linkError = null
                        runCatching { uriHandler.openUri(link.url) }.onFailure { linkError = "No installed app can open this link." }
                    } }
                }
                linkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (loading) item(key = "additional-loading") {
                Text("Loading additional details…", modifier = Modifier.testTag("anime-information-loading"))
            }
            if (failed) item(key = "additional-failed") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Additional title details are unavailable. Your existing information is still shown.",
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("anime-information-error"))
                    ActionButton("Retry additional details", modifier = Modifier.testTag("anime-information-retry")) {
                        backGranted.value = navigationOwnsFocus
                        retry++
                    }
                }
            }
            supplement?.let { data ->
                if (data.studios.isNotEmpty()) item(key = "studios") { NativeAnimeFactRow(NativeAnimeFact("Studios", data.studios.joinToString(" · "))) }
                items(data.rankings, key = { "ranking:$it" }) { rank -> NativeAnimeFactRow(NativeAnimeFact("Ranking", rank)) }
                if (data.characters.isNotEmpty()) {
                    item(key = "characters-heading") { Text("Characters", style = MaterialTheme.typography.titleLarge) }
                    items(data.characters, key = { "character:${it.id}" }) { character ->
                        NativeAnimeInformationPanel(Modifier.testTag("anime-information-character-${character.id}")) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                NativeArtwork(character.image, character.name, Modifier.size(58.dp, 76.dp), ContentScale.Crop,
                                    providerResult = media.artworkOrigin != MediaArtworkOrigin.SERVER)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(character.name, style = MaterialTheme.typography.titleMedium)
                                    Text(character.role, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                fun linkedRows(title: String, rows: List<NativeAnimeRelated>) {
                    if (rows.isEmpty()) return
                    item(key = "$title-heading") { Text(title, style = MaterialTheme.typography.titleLarge) }
                    items(rows, key = { it.key }) { related ->
                        NativeAnimeRelatedRow(related, Modifier.testTag("anime-information-${related.key}")
                            .then(if (returnKey == related.key && !navigationOwnsFocus) Modifier.initialTvFocus(returnFocus, returnGranted) else Modifier)) {
                            openRelated(related)
                        }
                    }
                }
                linkedRows("Relations", data.relations)
                linkedRows("Recommendations", data.recommendations)
            }
        }
    }
}

@Composable
private fun NativeAnimeFactRow(fact: NativeAnimeFact) {
    NativeAnimeInformationPanel(Modifier.testTag("anime-information-fact-${fact.label}")) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(fact.label, Modifier.width(156.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fact.value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Read-only rows are remote targets, allowing long metadata to be traversed without touch. */
@Composable
private fun NativeAnimeInformationPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
        .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(10.dp))
        .onFocusChanged { focused = it.isFocused }.focusable().padding(14.dp), content = content)
}

@Composable
private fun NativeAnimeSynopsis(description: String) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    Text(rememberNativeSynopsis(description), style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).testTag("anime-information-synopsis")
            .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionDown, Key.DirectionUp -> {
                        val forward = event.key == Key.DirectionDown
                        if (if (forward) scroll.canScrollForward else scroll.canScrollBackward) {
                            scope.launch { scroll.scrollBy((scroll.viewportSize * .75f).coerceAtLeast(1f) * if (forward) 1 else -1) }
                            true
                        } else false
                    }
                    else -> false
                }
            }.focusable().verticalScroll(scroll).padding(12.dp))
}

@Composable
private fun NativeAnimeRelatedRow(item: NativeAnimeRelated, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth(), scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
        border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(12.dp)))) {
        Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            NativeArtwork(item.media, item.media.title, Modifier.size(60.dp, 84.dp), ContentScale.Crop)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.media.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(item.relation, if (item.media.isManga) "Manga" else "Anime").filter(String::isNotBlank).joinToString(" · "))
            }
        }
    }
}
