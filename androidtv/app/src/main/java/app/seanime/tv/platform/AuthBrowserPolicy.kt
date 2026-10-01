package app.seanime.tv.platform

import java.net.URI

/** Provider-owned web content is isolated from the native TV application's presentation. */
object AuthBrowserPolicy {
    val providerHosts = setOf("anilist.co", "myanimelist.net", "www.myanimelist.net")
    fun isProviderUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host?.lowercase() in providerHosts && uri.rawUserInfo == null && uri.port in setOf(-1, 443)
    }
}
