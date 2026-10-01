package app.seanime.tv.data

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class NativeImageTransportTest {
    private fun request(url: String, headers: Map<String, String> = emptyMap()): Request =
        Request.Builder().url(url)
            .tag(NativeImageTransport.SourceHeaders::class.java, NativeImageTransport.SourceHeaders(headers)).build()

    private fun assertNoSourceHeaders(request: RecordedRequest) {
        for (name in listOf("X-Seanime-Token", "X-Seanime-Client-Id", "X-Seanime-Client-Id-Proof", "Authorization", "Cookie", "Origin", "Referer", "X-Image-Key")) {
            assertNull("$name must not leave the original image origin", request.getHeader(name))
        }
    }

    @Test fun `server image redirects load without forwarding server authority`() {
        MockWebServer().use { server -> MockWebServer().use { cdn ->
            server.start(); cdn.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", cdn.url("/page.jpg")))
            cdn.enqueue(MockResponse().setBody("image bytes"))
            SeanimeApiClient(server.url("/").toString(), "current-token").use { api ->
                NativeImageTransport(api).use { transport ->
                    val sourceHeaders = mapOf("X-Seanime-Token" to "stale-token", "Origin" to "http://obsolete.invalid", "Referer" to "http://obsolete.invalid/library")
                    transport.newCall(request(server.url("/page").toString(), sourceHeaders)).execute().use {
                        assertEquals(200, it.code)
                        assertEquals("image bytes", it.body!!.string())
                    }
                    val initial = server.takeRequest()
                    assertEquals("current-token", initial.getHeader("X-Seanime-Token"))
                    assertEquals(api.snapshotSession().canonicalOrigin, initial.getHeader("Origin"))
                    assertNotNull(initial.getHeader("X-Seanime-Client-Id"))
                    assertNull(initial.getHeader("Referer"))
                    assertNoSourceHeaders(cdn.takeRequest())
                }
            }
        } }
    }

    @Test fun `provider headers survive its own redirect but never gain server credentials`() {
        MockWebServer().use { server -> MockWebServer().use { provider ->
            server.start(); provider.start()
            provider.enqueue(MockResponse().setResponseCode(302).addHeader("Location", provider.url("/same-origin")))
            provider.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/untrusted-relay")))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", provider.url("/returned")))
            provider.enqueue(MockResponse().setBody("image bytes"))
            SeanimeApiClient(server.url("/").toString(), "server-secret").use { api ->
                NativeImageTransport(api).use { transport ->
                    val sourceHeaders = mapOf("Authorization" to "Bearer provider-token", "Cookie" to "provider-cookie",
                        "Origin" to "https://provider.example", "Referer" to "https://provider.example/chapter",
                        "X-Image-Key" to "provider-key", "X-Seanime-Token" to "unapproved-native-token")
                    transport.newCall(request(provider.url("/page").toString(), sourceHeaders)).execute().use { assertEquals(200, it.code) }
                    repeat(3) {
                        val sourceRequest = provider.takeRequest()
                        for ((name, value) in sourceHeaders.filterKeys { !it.startsWith("X-Seanime-") }) assertEquals(value, sourceRequest.getHeader(name))
                        assertNull(sourceRequest.getHeader("X-Seanime-Token"))
                        assertNull(sourceRequest.getHeader("X-Seanime-Client-Id"))
                    }
                    assertNoSourceHeaders(server.takeRequest())
                }
            }
        } }
    }

    @Test fun `returning to the original server refreshes its session headers`() {
        MockWebServer().use { server -> MockWebServer().use { relay ->
            server.start(); relay.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", relay.url("/relay")))
            server.enqueue(MockResponse().setBody("image bytes"))
            SeanimeApiClient(server.url("/").toString(), "first-token").use { api ->
                relay.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        api.setServerToken("refreshed-token")
                        return MockResponse().setResponseCode(302).addHeader("Location", server.url("/returned"))
                    }
                }
                NativeImageTransport(api).use { transport ->
                    transport.newCall(request(server.url("/page").toString(), api.requestHeaders())).execute().use { assertEquals(200, it.code) }
                    assertEquals("first-token", server.takeRequest().getHeader("X-Seanime-Token"))
                    assertNoSourceHeaders(relay.takeRequest())
                    val returned = server.takeRequest()
                    assertEquals("refreshed-token", returned.getHeader("X-Seanime-Token"))
                    assertEquals(api.snapshotSession().canonicalOrigin, returned.getHeader("Origin"))
                }
            }
        } }
    }

    @Test fun `image policies cannot bleed into independent requests on the same loader`() {
        MockWebServer().use { server -> MockWebServer().use { provider ->
            server.start(); provider.start()
            repeat(2) { provider.enqueue(MockResponse().setBody("image bytes")) }
            SeanimeApiClient(server.url("/").toString(), "server-secret").use { api ->
                NativeImageTransport(api).use { transport ->
                    transport.newCall(request(provider.url("/private").toString(), mapOf("Cookie" to "source-cookie", "X-Image-Key" to "private-key")))
                        .execute().close()
                    transport.newCall(request(provider.url("/public").toString())).execute().close()
                    assertEquals("source-cookie", provider.takeRequest().getHeader("Cookie"))
                    assertNoSourceHeaders(provider.takeRequest())
                }
            }
        } }
    }

    @Test fun `closing reader transport cancels pending images and rejects new calls`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val transport = NativeImageTransport(api)
                val failed = CountDownLatch(1)
                val imageRequest = request(server.url("/pending").toString())
                val pending = transport.newCall(imageRequest)
                try {
                    pending.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) { failed.countDown() }
                        override fun onResponse(call: Call, response: Response) { response.close() }
                    })
                    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    transport.close()
                    assertTrue(failed.await(5, TimeUnit.SECONDS))
                    assertTrue(pending.isCanceled())
                    assertTrue(runCatching { transport.newCall(imageRequest) }.exceptionOrNull() is IllegalStateException)
                    assertEquals("SourceHeaders(redacted)", NativeImageTransport.SourceHeaders(mapOf("Cookie" to "secret")).toString())
                } finally { transport.close() }
            }
        }
    }
}
