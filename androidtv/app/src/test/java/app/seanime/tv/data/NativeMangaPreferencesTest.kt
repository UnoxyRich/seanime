package app.seanime.tv.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMangaPreferencesTest {
    @Test fun languageAndScanlatorPatchesNeverOverwriteEachOtherOrSelectedProvider() {
        val language = nativeMangaFilterPayload("source-a", NativeMangaFilterEdit.Language("fr"))
        assertEquals(setOf("filter"), language.keys().asSequence().toSet())
        assertEquals(setOf("provider", "language"), language.getJSONObject("filter").keys().asSequence().toSet())
        assertEquals("fr", language.getJSONObject("filter").getString("language"))
        val scanlators = nativeMangaFilterPayload("source-a", NativeMangaFilterEdit.Scanlators(listOf("Group A", "Group B")))
        assertEquals(setOf("provider", "scanlators"), scanlators.getJSONObject("filter").keys().asSequence().toSet())
        assertEquals(2, scanlators.getJSONObject("filter").getJSONArray("scanlators").length())
        assertEquals("", nativeMangaFilterPayload("source-a", NativeMangaFilterEdit.Language("")).getJSONObject("filter").getString("language"))
        assertEquals(0, nativeMangaFilterPayload("source-a", NativeMangaFilterEdit.Scanlators(emptyList())).getJSONObject("filter").getJSONArray("scanlators").length())
    }

    @Test fun preferenceParsingKeepsEachProviderAndFilterMatchesActualChapterMetadata() {
        val preferences = parseNativeMangaPreferences(entry())
        assertEquals("source-a", preferences.provider)
        assertEquals(NativeMangaSourceFilter("fr", listOf("Group B")), preferences.filters["source-b"])
        val filter = preferences.filters.getValue("source-a")
        fun chapter(language: String, group: String) = MangaChapter("chapter", "Fixture", "1", "source-a", jsonObject("language" to language, "scanlator" to group))
        assertTrue(filter.accepts(chapter("en", "Group A")))
        assertFalse(filter.accepts(chapter("fr", "Group A")))
        assertFalse(filter.accepts(chapter("en", "Group B")))
        assertTrue(NativeMangaSourceFilter().accepts(chapter("", "")))
    }

    @Test fun invalidAndDownloadedProvidersCannotWriteSourcePreferences() {
        for (provider in listOf("", "__downloaded", "x".repeat(201)))
            assertTrue(runCatching { nativeMangaFilterPayload(provider, NativeMangaFilterEdit.Language("en")) }.isFailure)
        assertTrue(runCatching { nativeMangaFilterPayload("source-a", NativeMangaFilterEdit.Language("x".repeat(51))) }.isFailure)
        assertTrue(runCatching { nativeMangaFilterPayload("source-a", NativeMangaFilterEdit.Scanlators(List(21) { "Group $it" })) }.isFailure)
    }

    @Test fun sparseFilterRequestUsesExactRouteAndReturnsCurrentUnrelatedPreferences() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(envelope(jsonObject("entries" to jsonObject("42" to entry()))))
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"Retry fixture"}"""))
            server.enqueue(envelope(entry()))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val repo = SeanimeRepository(api)
                assertEquals("en", loadNativeMangaPreferences(repo, 42).filters.getValue("source-a").language)
                val edit = NativeMangaFilterEdit.Scanlators(listOf("Group A"))
                assertTrue(runCatching { saveNativeMangaFilter(repo, 42, "source-a", edit) }.isFailure)
                assertEquals("fr", saveNativeMangaFilter(repo, 42, "source-a", edit).filters.getValue("source-b").language)
            }
            assertEquals("/api/v1/manga/preferences", server.takeRequest().path)
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("PATCH", request.method); assertEquals("/api/v1/manga/preferences/42", request.path)
                val filter = JSONObject(request.body.readUtf8()).getJSONObject("filter")
                assertEquals(setOf("provider", "scanlators"), filter.keys().asSequence().toSet())
            }
        }
    }

    @Test fun preferenceEventsOnlyRefreshTheirMatchingTitle() {
        assertTrue(nativeMangaPreferenceEventAffects(jsonObject("mediaIds" to JSONArray().put(42).put(7)), 42))
        assertFalse(nativeMangaPreferenceEventAffects(jsonObject("mediaIds" to JSONArray().put(7)), 42))
        assertFalse(nativeMangaPreferenceEventAffects(null, 42))
    }

    @Test fun readerSettingsMigrateGlobalDefaultsWithoutOverwritingAnotherTitleOrResumeKeys() {
        val storage = mutableMapOf("rtl" to true, "double" to false, "coverAlone" to true,
            "media:7:rtl" to false, "media:7:double" to true, "media:7:coverAlone" to false)
        val original = storage.toMap()
        assertEquals(NativeMangaReaderSettings(true, false, true), loadNativeMangaReaderSettings(42, storage::get))
        assertEquals(NativeMangaReaderSettings(false, true, false), loadNativeMangaReaderSettings(7, storage::get))
        val changed = NativeMangaReaderSettings(false, true, true)
        storage.putAll(changed.storedValues(42))
        assertEquals(changed, loadNativeMangaReaderSettings(42, storage::get))
        original.forEach { (key, value) -> assertEquals("Preserve $key", value, storage[key]) }
        assertEquals(setOf("media:42:rtl", "media:42:double", "media:42:coverAlone"), changed.storedValues(42).keys)
        assertEquals(NativeMangaReaderSettings(), loadNativeMangaReaderSettings(99) { null })
    }

    @Test fun freshReaderDefaultsToTwoPagesWithoutChangingDirectionOrCoverBehavior() {
        val settings = loadNativeMangaReaderSettings(42) { null }
        assertEquals(NativeMangaReaderSettings(), settings)
        assertTrue(settings.doublePage)
        assertFalse(settings.rtl)
        assertFalse(settings.coverAlone)
    }

    @Test fun savedSinglePageChoiceWinsOverNewDefaultAtBothMigrationLevels() {
        val legacy = mapOf("double" to false)
        assertFalse(loadNativeMangaReaderSettings(42, legacy::get).doublePage)
        val perTitle = mapOf("double" to true, "media:42:double" to false)
        assertFalse(loadNativeMangaReaderSettings(42, perTitle::get).doublePage)
        assertTrue(loadNativeMangaReaderSettings(99, perTitle::get).doublePage)
        val partial = mapOf("media:42:rtl" to true, "media:42:coverAlone" to true)
        assertEquals(NativeMangaReaderSettings(true, true, true), loadNativeMangaReaderSettings(42, partial::get))
    }

    private fun entry() = jsonObject("provider" to "source-a", "filters" to jsonObject(
        "source-a" to jsonObject("language" to "en", "scanlators" to JSONArray().put("Group A")),
        "source-b" to jsonObject("language" to "fr", "scanlators" to JSONArray().put("Group B"))))
    private fun envelope(value: Any) = MockResponse().setBody(jsonObject("data" to value).toString())
}
