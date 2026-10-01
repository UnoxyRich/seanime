package app.seanime.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@NativeTvViewports
@Composable
fun nativeLibraryCollapsedNavigation() = LibraryPreview()

@PreviewTest
@NativeTvViewports
@Composable
fun nativeLibraryExpandedNavigation() = LibraryPreview(railExpanded = true)

@PreviewTest
@NativeTvViewports
@Composable
fun nativeLibraryEmptyCollection() = LibraryPreview(empty = true)

@PreviewTest
@NativeTvViewports
@Composable
fun nativeLibraryLongSearchAndTitles() = LibraryPreview(query = "The Lantern Keepers of the Last Winter Observatory")

@PreviewTest
@NativeTvLargeFontViewports
@Composable
fun nativeLibraryLargeFont() = LibraryPreview()

@PreviewTest
@NativeTvLargeFontViewports
@Composable
fun nativeLibraryLongSearchLargeFont() = LibraryPreview(query = "The Lantern Keepers of the Last Winter Observatory")

/** Same scaffold/page/toolbar/grid composition used by BrowseScreen, supplied with static data. */
@Composable
private fun LibraryPreview(railExpanded: Boolean = false, empty: Boolean = false, query: String = "") {
    val contentFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    // Drawer expansion is supplied fixture state; host previews do not prove acquired focus.
    val railFocusGranted = remember { mutableStateOf(true) }
    SeanimeTheme {
        NativeTvScaffold(
            destination = TvFeature.LIBRARY,
            statusLabel = "Ready",
            railExpanded = railExpanded,
            onNavigate = {},
            onRailFocusChanged = {},
            contentFocus = contentFocus,
            railFocus = railFocus,
            railFocusGranted = railFocusGranted,
        ) {
            NativeCollectionPage("Library", "Local profile", toolbar = {
                NativeCollectionToolbar(
                    query = query, showManage = true,
                    onSearch = {}, onOptions = {}, onDiscover = {}, onManage = {},
                )
            }) {
                if (empty) {
                    EmptyMessage("Your library is empty", "Add a media folder or discover a title to start your collection.")
                } else {
                    NativeCollectionGrid(
                        media = PreviewLibraryCards,
                        artwork = { card, modifier -> FictionalPosterArtwork((card.id - 1).toInt(), modifier) },
                        onDetails = {},
                    )
                }
            }
        }
    }
}
