package app.seanime.tv.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePluginEpisodeProtocolTest {
    @Test fun customSourceTitleIdsRetainAllBitsInEpisodeIdentityAndSelection() {
        for (id in listOf(2_147_483_648L, 4_294_967_317L, 9_007_199_254_740_991L)) {
            val episode = JSONObject().put("episodeNumber", 3).put("baseAnime", JSONObject().put("id", id))
            val collection = JSONObject().put("episodes", org.json.JSONArray().put(episode))
                .put("metadata", JSONObject().put("mappings", JSONObject().put("anilistId", id)))
            val parsed = nativePluginEpisodes(JSONObject(collection.toString()), id).single()
            val roundTrip = JSONObject(nativePluginEpisodeSelection(id, parsed).toString())
            assertEquals(id, roundTrip.getLong("mediaId"))
            assertEquals(id, roundTrip.getJSONObject("episode").getJSONObject("baseAnime").getLong("id"))
            assertEquals(3, roundTrip.getInt("episodeNumber"))
            assertTrue(runCatching { nativePluginEpisodes(collection, id + 1L) }.isFailure)
        }
        val aliased = JSONObject("""{"episodes":[{"episodeNumber":1,"baseAnime":{"id":21}}]}""")
        assertTrue(runCatching { nativePluginEpisodes(aliased, 4_294_967_317L) }.isFailure)
    }
    @Test fun initialSourceHonorsExplicitThenConfiguredThenAvailableAutomaticChoice() {
        fun choose(explicit: String? = null, configured: String = "", local: Boolean = false, online: Boolean = true,
            torrent: Boolean = true, debrid: Boolean = true, ids: Set<String> = setOf("plugin")) =
            initialNativeSourceMode(explicit, configured, local, online, torrent, debrid, ids)
        assertEquals("Online", choose("Online", "ext:plugin", local = true))
        assertEquals("episodeTab:plugin", choose(configured = "ext:plugin"))
        assertEquals("episodeTab:plugin", choose("episodeTab:plugin", "library"))
        assertEquals("Device", choose(configured = "library"))
        assertEquals("Device", choose(local = true))
        assertEquals("Debrid", choose())
        assertEquals("Torrent", choose(debrid = false))
        assertEquals("Online", choose(debrid = false, torrent = false))
        assertEquals("Device", choose(debrid = false, torrent = false, online = false))
        assertEquals("Online", choose(configured = "ext:missing", debrid = false, torrent = false))
        assertEquals("Device", choose(configured = "debridstream", local = true, debrid = false))
        assertEquals("Online", choose("Online", online = false))
    }

    @Test fun pluginRoutesRetainRegisteredSourceIdentityWithoutUrlLaunch() {
        val route = resolveNativePluginDestination("/entry?id=21&tab=ext%3Aplugin&episode=3")
        assertEquals("episodeTab:plugin", route.sourceMode)
        assertEquals(3, route.episode)
        assertEquals("plugin", nativePluginSourceId("episodeTab:plugin"))
        assertNull(nativePluginSourceId("ext:"))
        assertNull(nativePluginSourceId("https://example.com"))
    }

    @Test fun episodeSelectionPreservesSpecialNumberMetadataAndExactPayload() {
        val collection = JSONObject("""{"episodes":[{"episodeNumber":3,"aniDBEpisode":"S2","episodeTitle":"Special","baseAnime":{"id":21},"custom":{"x":7}}]}""")
        val episode = nativePluginEpisodes(collection, 21).single()
        val payload = nativePluginEpisodeSelection(21, episode)
        assertEquals(21, payload.getInt("mediaId"))
        assertEquals(3, payload.getInt("episodeNumber"))
        assertEquals("S2", payload.getString("aniDbEpisode"))
        assertEquals(7, payload.getJSONObject("episode").getJSONObject("custom").getInt("x"))
        assertFalse(payload.has("aniDBEpisode"))
        assertTrue(nativePluginEpisodes(JSONObject("""{"episodes":[],"metadata":{"mappings":{"anilistId":21}}}"""), 21).isEmpty())
    }

    @Test fun malformedOrExplicitlyWrongTitleCollectionsFailInsteadOfBecomingPlayableEpisodes() {
        assertTrue(runCatching { nativePluginEpisodes(JSONObject(), 21) }.isFailure)
        assertTrue(runCatching { nativePluginEpisodes(JSONObject("""{"episodes":[{"episodeNumber":1,"baseAnime":{"id":99}}]}"""), 21) }.isFailure)
        assertTrue(runCatching { nativePluginEpisodes(JSONObject("""{"episodes":["invalid"],"metadata":{"mappings":{"anilistId":21}}}"""), 21) }.isFailure)
        assertTrue(runCatching { nativePluginEpisodes(JSONObject("""{"episodes":[{"baseAnime":{"id":21.5}}]}"""), 21) }.isFailure)
    }

    @Test fun collectionMetadataOrEveryEpisodeMustBindTheResponseToTheCurrentTitle() {
        val metadataScoped = JSONObject("""{"episodes":[{"episodeNumber":2}],"metadata":{"mappings":{"anilistId":21}}}""")
        assertEquals(2, nativePluginEpisodes(metadataScoped, 21).single().number)
        assertTrue(runCatching { nativePluginEpisodes(metadataScoped, 22) }.isFailure)
        val everyEpisodeScoped = JSONObject("""{"episodes":[{"episodeNumber":1,"baseAnime":{"id":21}},{"episodeNumber":2,"baseAnime":{"id":21}}]}""")
        assertEquals(2, nativePluginEpisodes(everyEpisodeScoped, 21).size)
        assertTrue(runCatching { nativePluginEpisodes(everyEpisodeScoped, 22) }.isFailure)
        val conflicting = JSONObject("""{"episodes":[{"baseAnime":{"id":22}}],"metadata":{"mappings":{"anilistId":21}}}""")
        assertTrue(runCatching { nativePluginEpisodes(conflicting, 21) }.isFailure)
        assertTrue(runCatching { nativePluginEpisodes(conflicting, 22) }.isFailure)
    }

    @Test fun identityFreeAndPartiallyScopedResponsesAreAmbiguousEvenWhenEmpty() {
        listOf(
            """{"episodes":[]}""",
            """{"episodes":[{"episodeNumber":1}]}""",
            """{"episodes":[{"episodeNumber":1,"baseAnime":{"id":0}}]}""",
            """{"episodes":[{"baseAnime":{"id":21}},{"episodeNumber":2}]}""",
            """{"episodes":[],"metadata":{"mappings":{"anilistId":0}}}"""
        ).forEach { json ->
            val result = runCatching { nativePluginEpisodes(JSONObject(json), 21) }
            assertTrue(json, result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("no reliable title identity"))
        }
    }
}
