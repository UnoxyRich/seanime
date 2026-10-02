package app.seanime.tv.data

import java.io.IOException
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeImageTransportLifecycleTest {
    @Test fun `cancellation waits for a blocked decoder read to unwind before closing its source`() {
        MockWebServer().use { server ->
            server.start(loopback, 0)
            server.enqueue(incompleteImage())
            fixtureApi(server).use { api ->
                val cleanup = Executors.newSingleThreadExecutor()
                val reader = Executors.newSingleThreadExecutor()
                val sockets = RecordingSockets(holdReadExit = true)
                val transport = NativeImageTransport(api, proxySelector = fixtureProxySelector, cleanupExecutor = cleanup, socketFactory = sockets)
                try {
                    val call = transport.newCall(request(server))
                    val response = call.execute()
                    sockets.observeRead.set(true)
                    val read = reader.submit<Throwable?> { runCatching { response.body!!.source().readAll(Buffer()) }.exceptionOrNull() }
                    assertTrue(sockets.readStarted.await(5, TimeUnit.SECONDS))
                    val caller = Thread.currentThread()
                    call.cancel()
                    assertTrue(sockets.readReturned.await(5, TimeUnit.SECONDS))
                    val cleanupFinished = cleanup.submit {}
                    assertFalse("body cleanup must wait for the canceled source read to exit", cleanupFinished.isDone)
                    assertEquals(1, activeCallCount(transport))
                    sockets.releaseRead.countDown()
                    assertTrue(read.get(5, TimeUnit.SECONDS) is IOException)
                    cleanupFinished.get(5, TimeUnit.SECONDS)
                    assertReleased(transport, sockets, caller)
                } finally {
                    sockets.releaseRead.countDown()
                    transport.close()
                    awaitCleanup(cleanup)
                    cleanup.shutdown()
                    reader.shutdown()
                    assertTrue(cleanup.awaitTermination(5, TimeUnit.SECONDS))
                    assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun `cancel releases an executed response without the consumer reading or closing it`() =
        abandonedResponse(async = false, retire = false)

    @Test fun `retirement releases a delivered callback response without consumer cleanup`() =
        abandonedResponse(async = true, retire = true)

    @Test fun `prompt coroutine cancellation releases a response discarded after callback resume`() = runTest {
        MockWebServer().use { server ->
            val releaseResponse = CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    check(releaseResponse.await(10, TimeUnit.SECONDS)) { "test did not release response" }
                    return incompleteImage()
                }
            }
            server.start(loopback, 0)
            fixtureApi(server).use { api ->
                val cleanup = Executors.newSingleThreadExecutor()
                val sockets = RecordingSockets()
                val transport = NativeImageTransport(api, proxySelector = fixtureProxySelector, cleanupExecutor = cleanup, socketFactory = sockets)
                val call = transport.newCall(request(server))
                val handedOff = CountDownLatch(1)
                val consumerRan = AtomicBoolean(false)
                val caller = Thread.currentThread()
                val job = launch {
                    // Match Coil 2.7's await: resume has no onCancellation response cleanup.
                    val response = suspendCancellableCoroutine<Response> { continuation ->
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                if (!call.isCanceled()) continuation.resumeWithException(e)
                            }
                            override fun onResponse(call: Call, response: Response) {
                                continuation.resume(response)
                                handedOff.countDown()
                            }
                        })
                        continuation.invokeOnCancellation { call.cancel() }
                    }
                    consumerRan.set(true)
                    response.close()
                }
                try {
                    runCurrent() // Start await, then keep the resumed continuation queued.
                    releaseResponse.countDown()
                    assertTrue(handedOff.await(5, TimeUnit.SECONDS))
                    assertEquals(1, activeCallCount(transport))
                    job.cancel()
                    runCurrent()
                    awaitCleanup(cleanup)
                    assertTrue(job.isCancelled)
                    assertFalse("the consumer must never receive the discarded response", consumerRan.get())
                    assertReleased(transport, sockets, caller)
                } finally {
                    releaseResponse.countDown()
                    job.cancel()
                    transport.close()
                    awaitCleanup(cleanup)
                    cleanup.shutdown()
                    assertTrue(cleanup.awaitTermination(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun `cancel releases an abandoned HTTP2 stream and keeps its connection reusable`() {
        MockWebServer().use { server ->
            server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
            server.start(loopback, 0)
            server.enqueue(incompleteImage())
            server.enqueue(MockResponse().setBody("next image"))
            fixtureApi(server).use { api ->
                val cleanup = Executors.newSingleThreadExecutor()
                val sockets = RecordingSockets()
                val transport = NativeImageTransport(api, proxySelector = fixtureProxySelector, cleanupExecutor = cleanup, socketFactory = sockets,
                    protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE))
                val caller = Thread.currentThread()
                try {
                    val call = transport.newCall(request(server))
                    val abandoned = call.execute()
                    assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, abandoned.protocol)
                    assertEquals(1, activeCallCount(transport))
                    call.cancel()
                    awaitCleanup(cleanup)
                    assertEquals("the abandoned stream must finish without consumer cleanup", 0, activeCallCount(transport))
                    assertEquals(1, connectionCount(transport))
                    assertEquals("the HTTP2 connection must have no remaining call allocation", 1, idleConnectionCount(transport))
                    transport.newCall(request(server)).execute().use { assertEquals("next image", it.body!!.string()) }
                    assertEquals("a new image should reuse the released HTTP2 connection", 1, sockets.sockets.size)
                    transport.close()
                    awaitCleanup(cleanup)
                    assertReleased(transport, sockets, caller)
                } finally {
                    transport.close()
                    awaitCleanup(cleanup)
                    cleanup.shutdown()
                    assertTrue(cleanup.awaitTermination(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun `Coil style cancellation returns before socket teardown and preserves callback identity`() {
        MockWebServer().use { server ->
            server.start(loopback, 0)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            fixtureApi(server).use { api ->
                val sockets = RecordingSockets(blockClose = true)
                val transport = NativeImageTransport(api, proxySelector = fixtureProxySelector, socketFactory = sockets)
                val call = transport.newCall(request(server))
                val finished = CountDownLatch(1)
                val failedCall = AtomicReference<Call>()
                try {
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) { failedCall.set(call); finished.countDown() }
                        override fun onResponse(call: Call, response: Response) { response.close(); finished.countDown() }
                    })
                    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    val caller = Thread.currentThread()
                    // Coil's cancellation handler calls cancel synchronously from painter/loader disposal.
                    repeat(10) { call.cancel() }
                    assertTrue("cancellation must be immediately observable", call.isCanceled())
                    assertTrue(sockets.closeStarted.await(5, TimeUnit.SECONDS))
                    assertFalse("cancel must return before blocked socket teardown finishes", sockets.sockets.single().isClosed)
                    assertTrue(sockets.closeThreads.all { it !== caller })
                    sockets.releaseClose.countDown()
                    assertTrue(finished.await(5, TimeUnit.SECONDS))
                    assertSame("callbacks must receive the public cancellable call", call, failedCall.get())
                } finally {
                    sockets.releaseClose.countDown()
                    transport.close()
                    assertTrue(sockets.closeFinished.await(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun `cancellation rejects saved calls before cleanup but clones have independent state`() {
        MockWebServer().use { server ->
            server.start(loopback, 0)
            server.enqueue(MockResponse().setBody("cloned image"))
            fixtureApi(server).use { api ->
                val cleanup = CopyOnWriteArrayList<Runnable>()
                val transport = NativeImageTransport(api, proxySelector = fixtureProxySelector, cleanupExecutor = Executor { cleanup += it })
                try {
                    val call = transport.newCall(request(server))
                    repeat(10) { call.cancel() }
                    assertEquals("repeated cancellation must enqueue one task", 1, cleanup.size)
                    assertTrue(call.isCanceled())
                    assertFalse(call.isExecuted())
                    assertClosed(call)
                    assertTrue(call.isExecuted())
                    assertAsyncFailure(call.clone().also { it.cancel() })
                    assertEquals(0, server.requestCount)
                    val clone = call.clone()
                    assertFalse(clone.isCanceled())
                    assertFalse(clone.isExecuted())
                    clone.execute().use { assertEquals("cloned image", it.body!!.string()) }
                    assertEquals(1, server.requestCount)
                } finally {
                    transport.close()
                    cleanup.forEach { it.run() }
                }
            }
        }
    }

    @Test fun `close returns while real socket cleanup is blocked and never closes on its caller`() {
        MockWebServer().use { server ->
            server.start(loopback, 0)
            server.enqueue(MockResponse().setBody("image"))
            fixtureApi(server).use { api ->
                val sockets = RecordingSockets(blockClose = true)
                val transport = NativeImageTransport(api, proxySelector = fixtureProxySelector, socketFactory = sockets)
                try {
                    transport.newCall(request(server)).execute().use { assertEquals("image", it.body!!.string()) }
                    val caller = Thread.currentThread()
                    transport.close()
                    assertTrue("socket teardown must start off the caller", sockets.closeStarted.await(5, TimeUnit.SECONDS))
                    assertFalse("close must return before blocked socket teardown finishes", sockets.sockets.single().isClosed)
                    assertTrue(sockets.closeThreads.all { it !== caller })
                    repeat(10) { transport.close() }
                    assertTrue(runCatching { transport.newCall(request(server)) }.exceptionOrNull() is IllegalStateException)
                } finally {
                    sockets.releaseClose.countDown()
                    transport.close()
                    assertTrue("the retired socket must actually close", sockets.closeFinished.await(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun `retirement rejects saved calls and clones before and after asynchronous cleanup`() {
        MockWebServer().use { server ->
            server.start(loopback, 0)
            fixtureApi(server).use { api ->
                val cleanup = CopyOnWriteArrayList<Runnable>()
                val transport = NativeImageTransport(api, proxySelector = fixtureProxySelector, cleanupExecutor = Executor { cleanup += it })
                val saved = List(4) { transport.newCall(request(server)) }
                val cloned = saved.first().clone()
                try {
                    repeat(10) { transport.close() }
                    assertEquals("idempotent close must schedule one owner teardown", 1, cleanup.size)
                    assertClosed(saved[0])
                    assertClosed(cloned)
                    assertAsyncFailure(saved[1])
                    cleanup.removeAt(0).run()
                    assertClosed(saved[2])
                    assertAsyncFailure(saved[3])
                    assertEquals("late calls must never reach the server", 0, server.requestCount)
                } finally {
                    transport.close()
                    cleanup.forEach { it.run() }
                }
            }
        }
    }

    @Test fun `retirement cancels both pending requests and responses already handed to a reader`() {
        MockWebServer().use { server ->
            server.start(loopback, 0)
            // Advertise more bytes than are sent so the body stays open without a sleeping server task.
            server.enqueue(incompleteImage())
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            fixtureApi(server).use { api ->
                val sockets = RecordingSockets()
                val transport = NativeImageTransport(api, proxySelector = fixtureProxySelector, socketFactory = sockets)
                val bodyCall = transport.newCall(request(server))
                val response = bodyCall.execute()
                val pending = transport.newCall(request(server))
                val failed = CountDownLatch(1)
                try {
                    pending.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) { failed.countDown() }
                        override fun onResponse(call: Call, response: Response) { response.close() }
                    })
                    repeat(2) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                    transport.close()
                    assertTrue("the in-flight request must be interrupted", failed.await(5, TimeUnit.SECONDS))
                    assertTrue(pending.isCanceled())
                    assertTrue("response bodies remain owned after execute returns", bodyCall.isCanceled())
                    val readFailure = runCatching { response.body!!.string() }.exceptionOrNull()
                    assertTrue(readFailure is IOException || readFailure is IllegalStateException)
                    assertTrue("both live sockets must close", sockets.sockets.all { it.isClosed })
                } finally { response.close(); transport.close() }
            }
        }
    }

    @Test fun `retirement during DNS prevents a request from connecting after cleanup finishes`() {
        MockWebServer().use { server ->
            server.start(loopback, 0)
            fixtureApi(server).use { api ->
                val dnsStarted = CountDownLatch(1)
                val releaseDns = CountDownLatch(1)
                val cleanup = CopyOnWriteArrayList<Runnable>()
                val sockets = RecordingSockets()
                val transport = NativeImageTransport(api, proxySelector = fixtureProxySelector, providerDns = object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        requireFixtureHost(hostname)
                        dnsStarted.countDown()
                        check(releaseDns.await(10, TimeUnit.SECONDS)) { "test did not release DNS" }
                        return listOf(loopback)
                    }
                }, cleanupExecutor = Executor { cleanup += it }, socketFactory = sockets)
                val call = transport.newCall(Request.Builder().url(server.url("/image.png").newBuilder().host("provider.example").build())
                    .tag(NativeImageTransport.SourceHeaders::class.java, NativeImageTransport.SourceHeaders(emptyMap(), providerResult = true)).build())
                val failed = CountDownLatch(1)
                val callbackFailure = AtomicReference<IOException>()
                try {
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) { callbackFailure.set(e); failed.countDown() }
                        override fun onResponse(call: Call, response: Response) { response.close() }
                    })
                    val dnsObserved = dnsStarted.await(5, TimeUnit.SECONDS)
                    val failure = callbackFailure.get()
                    val failureClass = failure?.javaClass?.name?.take(96)
                        ?.takeIf { it.matches(Regex("[A-Za-z0-9_.$]+")) } ?: "none"
                    val policyFailure = failure?.stackTrace?.firstOrNull()?.className
                        ?.startsWith(ProviderUrlPolicy::class.java.name) == true
                    assertTrue("Injected fixture DNS did not start; callbackFailed=${failed.count == 0L}, " +
                        "failureClass=$failureClass, policyFailure=$policyFailure, canceled=${call.isCanceled()}", dnsObserved)
                    transport.close()
                    cleanup.removeAt(0).run()
                    assertTrue(call.isCanceled())
                    releaseDns.countDown()
                    assertTrue(failed.await(5, TimeUnit.SECONDS))
                    assertEquals(0, server.requestCount)
                    assertTrue("a call retired during DNS must not open a socket", sockets.sockets.isEmpty())
                } finally {
                    releaseDns.countDown()
                    transport.close()
                    cleanup.forEach { it.run() }
                }
            }
        }
    }

    @Test fun `rapid replacement keeps new owners usable while old owners finish closing`() {
        MockWebServer().use { server ->
            server.start(loopback, 0)
            repeat(8) { server.enqueue(MockResponse().setBody("image-$it")) }
            fixtureApi(server).use { api ->
                val oldCalls = mutableListOf<Call>()
                val retiredSockets = mutableListOf<RecordingSockets>()
                try {
                    repeat(8) { index ->
                        val sockets = RecordingSockets(blockClose = true)
                        retiredSockets += sockets
                        NativeImageTransport(api, proxySelector = fixtureProxySelector, socketFactory = sockets).use { transport ->
                            transport.newCall(request(server)).execute().use { assertEquals("image-$index", it.body!!.string()) }
                            oldCalls += transport.newCall(request(server))
                        }
                        assertTrue(sockets.closeStarted.await(5, TimeUnit.SECONDS))
                        oldCalls.forEach { assertClosed(it.clone()) }
                    }
                    assertEquals(8, server.requestCount)
                } finally {
                    retiredSockets.forEach { it.releaseClose.countDown() }
                    retiredSockets.forEach { assertTrue(it.closeFinished.await(5, TimeUnit.SECONDS)) }
                }
            }
        }
    }

    @Test fun `fixture direct selector permits only loopback aliases without changing the default selector`() {
        val originalDefault = ProxySelector.getDefault()
        MockWebServer().use { server ->
            server.start(loopback, 0)
            server.enqueue(MockResponse().setBody("fixture provider image"))
            fixtureApi(server).use { api ->
                val sockets = RecordingSockets()
                NativeImageTransport(api, providerDns = fixtureDns, socketFactory = sockets,
                    proxySelector = fixtureProxySelector, cleanupExecutor = Executor { it.run() }).use { transport ->
                    transport.newCall(providerRequest(server)).execute().use {
                        assertEquals("fixture provider image", it.body!!.string())
                    }
                    assertEquals(1, server.requestCount)
                    assertEquals(1, sockets.sockets.size)
                    assertTrue(sockets.sockets.single().inetAddress.isLoopbackAddress)
                    assertSame(originalDefault, ProxySelector.getDefault())
                }
                // Omitting the new optional argument preserves the selector inherited by OkHttp.
                val inherited = OkHttpClient.Builder().build().proxySelector
                NativeImageTransport(api, cleanupExecutor = Executor { it.run() }).use { transport ->
                    for (name in listOf("http", "offlineAssetHttp")) {
                        val client = NativeImageTransport::class.java.getDeclaredField(name)
                            .apply { isAccessible = true }.get(transport) as OkHttpClient
                        assertSame(inherited, client.proxySelector)
                    }
                }
            }
        }
        assertThrows(UnknownHostException::class.java) { fixtureDns.lookup("unexpected.example") }
        assertThrows(ProtocolException::class.java) { fixtureProxySelector.select(URI("http://unexpected.example/image.png")) }
        assertSame(originalDefault, ProxySelector.getDefault())
    }

    @Test fun `provider selector overrides reject HTTP and SOCKS before DNS or sockets without retry or global mutation`() {
        val originalDefault = ProxySelector.getDefault()
        MockWebServer().use { server ->
            server.start(loopback, 0)
            fixtureApi(server).use { api ->
                for (type in listOf(Proxy.Type.HTTP, Proxy.Type.SOCKS)) {
                    val selections = AtomicInteger()
                    val lookups = AtomicInteger()
                    val sockets = RecordingSockets()
                    val completed = CountDownLatch(1)
                    val failure = AtomicReference<IOException>()
                    val proxy = Proxy(type, InetSocketAddress(loopback, server.port))
                    val selector = object : ProxySelector() {
                        override fun select(uri: URI): List<Proxy> {
                            requireFixtureHost(uri.host)
                            selections.incrementAndGet()
                            return listOf(proxy, Proxy.NO_PROXY)
                        }
                        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
                    }
                    NativeImageTransport(api, providerDns = object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> {
                            lookups.incrementAndGet()
                            return fixtureDns.lookup(hostname)
                        }
                    }, socketFactory = sockets, proxySelector = selector,
                        cleanupExecutor = Executor { it.run() }).use { transport ->
                        val call = transport.newCall(providerRequest(server))
                        call.timeout().timeout(2, TimeUnit.SECONDS)
                        try {
                            call.enqueue(object : Callback {
                                override fun onFailure(call: Call, e: IOException) { failure.set(e); completed.countDown() }
                                override fun onResponse(call: Call, response: Response) { response.close(); completed.countDown() }
                            })
                            assertTrue("$type rejection must complete its callback", completed.await(5, TimeUnit.SECONDS))
                            // A timeout bounds regressions but is never accepted as policy rejection.
                            assertTrue("$type failure was ${failure.get()}", failure.get() is ProtocolException)
                            assertEquals("Provider requests cannot validate destinations through the configured proxy. The proxy was not bypassed.", failure.get().message)
                            assertEquals("provider policy rejection must be terminal", 1, selections.get())
                            assertEquals(0, lookups.get())
                            assertTrue(sockets.sockets.isEmpty())
                            assertEquals(0, server.requestCount)
                            assertSame(originalDefault, ProxySelector.getDefault())
                        } finally { call.cancel() }
                    }
                }
            }
        }
        assertSame(originalDefault, ProxySelector.getDefault())
    }

    private fun abandonedResponse(async: Boolean, retire: Boolean) {
        MockWebServer().use { server ->
            server.start(loopback, 0)
            server.enqueue(incompleteImage())
            fixtureApi(server).use { api ->
                val cleanup = Executors.newSingleThreadExecutor()
                val sockets = RecordingSockets()
                val transport = NativeImageTransport(api, proxySelector = fixtureProxySelector, cleanupExecutor = cleanup, socketFactory = sockets)
                val call = transport.newCall(request(server))
                val response = AtomicReference<Response>()
                val delivered = CountDownLatch(1)
                try {
                    if (async) call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) { delivered.countDown() }
                        override fun onResponse(call: Call, value: Response) { response.set(value); delivered.countDown() }
                    }) else { response.set(call.execute()); delivered.countDown() }
                    assertTrue(delivered.await(5, TimeUnit.SECONDS))
                    assertNotNull(response.get())
                    assertEquals(1, activeCallCount(transport))
                    assertEquals(1, connectionCount(transport))
                    val caller = Thread.currentThread()
                    if (retire) transport.close() else call.cancel()
                    awaitCleanup(cleanup)
                    // Keep the response strongly reachable and do not close or read its body.
                    assertNotNull(response.get())
                    assertReleased(transport, sockets, caller)
                } finally {
                    transport.close()
                    awaitCleanup(cleanup)
                    cleanup.shutdown()
                    assertTrue(cleanup.awaitTermination(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    private fun assertReleased(transport: NativeImageTransport, sockets: RecordingSockets, caller: Thread) {
        assertEquals("terminal body cleanup must remove the call from its owner", 0, activeCallCount(transport))
        assertEquals("closing the socket alone must not leave a live pool allocation", 0, connectionCount(transport))
        assertTrue(sockets.sockets.isNotEmpty())
        assertTrue(sockets.sockets.all { it.isClosed })
        assertTrue(sockets.closeThreads.isNotEmpty())
        assertTrue("all teardown must stay off the canceling thread", sockets.closeThreads.all { it !== caller })
    }
    private fun awaitCleanup(executor: ExecutorService) { executor.submit {}.get(5, TimeUnit.SECONDS) }
    // Inspect ownership/allocation, not GC timing: socket cancellation alone leaves both retained.
    private fun activeCallCount(transport: NativeImageTransport): Int {
        val calls = NativeImageTransport::class.java.getDeclaredField("activeCalls").apply { isAccessible = true }.get(transport) as Set<*>
        return synchronized(calls) { calls.size }
    }
    private fun connectionCount(transport: NativeImageTransport) = listOf("http", "providerHttp", "offlineAssetHttp").sumOf { name ->
        (NativeImageTransport::class.java.getDeclaredField(name).apply { isAccessible = true }.get(transport) as OkHttpClient)
            .connectionPool.connectionCount()
    }
    private fun idleConnectionCount(transport: NativeImageTransport) = listOf("http", "providerHttp", "offlineAssetHttp").sumOf { name ->
        (NativeImageTransport::class.java.getDeclaredField(name).apply { isAccessible = true }.get(transport) as OkHttpClient)
            .connectionPool.idleConnectionCount()
    }
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val fixtureHosts = setOf("localhost", "127.0.0.1", "provider.example")
    private fun requireFixtureHost(host: String) {
        if (host !in fixtureHosts) throw UnknownHostException("Unexpected fixture host")
    }
    private val fixtureDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            requireFixtureHost(hostname)
            return listOf(loopback)
        }
    }
    private val fixtureProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            if (uri.host !in fixtureHosts) throw ProtocolException("Unexpected fixture host")
            return listOf(Proxy.NO_PROXY)
        }
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }
    private fun fixtureApi(server: MockWebServer) = SeanimeApiClient(server.url("/").toString(),
        httpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .proxySelector(fixtureProxySelector).dns(fixtureDns).build())
    private fun providerRequest(server: MockWebServer) = Request.Builder()
        .url(server.url("/image.png").newBuilder().host("provider.example").build())
        .tag(NativeImageTransport.SourceHeaders::class.java,
            NativeImageTransport.SourceHeaders(emptyMap(), providerResult = true)).build()

    private fun incompleteImage() = MockResponse().setBody("partial image").setHeader("Content-Length", 1_048_576)
    private fun request(server: MockWebServer) = Request.Builder().url(server.url("/image.png")).build()
    private fun assertClosed(call: Call) {
        val failure = runCatching { call.execute().use { error("retired call succeeded") } }.exceptionOrNull()
        assertTrue("canceled or retired calls must fail with IOException: $failure", failure is IOException)
    }
    private fun assertAsyncFailure(call: Call) {
        val finished = CountDownLatch(1)
        val failure = AtomicReference<IOException>()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { failure.set(e); finished.countDown() }
            override fun onResponse(call: Call, response: Response) { response.close(); finished.countDown() }
        })
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertNotNull("late enqueued calls must fail", failure.get())
    }

    /** Actual TCP sockets expose both cancellation and idle pool eviction; no mock Call is used. */
    private class RecordingSockets(blockClose: Boolean = false, private val holdReadExit: Boolean = false) : SocketFactory() {
        val sockets = CopyOnWriteArrayList<Socket>()
        val closeThreads = CopyOnWriteArrayList<Thread>()
        val closeStarted = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val releaseClose = CountDownLatch(if (blockClose) 1 else 0)
        val observeRead = AtomicBoolean(false)
        val readStarted = CountDownLatch(1)
        val readReturned = CountDownLatch(1)
        val releaseRead = CountDownLatch(if (holdReadExit) 1 else 0)
        override fun createSocket(): Socket = object : Socket() {
            override fun getInputStream(): InputStream {
                val input = super.getInputStream()
                if (!holdReadExit) return input
                return object : FilterInputStream(input) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        val observed = observeRead.get()
                        if (observed) readStarted.countDown()
                        try { return super.read(bytes, offset, length) }
                        finally {
                            if (observed) {
                                readReturned.countDown()
                                check(releaseRead.await(10, TimeUnit.SECONDS)) { "test did not release source read" }
                            }
                        }
                    }
                }
            }
            override fun close() {
                closeThreads += Thread.currentThread()
                closeStarted.countDown()
                try {
                    check(releaseClose.await(10, TimeUnit.SECONDS)) { "test did not release socket cleanup" }
                    super.close()
                } finally { closeFinished.countDown() }
            }
        }.also { sockets += it }
        override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            createSocket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            createSocket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port)) }
    }
}
