package app.seanime.tv.data

import org.json.JSONArray
import org.json.JSONObject

internal data class NativeMangaSourceFilter(val language: String = "", val scanlators: List<String> = emptyList()) {
    fun accepts(chapter: MangaChapter): Boolean =
        (language.isBlank() || chapter.raw.optString("language") == language) &&
            (scanlators.isEmpty() || chapter.raw.optString("scanlator") in scanlators)
}

internal data class NativeMangaSourcePreferences(val provider: String = "", val filters: Map<String, NativeMangaSourceFilter> = emptyMap())

internal sealed interface NativeMangaFilterEdit {
    data class Language(val value: String) : NativeMangaFilterEdit
    data class Scanlators(val values: List<String>) : NativeMangaFilterEdit
}

internal fun parseNativeMangaPreferences(entry: JSONObject?): NativeMangaSourcePreferences {
    val filters = entry?.optJSONObject("filters")
    return NativeMangaSourcePreferences(entry?.optString("provider").orEmpty(), filters?.keys()?.asSequence()?.associateWith { provider ->
        val value = filters?.optJSONObject(provider)
        val scanlators = value?.optJSONArray("scanlators")
        NativeMangaSourceFilter(value?.optString("language").orEmpty(),
            (0 until (scanlators?.length() ?: 0)).mapNotNull { scanlators?.optString(it)?.takeIf(String::isNotBlank) }.distinct())
    }.orEmpty())
}

internal fun nativeMangaFilterPayload(provider: String, edit: NativeMangaFilterEdit): JSONObject {
    require(provider.isNotBlank() && provider != "__downloaded" && provider.length <= 200) { "Choose an available manga provider" }
    val filter = jsonObject("provider" to provider)
    when (edit) {
        is NativeMangaFilterEdit.Language -> {
            require(edit.value.length <= 50) { "Language is too long" }
            filter.put("language", edit.value)
        }
        is NativeMangaFilterEdit.Scanlators -> {
            require(edit.values.size <= 20 && edit.values.all { it.isNotBlank() && it.length <= 200 }) { "Choose up to 20 scanlators" }
            filter.put("scanlators", JSONArray(edit.values.distinct()))
        }
    }
    return jsonObject("filter" to filter)
}

internal suspend fun loadNativeMangaPreferences(repo: SeanimeRepository, mediaId: Long): NativeMangaSourcePreferences {
    val raw = repo.request("GET", "/api/v1/manga/preferences") as? JSONObject ?: error("Couldn't load manga preferences")
    return parseNativeMangaPreferences(raw.optJSONObject("entries")?.optJSONObject(mediaId.toString()))
}

internal suspend fun saveNativeMangaFilter(repo: SeanimeRepository, mediaId: Long, provider: String, edit: NativeMangaFilterEdit): NativeMangaSourcePreferences {
    require(mediaId > 0)
    val result = repo.request("PATCH", "/api/v1/manga/preferences/$mediaId", nativeMangaFilterPayload(provider, edit)) as? JSONObject
        ?: error("Couldn't save manga preferences")
    return parseNativeMangaPreferences(result)
}

internal fun nativeMangaPreferenceEventAffects(payload: Any?, mediaId: Long): Boolean {
    val ids = (payload as? JSONObject)?.optJSONArray("mediaIds") ?: return false
    return (0 until ids.length()).any { ids.optLong(it) == mediaId }
}

internal data class NativeMangaReaderSettings(val rtl: Boolean = false, val doublePage: Boolean = true, val coverAlone: Boolean = false) {
    fun storedValues(mediaId: Long): Map<String, Boolean> = mapOf(
        "media:$mediaId:rtl" to rtl, "media:$mediaId:double" to doublePage, "media:$mediaId:coverAlone" to coverAlone)
}

/** Existing global settings are migration defaults only; saves always use title-specific keys. */
internal fun loadNativeMangaReaderSettings(mediaId: Long, read: (String) -> Boolean?): NativeMangaReaderSettings {
    val defaults = NativeMangaReaderSettings()
    fun setting(name: String, fallback: Boolean) = read("media:$mediaId:$name") ?: read(name) ?: fallback
    return NativeMangaReaderSettings(setting("rtl", defaults.rtl), setting("double", defaults.doublePage), setting("coverAlone", defaults.coverAlone))
}
