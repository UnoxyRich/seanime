package app.seanime.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.data.*
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun RecentAiringScreen(repo: SeanimeRepository, initial: RecentAiringFilters, initialPage: Int = 1,
    onDetails: (Long) -> Unit, onBack: () -> Unit) {
    var from by rememberSaveable { mutableLongStateOf(initial.from) }
    var until by rememberSaveable { mutableLongStateOf(initial.until) }
    var upcoming by rememberSaveable { mutableStateOf(initial.upcoming) }
    var page by rememberSaveable { mutableIntStateOf(initialPage) }
    var retry by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<RecentAiringPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastKey by rememberSaveable { mutableStateOf("") }
    var restore by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    val backFocus = remember { FocusRequester() }
    val backGranted = remember { mutableStateOf(restore) }
    val itemFocus = remember { FocusRequester() }
    val itemGranted = remember { mutableStateOf(!restore) }
    val previousFocus = remember { FocusRequester() }
    val previousGranted = remember { mutableStateOf(true) }
    var restorePage by remember { mutableStateOf(false) }
    val filters = RecentAiringFilters(from, until, upcoming)
    ReportNativePluginScreen(NativeScreenLocation("/discover", mapOf("type" to "schedule", "from" to "$from", "until" to "$until", "upcoming" to "$upcoming", "page" to "$page")), priority = 2)
    BackHandler(onBack = onBack)
    LaunchedEffect(filters, page, retry) {
        loading = true; error = null
        try { result = loadRecentAiring(repo, filters, page) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Couldn't load the airing schedule" }
        finally { loading = false }
    }
    LaunchedEffect(loading, result, restore, restorePage) {
        if (!loading) {
            if (restore && (error != null || result?.items?.none { it.key == lastKey } != false)) { restore = false; backGranted.value = false }
            if (restorePage) { if (page > 1) previousGranted.value = false else backGranted.value = false; restorePage = false }
        }
    }
    LaunchedEffect(itemGranted.value) { if (itemGranted.value) restore = false }
    fun shift(days: Int) { from += days * 86_400L; until += days * 86_400L; page = 1; restore = false }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Airing schedule", style = MaterialTheme.typography.titleLarge)
            ActionButton("Back", modifier = Modifier.testTag("airing-back").initialTvFocus(backFocus, backGranted), onClick = onBack)
        }
        Text("${airingDate(from, false)} – ${airingDate(until, false)} · Japanese anime · TV shorts and adult titles omitted")
        ActionRow {
            ActionButton("Earlier", !loading, Modifier.testTag("airing-earlier")) { shift(-14) }
            ActionButton(if (upcoming) "Upcoming only" else "All in range", !loading, Modifier.testTag("airing-upcoming")) { upcoming = !upcoming; page = 1 }
            ActionButton("Later", !loading, Modifier.testTag("airing-later")) { shift(14) }
            ActionButton("Previous", !loading && page > 1, Modifier.testTag("airing-previous").initialTvFocus(previousFocus, previousGranted)) { page--; restorePage = true }
            ActionButton("Next", !loading && error == null && result?.hasNext == true, Modifier.testTag("airing-next")) { page++; restorePage = true }
        }
        Text("Page $page" + (result?.takeIf { !loading && error == null }?.lastPage?.let { " of $it" } ?: ""))
        when {
            loading -> LoadingMessage("Loading airing schedules…")
            error != null -> ErrorMessage(error!!) { retry++ }
            result?.items.isNullOrEmpty() -> EmptyMessage("No matching airings on this page", "Try another range or the next page if one is available.")
            else -> {
                val loaded = result!!
                var placed by remember(loaded) { mutableStateOf(false) }
                val restoreIndex = remember(loaded) { if (restore) loaded.items.indexOfFirst { it.key == lastKey }.coerceAtLeast(0) else 0 }
                LaunchedEffect(loaded, placed) { if (placed) list.scrollToItem(restoreIndex) }
                LazyColumn(Modifier.weight(1f).testTag("airing-list").onGloballyPositioned { placed = it.isAttached }, state = list,
                    contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(loaded.items, key = { it.key }) { item ->
                        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            NativeArtwork(item.media, item.media.title, Modifier.size(64.dp, 90.dp), ContentScale.Crop)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Episode ${item.episode} · ${airingDate(item.airingAt, true)}")
                                ActionButton(item.media.title, modifier = Modifier.testTag("airing-title-${item.media.id}-${item.episode}")
                                    .then(if (lastKey == item.key) Modifier.initialTvFocus(itemFocus, itemGranted) else Modifier)) {
                                    lastKey = item.key; restore = true; itemGranted.value = false; onDetails(item.media.id)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
private fun airingDate(seconds: Long, time: Boolean): String = SimpleDateFormat(if (time) "EEE, MMM d · HH:mm" else "MMM d", Locale.getDefault()).format(Date(seconds * 1000))
