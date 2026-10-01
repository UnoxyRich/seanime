package app.seanime.tv.platform

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** An explicitly unmatched indexed file, with no anime/episode identity to track. */
data class NativeUnmatchedFile(val path: String, val title: String) {
    init { require(path.isNotBlank()) { "The indexed file has no path" } }

    internal fun playbackInfo(serverUrl: String): JSONObject = JSONObject()
        .put("id", UUID.randomUUID().toString())
        // This existing Go endpoint still enforces the configured library roots.
        // Authority is supplied by the coordinator's same-origin header provider.
        .put("streamUrl", serverUrl.toHttpUrl().newBuilder().encodedPath("/api/v1/mediastream/file")
            .query(null).fragment(null).addQueryParameter("path", path).build().toString())
        .put("streamPath", path).put("playbackType", "url").put("streamType", "native")
        .put("media", JSONObject().put("id", 0).put("title", JSONObject().put("userPreferred", title)))
        .put("episode", JSONObject.NULL).put("subtitleTracks", JSONArray())
        .put("disableRestoreFromContinuity", true)
}
