package app.seanime.tv

import android.os.Bundle
import android.os.Parcel
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class PlaybackRecoverySnapshot(
    val checkpointId: String,
    val mediaUri: String,
    val processSessionId: String,
    val title: String,
    val subtitleTracksJson: String,
    val subtitleStyleJson: String,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val completed: Boolean,
    val speed: Float,
    val pitch: Float,
    val volume: Float,
    val muted: Boolean,
    val trackSelection: String,
    val playbackInfoJson: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("checkpointId", checkpointId)
        .put("mediaUri", mediaUri)
        .put("processSessionId", processSessionId)
        .put("title", title)
        .put("subtitleTracksJson", subtitleTracksJson)
        .put("subtitleStyleJson", subtitleStyleJson)
        .put("positionMs", positionMs.coerceAtLeast(0))
        .put("playWhenReady", playWhenReady)
        .put("completed", completed)
        .put("speed", speed.takeIf { it.isFinite() && it > 0 } ?: 1f)
        .put("pitch", pitch.takeIf { it.isFinite() && it > 0 } ?: 1f)
        .put("volume", volume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f)
        .put("muted", muted)
        .put("trackSelection", trackSelection)
        .put("playbackInfoJson", playbackInfoJson)

    fun toBridgeJson(): String = JSONObject().put("checkpointId", checkpointId).toString()

    fun withStream(url: String, ticket: String, processSessionId: String) =
        copy(checkpointId = ticket, mediaUri = url, processSessionId = processSessionId)

    companion object {
        private const val FILE_NAME = "androidtv-playback-recovery.json"
        private const val MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024
        private val invalidatedCheckpoints = mutableSetOf<Triple<String, String, String>>()
        private fun checkpointKey(filesDir: File, checkpointId: String, mediaUri: String) = Triple(filesDir.absolutePath, checkpointId, mediaUri)

        fun fromJson(json: JSONObject): PlaybackRecoverySnapshot? {
            val checkpointId = json.optString("checkpointId")
            val mediaUri = json.optString("mediaUri")
            if (checkpointId.isBlank() || mediaUri.isBlank()) return null
            return PlaybackRecoverySnapshot(
                checkpointId = checkpointId,
                mediaUri = mediaUri,
                processSessionId = json.optString("processSessionId"),
                title = json.optString("title", "Seanime TV"),
                subtitleTracksJson = json.optString("subtitleTracksJson", ""),
                subtitleStyleJson = json.optString("subtitleStyleJson", ""),
                positionMs = json.optLong("positionMs", 0L).coerceAtLeast(0),
                playWhenReady = json.optBoolean("playWhenReady", true),
                completed = json.optBoolean("completed", false),
                speed = json.optDouble("speed", 1.0).toFloat().takeIf { it.isFinite() && it > 0 } ?: 1f,
                pitch = json.optDouble("pitch", 1.0).toFloat().takeIf { it.isFinite() && it > 0 } ?: 1f,
                volume = json.optDouble("volume", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f,
                muted = json.optBoolean("muted", false),
                trackSelection = json.optString("trackSelection", ""),
                playbackInfoJson = json.optString("playbackInfoJson", ""),
            )
        }

        @Synchronized fun read(filesDir: File): PlaybackRecoverySnapshot? = runCatching {
            val file = File(filesDir, FILE_NAME)
            if (!file.isFile || file.length() > MAX_SNAPSHOT_BYTES) return null
            fromJson(JSONObject(file.readText()))
        }.getOrNull()

        @Synchronized fun write(filesDir: File, snapshot: PlaybackRecoverySnapshot) {
            if (checkpointKey(filesDir, snapshot.checkpointId, snapshot.mediaUri) in invalidatedCheckpoints) return
            val target = File(filesDir, FILE_NAME)
            val temporary = File(filesDir, "$FILE_NAME.tmp")
            runCatching {
                val bytes = snapshot.toJson().toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_SNAPSHOT_BYTES) { "playback recovery state is too large" }
                FileOutputStream(temporary).use { stream ->
                    stream.write(bytes)
                    stream.fd.sync()
                }
                if (!temporary.renameTo(target)) {
                    target.delete()
                    check(temporary.renameTo(target)) { "could not replace playback recovery state" }
                }
            }.onFailure { temporary.delete() }
        }

        @Synchronized fun clear(filesDir: File) {
            File(filesDir, FILE_NAME).delete()
            File(filesDir, "$FILE_NAME.tmp").delete()
        }

        /** Invalidation and writes share one boundary, including writes queued by a destroyed Activity. */
        @Synchronized fun clearCheckpoint(filesDir: File, checkpointId: String, mediaUri: String): Boolean {
            if (checkpointId.isBlank() || mediaUri.isBlank()) return false
            invalidatedCheckpoints += checkpointKey(filesDir, checkpointId, mediaUri)
            val stored = read(filesDir) ?: return false
            if (stored.checkpointId != checkpointId || stored.mediaUri != mediaUri) return false
            clear(filesDir)
            return true
        }

        @Synchronized fun clearSource(filesDir: File, playbackId: String, mediaUri: String, checkpointId: String = ""): Boolean {
            if (playbackId.isBlank() || mediaUri.isBlank()) return false
            val stored = read(filesDir)
            val storedId = stored?.let { runCatching { JSONObject(it.playbackInfoJson).optString("id") }.getOrNull() }
            if (stored != null && stored.checkpointId == checkpointId && stored.mediaUri == mediaUri && storedId != playbackId) return false
            if (checkpointId.isNotBlank()) invalidatedCheckpoints += checkpointKey(filesDir, checkpointId, mediaUri)
            if (stored == null) return false
            if (stored.mediaUri != mediaUri || storedId != playbackId || (checkpointId.isNotBlank() && stored.checkpointId != checkpointId)) return false
            return clearCheckpoint(filesDir, stored.checkpointId, mediaUri)
        }

        fun encodeBundle(bundle: Bundle?): String {
            if (bundle == null) return ""
            val parcel = Parcel.obtain()
            return try {
                parcel.writeBundle(bundle)
                Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
            } finally {
                parcel.recycle()
            }
        }

        fun decodeBundle(encoded: String): Bundle? {
            if (encoded.isBlank() || encoded.length > 64 * 1024) return null
            val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return null
            val parcel = Parcel.obtain()
            return try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                parcel.readBundle(PlaybackRecoverySnapshot::class.java.classLoader)
            } catch (_: RuntimeException) {
                null
            } finally {
                parcel.recycle()
            }
        }
    }
}
