package app.seanime.tv.ui

import app.seanime.tv.data.MangaPage
import app.seanime.tv.data.MangaPageDimensions
import org.json.JSONArray
import org.json.JSONObject

/** Pagination functions stay pure so page bounds, spreads and RTL controls are testable. */
internal object MangaPagination {
    fun clamp(page: Int, count: Int): Int = if (count <= 0) 0 else page.coerceIn(0, count - 1)
    fun move(page: Int, delta: Int, count: Int, doublePage: Boolean): Int =
        clamp(page + delta * (if (doublePage) 2 else 1), count)
    fun keyDelta(right: Boolean, rightToLeft: Boolean): Int = if (right != rightToLeft) 1 else -1
    fun visible(page: Int, count: Int, doublePage: Boolean): List<Int> =
        if (count <= 0) emptyList() else (clamp(page, count)..minOf(clamp(page, count) + if (doublePage) 1 else 0, count - 1)).toList()

    /** Each position occurs once. Wide pages and an optional cover occupy their own spread. */
    fun spreads(pages: List<MangaPage>, dimensions: Map<Int, MangaPageDimensions>, doublePage: Boolean,
        coverAlone: Boolean): List<List<Int>> = buildList {
        var position = 0
        fun wide(index: Int) = dimensions[pages[index].index]?.isWide == true
        while (position < pages.size) {
            val paired = doublePage && !(coverAlone && position == 0) && !wide(position) &&
                position + 1 < pages.size && !wide(position + 1) &&
                dimensions.containsKey(pages[position].index) && dimensions.containsKey(pages[position + 1].index)
            add(if (paired) listOf(position, position + 1) else listOf(position))
            position += if (paired) 2 else 1
        }
    }

    fun visible(page: Int, spreads: List<List<Int>>): List<Int> =
        spreads.firstOrNull { page in it } ?: spreads.lastOrNull().orEmpty()

    fun move(page: Int, delta: Int, spreads: List<List<Int>>): Int {
        if (spreads.isEmpty()) return 0
        val current = spreads.indexOfFirst { page in it }.coerceAtLeast(0)
        return spreads[(current + delta).coerceIn(spreads.indices)].first()
    }
}


internal fun JSONArray?.uiObjects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { optJSONObject(it) }
internal fun Any?.jsonObjects(): List<JSONObject> = (this as? JSONArray).uiObjects()
internal fun JSONObject.text(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback).takeUnless { it == "null" } ?: fallback
internal fun JSONObject.mediaTitle(fallback: String = "Untitled"): String {
    val title = optJSONObject("title")
    return title?.text("userPreferred")?.takeIf(String::isNotBlank)
        ?: title?.text("english")?.takeIf(String::isNotBlank)
        ?: title?.text("romaji")?.takeIf(String::isNotBlank)
        ?: text("title").takeIf { it.isNotBlank() && !it.startsWith("{") }
        ?: fallback
}
internal fun humanizeField(value: String): String = value.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
    .replace('_', ' ').replaceFirstChar { it.uppercase() }


internal fun pluginPermissionSummary(plugin: JSONObject): String {
    val permissions = plugin.optJSONObject("permissions") ?: return "No permissions declared"
    val allow = permissions.optJSONObject("allow")
    fun strings(array: JSONArray?): String = if (array == null) "" else (0 until array.length()).joinToString(", ") { array.optString(it) }
    return buildList {
        strings(permissions.optJSONArray("scopes")).takeIf(String::isNotBlank)?.let { add("Services: $it") }
        strings(allow?.optJSONObject("networkAccess")?.optJSONArray("allowedDomains")).takeIf(String::isNotBlank)?.let { add("Network: $it") }
        strings(allow?.optJSONArray("readPaths")).takeIf(String::isNotBlank)?.let { add("Read files: $it") }
        strings(allow?.optJSONArray("writePaths")).takeIf(String::isNotBlank)?.let { add("Write files: $it") }
        allow?.optJSONArray("commandScopes").uiObjects().forEach { add("Run command: ${it.text("command")} ${it.text("description")}") }
        allow?.optJSONArray("unsafeFlags").uiObjects().forEach { add("Unsafe capability: ${it.text("flag")} · ${it.text("reason")}") }
    }.joinToString("\n").ifBlank { "No permissions declared" }
}


