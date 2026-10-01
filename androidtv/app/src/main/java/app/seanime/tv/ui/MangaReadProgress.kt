package app.seanime.tv.ui

import app.seanime.tv.data.SeanimeRepository

internal data class MangaReadResult(val progress: Int, val updated: Boolean)

internal fun mangaChapterProgress(number: String): Int? = number.toDoubleOrNull()
    ?.takeIf { it.isFinite() && it >= 1 && it <= Int.MAX_VALUE.toDouble() }?.toInt()

/** Marking a chapter read advances progress; the separate manual editor may intentionally lower it. */
internal suspend fun markMangaChapterRead(repo: SeanimeRepository, mediaId: Long, number: String,
    fallbackTotal: Int = 0, fallbackMalId: Int? = null): MangaReadResult {
    val target = requireNotNull(mangaChapterProgress(number)) { "This chapter has no valid progress number" }
    val current = repo.mangaDetails(mediaId)
    check(current.media.id == mediaId) { "The server returned progress for another manga" }
    if (current.media.progress >= target) return MangaReadResult(current.media.progress, false)
    repo.updateMangaProgress(mediaId, target, current.media.totalEpisodes ?: fallbackTotal,
        current.media.raw.optInt("idMal").takeIf { it > 0 } ?: fallbackMalId)
    return MangaReadResult(target, true)
}

internal fun mangaEndPageRendered(page: Int, count: Int, doublePage: Boolean, rendered: Set<Int>): Boolean {
    if (count == 0) return false
    val visible = MangaPagination.visible(page, count, doublePage)
    return mangaEndPageRendered(visible, count, rendered)
}

internal fun mangaEndPageRendered(visible: List<Int>, count: Int, rendered: Set<Int>): Boolean =
    count > 0 && visible.lastOrNull() == count - 1 && visible.all { it in rendered }
