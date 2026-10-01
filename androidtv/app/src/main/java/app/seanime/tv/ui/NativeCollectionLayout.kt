package app.seanime.tv.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.data.MediaCard
import kotlin.math.ceil

/** State-free production layouts are also rendered by host screenshot fixtures. */
@Composable
internal fun NativeCollectionPage(title: String, profileName: String,
    toolbar: @Composable () -> Unit, pluginActions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(profileName.ifBlank { "Local profile" }, color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        }
        toolbar()
        pluginActions()
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@Composable
internal fun NativeCollectionToolbar(query: String, showManage: Boolean,
    searchModifier: Modifier = Modifier, optionsModifier: Modifier = Modifier, discoverModifier: Modifier = Modifier, manageModifier: Modifier = Modifier,
    onFocused: () -> Unit = {}, onSearch: () -> Unit, onOptions: () -> Unit, onDiscover: () -> Unit, onManage: () -> Unit) {
    Row(Modifier.fillMaxWidth().onFocusChanged { if (it.hasFocus) onFocused() }.focusRestorer().horizontalScroll(rememberScrollState()).padding(ActionRowContentPadding)
        .testTag("collection-toolbar"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onSearch, modifier = searchModifier.testTag("anime-search-submit")) {
            Text(if (query.isBlank()) "Search" else "Search: $query", maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp))
        }
        Button(onClick = onOptions, modifier = optionsModifier.testTag("anime-collection-options")) { Text("Lists & sort") }
        Button(onClick = onDiscover, modifier = discoverModifier.testTag("anime-discover")) { Text("Discover") }
        if (showManage) Button(onClick = onManage, modifier = manageModifier.testTag("anime-manage")) { Text("Manage") }
    }
}

@Composable
internal fun NativeCollectionGrid(media: List<MediaCard>, state: LazyGridState = rememberLazyGridState(),
    cardModifier: @Composable (Long) -> Modifier = { Modifier }, onCardFocused: (Long) -> Unit = {},
    cardActions: @Composable (MediaCard) -> Unit = {},
    artwork: @Composable (MediaCard, Modifier) -> Unit = { card, imageModifier ->
        NativeArtwork(card.imageUrl, card.title, imageModifier, ContentScale.Crop)
    }, onDetails: (Long) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("collection-viewport")) {
        // Bound width and height together: widening the screen cannot turn four
        // posters into four taller posters before an adaptive fifth column appears.
        val columns = ceil(maxWidth.value / 176f).toInt().coerceAtLeast(1)
        val cardWidth = (maxWidth - 16.dp - 16.dp * (columns - 1)) / columns
        val type = MaterialTheme.typography
        val captionAndInsets = with(LocalDensity.current) {
            type.titleSmall.lineHeight.toDp() * 2 + type.labelSmall.lineHeight.toDp() + 40.dp
        }
        val posterHeight = minOf(cardWidth / .7f, (maxHeight - captionAndInsets).coerceAtLeast(0.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(columns), state = state,
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxSize().testTag("media-grid")) {
            items(media, key = { it.id }) { card ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NativePosterCard(card, posterHeight,
                        modifier = cardModifier(card.id).onFocusChanged { if (it.isFocused) onCardFocused(card.id) }
                            .testTag("media-${card.id}"), artwork = { artwork(card, it) }, onClick = { onDetails(card.id) })
                    cardActions(card)
                }
            }
        }
    }
}
