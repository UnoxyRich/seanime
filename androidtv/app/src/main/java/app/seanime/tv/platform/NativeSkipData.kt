package app.seanime.tv.platform

import org.json.JSONObject

data class NativeSkipTarget(val label: String, val endMs: Long)

/** Immutable VideoCore skip intervals; wire units remain seconds. */
class NativeSkipData private constructor(private val op: Interval?, private val ed: Interval?) {
    private data class Interval(val start: Double, val end: Double) {
        val startMs get() = (start * 1000).toLong()
        val endMs get() = (end * 1000).toLong()
        fun json() = JSONObject().put("interval", JSONObject().put("startTime", start).put("endTime", end))
        fun contains(positionMs: Long, durationMs: Long) = durationMs > 0 && endMs <= durationMs && positionMs in startMs until endMs
    }

    fun toJson(): JSONObject = JSONObject().put("op", op?.json() ?: JSONObject.NULL).put("ed", ed?.json() ?: JSONObject.NULL)

    fun target(positionMs: Long, durationMs: Long): NativeSkipTarget? = when {
        op?.contains(positionMs, durationMs) == true -> NativeSkipTarget("Skip intro", op.endMs)
        ed?.contains(positionMs, durationMs) == true -> NativeSkipTarget("Skip ending", ed.endMs)
        else -> null
    }

    companion object {
        fun parse(raw: JSONObject?): NativeSkipData? {
            if (raw == null) return null
            fun interval(key: String): Interval? {
                val json = raw.optJSONObject(key)?.optJSONObject("interval") ?: return null
                val start = (json.opt("startTime") as? Number)?.toDouble() ?: return null
                val end = (json.opt("endTime") as? Number)?.toDouble() ?: return null
                if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start || end * 1000 >= Long.MAX_VALUE.toDouble()) return null
                return Interval(start, end).takeIf { it.endMs > it.startMs }
            }
            val op = interval("op")
            val ed = interval("ed")?.takeUnless { op != null && it.start <= op.end }
            return NativeSkipData(op, ed)
        }
    }
}

/** Skip data belongs to one playback, even when that playback changes transport URL. */
class NativeSkipState {
    private var playbackId = ""
    private var value: NativeSkipData? = null
    fun reset(id: String) { playbackId = id; value = null }
    fun set(id: String, raw: JSONObject?) {
        if (id.isNotBlank() && id == playbackId) value = NativeSkipData.parse(raw)
    }
    fun data(id: String): NativeSkipData? = value.takeIf { id.isNotBlank() && id == playbackId }
}
