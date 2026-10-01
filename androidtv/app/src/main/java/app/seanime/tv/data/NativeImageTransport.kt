package app.seanime.tv.data

import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A reader-owned image transport. Redirects never acquire another image's or server's authority. */
class NativeImageTransport internal constructor(private val api: SeanimeApiClient, private val providerDns: Dns = ProviderUrlPolicy.publicDns()) : Call.Factory, Closeable {
    /** Coil forwards this tag to OkHttp without putting these values on the request itself. */
    class SourceHeaders(headers: Map<String, String>, internal val providerResult: Boolean = false) {
        internal val values = headers.filterKeys { it.lowercase() !in TRANSPORT_HEADERS }.toMap()
        override fun toString(): String = "SourceHeaders(redacted)"
    }

    private class Source(val url: HttpUrl, val headers: Map<String, String>, val isServer: Boolean) {
        override fun toString(): String = "ImageSource(redacted)"
    }
    private val closed = AtomicBoolean(false)
    private fun client(providerResult: Boolean) = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 })
        .connectionPool(ConnectionPool(4, 1, TimeUnit.MINUTES))
        .followRedirects(true).followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val source = requireNotNull(request.tag(Source::class.java))
            val approved = if (sameOrigin(request.url, source.url)) {
                if (source.isServer) source.headers.filterKeys { !isNativeHeader(it) } + api.requestHeaders()
                else source.headers.filterKeys { !it.startsWith("X-Seanime-", ignoreCase = true) }
            } else emptyMap()
            val managedNames = source.headers.keys.map { it.lowercase() }.toSet() + ORIGIN_BOUND_HEADERS
            val builder = request.newBuilder()
            request.headers.names().forEach { name ->
                if (name.lowercase() in managedNames || name.startsWith("X-Seanime-", ignoreCase = true)) {
                    builder.removeHeader(name)
                }
            }
            approved.forEach { (name, value) -> builder.header(name, value) }
            chain.proceed(builder.build())
        }.apply { if (providerResult) { ProviderUrlPolicy.secureClient(this); dns(providerDns) } }.build()
    private val http = client(false)
    private val providerHttp = client(true)

    override fun newCall(request: Request): Call {
        check(!closed.get()) { "Image transport is closed" }
        val policy = request.tag(SourceHeaders::class.java)
        val providerResult = policy?.providerResult == true
        if (providerResult) ProviderUrlPolicy.requirePublicUrl(request.url.toString())
        val sourceHeaders = policy?.values.orEmpty()
        val source = Source(request.url, sourceHeaders, !providerResult && api.isServerUrl(request.url.toString()))
        return (if (providerResult) providerHttp else http).newCall(request.newBuilder().tag(Source::class.java, source).build())
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        for (client in listOf(http, providerHttp)) {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private companion object {
        val TRANSPORT_HEADERS = setOf("host", "connection", "content-length", "range", "if-range", "accept-encoding")
        val ORIGIN_BOUND_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "origin", "referer")
        fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port
        fun isNativeHeader(name: String): Boolean = name.startsWith("X-Seanime-", ignoreCase = true) ||
            name.equals("Origin", ignoreCase = true) || name.equals("Referer", ignoreCase = true)
    }
}
