package app.seanime.tv.platform

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeEpisodeNavigationTest {
    @Test fun customMediaIdsDoNotCollideWithTruncatedPlaylistSources() {
        for (id in listOf(2_147_483_648L, 4_294_967_297L, 9_007_199_254_740_991L)) {
            val low = episode("1").put("baseAnime", JSONObject().put("id", 1L))
            val high = episode("1").put("baseAnime", JSONObject().put("id", id))
            val payload = JSONObject().put("playlist", JSONObject().put("episodes", JSONArray().put(item(low)).put(item(high))))
                .put("playlistEpisode", item(JSONObject(high.toString())))
            assertEquals("Wrong media source selected for $id", NativeEpisodeNavigation(previous = true), NativeEpisodeNavigation.global(payload))
        }
    }

    @Test fun rawMediaAndLocalPlaylistBoundariesExposeOnlyActualNeighbors() {
        assertEquals(NativeEpisodeNavigation(), NativeEpisodeNavigation.local(null))
        assertEquals(NativeEpisodeNavigation(next = true), NativeEpisodeNavigation.local(JSONObject().put("nextEpisode", episode("2"))))
        assertEquals(NativeEpisodeNavigation(previous = true), NativeEpisodeNavigation.local(JSONObject().put("previousEpisode", episode("1"))))
    }

    @Test fun globalNeighborsFollowSavedOrderIncludingCompletedEpisodes() {
        val first = episode("S1")
        val second = episode("1")
        val third = episode("S2")
        val payload = JSONObject().put("playlist", JSONObject().put("episodes", JSONArray()
            .put(item(first)).put(item(second).put("isCompleted", true)).put(item(third))))
        payload.put("playlistEpisode", item(first))
        assertEquals(NativeEpisodeNavigation(next = true), NativeEpisodeNavigation.global(payload))
        payload.put("playlistEpisode", item(second))
        assertEquals(NativeEpisodeNavigation(true, true), NativeEpisodeNavigation.global(payload))
        payload.put("playlistEpisode", item(third))
        assertEquals(NativeEpisodeNavigation(previous = true), NativeEpisodeNavigation.global(payload))
    }

    @Test fun localGlobalIdentityUsesProgressAndIncompleteSnapshotsDoNotBlockValidNext() {
        val first = episode("1").put("progressNumber", 3).put("localFile", JSONObject().put("path", "/fixture/one.mkv"))
        val second = episode("2").put("progressNumber", 4)
        val payload = JSONObject().put("playlist", JSONObject().put("episodes", JSONArray().put(item(first)).put(item(second))))
            .put("playlistEpisode", item(episode("different-label").put("progressNumber", 3)))
        assertEquals(NativeEpisodeNavigation(next = true), NativeEpisodeNavigation.global(payload))
        assertEquals(NativeEpisodeNavigation(true, true), NativeEpisodeNavigation.global(null))
        payload.put("playlistEpisode", item(episode("unknown")))
        assertEquals(NativeEpisodeNavigation(true, true), NativeEpisodeNavigation.global(payload))
        payload.remove("playlistEpisode")
        assertEquals(NativeEpisodeNavigation(next = true), NativeEpisodeNavigation.global(payload))
    }

    private fun episode(number: String) = JSONObject().put("aniDBEpisode", number).put("baseAnime", JSONObject().put("id", 21))
    private fun item(episode: JSONObject) = JSONObject().put("episode", episode)
}
