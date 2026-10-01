package app.seanime.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SeanimeJsonTest {
    @Test fun libraryMappingPreservesListProgressAndDeduplicatesStream() {
        val json = JSONObject("""{"lists":[{"entries":[{"mediaId":42,"media":{"id":42,"title":{"english":null,"romaji":"A Series"},"coverImage":{"large":"https://images.example/42.jpg"},"episodes":12},"listData":{"progress":3,"status":"CURRENT"}}]}],"stream":{"anime":[{"id":42,"title":{"romaji":"A Series"}},{"id":99,"title":{"userPreferred":"Streaming Series"}}],"listData":{"99":{"progress":8}}}}""")
        val result = SeanimeJson.collection(json)
        assertEquals(2, result.size)
        assertEquals("A Series", result[0].title)
        assertEquals(3, result[0].progress)
        assertEquals(12, result[0].totalEpisodes)
        assertEquals(8, result[1].progress)
    }

    @Test fun rawAniListCollectionAndSearchUseTheirRealEnvelopes() {
        val raw = JSONObject("""{"MediaListCollection":{"lists":[{"entries":[{"progress":7,"status":"PAUSED","media":{"id":12,"title":{"romaji":"Manga"},"chapters":80}}]}]}}""")
        val list = SeanimeJson.collection(raw, true)
        assertEquals(7, list.single().progress)
        assertEquals(80, list.single().totalEpisodes)
        assertTrue(list.single().isManga)
        val search = SeanimeJson.collection(JSONObject("""{"Page":{"media":[{"id":123,"title":{"romaji":"Result"}}]}}"""))
        assertEquals(123L, search.single().id)
    }

    @Test fun episodeMappingPreservesFileAndAniDbIdentity() {
        val episode = SeanimeJson.episode(JSONObject("""{"episodeNumber":2,"aniDBEpisode":"S1","progressNumber":0,"displayTitle":"Special","episodeTitle":"","isDownloaded":true,"_isNakamaEpisode":true,"localFile":{"path":"content://storage/episode.mkv"},"episodeMetadata":{"image":"poster.jpg","summary":"Summary"}}"""))
        assertEquals("Special", episode.title)
        assertEquals("S1", episode.aniDbEpisode)
        assertEquals(0, episode.progressNumber)
        assertEquals("content://storage/episode.mkv", episode.localPath)
        assertTrue(episode.isNakama)
    }

    @Test fun initialStatusKeepsSettingsAbsentUntilSetup() {
        val status = SeanimeJson.status(JSONObject("""{"serverReady":true,"settings":null,"user":{"viewer":{"name":"User"},"isSimulated":true}}"""))
        assertTrue(status.ready)
        assertEquals(0, status.settings.length())
        assertTrue(status.isSimulated)
    }

    @Test fun serverErrorsAndMalformedBodiesAreNotReportedAsSuccess() {
        for ((code, body) in listOf(401 to """{"error":"UNAUTHENTICATED"}""", 200 to """{"error":"provider failed"}""", 200 to "<html>bad gateway</html>")) {
            val error = runCatching { SeanimeApiClient.decodeResponse(code, body, "/test") }.exceptionOrNull()
            assertTrue(error is ApiException)
            assertEquals(code, (error as ApiException).statusCode)
        }
        assertEquals(false, SeanimeApiClient.decodeResponse(200, """{"data":false}""", "/test"))
        assertNull(SeanimeApiClient.decodeResponse(204, "", "/test"))
    }

    @Test fun serverPasswordUsesSha256AndPathSegmentsAreEncoded() {
        assertEquals("2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b", SeanimeApiClient.hashPassword("secret"))
        assertEquals("a%2Fb%20%23%2B", SeanimeRepository.encodePathSegment("a/b #+"))
    }

    @Test fun setupUsesBuiltInTorrentClientAndExplicitFeatureChoices() {
        val payload = SeanimeRepository.setupPayload("/data/user/0/app/library", false, true)
        assertEquals("seanime", payload.getJSONObject("torrent").getString("defaultTorrentClient"))
        assertFalse(payload.getJSONObject("library").getBoolean("enableOnlinestream"))
        assertTrue(payload.getBoolean("enableTorrentStreaming"))
        assertEquals(3, payload.getJSONObject("torrent").getInt("seanimeMaxActiveDownloads"))
        assertEquals(8, listOf("library", "mediaPlayer", "torrent", "anilist", "manga", "discord", "notifications", "nakama").count { payload.optJSONObject(it) != null })
    }
    @Test fun catalogReleaseStatusDoesNotBecomeUserListStatus() {
        val media = SeanimeJson.media(JSONObject("""{"id":42,"status":"FINISHED","title":{"english":null,"romaji":null}}"""))
        assertEquals("", media.status)
        assertEquals("Untitled", media.title)
    }

}
