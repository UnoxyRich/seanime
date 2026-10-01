package app.seanime.tv.ui

import java.net.URI
import org.json.JSONObject

internal fun nativePluginLinkLabel(props: JSONObject): String = props.text("text").ifBlank {
    pluginNodes(props.opt("items")).joinToString(" ") { pluginNodeLabel(it) }.trim()
}.ifBlank { props.text("href", "Link") }

internal fun nativePluginLinkHandlerPayload(type: String, props: JSONObject): JSONObject =
    JSONObject().put("href", props.text("href")).apply { if (type == "anchor") put("text", props.text("text")) }

internal fun nativePluginLinkDestination(href: String, current: NativeScreenLocation): NativePluginDestination {
    require(href.isNotBlank() && !href.startsWith("//") && '\\' !in href && href.none(Char::isISOControl)) { "This plugin link has no supported native destination" }
    val relative = URI(href)
    require(!relative.isAbsolute && relative.rawAuthority == null) { "This link is not an internal native screen" }
    val resolved = if (relative.rawPath.isNullOrEmpty() && relative.rawQuery != null)
        URI(current.pathname + "?" + relative.rawQuery + (relative.rawFragment?.let { "#$it" } ?: ""))
    else URI(current.path).resolve(relative)
    return resolveNativePluginDestination(resolved.normalize().toString())
}

internal fun nativePluginExternalLink(href: String): Boolean = runCatching {
    val uri = URI(href)
    uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
}.getOrDefault(false)
