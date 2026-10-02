package app.seanime.tv.data

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NativePlayerHttpClientsTest {
    @Test fun `negative control idle TLS eviction closes the SSL socket on its caller`() {
        val tls = fixtureTls()
        MockWebServer().use { server ->
            server.useHttps(tls.context.socketFactory, false)
            server.start(loopback, 0)
            server.enqueue(MockResponse().setBody("media"))
            val sockets = RecordingTlsSockets(tls.context.socketFactory)
            val client = fixtureBuilder().sslSocketFactory(sockets, tls.trust).build()
            try {
                client.newCall(request(server)).execute().use {
                    assertNotNull("control must establish actual TLS", it.handshake)
                    assertEquals("media", it.body!!.string())
                }
                assertEquals(1, client.connectionPool.idleConnectionCount())
                val caller = Thread.currentThread()
                client.connectionPool.evictAll()
                assertEquals(listOf(caller), sockets.closeThreads.toList())
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test fun `TLS retirement returns before blocked close and leaves replacement usable`() {
        val tls = fixtureTls()
        MockWebServer().use { server ->
            server.useHttps(tls.context.socketFactory, false)
            server.start(loopback, 0)
            repeat(3) { server.enqueue(MockResponse().setBody("media-$it")) }
            val sockets = RecordingTlsSockets(tls.context.socketFactory, blockClose = true)
            val oldOwner = NativePlayerHttpClients()
            val oldClient = oldOwner.createClient { fixtureConfiguration(); sslSocketFactory(sockets, tls.trust) }
            val newOwner = NativePlayerHttpClients()
            val newClient = newOwner.createClient { fixtureConfiguration(); sslSocketFactory(tls.context.socketFactory, tls.trust) }
            try {
                oldClient.newCall(request(server)).execute().use {
                    assertNotNull(it.handshake)
                    assertEquals("media-0", it.body!!.string())
                }
                assertEquals(1, oldClient.connectionPool.idleConnectionCount())
                val caller = Thread.currentThread()
                repeat(10) { oldOwner.close() }
                assertTrue(sockets.closeStarted.await(5, TimeUnit.SECONDS))
                assertEquals(1, sockets.closeThreads.size)
                assertTrue(sockets.closeThreads.all { it !== caller })
                assertEquals("retirement returns before close completes", 1L, sockets.closeFinished.count)
                newClient.newCall(request(server)).execute().use { assertEquals("media-1", it.body!!.string()) }
                sockets.allowClose.countDown()
                assertTrue(sockets.closeFinished.await(5, TimeUnit.SECONDS))
                newClient.newCall(request(server)).execute().use { assertEquals("media-2", it.body!!.string()) }
                assertFalse(newClient.dispatcher.executorService.isShutdown)
                assertEquals(3, server.requestCount)
            } finally {
                sockets.allowClose.countDown()
                oldOwner.close()
                newOwner.close()
                assertTrue(sockets.closeFinished.await(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun `retirement is immediate and idempotent before queued cleanup executes`() {
        val cleanup = CopyOnWriteArrayList<Runnable>()
        val owner = NativePlayerHttpClients(Executor { cleanup.add(it) })
        MockWebServer().use { server ->
            server.start(loopback, 0)
            val first = owner.createClient { fixtureConfiguration() }
            val second = owner.createClient { fixtureConfiguration() }
            val saved = first.newCall(request(server))
            val clone = saved.clone()
            repeat(10) { owner.close() }
            assertEquals(1, cleanup.size)
            assertThrows(IllegalStateException::class.java) { owner.createClient {} }
            assertThrows(IOException::class.java) { saved.execute().close() }
            assertThrows(IOException::class.java) { clone.execute().close() }
            assertThrows(IOException::class.java) { second.newCall(request(server)).execute().close() }
            assertEquals(0, server.requestCount)
            drain(cleanup)
            assertTrue(first.dispatcher.executorService.isShutdown)
            assertTrue(second.dispatcher.executorService.isShutdown)
            assertThrows(IOException::class.java) { saved.clone().execute().close() }
        }
    }

    @Test fun `late Media3 body release evicts the retired HTTP2 pool after initial cleanup`() {
        val cleanup = CopyOnWriteArrayList<Runnable>()
        val owner = NativePlayerHttpClients(Executor { cleanup.add(it) })
        MockWebServer().use { server ->
            server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
            server.start(loopback, 0)
            server.enqueue(MockResponse().setBody("unread media"))
            val client = owner.createClient {
                fixtureConfiguration()
                protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
            }
            val call = client.newCall(request(server))
            val response = call.execute()
            try {
                assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol)
                assertEquals("delivered body is outside Dispatcher ownership", 0, client.dispatcher.runningCallsCount())
                owner.close()
                drain(cleanup)
                assertTrue("owner still cancels delivered calls", call.isCanceled())
                assertEquals("Media3 still owns the unread body", 1, client.connectionPool.connectionCount())
                assertEquals(0, client.connectionPool.idleConnectionCount())
                response.close()
                assertTrue("late release must schedule eviction", cleanup.isNotEmpty())
                drain(cleanup)
                assertEquals(0, client.connectionPool.connectionCount())
            } finally {
                response.close()
                owner.close()
                drain(cleanup)
            }
        }
    }

    @Test fun `retirement cancels a call still in DNS and prevents a later connection`() {
        val enteredDns = CountDownLatch(1)
        val leaveDns = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<IOException>()
        val cleanup = CopyOnWriteArrayList<Runnable>()
        val owner = NativePlayerHttpClients(Executor { cleanup.add(it) })
        MockWebServer().use { server ->
            server.start(loopback, 0)
            val client = owner.createClient {
                fixtureConfiguration()
                dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        require(hostname == fixtureHost)
                        enteredDns.countDown()
                        check(leaveDns.await(5, TimeUnit.SECONDS))
                        return listOf(loopback)
                    }
                })
            }
            val call = client.newCall(request(server))
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { failure.set(e); finished.countDown() }
                override fun onResponse(call: Call, response: Response) { response.close(); finished.countDown() }
            })
            try {
                assertTrue(enteredDns.await(5, TimeUnit.SECONDS))
                owner.close()
                drain(cleanup)
                assertTrue(call.isCanceled())
                leaveDns.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                assertNotNull(failure.get())
                assertEquals(0, server.requestCount)
                assertEquals(0, client.connectionPool.connectionCount())
            } finally {
                leaveDns.countDown()
                owner.close()
                drain(cleanup)
            }
        }
    }

    private fun drain(tasks: MutableList<Runnable>) {
        while (tasks.isNotEmpty()) tasks.removeAt(0).run()
    }

    private fun fixtureBuilder() = OkHttpClient.Builder().apply { fixtureConfiguration() }
    private fun OkHttpClient.Builder.fixtureConfiguration() {
        dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                require(hostname == fixtureHost)
                return listOf(loopback)
            }
        })
        proxySelector(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                require(uri.host == fixtureHost) { "Only the explicit fixture alias is allowed" }
                return listOf(Proxy.NO_PROXY)
            }
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
        })
        protocols(listOf(Protocol.HTTP_1_1))
        connectTimeout(5, TimeUnit.SECONDS)
        readTimeout(5, TimeUnit.SECONDS)
    }
    private fun request(server: MockWebServer) = Request.Builder()
        .url(server.url("/media").newBuilder().host(fixtureHost).build()).build()

    private data class Tls(val context: SSLContext, val trust: X509TrustManager)
    private fun fixtureTls(): Tls {
        // Public test-only identity, valid 2000-2100 for this loopback alias. No trust-all verifier.
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(Base64.getDecoder().decode(TEST_CERTIFICATE).inputStream())
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(TEST_PRIVATE_KEY)))
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("fixture", privateKey, CharArray(0), arrayOf(certificate))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("fixture", certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf(trust), SecureRandom()) }
        return Tls(context, trust)
    }

    /** Records the actual SSLSocket close boundary, not a mock Call or only a raw TCP socket. */
    private class RecordingTlsSockets(private val delegate: SSLSocketFactory, blockClose: Boolean = false) : SSLSocketFactory() {
        val closeThreads = CopyOnWriteArrayList<Thread>()
        val closeStarted = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val allowClose = CountDownLatch(if (blockClose) 1 else 0)
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            record(delegate.createSocket(s, host, port, autoClose) as SSLSocket)
        override fun createSocket(host: String, port: Int): Socket = error("Use the preconnected fixture socket")
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("Use the preconnected fixture socket")
        override fun createSocket(host: InetAddress, port: Int): Socket = error("Use the preconnected fixture socket")
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("Use the preconnected fixture socket")

        private fun record(socket: SSLSocket): SSLSocket = object : SSLSocket() {
            override fun close() {
                closeThreads.add(Thread.currentThread())
                closeStarted.countDown()
                try {
                    check(allowClose.await(10, TimeUnit.SECONDS)) { "Fixture did not unblock TLS close" }
                    socket.close()
                } finally { closeFinished.countDown() }
            }
            override fun getInputStream() = socket.inputStream
            override fun getOutputStream() = socket.outputStream
            override fun getSupportedCipherSuites() = socket.supportedCipherSuites
            override fun getEnabledCipherSuites() = socket.enabledCipherSuites
            override fun setEnabledCipherSuites(suites: Array<String>) { socket.enabledCipherSuites = suites }
            override fun getSupportedProtocols() = socket.supportedProtocols
            override fun getEnabledProtocols() = socket.enabledProtocols
            override fun setEnabledProtocols(protocols: Array<String>) { socket.enabledProtocols = protocols }
            override fun getSession(): SSLSession = socket.session
            override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener) = socket.addHandshakeCompletedListener(listener)
            override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener) = socket.removeHandshakeCompletedListener(listener)
            override fun startHandshake() = socket.startHandshake()
            override fun setUseClientMode(mode: Boolean) { socket.useClientMode = mode }
            override fun getUseClientMode() = socket.useClientMode
            override fun setNeedClientAuth(need: Boolean) { socket.needClientAuth = need }
            override fun getNeedClientAuth() = socket.needClientAuth
            override fun setWantClientAuth(want: Boolean) { socket.wantClientAuth = want }
            override fun getWantClientAuth() = socket.wantClientAuth
            override fun setEnableSessionCreation(flag: Boolean) { socket.enableSessionCreation = flag }
            override fun getEnableSessionCreation() = socket.enableSessionCreation
            override fun getSSLParameters(): SSLParameters = socket.sslParameters
            override fun setSSLParameters(parameters: SSLParameters) { socket.sslParameters = parameters }
            override fun getApplicationProtocol(): String = socket.applicationProtocol
            override fun setSoTimeout(timeout: Int) { socket.soTimeout = timeout }
            override fun getSoTimeout() = socket.soTimeout
            override fun isClosed() = socket.isClosed
            override fun isConnected() = socket.isConnected
            override fun isInputShutdown() = socket.isInputShutdown
            override fun isOutputShutdown() = socket.isOutputShutdown
            override fun getInetAddress() = socket.inetAddress
            override fun getPort() = socket.port
            override fun getLocalAddress() = socket.localAddress
            override fun getLocalPort() = socket.localPort
        }
    }

    private companion object {
        val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
        const val fixtureHost = "player-fixture.invalid"
        const val TEST_CERTIFICATE = "MIIC9zCCAd+gAwIBAgIUQHcDWbxTqQSR3rhjXlVGV9olRewwDQYJKoZIhvcNAQELBQAwITEfMB0GA1UEAwwWcGxheWVyLWZpeHR1cmUuaW52YWxpZDAgFw0wMDAxMDEwMDAwMDBaGA8yMTAwMDEwMTAwMDAwMFowITEfMB0GA1UEAwwWcGxheWVyLWZpeHR1cmUuaW52YWxpZDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAJ3MtpBPRZzxQJg9CDGTbU3oUG2NM+Wim5SZOF8RQbZHsfBqt3HBW3+bq78UdoDpwhjLvkHLEEGIqRVQICF7MW8ioKJ/G0YJmk/nnvHiGbt2L8ko8HG9wjGAYepDT/LTpvPn+0gOQhy2EYMH4j0oI9QfMEmwWyHW7fDN31PXssEJc6DAMvu76DXB9fPQqJkGsDayeWESycPfMnbngkr0pV+HVrJmpbBlZz3msSeYEe9PvD24vy5sIfJTNfa+u59LH4yTWb2t0wXktD5pD/WuwGbIXwdIpSDUgHYpP1jzZ7qSrxEB+1EsrbK+1tCiKLCXetufum/qJHjFLzaBxT7iZRsCAwEAAaMlMCMwIQYDVR0RBBowGIIWcGxheWVyLWZpeHR1cmUuaW52YWxpZDANBgkqhkiG9w0BAQsFAAOCAQEAfXIm8jD0lScdvkSAstTfn0AhlotY1jqwFDRNuO7P6E4wJrg695FdiKYlH9ztEXAz6mrcM3g4SY2t8/CpCboqTPJDv02JdWizHgXHbE1Mqw/cVEfcsTGVS4XZ1dpy00G2yYpWCtCaffB5ayV74NZuGIdL9bF4e/7PLWoV/iHd12QYukqa8Tb0I2OZuFUjDACILuVuTK0Y+wpetClCGZI70ybwOLs7PT2MI4d1CfPYs1OXwp8EVKReV7b/31MimsXYeFAQYavZyYLaR/tHMo+muPDTqmjOnePaWrMk8fGhURjeQ2ZSBVGlrXY7fM4/j52Drcv6RbM7YztEU2LhRsa71g=="
        const val TEST_PRIVATE_KEY = "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCdzLaQT0Wc8UCYPQgxk21N6FBtjTPlopuUmThfEUG2R7HwardxwVt/m6u/FHaA6cIYy75ByxBBiKkVUCAhezFvIqCifxtGCZpP557x4hm7di/JKPBxvcIxgGHqQ0/y06bz5/tIDkIcthGDB+I9KCPUHzBJsFsh1u3wzd9T17LBCXOgwDL7u+g1wfXz0KiZBrA2snlhEsnD3zJ254JK9KVfh1ayZqWwZWc95rEnmBHvT7w9uL8ubCHyUzX2vrufSx+Mk1m9rdMF5LQ+aQ/1rsBmyF8HSKUg1IB2KT9Y82e6kq8RAftRLK2yvtbQoiiwl3rbn7pv6iR4xS82gcU+4mUbAgMBAAECggEAS5bQDyHCDXNth1+ZCLJ/5hV2TXwZ05MIpu6cl8Ga8je2z503IblHXMHTzBT/zTHsxdb3XOnqcBIIOMrokVFDdWKngx+TD4IzFrqzo1e1Dt0G9/vx7fJBz1eZz8+NwRrM+0JxVutplPpMOjGxGK2dOBP2nB3sEbI0yai8pZuKJ7hfSCuFFqVt49USfTrug9rQpCDL0f1Ifi5ABtaFYUyoM9M4R5snKJ+wv+Zft+ZR8ix1GFM7hewaCi9OAZgkxPDkYE2l7rdKXG9WHzq2KJdK1B4ULEaw41RxWJIZYKhtpNBzMicba/6U/5jgNHqmOvmTJ4MZ+4YDGLlDlRSpRQvdAQKBgQDenEkzNIGBOx9Vz0r75lkk1ItdXvUXOiUlS0f291sQ/bN4NHvFisdXT+NQA2y22wukONVtGdOhVSmyMVmgPDmdi0/VJZRmIiqLB8H2/d9a4Yj26HlvSYpenGvQa8A95tUXSCBtMCMpMh+0IQi8Z5kWNfduBjKNJ4jsuXGtG2AbAQKBgQC1d9hh/pGhmI6Bckr0qu/BYiIFeZNHMAp+kzfhu9KujvOTxeoiq2b7uuyFcnxCJICjoo7EbSpWuLzxhRyDHcYUZu0msQSPsPzp6L3L8RMrctXgUuCaRsBhRsynee7H6dSc9kVbsTrkupGkLWemY69nK5Pb8LQf2ecch8SPU/uMGwKBgG4GwQdWBExjdHFtK5qll5nkk51quajpTELKmp8uUwxq2LGo/yP8G9rD2Y5Kowkd6vsYPCTYhwlOlnVEfw/7tF5x5Nts35Q7ftuI0g3KHQNGRfQDo0GmD4YDuiYhm7r8xIXlWGGfUUGjTJgzW6YDbl7T/Z+b4JBz4fFfFxiAV1gBAoGBAKUSCmxBpHmpi1/m9pYPdB6mRKpUSAuGgNVY14loUCJneNygOPYmknxEMejGFpAYIkg3k8TMRKo0S/MrEZ+Xktp2Mh1zAuIurjGcfCGq/rQUNsdivFq6Jz+Vpo5l1TZW1wec4cShuB/eMqN0hgeOQD0KH+r+zur2TUvfmIaEb5qnAoGBALHt7DQ3m/83ShLzfxte1CbO21HJxF4Nz/PQSIpv28yuMidithbHSFTFBDnn2EIgUrBGOR63XFGwXM1ezrkwP5hfM/yH3LeavV9JxjMQdDOJXuHfabwA1T1G2uFOAY9OCOb7VE0DoFszkugBd07s17mD+x3e5crwq6evLqJT4WgJ"
    }
}
