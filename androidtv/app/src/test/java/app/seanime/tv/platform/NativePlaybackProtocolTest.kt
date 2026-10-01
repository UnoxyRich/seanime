package app.seanime.tv.platform

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePlaybackProtocolTest {
    @Test fun `custom media numbers remain exact across normalization and playback event encoding`() {
        for (id in listOf(2_147_483_648L, 4_294_967_297L, 9_007_199_254_740_991L)) {
            val info = JSONObject().put("id", "playback-$id").put("streamType", "url").put("streamUrl", "https://media.example/$id.mp4")
                .put("media", JSONObject().put("id", id)).put("episode", JSONObject().put("baseAnime", JSONObject().put("id", id)).put("progressNumber", 3))
            val normalized = NativePlaybackProtocol.normalizePlaybackInfo(info) { it }
            val event = NativePlaybackProtocol.event("client", "video-playback-state", JSONObject().put("state", JSONObject().put("playbackInfo", normalized)))
            val restored = JSONObject(event.toString()).getJSONObject("payload").getJSONObject("state").getJSONObject("playbackInfo")
            assertEquals(id, restored.getJSONObject("media").getLong("id"))
            assertEquals(id, restored.getJSONObject("episode").getJSONObject("baseAnime").getLong("id"))
            assertEquals("playback-$id", restored.getString("id"))
            assertEquals(3, restored.getJSONObject("episode").getInt("progressNumber"))
        }
    }

    @Test fun `native watch envelope becomes canonical VideoCore state without losing metadata`() {
        val original = JSONObject("""{"id":"episode-1","streamType":"torrent","streamPath":"episode.mkv","streamUrl":"{{SERVER_URL}}/api/v1/directstream/stream?token=opaque","media":{"id":20},"episode":{"progressNumber":3},"subtitleTracks":[]}""")
        val mapped = NativePlaybackProtocol.normalizePlaybackInfo(original) { it.replace("{{SERVER_URL}}", "http://127.0.0.1:43211") }
        assertEquals("torrent", mapped.getString("playbackType"))
        assertEquals("native", mapped.getString("streamType"))
        assertEquals(3, mapped.getJSONObject("episode").getInt("progressNumber"))
        assertEquals("episode.mkv", mapped.getString("streamPath"))
        assertEquals("torrent", original.getString("streamType"))
        assertTrue(mapped.getString("streamUrl").endsWith("token=opaque"))
    }

    @Test fun `online source keeps online playback type`() {
        val mapped = NativePlaybackProtocol.normalizePlaybackInfo(JSONObject("""{"id":"x","playbackType":"onlinestream","streamType":"hls","streamUrl":"https://example.com/a.m3u8"}""")) { it }
        assertEquals("onlinestream", mapped.getString("playbackType"))
        assertEquals("hls", mapped.getString("streamType"))
    }

    @Test fun `status converts Media3 milliseconds to backend seconds`() {
        val result = NativePlaybackProtocol.status(JSONObject().put("id", "id-1"), "client-1", JSONObject()
            .put("positionMs", 12345L).put("durationMs", 900000L).put("paused", false))
        assertEquals(12.345, result.getDouble("currentTime"), 0.00001)
        assertEquals(900.0, result.getDouble("duration"), 0.00001)
        assertEquals("id-1", result.getString("id"))
        assertFalse(result.getBoolean("paused"))
        val event = NativePlaybackProtocol.event("client-1", "video-status", result)
        assertEquals("client-1", event.getString("clientId"))
        assertEquals("video-status", event.getString("type"))
        assertEquals(12.345, event.getJSONObject("payload").getDouble("currentTime"), 0.00001)
    }

    @Test fun `unknown Media3 duration is never published as negative time`() {
        val result = NativePlaybackProtocol.status(null, "client", JSONObject().put("positionMs", -10).put("durationMs", Long.MIN_VALUE))
        assertEquals(0.0, result.getDouble("currentTime"), 0.0)
        assertEquals(0.0, result.getDouble("duration"), 0.0)
        assertTrue(result.getBoolean("paused"))
    }

    @Test fun `continuity only resumes the matching unfinished episode`() {
        val history = JSONObject().put("episodeNumber", 2).put("currentTime", 15.25).put("duration", 100)
        assertEquals(15250L, NativePlaybackProtocol.continuityPosition(history, 2))
        assertEquals(0L, NativePlaybackProtocol.continuityPosition(history, 3))
        assertEquals(0L, NativePlaybackProtocol.continuityPosition(history.put("currentTime", 90), 2))
        assertEquals(0L, NativePlaybackProtocol.continuityPosition(history.put("duration", 0), 2))
    }

    @Test fun `explicit initial state wins over history even at zero or when paused`() {
        assertTrue(NativePlaybackProtocol.shouldRestoreContinuity(JSONObject()))
        assertFalse(NativePlaybackProtocol.shouldRestoreContinuity(JSONObject().put("disableRestoreFromContinuity", true)))
        assertFalse(NativePlaybackProtocol.shouldRestoreContinuity(JSONObject().put("initialState", JSONObject().put("currentTime", 0))))
        assertFalse(NativePlaybackProtocol.shouldRestoreContinuity(JSONObject().put("initialState", JSONObject().put("paused", true))))
    }

    @Test fun `local recovery sources bypass HTTP resolution while server sources are normalized`() {
        listOf("file:///data/user/0/app.seanime.tv/cache/video.m3u8", "content://media/external/video/media/42").forEach { source ->
            assertEquals(source, NativePlaybackProtocol.resolvePlaybackSource(source) { error("Local source reached HTTP resolver") })
        }
        assertEquals("https://server.example/api/stream", NativePlaybackProtocol.resolvePlaybackSource("/api/stream") { "https://server.example$it" })
        assertEquals("https://video.example/media.m3u8", NativePlaybackProtocol.resolvePlaybackSource("https://video.example/media.m3u8") { it })
    }

    @Test fun `source resolution rejects unsupported schemes and embedded credentials`() {
        listOf("ftp://example.com/a", "javascript:alert(1)", "https://user:password@example.com/a", "content://user@media/a", "file://user@localhost/a", "https:///missing-host").forEach { source ->
            assertThrows(IllegalArgumentException::class.java) { NativePlaybackProtocol.resolvePlaybackSource(source) { it } }
        }
    }

    @Test fun `completion matches existing VideoCore eighty percent threshold`() {
        val state = JSONObject().put("durationMs", 100000L).put("positionMs", 79999L)
        assertFalse(NativePlaybackProtocol.hasReachedCompletion(state))
        assertTrue(NativePlaybackProtocol.hasReachedCompletion(state.put("positionMs", 80000L)))
        assertFalse(NativePlaybackProtocol.hasReachedCompletion(state.put("durationMs", 0)))
    }
    @Test fun `MKV websocket milliseconds map to the playback seconds timeline`() {
        val interval = NativePlaybackProtocol.subtitleIntervalSeconds(JSONObject().put("startTime", 1250).put("duration", 2500))!!
        assertEquals(1.25, interval.first, 0.0)
        assertEquals(3.75, interval.second, 0.0)
        assertNull(NativePlaybackProtocol.subtitleIntervalSeconds(JSONObject().put("startTime", -1).put("duration", 2500)))
        assertNull(NativePlaybackProtocol.subtitleIntervalSeconds(JSONObject().put("startTime", 1000).put("duration", 0)))
    }

    @Test fun `native recovery requires source bound well formed metadata`() {
        val url = "https://video.example/episode.m3u8?token=opaque"
        assertFalse(NativePlaybackProtocol.usableNativeRecovery("", url) { true })
        assertFalse(NativePlaybackProtocol.usableNativeRecovery("{", url) { true })
        assertFalse(NativePlaybackProtocol.usableNativeRecovery("{}", url) { true })
        assertFalse(NativePlaybackProtocol.usableNativeRecovery(JSONObject().put("streamUrl", "https://other.example/episode").toString(), url) { true })
        assertTrue(NativePlaybackProtocol.usableNativeRecovery(JSONObject().put("streamUrl", url).toString(), url) { false })
        val noHost = "https:///episode.m3u8"
        assertFalse(NativePlaybackProtocol.usableNativeRecovery(JSONObject().put("streamUrl", noHost).toString(), noHost) { true })
        val credentials = "https://user:password@video.example/episode"
        assertFalse(NativePlaybackProtocol.usableNativeRecovery(JSONObject().put("streamUrl", credentials).toString(), credentials) { true })
    }

    @Test fun `deleted local file recovery is rejected but valid file is retained`() {
        val url = "file:///private/cache/fixture.mkv"
        val metadata = JSONObject().put("streamUrl", url).toString()
        assertFalse(NativePlaybackProtocol.usableNativeRecovery(metadata, url) { false })
        assertTrue(NativePlaybackProtocol.usableNativeRecovery(metadata, url) { it == "/private/cache/fixture.mkv" })
    }

}
