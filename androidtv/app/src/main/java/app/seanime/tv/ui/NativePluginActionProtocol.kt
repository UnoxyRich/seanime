package app.seanime.tv.ui

import org.json.JSONObject

/** Nine action families exposed by internal/plugin/ui/events.go. */
internal enum class NativePluginActionKind(val wire: String, val field: String = "items") {
    ANIME_PAGE_BUTTON("anime-page-buttons", "buttons"), ANIME_PAGE_MENU("anime-page-dropdown-items"),
    MANGA_PAGE_BUTTON("manga-page-buttons", "buttons"), MANGA_PAGE_MENU("manga-page-dropdown-items"),
    ANIME_LIBRARY("anime-library-dropdown-items"), MANGA_LIBRARY("manga-library-dropdown-items"),
    MEDIA_CARD("media-card-context-menu-items"), EPISODE_CARD("episode-card-context-menu-items"), EPISODE_GRID("episode-grid-item-menu-items");
    val request get() = "action:$wire:render"
    val update get() = "action:$wire:updated"
}

internal data class NativePluginAction(val extensionId: String, val kind: NativePluginActionKind, val id: String,
    val label: String, val disabled: Boolean, val loading: Boolean, val raw: JSONObject) {
    val key get() = "$extensionId:${kind.name}:$id"
}

/** Replaces only the sending extension's list; actions from other plugins survive updates. */
internal class NativePluginActionCatalog {
    private val actions = linkedMapOf<Pair<NativePluginActionKind, String>, List<NativePluginAction>>()
    fun receive(event: JSONObject): Boolean {
        val kind = NativePluginActionKind.entries.firstOrNull { it.update == event.optString("type") } ?: return false
        val extension = event.optString("extensionId").takeIf(String::isNotBlank) ?: return false
        val list = event.optJSONObject("payload")?.optJSONArray(kind.field).uiObjects()
            .take(3).filter { it.text("id").isNotBlank() }.distinctBy { it.text("id") }.map {
                NativePluginAction(extension, kind, it.text("id"), it.text("label", "Plugin action"), it.optBoolean("disabled"), it.optBoolean("loading"), it)
            }
        actions[kind to extension] = list
        return true
    }
    fun remove(extensionId: String) { actions.keys.filter { it.second == extensionId }.forEach(actions::remove) }
    fun clear() = actions.clear()
    fun visible(kinds: List<NativePluginActionKind>, mediaType: String?, episodeType: String?): List<NativePluginAction> =
        actions.values.flatten().filter { action -> action.kind in kinds && when (action.kind) {
            NativePluginActionKind.MEDIA_CARD -> action.raw.text("for") in setOf(mediaType, "both")
            NativePluginActionKind.EPISODE_CARD -> action.raw.text("type").isBlank() || action.raw.text("type") == episodeType
            NativePluginActionKind.EPISODE_GRID -> episodeType != null && action.raw.text("type") == episodeType
            else -> true
        } }.sortedWith(compareBy<NativePluginAction> { it.extensionId }.thenBy { it.kind.ordinal }.thenBy { it.label }.thenBy { it.id })
    fun current(action: NativePluginAction): NativePluginAction? = actions[action.kind to action.extensionId]?.firstOrNull { it.id == action.id }
}

internal fun nativePluginActionPayload(action: NativePluginAction, media: JSONObject?, episode: JSONObject?, episodeType: String?): JSONObject {
    val event = JSONObject()
    when (action.kind) {
        NativePluginActionKind.ANIME_LIBRARY, NativePluginActionKind.MANGA_LIBRARY -> Unit
        NativePluginActionKind.EPISODE_CARD -> event.put("episode", requireNotNull(episode))
        NativePluginActionKind.EPISODE_GRID -> event.put("episode", requireNotNull(episode)).put("type", requireNotNull(episodeType))
        else -> event.put("media", requireNotNull(media))
    }
    return JSONObject().put("actionId", action.id).put("event", event)
}
