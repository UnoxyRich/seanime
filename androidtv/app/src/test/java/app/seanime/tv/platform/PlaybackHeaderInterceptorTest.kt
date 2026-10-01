package app.seanime.tv.platform

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PlaybackHeaderInterceptorTest {
    private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port

    @Test fun `cross origin redirect removes server secrets and preserves byte range`() {
        MockWebServer().use { server -> MockWebServer().use { external ->
            server.start(); external.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", external.url("/stream")))
            external.enqueue(MockResponse().setBody("video"))
            val serverOrigin = server.url("/").toString().trimEnd('/')
            val http = OkHttpClient.Builder().addNetworkInterceptor(PlaybackHeaderInterceptor { url ->
                if (sameOrigin(url.toHttpUrl(), server.url("/"))) mapOf("X-Seanime-Token" to "server-secret", "X-Seanime-Client-Id-Proof" to "proof", "Origin" to serverOrigin, "Range" to "bytes=0-") else emptyMap()
            }).build()
            http.newCall(Request.Builder().url(server.url("/start")).header("Range", "bytes=42-")
                .header("Authorization", "untrusted-data-spec").header("Cookie", "untrusted-cookie").build()).execute().use { assertEquals(200, it.code) }
            val initial = server.takeRequest()
            assertEquals("server-secret", initial.getHeader("X-Seanime-Token"))
            assertEquals(serverOrigin, initial.getHeader("Origin"))
            assertEquals("bytes=42-", initial.getHeader("Range"))
            val redirected = external.takeRequest()
            assertNull(redirected.getHeader("X-Seanime-Token"))
            assertNull(redirected.getHeader("X-Seanime-Client-Id-Proof"))
            assertNull(redirected.getHeader("Origin"))
            assertNull(redirected.getHeader("Authorization"))
            assertNull(redirected.getHeader("Cookie"))
            assertEquals("bytes=42-", redirected.getHeader("Range"))
        } }
    }

    @Test fun `unapproved origin metadata is stripped from the initial data spec`() {
        MockWebServer().use { external ->
            external.start()
            external.enqueue(MockResponse().setBody("video"))
            val http = OkHttpClient.Builder().addNetworkInterceptor(PlaybackHeaderInterceptor { emptyMap() }).build()
            http.newCall(Request.Builder().url(external.url("/stream"))
                .header("oRiGiN", "http://127.0.0.1:43211")
                .header("rEfErEr", "http://127.0.0.1:43211/library")
                .header("X-Seanime-Token", "stale-server-secret")
                .header("Range", "bytes=42-").build()).execute().use { assertEquals(200, it.code) }
            val request = external.takeRequest()
            assertNull(request.getHeader("Origin"))
            assertNull(request.getHeader("Referer"))
            assertNull(request.getHeader("X-Seanime-Token"))
            assertEquals("bytes=42-", request.getHeader("Range"))
        }
    }

    @Test fun `return to exact server origin restores credentials on that hop only`() {
        MockWebServer().use { server -> MockWebServer().use { external ->
            server.start(); external.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", external.url("/relay")))
            external.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/final")))
            server.enqueue(MockResponse().setBody("video"))
            val serverOrigin = server.url("/").toString().trimEnd('/')
            val http = OkHttpClient.Builder().addNetworkInterceptor(PlaybackHeaderInterceptor { url ->
                if (sameOrigin(url.toHttpUrl(), server.url("/"))) mapOf("X-Seanime-Token" to "secret", "Origin" to serverOrigin) else emptyMap()
            }).build()
            http.newCall(Request.Builder().url(server.url("/start")).build()).execute().use { assertEquals(200, it.code) }
            val initial = server.takeRequest()
            assertEquals("secret", initial.getHeader("X-Seanime-Token"))
            assertEquals(serverOrigin, initial.getHeader("Origin"))
            val relay = external.takeRequest()
            assertNull(relay.getHeader("X-Seanime-Token"))
            assertNull(relay.getHeader("Origin"))
            val returned = server.takeRequest()
            assertEquals("secret", returned.getHeader("X-Seanime-Token"))
            assertEquals(serverOrigin, returned.getHeader("Origin"))
        } }
    }

    @Test fun `provider cookie and custom header do not follow cross port redirect`() {
        MockWebServer().use { provider -> MockWebServer().use { cdn ->
            provider.start(); cdn.start()
            provider.enqueue(MockResponse().setResponseCode(302).addHeader("Location", cdn.url("/segment")))
            cdn.enqueue(MockResponse().setBody("media"))
            val http = OkHttpClient.Builder().addNetworkInterceptor(PlaybackHeaderInterceptor { url ->
                if (sameOrigin(url.toHttpUrl(), provider.url("/"))) mapOf("Cookie" to "source-cookie", "X-Playback-Key" to "source-key", "Origin" to "https://provider.example", "Referer" to "https://provider.example/watch") else emptyMap()
            }).build()
            http.newCall(Request.Builder().url(provider.url("/manifest")).build()).execute().use { assertEquals(200, it.code) }
            val initial = provider.takeRequest()
            assertEquals("source-key", initial.getHeader("X-Playback-Key"))
            assertEquals("source-cookie", initial.getHeader("Cookie"))
            assertEquals("https://provider.example", initial.getHeader("Origin"))
            assertEquals("https://provider.example/watch", initial.getHeader("Referer"))
            val redirected = cdn.takeRequest()
            assertNull(redirected.getHeader("X-Playback-Key"))
            assertNull(redirected.getHeader("Cookie"))
            assertNull(redirected.getHeader("Origin"))
            assertNull(redirected.getHeader("Referer"))
        } }
    }
}
