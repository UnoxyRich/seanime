package app.seanime.tv.platform

import app.seanime.tv.ui.filePlayback
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeUnmatchedFileTest {
    private fun indexed(id: Long) = JSONObject().put("mediaId", id)
        .put("name", "Owned video #1 & friends.mkv").put("path", "/owned/video #1 & friends.mkv")
        .put("metadata", JSONObject().put("episode", 7).put("type", "main").put("aniDBEpisode", "7"))

    @Test fun matchedFilesRetainTheMetadataDependentLocalRoute() {
        val request = filePlayback(indexed(4_294_967_297L))
        assertNull(request.unmatchedFile)
        assertNull(request.stream)
        assertEquals(4_294_967_297L, request.mediaId)
        assertEquals("/owned/video #1 & friends.mkv", request.episode!!.localPath)
        assertEquals(7, request.episode!!.progressNumber)
        assertEquals("7", request.episode!!.aniDbEpisode)
    }

    @Test fun unmatchedFilesNeverClaimParsedAnimeOrEpisodeProgress() {
        val request = filePlayback(indexed(0))
        assertEquals(0L, request.mediaId)
        assertNull(request.episode)
        assertNull(request.stream)
        assertEquals("Owned video #1 & friends.mkv", request.title)
        assertEquals("/owned/video #1 & friends.mkv", request.unmatchedFile!!.path)
        val info = request.unmatchedFile!!.playbackInfo("http://127.0.0.1:43211")
        assertEquals(0, info.getJSONObject("media").getInt("id"))
        assertTrue(info.isNull("episode"))
        assertFalse(NativePlaybackProtocol.shouldRestoreContinuity(info))
    }

    @Test fun rawRouteEncodesTheExactPathAndRetainsTitleWithoutAuthorityInTheUrl() {
        val source = filePlayback(indexed(0)).unmatchedFile!!
        val info = source.playbackInfo("http://127.0.0.1:43211")
        val url = info.getString("streamUrl").toHttpUrl()
        assertEquals("/api/v1/mediastream/file", url.encodedPath)
        assertEquals(setOf("path"), url.queryParameterNames)
        assertEquals(source.path, url.queryParameter("path"))
        assertEquals(source.path, info.getString("streamPath"))
        assertEquals(source.title, info.getJSONObject("media").getJSONObject("title").getString("userPreferred"))
        assertEquals("url", info.getString("playbackType"))
        assertEquals("native", info.getString("streamType"))
        assertTrue(NativePlaybackProtocol.usableNativeRecovery(info.toString(), url.toString()) { false })
        assertFalse(NativePlaybackProtocol.usableNativeRecovery(info.toString(), url.newBuilder().setQueryParameter("path", "/other").build().toString()) { true })
    }

    @Test fun externalPlaybackRetainsExistingHeaderFreeProbeAndExactSourceGuard() {
        val info = filePlayback(indexed(0)).unmatchedFile!!.playbackInfo("http://127.0.0.1:43211")
        val source = NativeExternalPlaybackSource(info.getString("streamUrl"), info.getString("id"),
            "http://127.0.0.1:43211", info.getString("playbackType"), info.getString("streamPath"))
        val plan = planExternalPlayback(source)
        assertEquals(source.url, plan.uri)
        assertTrue(plan.needsHost)
        assertTrue(plan.probe)
        assertTrue(runCatching { planExternalPlayback(source.copy(streamPath = "/different.mkv")) }.isFailure)
    }

    @Test fun missingFilenameFallsBackToPathAndBlankPathIsRejected() {
        val request = filePlayback(indexed(0).put("name", ""))
        assertEquals("video #1 & friends.mkv", request.title)
        assertEquals(request.title, request.unmatchedFile!!.title)
        assertTrue(runCatching { filePlayback(indexed(0).put("path", "")) }.isFailure)
    }
}
