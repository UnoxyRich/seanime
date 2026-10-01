package app.seanime.tv.ui

import app.seanime.tv.data.OnlineEpisodeIdentity
import app.seanime.tv.data.SeanimeJson
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OnlineEpisodeSelectionTest {
    @Test fun largeCustomMediaIdentityIsPreservedAndCannotAliasAnotherTitle() = runBlocking {
        for (id in listOf(2_147_483_648L, 4_294_967_297L, 9_007_199_254_740_991L)) {
            val raw = JSONObject().put("number", 13).put("metadata", JSONObject().put("episodeNumber", 3)
                .put("aniDBEpisode", "S2").put("progressNumber", 0).put("baseAnime", JSONObject().put("id", id)))
            val row = SeanimeJson.episode(raw)
            val selected = resolveOnlineEpisode(id, 13, listOf(row)) { error("Expected cached source") }
            assertEquals(id, OnlineEpisodeIdentity.canonical(selected.raw)!!.getJSONObject("baseAnime").getLong("id"))
            assertTrue("A different large title aliased the selected source",
                runCatching { resolveOnlineEpisode(id - 1, 13, listOf(row)) { listOf(row) } }.isFailure)
        }
        val collided = SeanimeJson.episode(JSONObject("""{"number":1,"metadata":{"baseAnime":{"id":4294967297},"episodeNumber":1}}"""))
        assertTrue(runCatching { resolveOnlineEpisode(1L, 1, listOf(collided)) { listOf(collided) } }.isFailure)
    }

    @Test fun providerNumberStaysSeparateFromNestedSpecialIdentity() = runBlocking {
        val wire = JSONObject("""{"number":13,"title":"Provider title","metadata":{"episodeNumber":3,"aniDBEpisode":"S2","progressNumber":0,"type":"special","displayTitle":"Canonical special","baseAnime":{"id":21},"custom":"kept"}}""")
        val row = SeanimeJson.episode(wire)
        assertEquals(3, row.number)
        assertEquals("S2", row.aniDbEpisode)
        assertEquals(0, row.progressNumber)
        assertEquals("Canonical special", row.title)
        assertSame(wire, row.raw)
        val selected = resolveOnlineEpisode(21, 13, listOf(row)) { error("Cached provider identity must be retained") }
        val params = JSONObject().put("provider", "fixture").put("episodeNumber", 1)
        val playback = OnlineEpisodeIdentity.withParams(selected.raw, params)
        assertEquals(13, playback.getJSONObject("onlinestreamParams").getInt("episodeNumber"))
        assertEquals(3, playback.getInt("episodeNumber"))
        assertEquals("S2", playback.getString("aniDBEpisode"))
        assertEquals(0, playback.getInt("progressNumber"))
        assertEquals("kept", playback.getString("custom"))
        assertEquals(1, params.getInt("episodeNumber"))
        assertFalse(wire.getJSONObject("metadata").has("onlinestreamParams"))
    }

    @Test fun sourceSelectionLoadsItsActualProviderRowInsteadOfCloningTheOldEpisode() = runBlocking {
        val original = SeanimeJson.episode(JSONObject("""{"number":1,"metadata":{"episodeNumber":1,"aniDBEpisode":"1","progressNumber":1,"displayTitle":"Original"}}"""))
        val target = SeanimeJson.episode(JSONObject("""{"number":14,"metadata":{"episodeNumber":4,"aniDBEpisode":"4","progressNumber":4,"displayTitle":"Target","baseAnime":{"id":21}}}"""))
        var loads = 0
        val selected = resolveOnlineEpisode(21, 14, listOf(original)) { loads++; listOf(original, target) }
        assertSame(target, selected)
        assertEquals(1, loads)
        assertEquals("Target", selected.title)
        assertEquals(14, OnlineEpisodeIdentity.sourceNumber(selected.raw))
    }

    @Test fun missingMetadataKeepsTheStreamPlayableWithoutInventingWatchProgress() = runBlocking {
        val row = SeanimeJson.episode(JSONObject("""{"number":13,"title":"Unmapped provider episode","metadata":null}"""))
        val selected = resolveOnlineEpisode(21, 13, emptyList()) { listOf(row) }
        val playback = OnlineEpisodeIdentity.withParams(selected.raw, JSONObject().put("provider", "fixture"))
        assertEquals(13, OnlineEpisodeIdentity.sourceNumber(playback))
        assertNull("No canonical episode must become an absent wire episode", OnlineEpisodeIdentity.canonical(playback))
        assertEquals(0, selected.progressNumber)
        assertEquals("", selected.aniDbEpisode)
    }

    @Test fun ambiguousOrWrongTitleProviderRowsCannotBeSelected() = runBlocking {
        val row = SeanimeJson.episode(JSONObject("""{"number":13,"metadata":{"episodeNumber":3,"aniDBEpisode":"3","baseAnime":{"id":21}}}"""))
        val wrong = SeanimeJson.episode(JSONObject("""{"number":13,"metadata":{"episodeNumber":3,"aniDBEpisode":"3","baseAnime":{"id":22}}}"""))
        for (rows in listOf(emptyList(), listOf(row, row), listOf(wrong))) {
            assertTrue(runCatching { resolveOnlineEpisode(21, 13, rows) { rows } }.isFailure)
        }
    }
}
