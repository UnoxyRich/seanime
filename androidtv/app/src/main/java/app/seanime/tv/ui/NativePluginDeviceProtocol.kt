package app.seanime.tv.ui

import org.json.JSONArray
import org.json.JSONObject

internal data class NativePluginClipboardRequest(val extensionId: String, val text: String)

/** Native equivalents for device operations; this does not invent a browser DOM. */
internal class NativePluginDeviceProtocol(
    private val viewport: () -> Pair<Int, Int>,
    private val send: (String, String, JSONObject) -> Unit,
    private val requestCopy: (NativePluginClipboardRequest) -> Unit,
    private val unsupported: (String) -> Unit,
) {
    private val announced = mutableSetOf<String>()

    fun viewportChanged(extensionId: String = "") {
        val (width, height) = viewport()
        if (width > 0 && height > 0) send(extensionId, "dom:viewport-size", JSONObject().put("width", width).put("height", height))
    }

    fun receive(event: JSONObject): Boolean {
        val id = event.optString("extensionId")
        val type = event.optString("type")
        val payload = event.optJSONObject("payload") ?: JSONObject()
        when (type) {
            "dom:get-viewport-size" -> {
                val (width, height) = viewport()
                if (width > 0 && height > 0) send(id, "dom:viewport-size", JSONObject().put("width", width).put("height", height))
                else unsupported("The native screen is not measured yet. Retry the plugin request after it appears.")
            }
            "dom:clipboard:write" -> {
                val text = payload.opt("text") as? String
                if (text == null || text.length > MAX_CLIPBOARD_CHARACTERS) unsupported("The plugin clipboard request is invalid or exceeds 65,536 characters.")
                else requestCopy(NativePluginClipboardRequest(id, text))
            }
            "dom:query", "dom:query-one" -> {
                // Native Compose screens contain no CSS-selectable elements. Return
                // the truthful empty result so Go's request-scoped query promise
                // can resolve, rather than leaving it waiting forever.
                val request = payload.optString("requestId")
                if (request.isNotBlank()) {
                    val answer = JSONObject().put("requestId", request)
                    if (type == "dom:query") answer.put("elements", JSONArray()) else answer.put("element", JSONObject.NULL)
                    send(id, if (type == "dom:query") "dom:query-result" else "dom:query-one-result", answer)
                }
                explainNoDom(id)
            }
            "dom:observe", "dom:observe-in-view" -> {
                val observer = payload.optString("observerId")
                if (observer.isNotBlank()) send(id, "dom:observe-result", JSONObject().put("observerId", observer).put("elements", JSONArray()))
                explainNoDom(id)
            }
            "dom:stop-observe" -> Unit // No browser element observer was allocated.
            else -> return false
        }
        return true
    }

    private fun explainNoDom(id: String) {
        if (announced.add(id)) unsupported("This plugin looks for browser page elements. Native TV screens have no browser DOM, so those searches return no elements. Its native controls remain available.")
    }

    companion object { const val MAX_CLIPBOARD_CHARACTERS = 65_536 }
}
