package app.seanime.tv.ui

import app.seanime.tv.data.AnimeDiscoveryFilters
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePluginNavigationTest {
    @Test fun routesKeepCustomSourceMediaIdsLosslessAndRejectUnsafeIntegers() {
        listOf(2_147_483_648L, 4_294_967_297L, 9_007_199_254_740_991L).forEach { id ->
            assertEquals(id, resolveNativePluginDestination("/entry?id=$id").animeId)
            assertEquals(id, resolveNativePluginDestination("/manga/entry?id=$id").mangaId)
        }
        listOf("9007199254740992", "9223372036854775808", "1.5", "1e3", "-1").forEach { id ->
            assertTrue(id, runCatching { resolveNativePluginDestination("/entry?id=$id") }.isFailure)
        }
        assertEquals(3, resolveNativePluginDestination("/entry?id=4294967297&tab=onlinestream&episode=3").episode)
    }
    @Test fun supportedRoutesSelectActualNativeSubpages() {
        assertEquals("debrid", resolveNativePluginDestination("/debrid").downloadTab)
        assertEquals("torrent", resolveNativePluginDestination("/torrent-client").downloadTab)
        assertEquals("auto", resolveNativePluginDestination("/auto-downloader").downloadTab)
        assertEquals("Schedule", resolveNativePluginDestination("/schedule").libraryTab)
        assertEquals("Scan reports", resolveNativePluginDestination("/scan-summaries").libraryTab)
        assertEquals("torrent", resolveNativePluginDestination("/settings?tab=torrent-client").settingsSection)
        assertEquals("debrid", resolveNativePluginDestination("/settings?tab=debrid").settingsSection)
        assertTrue(resolveNativePluginDestination("/extensions/playground").playground)
        assertTrue(resolveNativePluginDestination("/extensions?tab=marketplace").marketplace)
        assertEquals("plugin", resolveNativePluginDestination("/extensions?tab=marketplace&type=plugin").marketplaceType)
        assertTrue(resolveNativePluginDestination("/custom-sources").customSources)
        assertEquals("owned-source", resolveNativePluginDestination("/custom-sources?provider=owned-source").customSourceProvider)
        assertEquals(42L, resolveNativePluginDestination("/manga/entry?id=42").mangaId)
        assertEquals("search", resolveNativePluginDestination("/discover?type=manga").mangaMode)
        val episode = resolveNativePluginDestination("/entry?id=21&tab=onlinestream&episode=3")
        assertEquals(21L, episode.animeId)
        assertEquals("Online", episode.sourceMode)
        assertEquals(3, episode.episode)
    }

    @Test fun discoveryLocationRoundTripsFiltersAndExactResultPage() {
        val filters = AnimeDiscoveryFilters(search = "Space opera", sort = "SCORE_DESC", statuses = listOf("FINISHED", "RELEASING"),
            genres = listOf("Action", "Sci-Fi"), tags = listOf("Space", "Time Travel"), averageScoreGreater = 79,
            season = "SPRING", seasonYear = 2026, format = "TV", isAdult = true)
        val location = nativeDiscoveryLocation(filters, 2)
        val target = resolveNativePluginDestination(location.path)
        assertEquals(filters, target.discovery)
        assertEquals(2, target.discoveryPage)
        assertEquals("/search", target.location.pathname)
        assertTrue(target.location.query.contains("query=Space+opera"))
        assertEquals(AnimeDiscoveryFilters(), resolveNativePluginDestination("/discover").discovery)
    }

    @Test fun mangaFiltersAndAiringWindowRoundTripToTheirActualNativeScreens() {
        val filters = AnimeDiscoveryFilters(search = "Manga", sort = "CHAPTERS_DESC", genres = listOf("Action"), averageScoreGreater = 8,
            seasonYear = 2024, format = "ONE_SHOT", manga = true, countryOfOrigin = "KR")
        val manga = resolveNativePluginDestination(nativeDiscoveryLocation(filters, 3).path)
        assertEquals(TvFeature.MANGA, manga.feature)
        assertEquals(filters, manga.discovery)
        assertEquals(3, manga.discoveryPage)
        val airing = resolveNativePluginDestination("/discover?type=schedule&from=1700000000&until=1701209600&upcoming=false&page=2")
        assertEquals(1700000000L, airing.airing!!.from)
        assertFalse(airing.airing.upcoming)
        assertEquals(2, airing.discoveryPage)
        assertNotNull(resolveNativePluginDestination("/discover?type=schedule").airing)
    }

    @Test fun unsupportedOrAmbiguousUrlsCannotBecomeExternalOrBroadNavigation() {
        listOf("https://example.com/entry?id=1", "//example.com/entry?id=1", "/%65ntry?id=1", "/entry-lookalike?id=1",
            "/entry?id=-1", "/entry?id=1&id=2", "/entry?id=1&download=true", "/entry?id=1&tab=plugin-anything",
            "/entry?id=1#fragment", "/webview?extensionId=plugin", "/settings?tab=ui", "/extensions?tab=marketplace&type=unknown",
            "/search?type=manga&season=SPRING", "/search?type=manga&format=NOVEL", "/search?countryOfOrigin=JP", "/search?format=MUSIC",
            "/discover?type=schedule&from=1700000000", "/discover?type=schedule&upcoming=maybe",
            "/search?page=oops", "/search?page=", "/entry?id=1&tab=onlinestream&episode=oops").forEach { path ->
            assertTrue("Must reject $path", runCatching { resolveNativePluginDestination(path) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun globalProtocolBroadcastsRealChangesAndTargetsCurrentReplyToRequestingPlugin() {
        var current = NativeScreenLocation("/settings", mapOf("tab" to "debrid"))
        val sent = mutableListOf<JSONObject>()
        val targets = mutableListOf<NativePluginDestination>()
        var reloads = 0
        val failures = mutableListOf<String>()
        val protocol = NativePluginScreenProtocol({ current }, { id, type, payload -> sent += pluginEventEnvelope(id, type, payload) },
            { targets += it }, { reloads++ }, { failures += it })
        protocol.changed()
        assertEquals("", sent.last().getString("extensionId"))
        assertEquals("/settings", sent.last().getJSONObject("payload").getString("pathname"))
        assertEquals("?tab=debrid", sent.last().getJSONObject("payload").getString("query"))
        protocol.receive(pluginEventEnvelope("remote-plugin", "screen:get-current"))
        assertEquals("remote-plugin", sent.last().getString("extensionId"))
        protocol.receive(pluginEventEnvelope("", "screen:navigate-to", JSONObject().put("path", "/manga/entry?id=7")))
        assertEquals(7L, targets.single().mangaId)
        current = targets.single().location
        protocol.receive(pluginEventEnvelope("other-plugin", "screen:get-current"))
        assertEquals("/manga/entry", sent.last().getJSONObject("payload").getString("pathname"))
        assertEquals("?id=7", sent.last().getJSONObject("payload").getString("query"))
        protocol.receive(pluginEventEnvelope("other-plugin", "screen:reload"))
        assertEquals(1, reloads)
        assertFalse(sent.any { it.getString("type") == "tray:render" })
        assertTrue(failures.isEmpty())
    }

    @Test fun repeatedUnsupportedCapabilitiesDoNotTrapTheRemoteWithModalNotices() {
        val failures = mutableListOf<String>()
        val protocol = NativePluginScreenProtocol({ NativeScreenLocation("/") }, { _, _, _ -> fail("No fabricated success reply") },
            { fail("No navigation") }, { fail("No reload") }, { failures += it })
        repeat(20) { protocol.receive(pluginEventEnvelope("plugin", "dom:query", JSONObject())) }
        protocol.receive(pluginEventEnvelope("plugin", "dom:observe", JSONObject()))
        assertEquals(1, failures.size)
        repeat(20) { protocol.receive(pluginEventEnvelope("plugin", "webview:iframe", JSONObject())) }
        assertEquals(2, failures.size)
        protocol.receive(pluginEventEnvelope("another-plugin", "dom:query", JSONObject()))
        assertEquals(3, failures.size)
    }
}
