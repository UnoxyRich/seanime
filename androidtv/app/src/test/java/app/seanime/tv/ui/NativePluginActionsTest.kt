package app.seanime.tv.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePluginActionsTest {
    @Test fun updatesReplaceOnlyTheirOwnerAndRemoveUnloadedActions() {
        val catalog = NativePluginActionCatalog()
        catalog.receive(update("first", NativePluginActionKind.ANIME_PAGE_BUTTON, "a"))
        catalog.receive(update("second", NativePluginActionKind.ANIME_PAGE_BUTTON, "a"))
        assertEquals(listOf("first", "second"), catalog.visible(listOf(NativePluginActionKind.ANIME_PAGE_BUTTON), "anime", null).map { it.extensionId })
        catalog.receive(update("first", NativePluginActionKind.ANIME_PAGE_BUTTON, "replacement"))
        assertEquals(setOf("replacement", "a"), catalog.visible(listOf(NativePluginActionKind.ANIME_PAGE_BUTTON), "anime", null).map { it.id }.toSet())
        catalog.remove("first")
        assertEquals("second", catalog.visible(listOf(NativePluginActionKind.ANIME_PAGE_BUTTON), "anime", null).single().extensionId)
    }

    @Test fun mediaAndEpisodeActionsRespectTheirExactContextFilters() {
        val catalog = NativePluginActionCatalog()
        catalog.receive(pluginEventEnvelope("plugin", NativePluginActionKind.MEDIA_CARD.update, JSONObject().put("items", JSONArray()
            .put(JSONObject().put("id", "anime").put("for", "anime"))
            .put(JSONObject().put("id", "manga").put("for", "manga"))
            .put(JSONObject().put("id", "both").put("for", "both")))))
        assertEquals(setOf("manga", "both"), catalog.visible(listOf(NativePluginActionKind.MEDIA_CARD), "manga", null).map { it.id }.toSet())
        catalog.receive(pluginEventEnvelope("plugin", NativePluginActionKind.EPISODE_CARD.update, JSONObject().put("items", JSONArray()
            .put(JSONObject().put("id", "all"))
            .put(JSONObject().put("id", "library").put("type", "library"))
            .put(JSONObject().put("id", "stream").put("type", "onlinestream")))))
        catalog.receive(pluginEventEnvelope("plugin", NativePluginActionKind.EPISODE_GRID.update, JSONObject().put("items", JSONArray()
            .put(JSONObject().put("id", "exact").put("type", "episodeTab:source"))
            .put(JSONObject().put("id", "missing")))))
        assertEquals(listOf("all"), catalog.visible(listOf(NativePluginActionKind.EPISODE_CARD), "anime", "episodeTab:source").map { it.id })
        assertEquals(listOf("exact"), catalog.visible(listOf(NativePluginActionKind.EPISODE_GRID), "anime", "episodeTab:source").map { it.id })
    }

    @Test fun clickPayloadsPreserveRawMediaEpisodeAndWireFieldNames() {
        val media = JSONObject("""{"id":21,"title":{"romaji":"Fixture"},"providerSpecific":"unchanged"}""")
        val episode = JSONObject("""{"episodeNumber":3,"aniDBEpisode":"S2","baseAnime":{"id":21},"custom":{"x":7}}""")
        NativePluginActionKind.entries.forEach { kind ->
            val action = NativePluginAction("plugin", kind, "action-id", "Label", false, false, JSONObject())
            val payload = nativePluginActionPayload(action, media, episode, "library")
            assertEquals("action-id", payload.getString("actionId"))
            val event = payload.getJSONObject("event")
            when (kind) {
                NativePluginActionKind.ANIME_LIBRARY, NativePluginActionKind.MANGA_LIBRARY -> assertEquals(0, event.length())
                NativePluginActionKind.EPISODE_CARD -> { assertSame(episode, event.getJSONObject("episode")); assertFalse(event.has("type")) }
                NativePluginActionKind.EPISODE_GRID -> { assertSame(episode, event.getJSONObject("episode")); assertEquals("library", event.getString("type")) }
                else -> assertSame(media, event.getJSONObject("media"))
            }
        }
    }

    @Test fun disabledLoadingAndBoundedActionsAreRetainedWithoutInterpretingStyles() {
        val catalog = NativePluginActionCatalog()
        val items = JSONArray().apply { (1..10).forEach { put(JSONObject().put("id", it.toString()).put("disabled", it == 1).put("loading", it == 2).put("style", JSONObject().put("display", "none"))) } }
        catalog.receive(pluginEventEnvelope("plugin", NativePluginActionKind.ANIME_PAGE_MENU.update, JSONObject().put("items", items)))
        val actions = catalog.visible(listOf(NativePluginActionKind.ANIME_PAGE_MENU), "anime", null)
        assertEquals(3, actions.size)
        assertTrue(actions.first { it.id == "1" }.disabled)
        assertTrue(actions.first { it.id == "2" }.loading)
        assertFalse(catalog.receive(update("", NativePluginActionKind.ANIME_PAGE_MENU, "unknown")))
    }

    private fun update(owner: String, kind: NativePluginActionKind, id: String) = pluginEventEnvelope(owner, kind.update,
        JSONObject().put(kind.field, JSONArray().put(JSONObject().put("id", id).put("label", id))))
}
