package app.seanime.tv.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SeanimeApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: SeanimeApiClient
    private lateinit var repo: SeanimeRepository

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = SeanimeApiClient(server.url("/").toString(), "test-hash")
        repo = SeanimeRepository(client)
    }
    @After fun tearDown() { client.close(); server.shutdown() }

    @Test fun nativeIdentityAndProofCarryAcrossRequests() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"serverReady":true}}""").addHeader("X-Seanime-Client-Id", "signed-client").addHeader("X-Seanime-Client-Id-Proof", "signed-proof"))
        server.enqueue(MockResponse().setBody("""{"data":[]} """))
        repo.status()
        repo.playlists()
        val first = server.takeRequest()
        assertEquals("androidtv", first.getHeader("X-Seanime-Client-Platform"))
        assertEquals("test-hash", first.getHeader("X-Seanime-Token"))
        val second = server.takeRequest()
        assertEquals("signed-client", second.getHeader("X-Seanime-Client-Id"))
        assertEquals("signed-proof", second.getHeader("X-Seanime-Client-Id-Proof"))
    }

    @Test fun searchAlwaysProvidesRequiredPagePointers() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"Page":{"media":[]}}}"""))
        repo.search("cowboy")
        val request = server.takeRequest()
        assertEquals("/api/v1/anilist/list-anime", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals(1, body.getInt("page"))
        assertEquals(40, body.getInt("perPage"))
        assertEquals("cowboy", body.getString("search"))
    }

    @Test fun mangaPagesResolveLocalAndDownloadedUrlsWithoutLeakingAuthentication() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"isDownloaded":false,"pages":[{"index":2,"url":"https://provider.example/page.jpg","headers":{"Referer":"https://provider.example/"}},{"index":1,"url":"{{manga-local-assets}}/book/page 1.jpg"}]}}"""))
        val pages = repo.mangaPages(42, "chapter-1", "local-manga")
        assertTrue(pages[0].url.contains("/api/v1/manga/local-page/%7B%7Bmanga-local-assets%7D%7D%2Fbook%2Fpage%201.jpg"))
        assertEquals("test-hash", pages[0].headers["X-Seanime-Token"])
        assertEquals("https://provider.example/", pages[1].headers["Referer"])
        assertFalse(pages[1].headers.containsKey("X-Seanime-Token"))
        assertFalse(pages[1].headers.containsKey("Origin"))
        server.enqueue(MockResponse().setBody("""{"data":{"isDownloaded":true,"pages":[{"index":0,"url":"42/chapter/page 2.jpg"}]}}"""))
        assertTrue(repo.mangaPages(42, "chapter", "provider").single().url.contains("/manga-downloads/42/chapter/page%202.jpg"))
    }

    @Test fun binaryReportDownloadIsNotParsedAsJson() = runBlocking {
        server.enqueue(MockResponse().setBody("PK_zip_fixture").setHeader("Content-Type", "application/zip"))
        assertEquals("PK_zip_fixture", repo.downloadIssueReport().toString(Charsets.UTF_8))
    }

    @Test fun requestsRejectCredentialExfiltrationToDifferentOrigin() = runBlocking {
        val error = runCatching { client.request("GET", "https://other.example/api/v1/status") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertNull(server.takeRequest(50, TimeUnit.MILLISECONDS))
    }

    @Test fun redirectsCannotCarryIdentityOrOriginToAnotherDestination() = runBlocking {
        MockWebServer().use { external ->
            external.start()
            external.enqueue(MockResponse().setBody("""{"data":true}"""))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", external.url("/api/v1/status")))
            val error = runCatching { client.request("GET", "/api/v1/status") }.exceptionOrNull()
            assertTrue(error is ApiException)
            assertEquals(302, (error as ApiException).statusCode)
            val initial = server.takeRequest()
            assertEquals(server.url("/").toString().trimEnd('/'), initial.getHeader("Origin"))
            assertEquals("test-hash", initial.getHeader("X-Seanime-Token"))
            assertNotNull(initial.getHeader("X-Seanime-Client-Id"))
            assertNull(external.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun cancellationCancelsTheUnderlyingCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async { client.request("GET", "/api/v1/status") }
        // Yield so the suspended coroutine enqueues its HTTP request.
        kotlinx.coroutines.yield()
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        pending.cancel()
        pending.join()
        assertTrue(pending.isCancelled)
    }
    @Test fun omittedScorePreservesExistingListScoreAndUsesRegisteredRoute() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":true}"""))
        repo.editListEntry(42, "CURRENT", 3)
        val request = server.takeRequest()
        assertEquals("/api/v1/anilist/list-entry", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertFalse(body.has("score"))
        assertEquals("anime", body.getString("type"))
    }

    @Test fun torrentFilePreviewArrayNormalizesWithoutLosingIndex() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"displayTitle":"Episode 3","index":4,"fileId":"7","isLikely":true}]}"""))
        val raw = JSONObject("""{"name":"Series","infoHash":"hash"}""")
        val result = repo.torrentFilePreviews(MediaCard(42, "Series"), 3, SeanimeJson.torrent(raw), true)
        assertEquals(4, result.getJSONArray("files").getJSONObject(0).getInt("index"))
        assertEquals("7", result.getJSONArray("files").getJSONObject(0).getString("fileId"))
        assertEquals("/api/v1/debrid/torrents/file-previews", server.takeRequest().path)
    }

    @Test fun extensionPermissionFailuresRemainVisibleInNativeManagement() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"extensions":[],"disabledExtensions":[],"invalidExtensions":[{"reason":"plugin_permissions_not_granted","extension":{"id":"plugin","name":"A Plugin","type":"plugin"}}]}}"""))
        val item = repo.extensions().single()
        assertEquals("plugin", item.id)
        assertTrue(item.disabled)
        assertTrue(item.description.contains("plugin_permissions_not_granted"))
    }

    @Test fun websocketBootstrapsSignedIdentityAndRoutesNativePayload() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"serverReady":true}}""").addHeader("X-Seanime-Client-Id", "native-client").addHeader("X-Seanime-Client-Id-Proof", "proof"))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"native-player","payload":{"type":"watch","payload":{"streamUrl":"/api/v1/directstream/stream"}}}""")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { client.events.first { it.type == "native-player" } } }
        client.awaitEventsReady()
        assertEquals("watch", (received.await().payload as JSONObject).getString("type"))
        assertEquals("/api/v1/status", server.takeRequest().path)
        val upgrade = server.takeRequest()
        assertEquals("native-client", upgrade.requestUrl!!.queryParameter("id"))
        assertEquals("proof", upgrade.requestUrl!!.queryParameter("proof"))
        assertEquals("androidtv", upgrade.requestUrl!!.queryParameter("platform"))
        assertEquals("test-hash", upgrade.requestUrl!!.queryParameter("token"))
        assertEquals(server.url("/").toString().trimEnd('/'), upgrade.getHeader("Origin"))
        client.closeEvents()
    }

    @Test fun pluginPermissionChallengeIsBoundToRequestedExtensionAndSameClient() = runBlocking {
        var socket: WebSocket? = null
        var verified = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/v1/status" -> MockResponse().setBody("""{"data":{"serverReady":true}}""")
                    .addHeader("X-Seanime-Client-Id", "permission-client").addHeader("X-Seanime-Client-Id-Proof", "proof")
                request.path?.startsWith("/events?") == true -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                })
                request.path == "/api/v1/extensions/plugin-permissions/grant" -> {
                    val body = JSONObject(request.body.readUtf8())
                    if (body.getString("clientId").startsWith("CODE:")) {
                        verified = body.getString("clientId") == "CODE:challenge:secret" && body.getString("id") == "plugin" && request.getHeader("X-Seanime-Client-Id") == "permission-client"
                        MockResponse().setBody("""{"data":$verified}""")
                    } else {
                        socket!!.send(jsonObject("type" to "grant-plugin-permission-check", "payload" to "other" + "$$$" + "wrong:wrong").toString())
                        socket!!.send(jsonObject("type" to "grant-plugin-permission-check", "payload" to "plugin" + "$$$" + "challenge:secret").toString())
                        MockResponse().setBody("""{"data":false}""")
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        assertTrue(repo.grantPluginPermissions("plugin"))
        assertTrue(verified)
        client.closeEvents()
    }

    @Test fun malformedMalOAuthResponseNeverClaimsAccountConnected() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"access_token":"","token_type":"","expires_in":0}}"""))
        assertTrue(runCatching { repo.loginMal("expired", "state", "verifier") }.exceptionOrNull() is ApiException)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("verifier", body.getString("code_verifier"))
        server.enqueue(MockResponse().setBody("""{"data":{"access_token":"valid-token","token_type":"Bearer","expires_in":3600}}"""))
        assertNotNull(repo.loginMal("new-code", "state", "verifier"))
    }

    @Test fun sameOriginSnapshotPreservesSignedIdentityAndHashedAuthentication() = runBlocking {
        client.setServerPassword("same-process-secret")
        server.enqueue(MockResponse().setBody("""{"data":true}""")
            .addHeader("X-Seanime-Client-Id", "continuing-native-player")
            .addHeader("X-Seanime-Client-Id-Proof", "signed-session-proof"))
        client.request("GET", "/api/v1/status")
        server.takeRequest()
        val snapshot = client.snapshotSession()
        val replacement = SeanimeApiClient(server.url("/").toString())
        try {
            assertTrue(replacement.restoreSession(snapshot))
            server.enqueue(MockResponse().setBody("""{"data":true}"""))
            replacement.request("GET", "/api/v1/status")
            val request = server.takeRequest()
            assertEquals("continuing-native-player", request.getHeader("X-Seanime-Client-Id"))
            assertEquals("signed-session-proof", request.getHeader("X-Seanime-Client-Id-Proof"))
            assertEquals(SeanimeApiClient.hashPassword("same-process-secret"), request.getHeader("X-Seanime-Token"))
            assertEquals(snapshot.canonicalOrigin, replacement.snapshotSession().canonicalOrigin)
        } finally { replacement.close() }
    }

    @Test fun snapshotsAreOriginBoundAndNeverRevealCredentialsInToString() {
        val snapshot = client.snapshotSession()
        val differentPort = SeanimeApiClient("http://localhost:${server.port + 1}", "unchanged-token")
        val differentScheme = SeanimeApiClient("https://localhost:${server.port}", "unchanged-token")
        try {
            for (other in listOf(differentPort, differentScheme)) {
                val original = other.snapshotSession()
                assertFalse(other.restoreSession(snapshot))
                assertSame(original, other.snapshotSession())
            }
            assertEquals("SessionSnapshot(redacted)", snapshot.toString())
            assertFalse(snapshot.toString().contains("test-hash"))
            assertFalse(snapshot.toString().contains(snapshot.clientId))
        } finally { differentPort.close(); differentScheme.close() }
    }

    @Test fun staleHttpIdentityCannotOverwriteAnAdoptedSession() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":true}""")
            .setHeadersDelay(150, TimeUnit.MILLISECONDS)
            .addHeader("X-Seanime-Client-Id", "obsolete-client")
            .addHeader("X-Seanime-Client-Id-Proof", "obsolete-proof"))
        val oldRequest = async(start = CoroutineStart.UNDISPATCHED) { client.request("GET", "/api/v1/status") }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val adopted = SeanimeApiClient.SessionSnapshot(client.snapshotSession().canonicalOrigin, "adopted-client", "adopted-proof", "adopted-token")
        assertTrue(client.restoreSession(adopted))
        oldRequest.await()
        val actual = client.snapshotSession()
        assertEquals("adopted-client", actual.clientId)
        assertEquals("adopted-proof", actual.identityProof)
        assertEquals("adopted-token", actual.serverToken)
        assertEquals("SessionSnapshot(redacted)", actual.toString())
    }

    @Test fun canonicalOriginIgnoresUrlPresentationButNotServerIdentity() {
        val canonicalA = SeanimeApiClient("http://EXAMPLE.invalid:80/some-base/?unused=true")
        val canonicalB = SeanimeApiClient("http://example.invalid/")
        val differentHost = SeanimeApiClient("http://other.invalid/")
        try {
            assertTrue(canonicalB.restoreSession(canonicalA.snapshotSession()))
            assertFalse(differentHost.restoreSession(canonicalA.snapshotSession()))
            assertEquals("http://example.invalid", canonicalB.snapshotSession().canonicalOrigin)
        } finally { canonicalA.close(); canonicalB.close(); differentHost.close() }
    }

    @Test fun restoringActiveSocketReconnectsWithOneCoherentSession() = runBlocking {
        fun upgrade(sendIdentity: Boolean) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (sendIdentity) webSocket.send("""{"type":"client-identity","payload":{"clientId":"ws-issued-id","proof":"ws-issued-proof"}}""")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        })
        server.enqueue(MockResponse().setBody("""{"data":{"serverReady":true}}"""))
        server.enqueue(upgrade(true))
        val identityEvent = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { client.events.first { it.type == "client-identity" } }
        }
        client.awaitEventsReady()
        identityEvent.await()
        assertEquals("ws-issued-id", client.snapshotSession().clientId)
        assertEquals("ws-issued-proof", client.snapshotSession().identityProof)
        server.takeRequest(); server.takeRequest()
        server.enqueue(MockResponse().setBody("""{"data":{"serverReady":true}}"""))
        server.enqueue(upgrade(false))
        val restored = SeanimeApiClient.SessionSnapshot(client.snapshotSession().canonicalOrigin, "handoff-id", "handoff-proof", "handoff-hash")
        assertTrue(client.restoreSession(restored))
        client.awaitEventsReady()
        val statusRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
        val websocketRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("handoff-id", statusRequest.getHeader("X-Seanime-Client-Id"))
        assertEquals("handoff-proof", statusRequest.getHeader("X-Seanime-Client-Id-Proof"))
        assertEquals("handoff-hash", statusRequest.getHeader("X-Seanime-Token"))
        assertEquals("handoff-id", websocketRequest.requestUrl!!.queryParameter("id"))
        assertEquals("handoff-proof", websocketRequest.requestUrl!!.queryParameter("proof"))
        assertEquals("handoff-hash", websocketRequest.requestUrl!!.queryParameter("token"))
        assertEquals("handoff-id", websocketRequest.getHeader("X-Seanime-Client-Id"))
        client.closeEvents()
    }

    @Test fun nativeSetupSuppliesTheActualServerOriginWithoutAWebViewOrPassword() = runBlocking {
        client.setServerToken(null)
        val expectedOrigin = server.url("/").toString().trimEnd('/')
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // Regression fixture for the transport requirement in local_security.go:
                // signed client identity does not replace the privileged mutation's Origin.
                if (request.getHeader("Origin") != expectedOrigin) {
                    return MockResponse().setResponseCode(403)
                        .setBody("""{"error":"this action requires either a server password or a trusted local origin"}""")
                }
                return MockResponse().setBody("""{"data":{"serverReady":true,"settings":{"library":{"libraryPath":"/data/library"}}}}""")
            }
        }
        val result = repo.completeSetup("/data/library")
        assertEquals("/data/library", result.settings.getJSONObject("library").getString("libraryPath"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/start", request.path)
        assertEquals(expectedOrigin, request.getHeader("Origin"))
        assertEquals("androidtv", request.getHeader("X-Seanime-Client-Platform"))
        assertNull(request.getHeader("X-Seanime-Token"))
        assertNull(request.getHeader("Forwarded"))
        assertNull(request.getHeader("X-Forwarded-For"))
    }

    @Test fun originIsCanonicalAndNeverSubstitutesLocalTrustForAnotherServer() {
        val destinations = listOf(
            "https://user:password@EXAMPLE.invalid:8443/base/?private=yes#fragment" to "https://example.invalid:8443",
            "https://EXAMPLE.invalid:443/path" to "https://example.invalid",
            "http://[::1]:43211/base" to "http://[::1]:43211",
        )
        destinations.forEach { (url, expectedOrigin) ->
            val target = SeanimeApiClient(url)
            try {
                assertEquals(expectedOrigin, target.requestHeaders()["Origin"])
                assertFalse(target.requestHeaders()["Origin"]!!.contains("password"))
                assertFalse(target.requestHeaders()["Origin"]!!.contains("private"))
                assertFalse(target.requestHeaders().containsKey("X-Forwarded-Host"))
                assertNotEquals("app://-", target.requestHeaders()["Origin"])
            } finally { target.close() }
        }
    }

}
