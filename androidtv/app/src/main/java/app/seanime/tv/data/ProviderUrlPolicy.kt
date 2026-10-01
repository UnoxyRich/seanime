package app.seanime.tv.data

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** Only for untrusted provider results. The selected Seanime server and local media use a separate context. */
object ProviderUrlPolicy {
    fun requirePublicUrl(value: String): HttpUrl {
        require(value.isNotBlank() && value.none { it.isWhitespace() || it.isISOControl() || it == '\\' }) { "The provider returned an invalid address" }
        val raw = runCatching { URI(value) }.getOrNull()
        require(raw != null && raw.scheme?.lowercase() in setOf("http", "https") && raw.rawUserInfo == null &&
            !raw.host.isNullOrBlank() && (raw.port == -1 || raw.port in 1..65535)) { "The provider returned an unsupported address" }
        val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("The provider returned an invalid address")
        require(url.username.isEmpty() && url.password.isEmpty()) { "The provider address contains credentials" }
        requirePublicHost(url.host)
        return url
    }

    /** No DNS here: parsing is safe on the UI thread; actual lookup happens inside OkHttp's worker. */
    internal fun requirePublicHost(host: String) {
        val normalized = host.lowercase().trimEnd('.')
        require(normalized.isNotEmpty() && normalized != "localhost" &&
            !listOf(".localhost", ".local", ".internal", ".home", ".lan").any(normalized::endsWith)) { "The provider address is local" }
        if (normalized.contains(':')) {
            require('%' !in normalized && isPublicAddress(InetAddress.getByName(normalized))) { "The provider address is not public" }
        } else if (normalized.matches(Regex("[0-9.]+"))) {
            val parts = normalized.split('.')
            require(parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && (it == "0" || !it.startsWith('0')) && it.toIntOrNull()?.let { number -> number in 0..255 } == true }) {
                "The provider address uses an ambiguous IP address"
            }
            require(isPublicAddress(InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray()))) { "The provider address is not public" }
        } else {
            require('.' in normalized && !normalized.split('.').any { it.startsWith("0x", true) }) { "The provider address is not public" }
        }
    }

    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 255 }
        if (bytes.size == 4) {
            val (a, b, c) = bytes
            return a !in setOf(0, 10, 127) && a < 224 &&
                !(a == 100 && b in 64..127) && !(a == 169 && b == 254) && !(a == 172 && b in 16..31) &&
                !(a == 192 && (b == 168 || (b == 0 && c in setOf(0, 2)) || (b == 88 && c == 99))) &&
                !(a == 198 && (b in 18..19 || (b == 51 && c == 100))) && !(a == 203 && b == 0 && c == 113)
        }
        if (bytes.size != 16) return false
        // Global unicast only; excludes ULA, link-local, multicast, unspecified and transition aliases.
        return bytes[0] in 0x20..0x3f && !(bytes[0] == 0x20 && bytes[1] == 0x02) &&
            !(bytes[0] == 0x3f && bytes[1] == 0xff && bytes[2] < 0x10) &&
            !(bytes[0] == 0x20 && bytes[1] == 0x01 && (bytes[2] < 2 || (bytes[2] == 0x0d && bytes[3] == 0xb8)))
    }

    internal fun publicDns(delegate: Dns = Dns.SYSTEM): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            try { requirePublicHost(hostname) }
            catch (_: IllegalArgumentException) { throw UnknownHostException("The provider address is not public") }
            val answers = delegate.lookup(hostname)
            if (answers.isEmpty() || answers.any { !isPublicAddress(it) }) throw UnknownHostException("The provider address did not resolve exclusively to public IP addresses")
            return answers
        }
    }

    /** DNS is checked before connecting, and every redirect is checked before OkHttp follows it. */
    fun secureClient(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val configuration = builder.build()
        require(configuration.proxy == null || configuration.proxy?.type() == Proxy.Type.DIRECT) {
            "Provider requests cannot validate destinations through the configured proxy"
        }
        return builder.dns(publicDns()).proxySelector(validatingProxySelector(configuration.proxySelector))
            .addNetworkInterceptor(redirectGuard())
    }

    /** Preserve the selected route. Never silently bypass a user's proxy to enforce this policy. */
    internal fun validatingProxySelector(delegate: ProxySelector): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val routes = delegate.select(uri)
            if (routes.any { it.type() != Proxy.Type.DIRECT }) throw IOException(
                "Provider requests cannot validate destinations through the configured proxy. The proxy was not bypassed.")
            return routes
        }
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = delegate.connectFailed(uri, sa, ioe)
    }

    internal fun redirectGuard(): Interceptor = Interceptor { chain ->
        try { requirePublicUrl(chain.request().url.toString()) }
        catch (error: IllegalArgumentException) { throw IOException(error.message, error) }
        val response = chain.proceed(chain.request())
        if (response.code in setOf(300, 301, 302, 303, 307, 308)) {
            response.header("Location")?.let { location ->
                try {
                    require(location.none { it.isISOControl() || it == '\\' }) { "The provider redirect is invalid" }
                    val raw = URI(location)
                    require(raw.rawUserInfo == null) { "The provider redirect contains credentials" }
                    val target = response.request.url.resolve(location) ?: throw IllegalArgumentException("The provider redirect is invalid")
                    requirePublicUrl(target.toString())
                } catch (error: Exception) { response.close(); throw IOException("The provider redirect is not public HTTP(S)", error) }
            }
        }
        response
    }
}

/** Immutable authority for one MediaItem, including its manifests, keys, segments and subtitles. */
internal class ProviderMediaContext(
    val providerResult: Boolean,
    private val serverUrl: String = "",
    private val allowServerMedia: Boolean = !providerResult,
    sourceUrl: String = "",
    sourceHeaders: Map<String, String> = emptyMap(),
    private val serverHeaders: () -> Map<String, String> = { emptyMap() },
) {
    private val providerOrigin = sourceUrl.toHttpUrlOrNull()
    private val serverOrigin = serverUrl.toHttpUrlOrNull()
    private val providerHeaders = sourceHeaders.filterKeys { !it.startsWith("X-Seanime-", true) }.toMap()

    fun requiresPublicUrl(url: String): Boolean = providerResult && !(allowServerMedia && sameOrigin(url.toHttpUrlOrNull(), serverOrigin))

    fun requireMediaUri(url: String, inlineSubtitleUris: Set<String> = emptySet()) {
        if (providerResult && url !in inlineSubtitleUris && requiresPublicUrl(url)) ProviderUrlPolicy.requirePublicUrl(url)
    }

    fun headersFor(initialUrl: String, targetUrl: String): Map<String, String> {
        val initial = initialUrl.toHttpUrlOrNull()
        val target = targetUrl.toHttpUrlOrNull()
        if (!sameOrigin(initial, target)) return emptyMap()
        return when {
            allowServerMedia && sameOrigin(initial, serverOrigin) -> serverHeaders()
            sameOrigin(initial, providerOrigin) -> providerHeaders
            else -> emptyMap()
        }
    }

    companion object {
        fun isProviderPlayback(info: org.json.JSONObject?): Boolean = info?.optString("playbackType") == "onlinestream" ||
            info?.optString("streamType") == "onlinestream" || info?.optJSONObject("onlinestreamParams") != null
        private fun sameOrigin(a: HttpUrl?, b: HttpUrl?): Boolean = a != null && b != null && a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }
}
