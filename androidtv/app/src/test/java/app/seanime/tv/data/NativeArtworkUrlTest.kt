package app.seanime.tv.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeArtworkUrlTest {
    @Test fun `offline anime and manga artwork resolve to the unchanged static route`() {
        val media = JSONObject("""{"id":42,"title":{"userPreferred":"Offline title"},"coverImage":{"extraLarge":"{{LOCAL_ASSETS}}/42/cover.jpg"},"bannerImage":"{{LOCAL_ASSETS}}/42/banner.jpg"}""")
        for (isManga in listOf(false, true)) {
            val card = SeanimeJson.media(media, manga = isManga)
            assertEquals("http://127.0.0.1:43211/offline-assets/42/cover.jpg",
                NativeArtworkUrl.resolve("http://127.0.0.1:43211/some-base?ignored=yes#fragment", card.imageUrl))
            assertEquals("http://127.0.0.1:43211/offline-assets/42/banner.jpg",
                NativeArtworkUrl.resolve("http://127.0.0.1:43211", card.bannerUrl))
        }
    }

    @Test fun `offline filenames remain path segments rather than query or fragment syntax`() {
        val resolved = NativeArtworkUrl.resolve("https://user:password@EXAMPLE.invalid:8443/path?private=yes",
            "{{LOCAL_ASSETS}}/42/封面 100% #1?.jpg")!!.toHttpUrl()
        assertEquals("https://example.invalid:8443", resolved.newBuilder().encodedPath("/").build().toString().trimEnd('/'))
        assertEquals(listOf("offline-assets", "42", "封面 100% #1?.jpg"), resolved.pathSegments)
        assertNull(resolved.query)
        assertNull(resolved.fragment)
        assertEquals("", resolved.username)
        assertEquals("", resolved.password)
    }

    @Test fun `server relative and remote plugin images retain their actual destination`() {
        val base = "https://127.0.0.1:43211/base/?stale=true"
        assertEquals("https://127.0.0.1:43211/assets/icon.png", NativeArtworkUrl.resolve(base, "/assets/icon.png"))
        assertEquals("https://127.0.0.1:43211/offline-assets/42/cover.jpg", NativeArtworkUrl.resolve(base, "offline-assets/42/cover.jpg"))
        assertEquals("https://cdn.example:8443/cover.jpg?signature=abc", NativeArtworkUrl.resolve(base, "https://cdn.example:8443/cover.jpg?signature=abc"))
        assertEquals("https://cdn.example/cover.jpg", NativeArtworkUrl.resolve(base, "//cdn.example/cover.jpg"))
        assertEquals("http://[::1]:43211/offline-assets/42/cover.jpg", NativeArtworkUrl.resolve("http://[::1]:43211", "{{LOCAL_ASSETS}}/42/cover.jpg"))
    }

    @Test fun `malformed markers and non network image sources cannot access Android storage`() {
        for (source in listOf(null, "", "  ", "{{LOCAL_ASSETS}}", "{{LOCAL_ASSETS}}evil/cover.jpg", "{{LOCAL_ASSETS}}/",
            "{{LOCAL_ASSETS}}/../private.jpg", "{{LOCAL_ASSETS}}/42/./cover.jpg", "{{OTHER}}/cover.jpg",
            "file:///data/private.jpg", "content://private/cover", "android.resource://app.seanime.tv/private",
            "data:image/png;base64,AA==", "javascript:alert(1)", "ftp://example.invalid/cover.jpg",
            "https://user:password@example.invalid/cover.jpg")) {
            assertNull(source, NativeArtworkUrl.resolve("http://127.0.0.1:43211", source))
        }
    }

    @Test fun `resolved offline artwork gets fresh server headers while remote artwork and redirects do not`() {
        MockWebServer().use { server -> MockWebServer().use { external ->
            server.start(); external.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", external.url("/redirected-cover.jpg")))
            repeat(2) { external.enqueue(MockResponse().setBody("cover bytes")) }
            SeanimeApiClient(server.url("/").toString(), "artwork-secret").use { api ->
                api.restoreSession(SeanimeApiClient.SessionSnapshot(api.snapshotSession().canonicalOrigin,
                    "artwork-client", "artwork-proof", "artwork-secret"))
                NativeImageTransport(api).use { transport ->
                    val offline = NativeArtworkUrl.resolve(api.baseUrl, "{{LOCAL_ASSETS}}/42/cover.jpg")!!
                    transport.newCall(Request.Builder().url(offline).build()).execute().use {
                        assertEquals(200, it.code)
                        assertEquals("cover bytes", it.body!!.string())
                    }
                    val local = server.takeRequest()
                    assertEquals("/offline-assets/42/cover.jpg", local.path)
                    assertEquals("artwork-secret", local.getHeader("X-Seanime-Token"))
                    assertEquals("artwork-client", local.getHeader("X-Seanime-Client-Id"))
                    assertEquals("artwork-proof", local.getHeader("X-Seanime-Client-Id-Proof"))
                    assertEquals(api.snapshotSession().canonicalOrigin, local.getHeader("Origin"))
                    val remote = NativeArtworkUrl.resolve(api.baseUrl, external.url("/direct-cover.jpg").toString())!!
                    transport.newCall(Request.Builder().url(remote).build()).execute().close()
                    repeat(2) {
                        val request = external.takeRequest()
                        for (name in listOf("X-Seanime-Token", "X-Seanime-Client-Id", "X-Seanime-Client-Id-Proof", "Origin", "Referer")) {
                            assertNull("$name must remain on the original server origin", request.getHeader(name))
                        }
                    }
                }
            }
        } }
    }
}
