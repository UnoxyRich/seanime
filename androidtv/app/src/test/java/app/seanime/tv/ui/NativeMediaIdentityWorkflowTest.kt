package app.seanime.tv.ui

import app.seanime.tv.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMediaIdentityWorkflowTest {
    private val ids = listOf(2_147_483_648L, 4_294_967_297L, 9_007_199_254_740_991L)
    @Test fun fileMatchPlaybackAndSpecialResolutionKeepFullTitleIdentity() = runBlocking {
        ids.forEach { id ->
            val file = jsonObject("path" to "/fixture/$id.mkv", "name" to "Special", "mediaId" to id,
                "locked" to true, "ignored" to false, "metadata" to jsonObject("episode" to 3, "type" to "special", "aniDBEpisode" to "S2"))
            val edited = JSONObject(file.toString()).put("locked", false)
            assertEquals(id, nativeFileMatchPayload(file, file, edited).getLong("mediaId"))
            val playback = filePlayback(file)
            assertEquals(id, playback.mediaId); assertEquals(3, playback.episode!!.number)
            val source = playback.episode!!.copy(raw = jsonObject("aniDBEpisode" to "S2", "baseAnime" to jsonObject("id" to id)))
            assertEquals(source, resolveStreamEpisode(id, source, 3) { error("Recorded special must not be refetched") })
            assertTrue(runCatching { resolveStreamEpisode(id.toInt().toLong(), source, 3) { emptyList() } }.isFailure)
        }
    }
    @Test fun playlistAppendDoesNotCollapseTitlesWithTheSameLow32Bits() {
        val allIds = listOf(1L) + ids
        val episodes = allIds.map { id -> PlaylistEpisode(Episode(3, "Special", aniDbEpisode = "S2",
            raw = jsonObject("aniDBEpisode" to "S2", "baseAnime" to jsonObject("id" to id)))) }
        val result = applyPlaylistEdit(Playlist(7, "Fixture"), PlaylistEdit.Append(episodes))
        assertEquals(7, result.id) // Database identity remains Int.
        assertEquals(allIds.size, result.episodes.size)
        assertEquals(allIds.map { "$it:episode::S2:3" }, result.episodes.map(::playlistEpisodeIdentity))
    }
    @Test fun offlineMetadataQueueKeysAndPayloadIdsRemainDistinct() {
        val tasks = JSONObject().apply { ids.forEach { id -> put(id.toString(), jsonObject("mediaId" to id, "title" to "Title $id")) } }
        val state = MetadataSyncState().snapshot(jsonObject("animeTasks" to tasks, "mangaTasks" to JSONObject()))
        assertEquals(ids.toSet(), state.tasks.map { it.id }.toSet())
        assertEquals(3, state.tasks.size)
    }
}
