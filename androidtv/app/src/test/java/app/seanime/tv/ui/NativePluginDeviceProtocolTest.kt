package app.seanime.tv.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePluginDeviceProtocolTest {
    @Test fun viewportRepliesWithNativeLogicalDimensionsToOnlyTheRequester() {
        val sent = mutableListOf<JSONObject>()
        var size = 960 to 540
        val protocol = NativePluginDeviceProtocol({ size }, { id, type, payload -> sent += pluginEventEnvelope(id, type, payload) },
            { fail("No clipboard request") }, { fail(it) })
        assertTrue(protocol.receive(pluginEventEnvelope("size-plugin", "dom:get-viewport-size")))
        assertEquals("size-plugin", sent.single().getString("extensionId"))
        assertEquals("dom:viewport-size", sent.single().getString("type"))
        assertEquals(960, sent.single().getJSONObject("payload").getInt("width"))
        assertEquals(540, sent.single().getJSONObject("payload").getInt("height"))
        size = 1280 to 720
        protocol.viewportChanged()
        assertEquals("", sent.last().getString("extensionId"))
        assertEquals(1280, sent.last().getJSONObject("payload").getInt("width"))
        protocol.viewportChanged("new-plugin")
        assertEquals("new-plugin", sent.last().getString("extensionId"))
        assertFalse(protocol.receive(pluginEventEnvelope("size-plugin", "webview:iframe")))
    }

    @Test fun clipboardRequestsAreBoundedTypedAndDoNotPretendTheCopyHappened() {
        val pending = mutableListOf<NativePluginClipboardRequest>()
        val errors = mutableListOf<String>()
        val protocol = NativePluginDeviceProtocol({ 1 to 1 }, { _, _, _ -> fail("The wire contract has no clipboard acknowledgement") },
            pending::add, errors::add)
        protocol.receive(pluginEventEnvelope("copy-plugin", "dom:clipboard:write", JSONObject().put("text", "Exact text\n第二行")))
        protocol.receive(pluginEventEnvelope("copy-plugin", "dom:clipboard:write", JSONObject().put("text", "")))
        protocol.receive(pluginEventEnvelope("copy-plugin", "dom:clipboard:write", JSONObject().put("text", 7)))
        protocol.receive(pluginEventEnvelope("copy-plugin", "dom:clipboard:write", JSONObject().put("text", "x".repeat(65_537))))
        assertEquals(listOf(NativePluginClipboardRequest("copy-plugin", "Exact text\n第二行"), NativePluginClipboardRequest("copy-plugin", "")), pending)
        assertEquals(2, errors.size)
    }

    @Test fun domQueriesResolveTruthfulEmptyResultsWithoutFabricatedElementsOrMutationSuccess() {
        val sent = mutableListOf<JSONObject>()
        val notices = mutableListOf<String>()
        val protocol = NativePluginDeviceProtocol({ 1 to 1 }, { id, type, payload -> sent += pluginEventEnvelope(id, type, payload) },
            { fail("No copy") }, notices::add)
        protocol.receive(pluginEventEnvelope("dom-plugin", "dom:query", JSONObject().put("requestId", "many").put("selector", ".anime-card")))
        protocol.receive(pluginEventEnvelope("dom-plugin", "dom:query-one", JSONObject().put("requestId", "one").put("selector", "body")))
        protocol.receive(pluginEventEnvelope("dom-plugin", "dom:observe-in-view", JSONObject().put("observerId", "watch")))
        assertEquals("many", sent[0].getJSONObject("payload").getString("requestId"))
        assertEquals(0, sent[0].getJSONObject("payload").getJSONArray("elements").length())
        assertEquals("one", sent[1].getJSONObject("payload").getString("requestId"))
        assertTrue(sent[1].getJSONObject("payload").isNull("element"))
        assertEquals("watch", sent[2].getJSONObject("payload").getString("observerId"))
        assertEquals(1, notices.size)
        assertTrue(sent.all { it.getString("extensionId") == "dom-plugin" })
        assertTrue(protocol.receive(pluginEventEnvelope("dom-plugin", "dom:stop-observe")))
        assertEquals(3, sent.size)
        assertFalse(protocol.receive(pluginEventEnvelope("dom-plugin", "dom:create")))
        assertFalse(protocol.receive(pluginEventEnvelope("dom-plugin", "dom:manipulate")))
    }
}
