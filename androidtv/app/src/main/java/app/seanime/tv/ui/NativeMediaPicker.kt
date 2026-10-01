package app.seanime.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.data.MediaCard
import app.seanime.tv.data.SeanimeRepository
import kotlinx.coroutines.CancellationException

/** A title chooser keeps ordinary TV workflows independent of numeric database identifiers. */
@Composable
internal fun NativeMediaPickerDialog(
    repo: SeanimeRepository,
    title: String,
    manga: Boolean = false,
    onDismiss: () -> Unit,
    onSelect: (MediaCard) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var catalog by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(1) }
    var retry by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<MediaCard>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val collectionFocus = remember { FocusRequester() }
    val focusGranted = remember { mutableStateOf(false) }
    LaunchedEffect(repo, manga, catalog, submitted, page, retry) {
        loading = true
        error = null
        try {
            results = if (catalog) repo.search(submitted, manga, page)
                else if (manga) repo.mangaList() else repo.library()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Couldn't load titles"; results = emptyList() }
        finally { loading = false }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(820.dp).heightIn(min = 350.dp, max = 470.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                ActionButton("Cancel", modifier = Modifier.testTag("media-picker-cancel"), onClick = onDismiss)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, label = { Text(if (manga) "Manga title" else "Anime title") },
                    singleLine = true, modifier = Modifier.weight(1f).testTag("media-picker-query"))
                ActionButton("Search titles", modifier = Modifier.testTag("media-picker-search")) {
                    submitted = query.trim(); catalog = true; page = 1; retry++
                }
            }
            ActionRow {
                ActionButton(if (catalog) "My collection" else "✓ My collection",
                    modifier = Modifier.initialTvFocus(collectionFocus, focusGranted).testTag("media-picker-collection")) {
                    catalog = false; page = 1; retry++
                }
                ActionButton(if (catalog && submitted.isBlank()) "✓ Trending" else "Trending") {
                    query = ""; submitted = ""; catalog = true; page = 1; retry++
                }
                if (catalog) Text("Page $page", modifier = Modifier.align(Alignment.CenterVertically))
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("media-picker-loading"))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("media-picker-results"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (error != null) item {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    ActionButton("Try again") { retry++ }
                } else if (!loading && results.isEmpty()) item {
                    Text(if (catalog) "No titles found. Try another search." else "Your collection is empty. Search titles or explore Trending.")
                }
                if (!loading && error == null) items(results, key = { it.id }) { media ->
                    androidx.tv.material3.Button(onClick = { onSelect(media); onDismiss() },
                        modifier = Modifier.fillMaxWidth().testTag("media-picker-${media.id}")) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            NativeArtwork(media.imageUrl, null, Modifier.size(48.dp, 68.dp), ContentScale.Crop)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(media.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                val progress = if (manga) "Chapter ${media.progress}" else "Episode ${media.progress}"
                                if (media.progress > 0) Text(progress, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("Select")
                        }
                    }
                }
            }
            if (catalog) ActionRow {
                ActionButton("Previous page", !loading && page > 1) { page-- }
                ActionButton("Next page", !loading && results.size == 40 && error == null) { page++ }
            }
        }
    }
}
