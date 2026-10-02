package app.seanime.tv.data

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException

class MediaArtworkOriginTest {
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


    @Test fun customIdentityAndProviderFlagsCannotImpersonateServerArtwork() {
        val server = SeanimeJson.media(JSONObject("""{"id":2147483647,"coverImage":{"large":"http://192.168.1.2/cover.png"}}"""))
        assertFalse(server.providerArtwork())
        for (id in listOf(2_147_483_648L, 4_294_967_297L, MAX_NATIVE_MEDIA_ID)) {
            val media = SeanimeJson.media(JSONObject("""{"id":$id,"isDownloaded":true,"artworkOrigin":"SERVER","coverImage":{"large":"{{LOCAL_ASSETS}}/$id/cover.png"}}"""))
            assertEquals(id, media.id)
            assertEquals(MediaArtworkOrigin.PROVIDER, media.artworkOrigin)
            assertTrue(media.providerArtwork())
            assertTrue(media.providerArtwork("http://192.168.1.2/image.png"))
            assertTrue(media.providerArtwork("https://api.example/cover.png"))
        }
    }

    @Test fun offlineAuthorityIsLimitedToTheRequestedTitlesSnapshotAssets() {
        val media = MediaCard(4_294_967_297L, "Downloaded", artworkOrigin = MediaArtworkOrigin.LOCAL_ASSET)
        assertFalse(media.providerArtwork("{{LOCAL_ASSETS}}/${media.id}/cover.png"))
        for (url in listOf("{{LOCAL_ASSETS}}/1/cover.png", "{{LOCAL_ASSETS}}/${media.id}/../private.png", "/private.png",
            "{{LOCAL_ASSETS}}/${media.id}/%2e%2e.png", "{{LOCAL_ASSETS}}/${media.id}/folder/cover.png", "{{LOCAL_ASSETS}}/${media.id}/registry.json",
            "http://127.0.0.1/image.png", "https://api.example/image.png")) assertTrue(url, media.providerArtwork(url))
    }

    @Test fun offlineStatusAuthorizesDetailEpisodesAndCollectionsWithoutTrustingOnlineMarkers() = runBlocking {
        MockWebServer().use { server ->
            server.start(loopback, 0)
            fixtureApi(server.url("/").toString()).use { api ->
                val repo = SeanimeRepository(api)
                val id = MAX_NATIVE_MEDIA_ID
                for (offline in listOf(false, true)) {
                    server.enqueue(data("""{"media":{"id":$id,"coverImage":{"large":"https://provider.example/cover.png"}},"episodes":[{"episodeNumber":1,"episodeMetadata":{"image":"{{LOCAL_ASSETS}}/$id/episode.png"}}]}"""))
                    server.enqueue(data("""{"isOffline":$offline}"""))
                    val details = repo.animeDetails(id)
                    assertEquals(offline, !details.media.providerArtwork(details.episodes.single().imageUrl))
                    assertTrue(details.media.providerArtwork())
                    assertEquals("/api/v1/library/anime-entry/$id", server.takeRequest().path)
                    assertEquals("/api/v1/status", server.takeRequest().path)
                    server.enqueue(data("""[{"media":{"id":$id,"coverImage":{"large":"{{LOCAL_ASSETS}}/$id/cover.png"}}}]"""))
                    server.enqueue(data("""{"isOffline":$offline}"""))
                    assertEquals(offline, !loadPersonalCollection(repo, manga = true).entries.single().media.providerArtwork())
                    assertEquals("/api/v1/manga/collection", server.takeRequest().path)
                    assertEquals("/api/v1/status", server.takeRequest().path)
                }
            }
        }
    }

