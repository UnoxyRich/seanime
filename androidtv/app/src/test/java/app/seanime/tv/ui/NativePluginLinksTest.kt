package app.seanime.tv.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePluginLinksTest {
    @Test fun anchorItemsKeepTheirReadableLabelAndExactHandlerPayload() {
        val props = JSONObject("""{"href":"/manga/entry?id=7","items":[{"type":"text","props":{"text":"Read"}},"Fixture manga"],"onClick":"open-handler"}""")
        assertEquals("Read Fixture manga", nativePluginLinkLabel(props))
        val itemHandler = nativePluginLinkHandlerPayload("a", props)
        assertEquals(setOf("href"), itemHandler.keys().asSequence().toSet())
        assertEquals("/manga/entry?id=7", itemHandler.getString("href"))
        props.put("text", "Explicit label")
        assertEquals("Explicit label", nativePluginLinkLabel(props))
        assertEquals("Explicit label", nativePluginLinkHandlerPayload("anchor", props).getString("text"))
    }

    @Test fun rootRelativePathRelativeAndQueryLinksResolveOnlyToTypedNativeDestinations() {
        val rootRelative = nativePluginLinkDestination("/manga/entry?id=7", NativeScreenLocation("/extensions"))
        assertEquals(7L, rootRelative.mangaId)
        assertEquals(21L, nativePluginLinkDestination("entry?id=21", NativeScreenLocation("/extensions")).animeId)
        assertEquals(22L, nativePluginLinkDestination("?id=22", NativeScreenLocation("/entry", mapOf("id" to "21"))).animeId)
        listOf("//example.com/entry?id=1", "javascript:alert(1)", "https://example.com/entry?id=1", "/arbitrary-dom", "/entry?id=bad").forEach { href ->
            assertTrue(href, runCatching { nativePluginLinkDestination(href, NativeScreenLocation("/")) }.isFailure)
        }
        assertTrue(nativePluginExternalLink("https://example.com/docs"))
        assertFalse(nativePluginExternalLink("javascript:alert(1)"))
        assertFalse(nativePluginExternalLink("https://name:password@example.com/docs"))
    }
}
