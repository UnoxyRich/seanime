package app.seanime.tv.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeListEntryEditorTest {
    @Test fun unchangedDraftOmitsScoreDatesProgressStatusAndOpaqueFields() {
        val snapshot = snapshot(score = 85.5)
        assertEquals("8.55", snapshot.scoreText)
        val payload = nativeListEntryPayload(snapshot, NativeListEntryDraft.from(snapshot))
        assertEquals(setOf("mediaId", "type"), payload.keys().asSequence().toSet())
        assertEquals("preserve", snapshot.listData!!.getString("providerField"))
    }

    @Test fun statusOnlyChangeDoesNotResetMissingScoreOrRewriteProgressAndDates() {
        val snapshot = snapshot(score = null)
        val payload = nativeListEntryPayload(snapshot, NativeListEntryDraft.from(snapshot).copy(status = "PAUSED"))
        assertEquals(setOf("mediaId", "type", "status"), payload.keys().asSequence().toSet())
        assertEquals("PAUSED", payload.getString("status"))
    }

    @Test fun scoreConversionIsExactAndExplicitClearIsZero() {
        assertEquals(95, nativeScoreRaw("9.5"))
        assertEquals(95, nativeScoreRaw("9.50"))
        assertEquals(1, nativeScoreRaw("0.1"))
        assertEquals(100, nativeScoreRaw("10"))
        assertEquals(0, nativeScoreRaw(""))
        for (invalid in listOf("9.55", "10.1", "-1", "NaN", "Infinity")) assertTrue(runCatching { nativeScoreRaw(invalid) }.isFailure)
        val snapshot = snapshot(score = 95)
        assertEquals("9.5", snapshot.scoreText)
        val cleared = nativeListEntryPayload(snapshot, NativeListEntryDraft.from(snapshot).copy(score = ""))
        assertEquals(0, cleared.getInt("score"))
        assertEquals(setOf("mediaId", "type", "score"), cleared.keys().asSequence().toSet())
    }

    @Test fun newEntryAddsPlanningWithoutInventingScoreOrDates() {
        val snapshot = snapshot().copy(listData = null)
        val payload = nativeListEntryPayload(snapshot, NativeListEntryDraft.from(snapshot))
        assertEquals("PLANNING", payload.getString("status"))
        assertEquals(setOf("mediaId", "type", "status"), payload.keys().asSequence().toSet())
        for (invalid in listOf("-1", "1.5", "99999999999999", "25")) {
            assertTrue(runCatching { nativeListEntryPayload(snapshot, NativeListEntryDraft.from(snapshot).copy(progress = invalid)) }.isFailure)
        }
    }

    @Test fun sparseMangaSaveUsesExactExistingEndpointAndRetainsErrors() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"Try again"}"""))
            server.enqueue(envelope(true))
            val snapshot = snapshot(manga = true)
            val draft = NativeListEntryDraft.from(snapshot).copy(score = "7.5")
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val repo = SeanimeRepository(api)
                assertTrue(runCatching { saveNativeListEntry(repo, snapshot, draft) }.isFailure)
                assertTrue(saveNativeListEntry(repo, snapshot, draft))
            }
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/api/v1/anilist/list-entry", request.path)
                val body = JSONObject(request.body.readUtf8())
                assertEquals(setOf("mediaId", "type", "score"), body.keys().asSequence().toSet())
                assertEquals("manga", body.getString("type")); assertEquals(75, body.getInt("score"))
            }
        }
    }

    @Test fun removalReloadsActualAnimeAndMangaEntryBeforeExactDelete() = runBlocking {
        for (manga in listOf(false, true)) MockWebServer().use { server ->
            server.enqueue(envelope(jsonObject("isOffline" to false)))
            server.enqueue(envelope(jsonObject("media" to jsonObject("id" to 42), "listData" to jsonObject("status" to "CURRENT"))))
            server.enqueue(envelope(true))
            SeanimeApiClient(server.url("/").toString()).use { api -> removeNativeListEntry(SeanimeRepository(api), 42, manga) }
            assertEquals("/api/v1/status", server.takeRequest().path)
            assertEquals(if (manga) "/api/v1/manga/entry/42" else "/api/v1/library/anime-entry/42", server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            val body = JSONObject(request.body.readUtf8())
            assertEquals(setOf("mediaId", "type"), body.keys().asSequence().toSet())
            assertEquals(42, body.getInt("mediaId")); assertEquals(if (manga) "manga" else "anime", body.getString("type"))
        }
    }

    @Test fun offlineMissingAndMismatchedEntriesCannotBeRemoved() = runBlocking {
        for ((offline, id, exists) in listOf(Triple(true, 42, true), Triple(false, 42, false), Triple(false, 43, true))) {
            MockWebServer().use { server ->
                server.enqueue(envelope(jsonObject("isOffline" to offline)))
                server.enqueue(envelope(jsonObject("media" to jsonObject("id" to id)).apply {
                    if (exists) put("listData", jsonObject("status" to "CURRENT"))
                }))
                SeanimeApiClient(server.url("/").toString()).use { api ->
                    assertTrue(runCatching { removeNativeListEntry(SeanimeRepository(api), 42, false) }.isFailure)
                }
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun rawEntryLookupPreservesNullableDateComponentsForAnimeAndManga() = runBlocking {
        for (manga in listOf(false, true)) MockWebServer().use { server ->
            server.enqueue(envelope(jsonObject("user" to jsonObject("isSimulated" to false))))
            server.enqueue(envelope(jsonObject("media" to jsonObject("id" to 42), "listData" to jsonObject("status" to "CURRENT",
                "startedAt" to "2023-11-30T00:00:00Z")))) // Lossy detail representation must never supply the date.
            server.enqueue(envelope(rawCollection(jsonObject("media" to jsonObject("id" to 7), "startedAt" to NativeListDate(1999, 1, 1).payload()),
                jsonObject("media" to jsonObject("id" to 42), "status" to "CURRENT", "score" to 95,
                    "startedAt" to jsonObject("year" to 2024, "month" to null, "day" to 29), "completedAt" to JSONObject()))))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val current = loadNativeListEntry(SeanimeRepository(api), 42, manga)
                assertEquals(NativeListDate(2024, null, 29), current.startedAt)
                assertEquals(NativeListDate(), current.completedAt)
                assertEquals("2024-??-29", current.startedAt.label)
                assertEquals(setOf("mediaId", "type"), nativeListEntryPayload(current, NativeListEntryDraft.from(current)).keys().asSequence().toSet())
            }
            server.takeRequest(); server.takeRequest()
            assertEquals(if (manga) "/api/v1/manga/anilist/collection/raw" else "/api/v1/anilist/collection/raw", server.takeRequest().path)
        }
    }

    @Test fun missingRawEntryCannotSilentlyReplaceKnownDatesWithEmptyDates() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(envelope(jsonObject("isOffline" to false)))
            server.enqueue(envelope(jsonObject("media" to jsonObject("id" to 42), "listData" to jsonObject("status" to "CURRENT"))))
            server.enqueue(envelope(rawCollection(jsonObject("media" to jsonObject("id" to 7)))))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                assertTrue(runCatching { loadNativeListEntry(SeanimeRepository(api), 42, false) }.isFailure)
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun datesValidateRealCalendarDaysAndRetainPermittedUnknownComponents() {
        for (date in listOf(NativeListDate(2024, 2, 29), NativeListDate(2000, 2, 29), NativeListDate(9999, 12, 31)))
            validateNativeListDate(date, allowPartial = false)
        for (date in listOf(NativeListDate(2023, 2, 29), NativeListDate(1900, 2, 29), NativeListDate(2024, 4, 31),
            NativeListDate(0, 1, 1), NativeListDate(10000, 1, 1), NativeListDate(2024, 13, 1), NativeListDate(2024, 1, 0)))
            assertTrue("Must reject $date", runCatching { validateNativeListDate(date, allowPartial = false) }.isFailure)
        for (date in listOf(NativeListDate(), NativeListDate(2024), NativeListDate(null, 2, 29), NativeListDate(null, null, 31)))
            validateNativeListDate(date, allowPartial = true)
        assertTrue(runCatching { validateNativeListDate(NativeListDate(null, 2, 30), allowPartial = true) }.isFailure)
    }

    @Test fun fullDateEditOmitsEveryUntouchedFieldAndKeepsPartialCompletion() {
        val current = snapshot().copy(startedAt = NativeListDate(2024), completedAt = NativeListDate(null, 3, 8))
        val edited = NativeListEntryDraft.from(current).copy(startedAt = NativeListDate(2024, 2, 29))
        val payload = nativeListEntryPayload(current, edited)
        assertEquals(setOf("mediaId", "type", "startedAt"), payload.keys().asSequence().toSet())
        val date = payload.getJSONObject("startedAt")
        assertEquals(setOf("year", "month", "day"), date.keys().asSequence().toSet())
        assertEquals(2024, date.getInt("year")); assertEquals(2, date.getInt("month")); assertEquals(29, date.getInt("day"))
        assertEquals(NativeListDate(null, 3, 8), edited.completedAt)
    }

    @Test fun localDateClearingIsAnExplicitObjectWithNullComponentsAndLiveEditsRequireFullDates() {
        val original = snapshot().copy(startedAt = NativeListDate(2024, 2, 29), completedAt = NativeListDate(2025, 1, 1))
        val clear = NativeListEntryDraft.from(original).copy(startedAt = NativeListDate(), completedAt = NativeListDate(2025))
        for (current in listOf(original.copy(simulated = true), original.copy(offline = true))) {
            val payload = nativeListEntryPayload(current, clear)
            val start = payload.getJSONObject("startedAt")
            assertEquals(setOf("year", "month", "day"), start.keys().asSequence().toSet())
            assertTrue(start.isNull("year") && start.isNull("month") && start.isNull("day"))
            assertEquals(2025, payload.getJSONObject("completedAt").getInt("year"))
            assertTrue(payload.getJSONObject("completedAt").isNull("month"))
            assertTrue(payload.getJSONObject("completedAt").isNull("day"))
        }
        assertTrue(runCatching { nativeListEntryPayload(original, clear) }.isFailure)
        val partial = original.copy(startedAt = NativeListDate(2024))
        assertFalse(nativeListEntryPayload(partial, NativeListEntryDraft.from(partial).copy(score = "8")).has("startedAt"))
        assertTrue(runCatching { nativeListEntryPayload(partial, NativeListEntryDraft.from(partial).copy(startedAt = NativeListDate(2025))) }.isFailure)
    }

    @Test fun modeChangeToLiveBeforeSaveBlocksLocalOnlyClearWithoutSendingMutation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(envelope(jsonObject("isOffline" to false, "user" to jsonObject("isSimulated" to false))))
            val current = snapshot().copy(simulated = true, startedAt = NativeListDate(2024, 1, 2))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                assertTrue(runCatching { saveNativeListEntry(SeanimeRepository(api), current,
                    NativeListEntryDraft.from(current).copy(startedAt = NativeListDate())) }.isFailure)
            }
            assertEquals(1, server.requestCount)
            assertEquals("/api/v1/status", server.takeRequest().path)
        }
    }

    @Test fun completeDateFailureCanRetryTheSameSparsePayloadAfterFreshModeCheck() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { attempt ->
                server.enqueue(envelope(jsonObject("isOffline" to false, "user" to jsonObject("isSimulated" to false))))
                server.enqueue(if (attempt == 0) MockResponse().setResponseCode(503).setBody("""{"error":"Try again"}""") else envelope(true))
            }
            val current = snapshot()
            val draft = NativeListEntryDraft.from(current).copy(completedAt = NativeListDate(2024, 2, 29))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val repo = SeanimeRepository(api)
                assertTrue(runCatching { saveNativeListEntry(repo, current, draft) }.isFailure)
                assertTrue(saveNativeListEntry(repo, current, draft))
            }
            repeat(2) {
                assertEquals("/api/v1/status", server.takeRequest().path)
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                val payload = JSONObject(request.body.readUtf8())
                assertEquals(setOf("mediaId", "type", "completedAt"), payload.keys().asSequence().toSet())
                assertEquals(29, payload.getJSONObject("completedAt").getInt("day"))
            }
        }
    }

    private fun snapshot(score: Number? = 95, manga: Boolean = false) = NativeListEntrySnapshot(
        MediaCard(42, "Fixture", totalEpisodes = 24, isManga = manga),
        jsonObject("status" to "CURRENT", "progress" to 5, "score" to score, "repeat" to 3,
            "startedAt" to "2024-01-02T00:00:00Z", "completedAt" to "2025-02-03T00:00:00Z", "providerField" to "preserve"), false)
    private fun envelope(data: Any) = MockResponse().setBody(jsonObject("data" to data).toString())
    private fun rawCollection(vararg entries: JSONObject) = jsonObject("MediaListCollection" to jsonObject("lists" to
        JSONArray().put(jsonObject("entries" to JSONArray(entries.toList())))))
}