private val settingLabels = mapOf(
    "enableOnlinestream" to "Enable online streaming", "enableManga" to "Enable manga",
    "enableWatchContinuity" to "Resume unfinished episodes", "autoPlayNextEpisode" to "Play next episode automatically",
    "autoUpdateProgress" to "Update viewing progress automatically", "mangaAutoUpdateProgress" to "Update reading progress automatically",
    "defaultMangaProvider" to "Default manga provider", "mangaLocalSourceDirectory" to "Local manga folder",
    "libraryPath" to "Main anime folder", "libraryPaths" to "Additional anime folders",
    "defaultTorrentClient" to "Default torrent client", "defaultPlayer" to "External player backend",
    "remoteServerURL" to "Nakama host or room URL", "isHost" to "Act as Nakama host",
    "hostShareLocalAnimeLibrary" to "Share local anime with Nakama peers", "includeNakamaAnimeLibrary" to "Include host library",
    "enableExtensionSecureMode" to "Extension secure mode", "autoSyncOfflineLocalData" to "Synchronize offline data automatically",
)

internal fun settingLabel(key: String): String = settingLabels[key] ?: humanizeField(key)
internal fun settingIsSecret(key: String): Boolean = listOf("password", "apikey", "token", "secret").any { key.contains(it, true) }

private const val REDACTED_SETTING = "••••••••"

internal fun redactSettingsForEditor(source: JSONObject): JSONObject = JSONObject().apply {
    source.keys().forEach { key ->
        val value = source.opt(key)
        put(key, when {
            settingIsSecret(key) -> REDACTED_SETTING
            value is JSONObject -> redactSettingsForEditor(value)
            else -> value
        })
    }
}

internal fun preserveSettingSecrets(edited: JSONObject, current: JSONObject): JSONObject = JSONObject(edited.toString()).apply {
    current.keys().forEach { key ->
        val existing = current.opt(key)
        if (settingIsSecret(key)) put(key, existing)
        else if (existing is JSONObject && optJSONObject(key) != null) put(key, preserveSettingSecrets(getJSONObject(key), existing))
    }
}


/** Existing plugin event envelope. Neither script source nor web markup is interpreted here. */
internal fun pluginEventEnvelope(extensionId: String, type: String, payload: JSONObject = JSONObject()): JSONObject =
    JSONObject().put("extensionId", extensionId).put("type", type).put("payload", payload)

internal fun unpackPluginEvents(value: Any?, depth: Int = 0): List<JSONObject> {
    if (depth > 8) return emptyList()
    val event = value as? JSONObject ?: return emptyList()
    return if (event.text("type") == "plugin:batch-events") event.optJSONObject("payload")?.optJSONArray("events").uiObjects().flatMap { unpackPluginEvents(it, depth + 1) }
        else listOf(event)
}

internal fun pluginNodes(value: Any?): List<Any> = when (value) {
    is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it).takeUnless { it == null || it == JSONObject.NULL || it is Boolean } }
    null, JSONObject.NULL -> emptyList()
    is Boolean -> emptyList()
    else -> listOf(value)
}

internal fun pluginNodeLabel(value: Any?, depth: Int = 0): String {
    if (depth > 8) return "Plugin control"
    return when (value) {
        is String -> value
        is JSONObject -> {
            val props = value.optJSONObject("props") ?: value
            props.text("label").ifBlank { props.text("text") }.ifBlank { props.text("title") }.ifBlank {
                if (props.has("item")) pluginNodeLabel(props.opt("item"), depth + 1)
                else pluginNodes(props.opt("items")).joinToString(" ") { pluginNodeLabel(it, depth + 1) }
            }.ifBlank { "Plugin control" }
        }
        else -> "Plugin control"
    }
}

internal fun pluginFormData(formName: String, fields: List<JSONObject>, values: Map<String, Any?>): JSONObject = JSONObject().apply {
    fields.filter { it.text("type") != "submit" && it.text("name").isNotBlank() }.forEach { field ->
        val type = field.text("type")
        val default: Any = if (type in setOf("checkbox", "switch")) false else if (type == "number") 0 else ""
        put(field.text("name"), values["$formName:${field.text("name")}"] ?: field.opt("value")?.takeUnless { it == JSONObject.NULL } ?: default)
    }
}
