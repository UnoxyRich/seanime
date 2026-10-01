package app.seanime.tv.ui

import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import app.seanime.tv.data.jsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AutoDownloaderBatchTest {
    @Test fun sharedPreferencesKeepEachLongMediaIdentityReleaseTitleAndFolder() {
        val shared = rule(1).copy(enabled = false, profileId = 7, releaseGroups = listOf("Owned"), resolutions = listOf("1080p"), includeTerms = listOf("dual"))
        val entry = AutoBatchEntry(9_007_199_254_740_991L, "Display", "Release name", "/library/Second")
        val result = autoBatchRule(shared, entry)
        assertEquals(0, result.id); assertEquals(entry.mediaId, result.mediaId); assertEquals(entry.releaseTitle, result.title); assertEquals(entry.destination, result.destination)
        assertEquals(shared.releaseGroups, result.releaseGroups); assertEquals(7, result.profileId); assertFalse(result.enabled)
        val payload = AutoDownloaderPayload.ruleBody(result)
        assertEquals(setOf("rule"), payload.keys().asSequence().toSet())
        assertEquals(entry.mediaId, payload.getJSONObject("rule").getLong("mediaId"))
        assertEquals("recent", payload.getJSONObject("rule").getString("episodeType"))
        assertEquals(entry.mediaId, AutoRule.from(payload.getJSONObject("rule")).mediaId)
    }

    @Test fun partialCreationRetriesOnlyRejectedEntriesAfterFreshReconciliation() = runBlocking {
        fixture { repo, server ->
            server.createFailures[2L] = 400
            val desired = listOf(rule(1), rule(2), rule(3))
            val first = createNativeAutoBatch(repo, desired)
            assertEquals(AutoBatchState.SAVED, first[1L]?.state)
            assertEquals(AutoBatchState.FAILED, first[2L]?.state)
            assertEquals(AutoBatchState.SAVED, first[3L]?.state)
            val second = createNativeAutoBatch(repo, desired, first)
            assertTrue(second.values.all { it.complete })
            assertEquals(listOf(1L, 2L, 3L, 2L), server.createdMedia)
            server.requests.forEachIndexed { index, request ->
                if (request.method == "POST" && request.path == "/api/v1/auto-downloader/rule")
                    assertEquals("/api/v1/auto-downloader/rules", server.requests[index - 1].path)
            }
        }
    }

    @Test fun serverErrorAfterPersistIsReconciledWithoutADuplicatePost() = runBlocking {
        fixture { repo, server ->
            server.createFailures[1L] = 503; server.persistOnFailure += 1L
            val desired = listOf(rule(1))
            val result = createNativeAutoBatch(repo, desired)
            assertEquals(AutoBatchState.EXISTS, result[1L]?.state)
            assertTrue(createNativeAutoBatch(repo, desired, result)[1L]!!.complete)
            assertEquals(listOf(1L), server.createdMedia)
        }
    }

    @Test fun uncertainRequestIsNotResentWhenCurrentReadIsStillEmpty() = runBlocking {
        fixture { repo, server ->
            server.createFailures[1L] = 503
            val desired = listOf(rule(1))
            val first = createNativeAutoBatch(repo, desired)
            assertEquals(AutoBatchState.UNCERTAIN, first[1L]?.state)
            val retry = createNativeAutoBatch(repo, desired, first)
            assertEquals(AutoBatchState.UNCERTAIN, retry[1L]?.state)
            assertEquals(listOf(1L), server.createdMedia)
            server.rules[9] = rule(1).copy(id = 9)
            assertEquals(AutoBatchState.EXISTS, createNativeAutoBatch(repo, desired, retry)[1L]?.state)
            assertEquals(listOf(1L), server.createdMedia)
        }
    }

    @Test fun existingDifferentRuleAndInvalidBatchCannotCreateDuplicates() = runBlocking {
        fixture { repo, server ->
            server.rules[7] = rule(1).copy(id = 7, destination = "/library/Other")
            assertEquals(AutoBatchState.CONFLICT, createNativeAutoBatch(repo, listOf(rule(1)))[1L]?.state)
            assertTrue(runCatching { createNativeAutoBatch(repo, listOf(rule(2), rule(2))) }.isFailure)
            assertTrue(runCatching { createNativeAutoBatch(repo, listOf(rule(2).copy(destination = "relative"))) }.isFailure)
            assertTrue(server.createdMedia.isEmpty())
        }
    }

    @Test fun finishedCandidatesUseActualAiringStatusAndPreserveLongIdentities() = runBlocking {
        fixture { repo, server ->
            val highId = 4_294_967_399L
            server.rules[1] = rule(highId).copy(id = 1); server.rules[2] = rule(2).copy(id = 2); server.rules[3] = rule(3).copy(id = 3)
            server.airing[highId] = "FINISHED"; server.airing[2L] = "RELEASING"; server.airing[3L] = "NOT_YET_RELEASED"
            assertEquals(listOf(1), finishedAutoRuleCandidates(repo).map { it.id })
            assertTrue(server.requests.any { it.method == "POST" && it.path == "/api/v1/anilist/collection/raw" })
            assertTrue(server.deletedIds.isEmpty())
        }
    }

    @Test fun cleanupReportsPartialFailureAndRetriesOnlyExplicitRemainingIds() = runBlocking {
        fixture { repo, server ->
            server.rules[1] = rule(1).copy(id = 1); server.rules[2] = rule(2).copy(id = 2); server.rules[3] = rule(3).copy(id = 3)
            server.airing[1L] = "FINISHED"; server.airing[2L] = "FINISHED"; server.airing[3L] = "RELEASING"
            val confirmed = finishedAutoRuleCandidates(repo)
            server.falseDeleteOnce += 1
            val first = removeFinishedAutoRules(repo, confirmed)
            assertEquals(setOf(2), first.removed); assertEquals(setOf(1), first.errors.keys)
            val second = removeFinishedAutoRules(repo, confirmed, first.removed)
            assertEquals(setOf(1, 2), second.removed); assertTrue(second.errors.isEmpty())
            assertEquals(listOf(1, 2, 1), server.deletedIds)
            assertEquals(setOf(3), server.rules.keys)
            assertTrue(server.requests.none { it.path.endsWith("/-1") })
        }
    }

    @Test fun changedRuleOrAiringStatusAndUnconfirmedReadbackBlockCleanupSuccess() = runBlocking {
        fixture { repo, server ->
            server.rules[1] = rule(1).copy(id = 1); server.airing[1L] = "FINISHED"
            val confirmed = finishedAutoRuleCandidates(repo)
            server.rules[1] = server.rules.getValue(1).copy(title = "Changed elsewhere")
            assertEquals(setOf(1), removeFinishedAutoRules(repo, confirmed).errors.keys)
            assertTrue(server.deletedIds.isEmpty())
            server.rules[1] = confirmed.single(); server.airing[1L] = "RELEASING"
            assertEquals(setOf(1), removeFinishedAutoRules(repo, confirmed).errors.keys)
            assertTrue(server.deletedIds.isEmpty())
            server.airing[1L] = "FINISHED"; server.keepAfterTrueDelete += 1
            val result = removeFinishedAutoRules(repo, confirmed)
            assertTrue(result.removed.isEmpty()); assertEquals(setOf(1), result.errors.keys)
        }
    }

    private fun rule(mediaId: Long) = AutoRule(mediaId = mediaId, title = "Owned $mediaId", destination = "/library/$mediaId")
    private suspend fun fixture(block: suspend (SeanimeRepository, Fixture) -> Unit) {
        val fixture = Fixture()
        MockWebServer().use { server ->
            server.dispatcher = fixture
            SeanimeApiClient(server.url("/").toString()).use { api -> block(SeanimeRepository(api), fixture) }
        }
    }

    private data class Request(val method: String, val path: String)
    private class Fixture : Dispatcher() {
        val rules = linkedMapOf<Int, AutoRule>()
        val airing = mutableMapOf<Long, String>()
        val createFailures = mutableMapOf<Long, Int>()
        val persistOnFailure = mutableSetOf<Long>()
        val falseDeleteOnce = mutableSetOf<Int>()
        val keepAfterTrueDelete = mutableSetOf<Int>()
        val createdMedia = mutableListOf<Long>()
        val deletedIds = mutableListOf<Int>()
        val requests = mutableListOf<Request>()
        @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty(); requests += Request(request.method.orEmpty(), path)
            return when {
                path == "/api/v1/auto-downloader/rules" -> data(JSONArray(rules.values.map { it.payload() }))
                path == "/api/v1/auto-downloader/rule" && request.method == "POST" -> {
                    val desired = AutoRule.from(JSONObject(request.body.readUtf8()).getJSONObject("rule")); createdMedia += desired.mediaId
                    val failure = createFailures.remove(desired.mediaId)
                    val created = desired.copy(id = (rules.keys.maxOrNull() ?: 0) + 1)
                    if (failure == null || desired.mediaId in persistOnFailure) rules[created.id] = created
                    if (failure == null) data(created.payload()) else MockResponse().setResponseCode(failure).setBody("""{"error":"Owned create failure"}""")
                }
                path == "/api/v1/anilist/collection/raw" -> data(jsonObject("MediaListCollection" to jsonObject("lists" to JSONArray().put(jsonObject("entries" to
                    JSONArray(airing.map { (id, status) -> jsonObject("status" to "CURRENT", "media" to jsonObject("id" to id, "title" to "Owned $id", "status" to status)) }))))))
                path.startsWith("/api/v1/auto-downloader/rule/") && request.method == "DELETE" -> {
                    val id = path.substringAfterLast('/').toInt(); deletedIds += id
                    if (falseDeleteOnce.remove(id)) data(false) else { if (id !in keepAfterTrueDelete) rules.remove(id); data(true) }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        private fun data(value: Any) = MockResponse().setBody(jsonObject("data" to value).toString())
    }
}
