package app.seanime.tv.platform

import org.json.JSONArray
import org.json.JSONObject

/** Original MKV stream events stay in their server's millisecond timeline. */
data class NativeSubtitleEvent(val track: Int, val startMs: Long, val durationMs: Long, val codec: String,
    val text: String, val extra: JSONObject) {
    val endMs get() = startMs + durationMs
    val sizeBytes get() = (text.length.toLong() + extra.toString().length) * 2
    val key get() = if (isPgs) "$track:$startMs:pgs" else "$track:$startMs:${text.hashCode()}:${extra.toString().hashCode()}"
    val isPgs get() = codec.contains("PGS", true)
}

object NativeSubtitleProtocol {
    const val DIALOGUE_FORMAT = "Format: ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
    val fallbackHeader = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 640
        PlayResY: 360
        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Roboto,28,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,1,2,20,20,20,1
        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    """.trimIndent()

    fun parse(event: JSONObject): NativeSubtitleEvent? {
        val start = event.optDouble("startTime", -1.0)
        val duration = event.optDouble("duration", 0.0)
        val text = event.optString("text")
        if (!start.isFinite() || !duration.isFinite() || start < 0 || duration <= 0 || start + duration > Long.MAX_VALUE.toDouble()) return null
        if (text.isBlank() || text.length > 4 * 1024 * 1024 || event.optInt("trackNumber", -1) < 0 || duration < 1) return null
        if ((event.optJSONObject("extraData")?.toString()?.length ?: 0) > 64 * 1024) return null
        return NativeSubtitleEvent(event.optInt("trackNumber", -1), start.toLong(), duration.toLong(), event.optString("codecID"), text,
            event.optJSONObject("extraData")?.let { JSONObject(it.toString()) } ?: JSONObject())
    }

    fun header(track: JSONObject): ByteArray {
        val source = track.optString("codecPrivate")
        require(source.length <= 1024 * 1024) { "Subtitle style header is too large" }
        val bytes = source.trimEnd('\u0000').ifBlank { fallbackHeader }.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1024 * 1024) { "Subtitle style header is too large" }
        return bytes
    }

    /** libass's Matroska chunk syntax preserves authored style, layer, effects and override tags. */
    fun assChunk(event: NativeSubtitleEvent, readOrder: Long? = null): ByteArray {
        val data = event.extra
        val text = if (event.codec.contains("ASS", true) || event.codec.contains("SSA", true)) event.text
            else event.text.replace("\r\n", "\n").replace("\n", "\\N")
        return listOf(readOrder?.toString() ?: data.optString("readorder", data.optString("readOrder", event.startMs.toString())), data.optString("layer", "0"),
            data.optString("style", "Default"), data.optString("name", ""), data.optString("marginl", data.optString("marginL", "0")),
            data.optString("marginr", data.optString("marginR", "0")), data.optString("marginv", data.optString("marginV", "0")), data.optString("effect", ""), text)
            .joinToString(",").toByteArray(Charsets.UTF_8)
    }
}

/** Rejects cross-source and obsolete seek generations, and bounds untrusted subtitle buffers. */
class NativeSubtitleTimeline(private val playbackId: String) {
    var generation: Long = -1
        private set
    var correctionRevision: Long = 0
        private set
    private var awaitingNewGeneration = false
    private val values = LinkedHashMap<String, NativeSubtitleEvent>()
    val events: List<NativeSubtitleEvent> get() = values.values.toList()

    fun seek() { values.clear(); awaitingNewGeneration = true }

    fun accept(batch: JSONObject): Boolean {
        if (batch.optString("playbackId") != playbackId) return false
        val incoming = batch.optLong("generationId", -1)
        if (incoming < 0 || incoming < generation || (awaitingNewGeneration && incoming <= generation)) return false
        if (incoming > generation) { values.clear(); generation = incoming; awaitingNewGeneration = false }
        val events = batch.optJSONArray("events") ?: return false
        for (i in 0 until events.length()) events.optJSONObject(i)?.let(NativeSubtitleProtocol::parse)?.let {
            if (values[it.key]?.let { old -> old.durationMs != it.durationMs || old.text != it.text } == true) correctionRevision++
            values[it.key] = it
        }
        var bytes = values.values.sumOf { it.sizeBytes }
        while (values.size > 4000 || bytes > 16L * 1024 * 1024) {
            val key = values.keys.firstOrNull() ?: break
            bytes -= values.remove(key)?.sizeBytes ?: 0
            correctionRevision++
        }
        return true
    }

    fun active(track: Int, positionMs: Long) = values.values.filter { it.track == track && positionMs >= it.startMs && positionMs < it.endMs }
}

/** Some server packets omit ReadOrder, and UTF8 packets all contain zero. Never let libass deduplicate unrelated lines. */
class NativeAssReadOrders {
    private val used = mutableSetOf<Long>()
    private var next = 0L
    fun clear() { used.clear(); next = 0 }
    fun next(event: NativeSubtitleEvent): Long {
        val original = event.extra.optString("readorder", event.extra.optString("readOrder")).toLongOrNull()
        if (original != null && original in 0..Int.MAX_VALUE.toLong() && used.add(original)) return original
        while (!used.add(next)) next++
        return next++
    }
}

/** Validate PNG dimensions before Android allocates pixel storage. */
object NativePgsBudget {
    const val BYTES = 32L * 1024 * 1024
    fun allocationBytes(width: Int, height: Int): Long? {
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) return null
        return (width.toLong() * height * 4).takeIf { it <= BYTES }
    }
    fun fits(width: Int, height: Int, retainedBytes: Long) = allocationBytes(width, height)?.let {
        retainedBytes >= 0 && retainedBytes <= BYTES - it
    } ?: false
}
