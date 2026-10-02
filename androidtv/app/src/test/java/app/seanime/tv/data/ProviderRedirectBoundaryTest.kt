package app.seanime.tv.data

import app.seanime.tv.platform.PlaybackHeaderInterceptor
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class ProviderRedirectBoundaryTest {
    // Only this fixture maps a public-shaped test hostname to its local socket. Production's
    // secureClient installs its rejecting DNS; ProviderUrlPolicyTest verifies that separately.
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val fixtureHosts = setOf("localhost", "127.0.0.1", "api.example", "provider.example")
    private val fixtureDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname !in fixtureHosts) throw UnknownHostException("Unexpected fixture host")
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
    private fun fixtureApi(url: String, token: String? = null) = SeanimeApiClient(url, token,
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .proxySelector(fixtureProxySelector).dns(fixtureDns).build())

    private fun fixtureClientBuilder() = OkHttpClient.Builder().proxySelector(fixtureProxySelector)
        .dns(fixtureDns).addNetworkInterceptor(ProviderUrlPolicy.redirectGuard())

    @Test fun privateRedirectIsRejectedBeforeTargetReceivesARequest() {
        MockWebServer().use { source -> MockWebServer().use { privateTarget ->
            source.start(loopback, 0); privateTarget.start(loopback, 0)
            val initial = source.url("/video").newBuilder().host("provider.example").build()
            source.enqueue(MockResponse().setResponseCode(302).addHeader("Location", privateTarget.url("/api/v1/status")))
            val client = fixtureClientBuilder().build()
            try {
                assertTrue(runCatching { client.newCall(Request.Builder().url(initial).build()).execute().close() }.isFailure)
                assertEquals(1, source.requestCount)
                assertEquals(0, privateTarget.requestCount)
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        } }
    }

    @Test fun crossOriginRedirectDropsProviderHeadersAndNeverAcquiresServerHeaders() {
        MockWebServer().use { source -> MockWebServer().use { target ->
            source.start(loopback, 0); target.start(loopback, 0)
            val initial = source.url("/video").newBuilder().host("provider.example").build().toString()
            val api = target.url("/api/v1/status").newBuilder().host("api.example").build().toString()
            source.enqueue(MockResponse().setResponseCode(302).addHeader("Location", api))
            target.enqueue(MockResponse().setBody("fixture"))
            val context = ProviderMediaContext(true, api, false, initial, mapOf("Cookie" to "provider-secret", "Referer" to "https://provider.example/", "X-Provider-Key" to "key")) {
                mapOf("X-Seanime-Token" to "server-secret")
            }
            val originalDefault = ProxySelector.getDefault()
            MockWebServer().use { proxy ->
                proxy.start(loopback, 0)
                proxy.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
                var proxySelections = 0
                val hostileSelector = object : ProxySelector() {
                    override fun select(uri: URI): List<Proxy> {
                        proxySelections++
                        return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(loopback, proxy.port)))
                    }
                    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
                }
                val hostileClient = fixtureClientBuilder().proxySelector(hostileSelector)
                    .retryOnConnectionFailure(false).callTimeout(2, TimeUnit.SECONDS).build()
                try {
                    val failure = assertThrows(IOException::class.java) {
                        hostileClient.newCall(Request.Builder().url(initial).build()).execute().close()
                    }
                    assertTrue("the controlled proxy must close before response headers", failure.cause is EOFException)
                    assertEquals(1, proxySelections)
                    assertEquals(0, source.requestCount)
                    assertEquals(0, target.requestCount)
                } finally { hostileClient.connectionPool.evictAll(); hostileClient.dispatcher.executorService.shutdown() }
            }
            val client = fixtureClientBuilder()
                .addNetworkInterceptor(PlaybackHeaderInterceptor { context.headersFor(initial, it) }).build()
            try {
                client.newCall(Request.Builder().url(initial).build()).execute().use { assertEquals(200, it.code) }
                assertEquals("provider-secret", source.takeRequest().getHeader("Cookie"))
                val redirected = target.takeRequest()
                for (header in listOf("Cookie", "Referer", "X-Provider-Key", "X-Seanime-Token", "X-Seanime-Client-Id")) assertNull(redirected.getHeader(header))
                assertSame(originalDefault, ProxySelector.getDefault())
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        } }
    }

    @Test fun providerImageCannotTargetTheSelectedLoopbackServer() {
        MockWebServer().use { server ->
            server.start(loopback, 0)
            fixtureApi(server.url("/").toString(), "private-token").use { api ->
                NativeImageTransport(api, proxySelector = fixtureProxySelector).use { transport ->
                    val request = Request.Builder().url(server.url("/api/v1/status"))
                        .tag(NativeImageTransport.SourceHeaders::class.java, NativeImageTransport.SourceHeaders(emptyMap(), true)).build()
                    assertTrue(runCatching { transport.newCall(request) }.isFailure)
                    assertEquals(0, server.requestCount)
                }
            }
        }
    }
}
