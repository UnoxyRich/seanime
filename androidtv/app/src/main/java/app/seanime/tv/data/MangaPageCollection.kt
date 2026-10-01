package app.seanime.tv.data

/** Dimensions are keyed by the provider's page.index, not the position in the sorted list. */
data class MangaPageDimensions(val width: Int, val height: Int) {
    val isWide: Boolean get() = width > height
}

data class MangaPageCollection(
    val pages: List<MangaPage>,
    val dimensions: Map<Int, MangaPageDimensions> = emptyMap(),
)
