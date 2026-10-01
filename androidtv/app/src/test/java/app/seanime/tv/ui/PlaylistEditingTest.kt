package app.seanime.tv.ui

import app.seanime.tv.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistEditingTest {
    @Test fun renameFetchesCurrentCompletionAndUnrelatedAdditionsBeforePatch() = runBlocking {
        val latest = playlist(episode(1, true), episode(2), episode(3, true))
        MockWebServer().use { server ->
            server.enqueue(response(JSONArray().put(latest.raw)))
            server.enqueue(response(JSONObject(latest.raw.toString()).put("name", "Renamed")))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                editCurrentPlaylist(SeanimeRepository(api), 7, PlaylistEdit.Rename("Renamed"))
            }
            assertEquals("GET", server.takeRequest().method)
            val patch = server.takeRequest()
            assertEquals("PATCH", patch.method)
            val body = JSONObject(patch.body.readUtf8())
            assertEquals("Renamed", body.getString("name"))
            assertEquals(latest.raw.getJSONArray("episodes").toString(), body.getJSONArray("episodes").toString())
        }
    }

    @Test fun moveAndRemoveIdentifyTheTargetAfterAnotherEpisodeWasInserted() {
        val first = episode(1, true)
        val target = episode(2)
        val added = episode(3, true)
        val latest = playlist(added, first, target)
        val moved = applyPlaylistEdit(latest, PlaylistEdit.Move(playlistEpisodeIdentity(target), -1))
        assertEquals(listOf(3, 2, 1), moved.episodes.map { it.episode!!.number })
        assertTrue(moved.episodes.first().completed)
        assertTrue(moved.episodes.last().completed)
        assertEquals("opaque-1", moved.episodes.last().episode!!.raw.getString("providerMetadata"))
        val removed = applyPlaylistEdit(latest, PlaylistEdit.Remove(playlistEpisodeIdentity(target)))
        assertEquals(listOf(3, 1), removed.episodes.map { it.episode!!.number })
    }

    @Test fun appendKeepsLatestCompletionAndDeduplicatesIncomingEpisodes() {
        val watched = episode(1, true)
        val latest = playlist(watched, episode(3, true))
        val edited = applyPlaylistEdit(latest, PlaylistEdit.Append(listOf(episode(1), episode(2), episode(2))))
        assertEquals(listOf(1, 3, 2), edited.episodes.map { it.episode!!.number })
        assertTrue(edited.episodes[0].completed)
        assertTrue(edited.episodes[1].completed)
        assertSame(latest.episodes[0], edited.episodes[0])
    }

    @Test fun explicitCompletionOnlyChangesTheSelectedCurrentEpisode() {
        val latest = playlist(episode(3, true), episode(1, true), episode(2))
        val edited = applyPlaylistEdit(latest, PlaylistEdit.Complete(playlistEpisodeIdentity(episode(2)), true))
        assertTrue(edited.episodes.all { it.completed })
        assertEquals(latest.episodes[2].raw.toString(), edited.episodes[2].raw.toString())
    }

    @Test fun removedOrAmbiguousTargetsNeverPatchAnotherEpisode() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response(JSONArray().put(playlist(episode(1)).raw)))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val failure = runCatching { editCurrentPlaylist(SeanimeRepository(api), 7, PlaylistEdit.Remove(playlistEpisodeIdentity(episode(2)))) }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
            }
            assertEquals(1, server.requestCount)
        }
        assertTrue(runCatching { applyPlaylistEdit(playlist(episode(1), episode(1)), PlaylistEdit.Remove(playlistEpisodeIdentity(episode(1)))) }.isFailure)
    }

    private fun episode(number: Int, completed: Boolean = false): PlaylistEpisode {
        val raw = jsonObject("watchType" to "localfile", "isCompleted" to completed,
            "episode" to jsonObject("baseAnime" to jsonObject("id" to 42), "episodeNumber" to number,
                "providerMetadata" to "opaque-$number", "localFile" to jsonObject("path" to "/fixture/$number.mkv")))
        return PlaylistEpisode(SeanimeJson.episode(raw.getJSONObject("episode")), completed, "localfile", raw)
    }
    private fun playlist(vararg episodes: PlaylistEpisode): Playlist = SeanimeJson.playlist(jsonObject("dbId" to 7,
        "name" to "Fixture queue", "episodes" to JSONArray(episodes.map { it.raw })))
    private fun response(data: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(jsonObject("data" to data).toString())
}
