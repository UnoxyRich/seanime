package app.seanime.tv.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePluginProtocolTest {
    @Test fun handlerEnvelopeMatchesGoPluginProtocol() {
        val message = pluginEventEnvelope("my-plugin", "handler:triggered", JSONObject().put("handlerName", "save").put("event", JSONObject().put("value", "hello")))
        assertEquals(setOf("extensionId", "type", "payload"), message.keys().asSequence().toSet())
        assertEquals("my-plugin", message.getString("extensionId"))
        assertEquals("handler:triggered", message.getString("type"))
        assertEquals("save", message.getJSONObject("payload").getString("handlerName"))
        assertEquals("hello", message.getJSONObject("payload").getJSONObject("event").getString("value"))
    }
    @Test fun batchEventsKeepTheirExtensionIdentityAndOrder() {
        val first = pluginEventEnvelope("a", "tray:updated")
        val second = pluginEventEnvelope("b", "form:set-values")
        val batch = pluginEventEnvelope("a", "plugin:batch-events", JSONObject().put("events", JSONArray().put(first).put(second)))
        assertEquals(listOf("a", "b"), unpackPluginEvents(batch).map { it.text("extensionId") })
        assertEquals(listOf("tray:updated", "form:set-values"), unpackPluginEvents(batch).map { it.text("type") })
    }
    @Test fun conditionalAndNullPluginChildrenDoNotRenderAsText() {
        val nodes = pluginNodes(JSONArray().put(JSONObject.NULL).put(false).put("Visible").put(JSONObject().put("type", "button")))
        assertEquals(2, nodes.size)
        assertEquals("Visible", nodes.first())
    }
    @Test fun nestedButtonLabelCanUseAComponentTrigger() {
        val trigger = JSONObject("""{"type":"button","props":{"label":"Open settings"}}""")
        assertEquals("Open settings", pluginNodeLabel(trigger))
        assertEquals("Plugin control", pluginNodeLabel(JSONObject()))
    }
    @Test fun formPreservesBooleanAndNumberTypesAndDoesNotSubmitButtons() {
        val fields = JSONArray("""[{"name":"title","type":"input","value":"Initial"},{"name":"enabled","type":"switch","value":true},{"name":"count","type":"number"},{"name":"send","type":"submit"}]""").uiObjects()
        val data = pluginFormData("settings", fields, mapOf("settings:title" to "Changed", "settings:enabled" to false, "settings:count" to 4))
        assertEquals("Changed", data.getString("title"))
        assertFalse(data.getBoolean("enabled"))
        assertEquals(4, data.getInt("count"))
        assertFalse(data.has("send"))
        assertEquals(3, data.length())
    }
}
