package app.seanime.tv.ui

import app.seanime.tv.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeAnimeMetadataModelsTest {
    @Test fun baseFactsPreserveMissingAndPartialValuesAndTheScorePreference() {
        val media = SeanimeJson.media(jsonObject("id" to 1, "title" to jsonObject("userPreferred" to "Fixture title", "romaji" to "Other title"),
            "status" to "FINISHED", "season" to "FALL", "seasonYear" to 2018, "duration" to 24,
            "episodes" to 13, "meanScore" to 82, "genres" to JSONArray(listOf("Drama", "Romance")),
            "startDate" to jsonObject("year" to 2018, "month" to 10), "endDate" to jsonObject("year" to 2018)))
        val hidden = nativeAnimeFacts(media, false).associate { it.label to it.value }
        assertEquals("Fall 2018", hidden["Season"])
        assertEquals("2018-10", hidden["Started"])
        assertEquals("2018", hidden["Ended"])
        assertEquals("24 minutes", hidden["Episode duration"])
        assertEquals("Drama · Romance", hidden["Genres"])
        assertFalse(hidden.containsKey("Audience score"))
        assertEquals("8.2 / 10", nativeAnimeFacts(media, true).single { it.label == "Audience score" }.value)
        assertNull(nativeAnimeDate(jsonObject("month" to 10, "day" to 1)))
        assertFalse(nativeAnimeFacts(MediaCard(1, "Fixture title"), true).any())
    }

    @Test fun relatedAndRecommendedCardsKeepLongIdsAndTheirCorrectMediaType() {
        val animeId = 4_294_967_297L
        val mangaId = 9_007_199_254_740_990L
        val relationEdges = JSONArray().put(jsonObject("relationType" to "SEQUEL", "node" to title(animeId)))
                .put(jsonObject("relationType" to "SOURCE", "node" to title(mangaId, "MANGA")))
                .put(jsonObject("relationType" to "CHARACTER", "node" to title(2)))
                .put(jsonObject("node" to title(0))).put(jsonObject("node" to jsonObject("id" to 3, "type" to "ANIME")))
                .put(jsonObject("node" to title(4).put("title", "   ")))
        val result = parseNativeAnimeSupplement(jsonObject("id" to 1, "relations" to jsonObject("edges" to relationEdges)), 1)
        assertEquals(listOf(animeId, mangaId), result.relations.map { it.media.id })
        assertFalse(result.relations.first().media.isManga)
        assertTrue(result.relations.last().media.isManga)
        val recommendationEdges = JSONArray()
            .put(jsonObject("node" to jsonObject("mediaRecommendation" to title(animeId))))
            .put(jsonObject("node" to jsonObject("mediaRecommendation" to title(animeId))))
        val recommendations = parseNativeAnimeSupplement(jsonObject("id" to 1,
            "recommendations" to jsonObject("edges" to recommendationEdges)), 1)
        assertEquals(1, recommendations.recommendations.size)
        assertEquals(animeId, recommendations.recommendations.single().media.id)
    }

    @Test fun supplementaryDetailsNeverReplaceTheRequestedTitleIdentity() {
        for (wrong in listOf(JSONObject(), jsonObject("id" to 2), jsonObject("id" to -1))) {
            assertThrows(IllegalArgumentException::class.java) { parseNativeAnimeSupplement(wrong, 1) }
        }
        val empty = parseNativeAnimeSupplement(jsonObject("id" to 1), 1)
        assertTrue(empty.relations.isEmpty() && empty.characters.isEmpty() && empty.studios.isEmpty())
    }

    @Test fun charactersStudiosAndRankingsUseOnlyReturnedFields() {
        val character = jsonObject("role" to "MAIN", "node" to jsonObject("id" to 42,
            "name" to jsonObject("full" to "Character fixture"), "image" to jsonObject("large" to "https://images.example.test/42.jpg")))
        val result = parseNativeAnimeSupplement(jsonObject("id" to 1,
            "studios" to jsonObject("nodes" to JSONArray().put(jsonObject("name" to "Studio fixture"))),
            "rankings" to JSONArray().put(jsonObject("rank" to 4, "type" to "RATED", "season" to "FALL", "year" to 2018)),
            "characters" to jsonObject("edges" to JSONArray().put(character))), 1)
        assertEquals(listOf("Studio fixture"), result.studios)
        assertEquals(listOf("#4 Highest rated · Fall 2018"), result.rankings)
        assertEquals("Character fixture", result.characters.single().name)
        assertEquals("https://images.example.test/42.jpg", result.characters.single().image)
    }

    @Test fun onlySupportedMetadataLinksReachThePlatformUriHandler() {
        fun links(site: String, trailerId: String = "abcdEFG_123", trailerSite: String = "youtube") = nativeAnimeLinks(SeanimeJson.media(
            title(1).put("siteUrl", site).put("trailer", jsonObject("id" to trailerId, "site" to trailerSite))))
        assertEquals("Open AniList", links("https://anilist.co/anime/1").first().label)
        assertEquals("Open website", links("https://catalog.example.test/title/1").first().label)
        assertEquals("https://www.youtube.com/watch?v=abcdEFG_123", links("https://anilist.co/anime/1").last().url)
        assertTrue(links("javascript:alert(1)", "bad&id=other").isEmpty())
        assertTrue(links("https://secret@anilist.co/anime/1", trailerSite = "unknown").isEmpty())
    }

    private fun title(id: Long, type: String = "ANIME") = jsonObject("id" to id, "type" to type,
        "title" to jsonObject("userPreferred" to "Fixture $id"), "format" to if (type == "MANGA") "MANGA" else "TV")
}
