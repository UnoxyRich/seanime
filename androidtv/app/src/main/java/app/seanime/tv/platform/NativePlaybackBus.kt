package app.seanime.tv.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** Native-only connection between the fullscreen Media3 activity and its session owner. */
object NativePlaybackBus {
    interface Listener {
        fun onSnapshot(snapshot: JSONObject)
        fun onPlayerEvent(type: String, payload: JSONObject)
        fun onAction(action: String)
    }

    @Volatile var listener: Listener? = null
    @Volatile var playbackInfoJson: String = ""
    @Volatile var headerProvider: ((String) -> Map<String, String>)? = null

    fun snapshot(value: JSONObject) { listener?.onSnapshot(value) }
    fun event(type: String, payload: JSONObject = JSONObject()) { listener?.onPlayerEvent(type, payload) }
    fun action(value: String) { listener?.onAction(value) }

    fun localUrl(url: String?): String? {
        val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        if (uri.scheme != "http" || uri.host !in setOf("127.0.0.1", "localhost") ||
            uri.port != 43211 || uri.userInfo != null) return null
        return uri.buildUpon().encodedAuthority("127.0.0.1:43211").build().toString()
    }

    fun recoveryIntent(context: Context, checkpointId: String): Intent =
        Intent().setClassName(context.packageName, "app.seanime.tv.MainActivity")
            .putExtra("playback-recovery-id", checkpointId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
