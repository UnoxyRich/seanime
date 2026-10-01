package app.seanime.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import app.seanime.tv.data.AnimeDiscoveryFilters
import app.seanime.tv.data.AnimeDiscoveryPage
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@NativeTvViewports
@Composable
fun nativeDiscoveryFirstPage() = DiscoveryPreview()

@PreviewTest
@NativeTvViewports
@Composable
fun nativeDiscoveryLastPage() = DiscoveryPreview(page = 3)

@PreviewTest
@NativeTvViewports
@Composable
fun nativeDiscoveryEmptyResults() = DiscoveryPreview(empty = true)

@Composable
private fun DiscoveryPreview(page: Int = 1, empty: Boolean = false) {
    val contentFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    val railFocusGranted = remember { mutableStateOf(true) }
    SeanimeTheme {
        NativeTvScaffold(
            destination = TvFeature.ANILIST,
            statusLabel = "Ready",
            railExpanded = false,
            onNavigate = {},
            onRailFocusChanged = {},
            contentFocus = contentFocus,
            railFocus = railFocus,
            railFocusGranted = railFocusGranted,
        ) {
            NativeDiscoveryContent(
                filters = AnimeDiscoveryFilters(
                    search = if (empty) "An undiscovered title" else if (page > 1) "The Lantern Keepers of the Last Winter Observatory" else "",
                    sort = "SCORE_DESC", genres = listOf("Adventure", "Fantasy"),
                ),
                page = page,
                result = AnimeDiscoveryPage(if (empty) emptyList() else PreviewLibraryCards,
                    currentPage = page, hasNextPage = !empty && page < 3, lastPage = if (empty) 1 else 3),
                actions = NativeDiscoveryActions(onAiring = {}),
                artwork = { card, modifier -> FictionalPosterArtwork((card.id - 1).toInt(), modifier) },
            )
        }
    }
}