    @Test fun customImageAtTheApiOriginMakesARealRequestWithoutServerCredentials() {
        MockWebServer().use { server ->
            server.start(loopback, 0)
            val url = server.url("/cover.png").newBuilder().host("api.example").build().toString()
            server.enqueue(MockResponse().setBody("fixture image"))
            fixtureApi(url, "server-secret").use { api ->
                val media = MediaCard(MAX_NATIVE_MEDIA_ID, "Custom", imageUrl = url)
                val request = Request.Builder().url(url).tag(NativeImageTransport.SourceHeaders::class.java,
                    NativeImageTransport.SourceHeaders(mapOf("X-Seanime-Token" to "forged-token"), media.providerArtwork())).build()
                val originalDefault = ProxySelector.getDefault()
                var proxySelections = 0
                val hostileSelector = object : ProxySelector() {
                    override fun select(uri: URI): List<Proxy> {
                        proxySelections++
                        return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(loopback, server.port)))
                    }
                    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
                }
                // A per-client proxy reproduces the fixture failure without changing global routing.
                NativeImageTransport(api, providerDns = fixtureDns, proxySelector = hostileSelector).use { transport ->
                    assertThrows(ProtocolException::class.java) { transport.newCall(request).execute().close() }
                    assertEquals(1, proxySelections)
                    assertEquals(0, server.requestCount)
                }
                NativeImageTransport(api, providerDns = fixtureDns, proxySelector = fixtureProxySelector).use { transport ->
                    transport.newCall(request).execute().use { assertEquals("fixture image", it.body!!.string()) }
                    val received = server.takeRequest()
                    assertEquals("/cover.png", received.path)
                    for (name in listOf("X-Seanime-Token", "X-Seanime-Client-Id", "X-Seanime-Client-Id-Proof", "Authorization", "Cookie", "Origin", "Referer"))
                        assertNull(name, received.getHeader(name))
                    val blocked = request.newBuilder().url(server.url("/local.png")).build()
                    assertTrue(runCatching { transport.newCall(blocked) }.exceptionOrNull() is IllegalArgumentException)
                    assertEquals(1, server.requestCount)
                }
                assertSame(originalDefault, ProxySelector.getDefault())
            }
        }
    }

    @Test fun localAssetCapabilityHasNoCredentialsNoRedirectsAndNoApiOrTraversalAuthority() {
        MockWebServer().use { server ->
            server.start(loopback, 0)
            val id = MAX_NATIVE_MEDIA_ID
            fixtureApi(server.url("/").toString(), "server-secret").use { api ->
                NativeImageTransport(api, proxySelector = fixtureProxySelector).use { transport ->
                    fun asset(path: String, mediaId: Long = id): Request = Request.Builder().url(server.url(path))
                        .header("Authorization", "Bearer hidden").header("X-Image-Key", "hidden").header("Cookie", "hidden")
                        .tag(NativeImageTransport.SourceHeaders::class.java,
                            NativeImageTransport.SourceHeaders(api.requestHeaders() + mapOf("Referer" to "hidden"), offlineAssetMediaId = mediaId)).build()
                    val path = "/offline-assets/$id/cover.png"
                    server.enqueue(MockResponse().setBody("cached pixels"))
                    transport.newCall(asset(path)).execute().use { assertEquals("cached pixels", it.body!!.string()) }
                    val received = server.takeRequest()
                    for (header in listOf("X-Seanime-Token", "X-Seanime-Client-Id", "X-Seanime-Client-Id-Proof", "Origin", "Referer", "Cookie", "Authorization", "X-Image-Key"))
                        assertNull(header, received.getHeader(header))
                    server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/api/v1/status"))
                    transport.newCall(asset(path)).execute().use { assertEquals(302, it.code) }
                    assertEquals(path, server.takeRequest().path)
                    for (invalid in listOf("/api/v1/status", "/offline-assets/1/cover.png", "/offline-assets/$id/../cover.png",
                        "/offline-assets/$id/%2e%2e%2fcover.png", "/offline-assets/$id/%252e%252e%252fcover.png",
                        "/offline-assets/$id/folder/cover.png", "/offline-assets/$id/registry.json", "$path?token=secret", "$path#fragment")) {
                        assertTrue(invalid, runCatching { transport.newCall(asset(invalid)) }.exceptionOrNull() is IllegalArgumentException)
                    }
                    assertTrue(runCatching { transport.newCall(asset(path).newBuilder().post("bad".toRequestBody()).build()) }.exceptionOrNull() is IllegalArgumentException)
                    assertTrue(runCatching { transport.newCall(asset(path).newBuilder().url("https://other.example$path").build()) }.exceptionOrNull() is IllegalArgumentException)
                    assertEquals(2, server.requestCount)
                }
            }
        }
    }

    private fun data(raw: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("{\"data\":$raw}")
}
