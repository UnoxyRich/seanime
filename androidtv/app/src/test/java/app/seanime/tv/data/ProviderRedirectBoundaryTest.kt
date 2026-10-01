package app.seanime.tv.data

import app.seanime.tv.platform.PlaybackHeaderInterceptor
import java.net.InetAddress
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProviderRedirectBoundaryTest {
    // Only this fixture maps a public-shaped test hostname to its local socket. Production's
    // secureClient installs its rejecting DNS; ProviderUrlPolicyTest verifies that separately.
    private val fixtureDns = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1")) }

    @Test fun privateRedirectIsRejectedBeforeTargetReceivesARequest() {
        MockWebServer().use { source -> MockWebServer().use { privateTarget ->
            source.start(); privateTarget.start()
            val initial = source.url("/video").newBuilder().host("provider.example").build()
            source.enqueue(MockResponse().setResponseCode(302).addHeader("Location", privateTarget.url("/api/v1/status")))
            val client = OkHttpClient.Builder().dns(fixtureDns).addNetworkInterceptor(ProviderUrlPolicy.redirectGuard()).build()
            try {
                assertTrue(runCatching { client.newCall(Request.Builder().url(initial).build()).execute().close() }.isFailure)
                assertEquals(1, source.requestCount)
                assertEquals(0, privateTarget.requestCount)
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        } }
    }

    @Test fun crossOriginRedirectDropsProviderHeadersAndNeverAcquiresServerHeaders() {
        MockWebServer().use { source -> MockWebServer().use { target ->
            source.start(); target.start()
            val initial = source.url("/video").newBuilder().host("provider.example").build().toString()
            val api = target.url("/api/v1/status").newBuilder().host("api.example").build().toString()
            source.enqueue(MockResponse().setResponseCode(302).addHeader("Location", api))
            target.enqueue(MockResponse().setBody("fixture"))
            val context = ProviderMediaContext(true, api, false, initial, mapOf("Cookie" to "provider-secret", "Referer" to "https://provider.example/", "X-Provider-Key" to "key")) {
                mapOf("X-Seanime-Token" to "server-secret")
            }
            val client = OkHttpClient.Builder().dns(fixtureDns).addNetworkInterceptor(ProviderUrlPolicy.redirectGuard())
                .addNetworkInterceptor(PlaybackHeaderInterceptor { context.headersFor(initial, it) }).build()
            try {
                client.newCall(Request.Builder().url(initial).build()).execute().use { assertEquals(200, it.code) }
                assertEquals("provider-secret", source.takeRequest().getHeader("Cookie"))
                val redirected = target.takeRequest()
                for (header in listOf("Cookie", "Referer", "X-Provider-Key", "X-Seanime-Token", "X-Seanime-Client-Id")) assertNull(redirected.getHeader(header))
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        } }
    }

    @Test fun providerImageCannotTargetTheSelectedLoopbackServer() {
        MockWebServer().use { server ->
            server.start()
            SeanimeApiClient(server.url("/").toString(), "private-token").use { api ->
                NativeImageTransport(api).use { transport ->
                    val request = Request.Builder().url(server.url("/api/v1/status"))
                        .tag(NativeImageTransport.SourceHeaders::class.java, NativeImageTransport.SourceHeaders(emptyMap(), true)).build()
                    assertTrue(runCatching { transport.newCall(request) }.isFailure)
                    assertEquals(0, server.requestCount)
                }
            }
        }
    }
}
