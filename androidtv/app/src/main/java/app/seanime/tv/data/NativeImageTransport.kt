package app.seanime.tv.data

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/** A reader-owned image transport. Redirects never acquire another image's or server's authority. */
class NativeImageTransport internal constructor(
    private val api: SeanimeApiClient,
    private val providerDns: Dns = ProviderUrlPolicy.publicDns(),
    private val cleanupExecutor: Executor = Dispatchers.IO.asExecutor(),
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val protocols: List<Protocol>? = null,
) : Call.Factory, Closeable {
    /** Coil forwards this tag to OkHttp without putting these values on the request itself. */
    class SourceHeaders(headers: Map<String, String>, internal val providerResult: Boolean = false, internal val offlineAssetMediaId: Long? = null) {
        internal val values = headers.filterKeys { it.lowercase() !in TRANSPORT_HEADERS }.toMap()
        override fun toString(): String = "SourceHeaders(redacted)"
    }

    private class Source(val url: HttpUrl, val headers: Map<String, String>, val isServer: Boolean) {
        override fun toString(): String = "ImageSource(redacted)"
    }
    private class Cancellation {
        val canceled = AtomicBoolean(false)
        private var responseBody: OwnedBody? = null
        private var finished = false

        fun retain(body: OwnedBody?): Boolean = synchronized(this) {
            if (canceled.get()) false else { if (!finished) responseBody = body; true }
        }
        fun release() = synchronized(this) { finished = true; responseBody = null }
        fun takeBody(): OwnedBody? = synchronized(this) {
            responseBody.also { responseBody = null }
        }
    }
    private class OwnedBody(private val delegate: ResponseBody) : ResponseBody() {
        private val readLock = Any()
        private var released = false
        private val buffered = object : ForwardingSource(delegate.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long = synchronized(readLock) {
                if (released) throw IOException("Canceled")
                super.read(sink, byteCount)
            }
            override fun close() = release()
        }.buffer()

        // Cancellation first interrupts the socket, then waits for any active delegate read.
        // Do not mutate the consumer's outer buffer from the cleanup thread.
        fun release() = synchronized(readLock) {
            if (!released) { released = true; delegate.close() }
        }
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source() = buffered
    }
    private val closed = AtomicBoolean(false)
    // Unlike Dispatcher.runningCalls(), this includes responses whose bodies are still being read.
    private val activeCalls = mutableSetOf<Call>()
    private fun client(providerResult: Boolean, followRedirects: Boolean = true): OkHttpClient {
        val pool = ConnectionPool(4, 1, TimeUnit.MINUTES)
        return OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 })
        .connectionPool(pool).socketFactory(socketFactory)
        .apply { this@NativeImageTransport.protocols?.let { protocols(it) } }
        .followRedirects(followRedirects).followSslRedirects(false)
        .eventListener(object : EventListener() {
            override fun callStart(call: Call) {
                synchronized(activeCalls) { if (!closed.get()) activeCalls.add(call) }
            }
            override fun callEnd(call: Call) = finished(call)
            override fun callFailed(call: Call, ioe: IOException) = finished(call)
            private fun finished(call: Call) {
                call.request().tag(Cancellation::class.java)?.release()
                synchronized(activeCalls) { activeCalls.remove(call) }
            }
            override fun connectionReleased(call: Call, connection: Connection) {
                // A response may release its connection after the first eviction has finished.
                if (closed.get()) cleanupExecutor.execute { pool.evictAll() }
            }
        })
        .addInterceptor { chain ->
            // newCall/clone may have happened before retirement. This runs before DNS or sockets,
            // even for synchronous execute(), which can outlive a dispatcher's shutdown.
            val cancellation = requireNotNull(chain.request().tag(Cancellation::class.java))
            if (closed.get()) throw IOException("Image transport is closed")
            if (cancellation.canceled.get()) throw IOException("Canceled")
            val response = chain.proceed(chain.request())
            val body = response.body?.let(::OwnedBody)
            // Own the body before handing it to Coil: prompt coroutine cancellation can discard
            // a resumed response without ever reading or closing it.
            if (closed.get() || !cancellation.retain(body)) {
                body?.release()
                throw IOException("Canceled")
            }
            response.newBuilder().body(body).build()
        }
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
    }
    private val http = client(false)
    private val providerHttp = client(true)
    private val offlineAssetHttp = client(false, followRedirects = false)

    override fun newCall(request: Request): Call {
        check(!closed.get()) { "Image transport is closed" }
        val policy = request.tag(SourceHeaders::class.java)
        val providerResult = policy?.providerResult == true
        val offlineAssetMediaId = policy?.offlineAssetMediaId
        if (providerResult) ProviderUrlPolicy.requirePublicUrl(request.url.toString())
        if (offlineAssetMediaId != null) {
            val segments = request.url.pathSegments
            require(!providerResult && request.method == "GET" && request.body == null &&
                offlineAssetMediaId in 1L..MAX_NATIVE_MEDIA_ID && api.isServerUrl(request.url.toString()) &&
                request.url.username.isEmpty() && request.url.password.isEmpty() && request.url.query == null && request.url.fragment == null &&
                segments.size == 3 && segments[0] == "offline-assets" && segments[1] == offlineAssetMediaId.toString() &&
                MediaArtworkOrigin.isAssetFilename(segments[2]) && request.url.encodedPath == "/offline-assets/$offlineAssetMediaId/${segments[2]}") {
                "Invalid offline artwork asset"
            }
        }
        val sourceHeaders = if (offlineAssetMediaId != null) emptyMap() else policy?.values.orEmpty()
        val source = Source(request.url, sourceHeaders, offlineAssetMediaId == null && !providerResult && api.isServerUrl(request.url.toString()))
        val client = if (offlineAssetMediaId != null) offlineAssetHttp else if (providerResult) providerHttp else http
        return ImageCall(client, request.newBuilder().apply {
                if (offlineAssetMediaId != null) headers(okhttp3.Headers.Builder().build())
            }.tag(Source::class.java, source).build())
    }

    /** Coil cancels calls on the disposing thread, including when an individual painter leaves. */
    private inner class ImageCall(private val client: OkHttpClient, request: Request) : Call {
        private val cancellation = Cancellation()
        private val delegate = client.newCall(request.newBuilder().tag(Cancellation::class.java, cancellation).build())

        override fun cancel() {
            // Publish cancellation before dispatching: saved calls cannot start while cleanup waits.
            if (cancellation.canceled.compareAndSet(false, true)) cleanupExecutor.execute { cancelAndRelease(delegate) }
        }
        override fun isCanceled() = cancellation.canceled.get() || delegate.isCanceled()
        override fun isExecuted() = delegate.isExecuted()
        override fun request() = delegate.request()
        override fun timeout() = delegate.timeout()
        override fun clone(): Call = ImageCall(client, delegate.request())
        override fun execute(): Response = delegate.execute()
        override fun enqueue(responseCallback: Callback) = delegate.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = responseCallback.onFailure(this@ImageCall, e)
            override fun onResponse(call: Call, response: Response) = responseCallback.onResponse(this@ImageCall, response)
        })
    }

    override fun close() {
        val calls = synchronized(activeCalls) {
            if (!closed.compareAndSet(false, true)) return
            activeCalls.toList().also { calls ->
                calls.forEach { it.request().tag(Cancellation::class.java)?.canceled?.set(true) }
                activeCalls.clear()
            }
        }
        // Compose disposes on main. Cancellation and pool eviction can both close sockets.
        // A finite task on shared IO survives the disposed composition and owns no new executor.
        cleanupExecutor.execute {
            calls.forEach(::cancelAndRelease)
            for (client in listOf(http, providerHttp, offlineAssetHttp)) {
                client.dispatcher.cancelAll()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    private fun cancelAndRelease(call: Call) {
        val body = call.request().tag(Cancellation::class.java)?.takeBody()
        try { call.cancel() }
        finally { body?.release() }
    }

    private companion object {
        val TRANSPORT_HEADERS = setOf("host", "connection", "content-length", "range", "if-range", "accept-encoding")
        val ORIGIN_BOUND_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "origin", "referer")
        fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port
        fun isNativeHeader(name: String): Boolean = name.startsWith("X-Seanime-", ignoreCase = true) ||
            name.equals("Origin", ignoreCase = true) || name.equals("Referer", ignoreCase = true)
    }
}
