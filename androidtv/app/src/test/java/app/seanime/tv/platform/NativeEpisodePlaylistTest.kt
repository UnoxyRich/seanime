package app.seanime.tv.platform

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import app.seanime.tv.data.OnlineEpisodeIdentity

class NativeEpisodePlaylistTest {
    @Test fun queuedCustomMediaMetadataKeepsItsLongIdentityAcrossOwnershipTransfer() {
        for (id in listOf(2_147_483_648L, 4_294_967_297L, 9_007_199_254_740_991L)) {
            fun episode(number: Int) = JSONObject().put("episodeNumber", number).put("progressNumber", number)
                .put("baseAnime", JSONObject().put("id", id)).put("aniDBEpisode", number.toString())
            val current = JSONObject().put("id", "playback-$id").put("playbackType", "localfile")
                .put("media", JSONObject().put("id", id)).put("episode", episode(1))
            val built = NativeEpisodePlaylist.build(current, null, JSONArray().put(episode(1)).put(episode(2)))
            val queue = NativeEpisodePlaylist().apply { begin("playback-$id", 1) }
            assertTrue(queue.publish("playback-$id", 1, built))
            val transferred = requireNotNull(queue.copy().current("playback-$id"))
            assertEquals(id, transferred.getJSONObject("currentEpisode").getJSONObject("baseAnime").getLong("id"))
            assertEquals(id, transferred.getJSONObject("nextEpisode").getJSONObject("baseAnime").getLong("id"))
            assertEquals(2, transferred.getJSONObject("nextEpisode").getInt("progressNumber"))
            assertNull(queue.current(id.toString()))
        }
    }

    @Test fun `accepted source clears prior episodes and late responses cannot restore them`() {
        val state = NativeEpisodePlaylist()
        val previous = JSONObject().put("nextEpisode", JSONObject().put("episodeNumber", 2))
        state.begin("previous", 1)
        assertTrue(state.publish("previous", 1, previous))
        assertNotNull(state.current("previous"))
        val transferred = state.copy()
        assertTrue(state.publish("previous", 1, null))
        assertNotNull("An old owner's late response altered its successor", transferred.current("previous"))
        assertNull(state.current("next"))
        state.begin("next", 2)
        assertNull(state.current("next"))
        assertFalse(state.publish("previous", 1, previous))
        assertNull(state.current("next"))
        val next = JSONObject().put("nextEpisode", JSONObject().put("episodeNumber", 8))
        assertTrue(state.publish("next", 2, next))
        state.begin("next", 3)
        assertFalse("A same-ID old response replaced the current launch", state.publish("next", 2, previous))
        assertTrue(state.publish("next", 3, null))
        assertNull(state.current("next"))
    }

    @Test fun `nonlibrary streams use full collections and respect available external episodes`() {
        listOf("torrent", "debrid", "url", "onlinestream").forEach { assertFalse(NativeEpisodePlaylist.usesLibraryEpisodes(it)) }
        val current = JSONObject().put("playbackType", "torrent").put("episode", episode(5))
            .put("playlistExternalEpisodeNumbers", JSONArray().put(3).put(5).put(6))
        val result = requireNotNull(NativeEpisodePlaylist.build(current, null, JSONArray((3..7).map(::episode))))
        assertEquals(3, result.getJSONArray("episodes").length())
        assertEquals(5, result.getJSONObject("currentEpisode").getInt("episodeNumber"))
        assertFalse(result.has("previousEpisode"))
        assertEquals(6, result.getJSONObject("nextEpisode").getInt("episodeNumber"))
        assertNull(NativeEpisodePlaylist.build(current, null, null))
        assertNull(NativeEpisodePlaylist.build(current, null, JSONArray().put(episode(1))))
    }

    @Test fun `library and provider episodes normalize without mutating their wire metadata`() {
        assertTrue(NativeEpisodePlaylist.usesLibraryEpisodes("localfile"))
        assertTrue(NativeEpisodePlaylist.usesLibraryEpisodes("nakama"))
        val metadata = JSONObject().put("aniDBEpisode", "2")
        val provider = JSONArray().put(JSONObject().put("number", 2).put("metadata", metadata))
        val current = JSONObject().put("playbackType", "onlinestream").put("episode", episode(2))
        assertEquals(2, NativeEpisodePlaylist.build(current, null, provider)!!.getJSONObject("currentEpisode").getInt("progressNumber"))
        assertFalse(metadata.has("progressNumber"))
        current.put("playbackType", "localfile").put("playlistExternalEpisodeNumbers", JSONArray())
        assertNotNull(NativeEpisodePlaylist.build(current, null, JSONArray().put(episode(2))))
    }

    @Test fun `online next keeps provider source number separate from canonical progress`() {
        val current = JSONObject().put("playbackType", "onlinestream").put("episode", episode(3))
            .put("onlinestreamParams", JSONObject().put("provider", "fixture").put("episodeNumber", 13))
            .put("playlistExternalEpisodeNumbers", JSONArray().put(13).put(14).put(15))
        val rows = JSONArray().put(JSONObject().put("number", 13).put("metadata", episode(3)))
            .put(JSONObject().put("number", 14).put("metadata", episode(4)))
            .put(JSONObject().put("number", 15).put("metadata", JSONObject.NULL))
        val playlist = requireNotNull(NativeEpisodePlaylist.build(current, null, rows))
        assertEquals(2, playlist.getJSONArray("episodes").length())
        val next = playlist.getJSONObject("nextEpisode")
        assertEquals(4, next.getInt("episodeNumber"))
        assertEquals(4, next.getInt("progressNumber"))
        assertEquals(14, OnlineEpisodeIdentity.sourceNumber(next))
    }

    private fun episode(number: Int) = JSONObject().put("episodeNumber", number).put("progressNumber", number).put("aniDBEpisode", number.toString())
}
