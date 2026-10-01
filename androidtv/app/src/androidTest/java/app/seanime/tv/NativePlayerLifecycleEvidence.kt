package app.seanime.tv

import android.net.Uri
import androidx.media3.common.Player
import app.seanime.tv.data.NativeNetworkFailure
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

/** Fixed-shape evidence for generated WAV lifecycle fixtures; never stores media URLs or error messages. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class NativePlayerLifecycleEvidence(cacheDir: File) {
    private val file = File(cacheDir, "native-acceptance-diagnostics/player-lifecycle-recreation.json")
    private val stages = JSONArray()
    private val root = JSONObject().put("schemaVersion", 1).put("scenario", "player-lifecycle-recreation")
        .put("outcome", "running").put("startedAtMs", System.currentTimeMillis()).put("stages", stages)
    private var current: JSONObject? = null

    @Synchronized
    fun begin(id: String) {
        require(id in STAGE_IDS && stages.length() < STAGE_IDS.size)
        current = JSONObject().put("id", id).put("result", "waiting")
            .put("playerPresent", false).put("playbackState", 0).put("positionMs", 0)
            .put("durationMs", -1).put("paused", true).put("mediaMatches", false).put("readyForMs", 0)
            .also(stages::put)
        write()
    }

    @Synchronized
    fun record(result: String, player: Player?, expectedUri: Uri?, readyForMs: Long = 0) {
        require(result in setOf("waiting", "ready", "error"))
        val stage = requireNotNull(current)
        val previousResult = stage.getString("result")
        stage.put("result", result).put("playerPresent", player != null)
            .put("playbackState", player?.playbackState ?: 0)
            .put("positionMs", player?.currentPosition ?: 0)
            .put("durationMs", player?.duration?.takeIf { it >= 0 } ?: -1)
            .put("paused", player?.playWhenReady != true)
            .put("mediaMatches", player != null && player.currentMediaItem?.localConfiguration?.uri == expectedUri)
            .put("readyForMs", readyForMs)
        player?.playerError?.let { error ->
            stage.put("errorCode", error.errorCode)
                .put("errorSummary", NativeNetworkFailure.summary(error, error.errorCode))
            val frames = JSONArray()
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var cause: Throwable? = error
            var causeDepth = 0
            while (cause != null && causeDepth < 8 && seen.add(cause)) {
                cause.stackTrace.take(4).filter { frame ->
                    FRAME_CLASS.matches(frame.className) && FRAME_METHOD.matches(frame.methodName)
                }.forEach { frame ->
                    frames.put(JSONObject().put("causeDepth", causeDepth).put("className", frame.className)
                        .put("methodName", frame.methodName).put("lineNumber", frame.lineNumber))
                }
                cause = cause.cause
                causeDepth++
            }
            stage.put("causeFrames", frames)
        }
        // Keep polling off disk. Commit transitions, terminal results, and the
        // latest in-memory sample when an assertion ends the test.
        if (result != "waiting" || previousResult != result) write()
    }

    @Synchronized
    fun timeout() {
        requireNotNull(current).put("result", "timeout")
        write()
    }

    @Synchronized
    fun finish(outcome: String) {
        require(outcome == "passed" || outcome == "failed")
        root.put("outcome", outcome).put("completedAtMs", System.currentTimeMillis())
        write()
    }

    private fun write() {
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        file.writeText(root.toString(2))
    }

    private companion object {
        val STAGE_IDS = setOf("initial-autoplay", "resume-after-stop", "paused-media-handoff",
            "playing-media-handoff", "activity-recreation")
        val FRAME_CLASS = Regex("(?:androidx\\.media3|io\\.github\\.peerless2012|app\\.seanime\\.tv|android|java|javax|kotlin|kotlinx|com\\.google\\.common)\\.[A-Za-z0-9_.$]{1,160}")
        val FRAME_METHOD = Regex("[A-Za-z0-9_$<>-]{1,160}")
    }
}
