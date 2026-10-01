package app.seanime.tv.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.NativeImageTransport
import app.seanime.tv.data.MAX_NATIVE_MEDIA_ID
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Loopback HTTP fixtures exercise the production Coil loader, OkHttp transport, Android PNG
 * decoder and Compose drawing. Pixel assertions are taken from the actual Android view.
 * Route controls use semantic OnClick solely to select thumbnail fixtures; remote navigation is
 * covered by the separate focus suite. These are not evidence that live providers are online.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-television-mdpi-notouch-nokeys-navexposed-dpad")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeArtworkHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun customDetailMetadataAndRelatedCoversDecodeAtApiOriginWithoutItsCredentials() = fixture(apiHost = "api.example") { api, server, requests, show ->
        val id = MAX_NATIVE_MEDIA_ID
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path) {
                    "/api/v1/library/anime-entry/$id" -> json(org.json.JSONObject("""{"media":{"id":$id,"title":"Custom detail","coverImage":{"large":"${api.baseUrl}/cover.png"},"bannerImage":"${api.baseUrl}/banner.png"},"episodes":[{"episodeNumber":1,"episodeTitle":"Cover fallback"}]}"""))
                    "/api/v1/anilist/media-details/$id" -> json(org.json.JSONObject("""{"id":$id,"characters":{"edges":[{"role":"MAIN","node":{"id":1,"name":{"full":"Custom character"},"image":{"large":"${api.baseUrl}/character.png"}}}]},"relations":{"edges":[{"node":{"id":21,"type":"ANIME","title":"AniList relation","siteUrl":"https://anilist.co/anime/21","coverImage":{"large":"${api.baseUrl}/related.png"}}}]}}"""))
                    "/cover.png" -> image(Color.RED)
                    "/banner.png", "/related.png" -> image(Color.GREEN)
                    "/character.png" -> image(Color.BLUE)
                    else -> json(org.json.JSONObject())
                }
            }
        }
        show { AnimeDetailScreen(id, app.seanime.tv.data.SeanimeRepository(api), {}, {}) }
        awaitImageDescription("Custom detail"); assertDescriptionPixel("Custom detail", Color.RED)
        awaitImageDescription("Cover fallback thumbnail")
        compose.onNodeWithContentDescription("Cover fallback thumbnail", useUnmergedTree = true).performScrollTo()
        assertDescriptionPixel("Cover fallback thumbnail", Color.RED)
        compose.onNodeWithTag("anime-more-information").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        awaitImageDescription("Custom detail banner"); assertDescriptionPixel("Custom detail banner", Color.GREEN)
        awaitImageDescription("Custom character")
        compose.onNodeWithContentDescription("Custom character", useUnmergedTree = true).performScrollTo()
        assertDescriptionPixel("Custom character", Color.BLUE)
        compose.onNodeWithTag("anime-information-relation:anime:21").performScrollTo()
        awaitImageDescription("AniList relation"); assertDescriptionPixel("AniList relation", Color.GREEN)
        val images = requests.filter { it.path?.endsWith(".png") == true }
        assertEquals(setOf("/cover.png", "/banner.png", "/character.png", "/related.png"), images.map { it.path }.toSet())
        images.forEach(::assertNoServerAuthority)
        assertTrue(requests.filter { it.path?.startsWith("/api/") == true }.all { it.getHeader("X-Seanime-Token") == "fixture-native-token" })
        capture("custom-detail-metadata-public-artwork")
    }

    @Test fun customDetailRejectsLocalUrlsAndForgedOfflineMarkersAcrossMetadata() = fixture { api, server, requests, show ->
        val id = 4_294_967_297L
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path) {
                    "/api/v1/library/anime-entry/$id" -> json(org.json.JSONObject("""{"media":{"id":$id,"title":"Blocked custom","artworkOrigin":"SERVER","isDownloaded":true,"coverImage":{"large":"${server.url("/private-cover.png")}"},"bannerImage":"{{LOCAL_ASSETS}}/$id/banner.png"},"episodes":[{"episodeNumber":1,"episodeTitle":"Blocked episode","episodeMetadata":{"image":"{{LOCAL_ASSETS}}/$id/episode.png"}}]}"""))
                    "/api/v1/status" -> json(org.json.JSONObject("""{"isOffline":false}"""))
                    "/api/v1/anilist/media-details/$id" -> json(org.json.JSONObject("""{"id":$id,"characters":{"edges":[{"role":"MAIN","node":{"id":1,"name":{"full":"Blocked character"},"image":{"large":"${server.url("/private-character.png")}"}}}]},"relations":{"edges":[{"node":{"id":21,"type":"ANIME","title":"Blocked relation","coverImage":{"large":"${server.url("/private-related.png")}"}}}]}}"""))
                    else -> json(org.json.JSONObject())
                }
            }
        }
        show { AnimeDetailScreen(id, app.seanime.tv.data.SeanimeRepository(api), {}, {}) }
        awaitDescriptionPhase("Blocked custom", "Image unavailable")
        awaitDescriptionPhase("Blocked episode thumbnail", "Image unavailable")
        compose.onNodeWithTag("anime-more-information").performSemanticsAction(SemanticsActions.OnClick) { it() }
        awaitDescriptionPhase("Blocked custom banner", "Image unavailable")
        awaitDescriptionPhase("Blocked character", "Image unavailable")
        compose.onNodeWithTag("anime-information-relation:anime:21").performScrollTo()
        awaitDescriptionPhase("Blocked relation", "Image unavailable")
        assertFalse(requests.any { it.path?.endsWith(".png") == true })
        capture("custom-detail-metadata-rejected-local-artwork")
    }

    @Test fun verifiedOfflineCustomDetailStillDecodesServerSnapshotCoverAndEpisode() = fixture { api, server, requests, show ->
        val id = 2_147_483_648L
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path) {
                    "/api/v1/library/anime-entry/$id" -> json(org.json.JSONObject("""{"media":{"id":$id,"title":"Offline custom","coverImage":{"large":"{{LOCAL_ASSETS}}/$id/cover.png"}},"episodes":[{"episodeNumber":1,"episodeTitle":"Offline episode","episodeMetadata":{"image":"{{LOCAL_ASSETS}}/$id/episode.png"}}]}"""))
                    "/api/v1/status" -> json(org.json.JSONObject("""{"isOffline":true}"""))
                    "/offline-assets/$id/cover.png" -> image(Color.GREEN)
                    "/offline-assets/$id/episode.png" -> image(Color.BLUE)
                    else -> json(org.json.JSONObject())
                }
            }
        }
        show { AnimeDetailScreen(id, app.seanime.tv.data.SeanimeRepository(api), {}, {}) }
        awaitImageDescription("Offline custom"); assertDescriptionPixel("Offline custom", Color.GREEN)
        awaitImageDescription("Offline episode thumbnail")
        compose.onNodeWithContentDescription("Offline episode thumbnail", useUnmergedTree = true).performScrollTo()
        assertDescriptionPixel("Offline episode thumbnail", Color.BLUE)
        val images = requests.filter { it.path?.endsWith(".png") == true }
        assertEquals(2, images.size)
        images.forEach(::assertNoServerAuthority)
        capture("custom-offline-detail-snapshot-artwork")
    }

    @Test fun trackedAndDownloadedCustomSnapshotsLoadOnlineAndOfflineWithoutCredentialsOrProviderCacheLeakage() = fixture { api, server, requests, show ->
        val id = 4_294_967_297L
        val offline = java.util.concurrent.atomic.AtomicBoolean(false)
        var route by mutableIntStateOf(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path) {
                    "/api/v1/local/track" -> json(org.json.JSONArray().put(org.json.JSONObject("""{"mediaId":$id,"type":"manga","mangaEntry":{"media":{"id":$id,"title":"Tracked custom","coverImage":{"large":"{{LOCAL_ASSETS}}/$id/cover.png"}}}}""")))
                    "/api/v1/manga/downloads" -> json(org.json.JSONArray().put(org.json.JSONObject("""{"mediaId":$id,"media":{"id":$id,"title":"Downloaded custom","isDownloaded":true,"coverImage":{"large":"{{LOCAL_ASSETS}}/$id/cover.png"}}}""")))
                    "/api/v1/local/storage/size" -> json("1 MB")
                    "/api/v1/local/updated" -> json(false)
                    "/api/v1/status" -> json(org.json.JSONObject("""{"serverReady":true,"isOffline":${offline.get()},"user":{"isSimulated":false}}"""))
                    "/api/v1/manga/source-refresh" -> json(org.json.JSONObject.NULL)
                    "/offline-assets/$id/cover.png" -> image(Color.GREEN)
                    else -> json(org.json.JSONObject())
                }
            }
        }
        show { key(route) {
            val repo = remember { app.seanime.tv.data.SeanimeRepository(api) }
            if (route < 2) FeatureScreen(TvFeature.OFFLINE, repo, {}, {})
            else if (route < 4) MangaScreen(repo, initialMode = "downloaded")
            else NativeArtwork(app.seanime.tv.data.MediaCard(id, "Direct provider", imageUrl = "{{LOCAL_ASSETS}}/$id/cover.png"),
                "Direct provider", Modifier.size(100.dp))
        } }
        fun scrollTracked() {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("1 tracked titles · 1 MB stored").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .performScrollToNode(hasContentDescription("Tracked custom"))
        }
        scrollTracked(); awaitImageDescription("Tracked custom"); assertDescriptionPixel("Tracked custom", Color.GREEN)
        assertEquals(1, requests.count { it.path?.endsWith(".png") == true })
        capture("custom-online-tracked-snapshot-artwork")
        offline.set(true); compose.runOnIdle { route = 1 }
        scrollTracked(); awaitImageDescription("Tracked custom"); assertDescriptionPixel("Tracked custom", Color.GREEN)
        capture("custom-offline-tracked-snapshot-artwork")
        offline.set(false); compose.runOnIdle { route = 2 }
        awaitImageDescription("Downloaded custom cover"); assertDescriptionPixel("Downloaded custom cover", Color.GREEN)
        // Coil may decode another size for the downloaded card's larger bounds.
        assertTrue(requests.any { it.path?.endsWith(".png") == true })
        capture("custom-online-downloaded-snapshot-artwork")
        offline.set(true); compose.runOnIdle { route = 3 }
        awaitImageDescription("Downloaded custom cover"); assertDescriptionPixel("Downloaded custom cover", Color.GREEN)
        requests.filter { it.path?.endsWith(".png") == true }.forEach(::assertNoServerAuthority)
        capture("custom-offline-downloaded-snapshot-artwork")
        val assetRequestCount = requests.count { it.path?.endsWith(".png") == true }
        compose.runOnIdle { route = 4 }
        awaitDescriptionPhase("Direct provider", "Image unavailable")
        assertEquals(assetRequestCount, requests.count { it.path?.endsWith(".png") == true })
    }

    @Test fun featureActionsResumeHttpWorkOnAndroidMainThread() = fixture { api, server, requests, show ->
        val completed = java.util.concurrent.atomic.AtomicBoolean()
        val mainBefore = java.util.concurrent.atomic.AtomicBoolean()
        val mainAfter = java.util.concurrent.atomic.AtomicBoolean()
        val threadNames = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return json(org.json.JSONArray())
            }
        }
        show {
            val action = rememberFeatureAction()
            LaunchedEffect(Unit) {
                action.run {
                    mainBefore.set(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                    threadNames += "before: " + Thread.currentThread().name
                    app.seanime.tv.data.SeanimeRepository(api).playlists()
                    mainAfter.set(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                    threadNames += "after: " + Thread.currentThread().name
                    completed.set(true)
                }
            }
        }
        compose.waitUntil(10_000) { completed.get() }
        assertTrue(threadNames.toString(), mainBefore.get())
        assertTrue(threadNames.toString(), mainAfter.get())
    }

    @Test fun serverRelativeAbsoluteAndOfflineMarkersDecodeIntoVisiblePixelsWithScopedHeaders() = fixture { api, server, requests, show ->
        MockWebServer().use { cdn ->
            val cdnRequests = CopyOnWriteArrayList<RecordedRequest>()
            cdn.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    cdnRequests += request
                    return image(Color.BLUE)
                }
            }
            cdn.start(InetAddress.getByName("127.0.0.1"), 0)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return if (request.path == "/redirect.png") MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/redirected.png")) else image(Color.GREEN)
                }
            }
            show {
                Row {
                    NativeArtwork("/relative.png", "Relative", Modifier.size(100.dp, 140.dp).testTag("relative"))
                    NativeArtwork("{{LOCAL_ASSETS}}/42/cover.png", "Offline", Modifier.size(100.dp, 140.dp).testTag("offline"))
                    NativeArtwork(cdn.url("/remote.png").toString(), "Remote", Modifier.size(100.dp, 140.dp).testTag("remote"))
                    NativeArtwork("/redirect.png", "Redirect", Modifier.size(100.dp, 140.dp).testTag("redirect"))
                }
            }
            for (tag in listOf("relative", "offline", "remote", "redirect")) awaitLoaded(tag)
            assertPixel("relative", Color.GREEN); assertPixel("offline", Color.GREEN)
            assertPixel("remote", Color.BLUE); assertPixel("redirect", Color.BLUE)
            assertEquals(setOf("/relative.png", "/offline-assets/42/cover.png", "/redirect.png"), requests.map { it.path }.toSet())
            requests.forEach { assertEquals("fixture-native-token", it.getHeader("X-Seanime-Token")); assertNotNull(it.getHeader("X-Seanime-Client-Id")) }
            assertEquals(2, cdnRequests.size)
            cdnRequests.forEach { request ->
                for (name in listOf("X-Seanime-Token", "X-Seanime-Client-Id", "X-Seanime-Client-Id-Proof", "Origin", "Referer", "Cookie", "Authorization")) assertNull(name, request.getHeader(name))
            }
            capture("relative-offline-remote-redirect")
        }
    }

    @Test fun providerHeaderChangesAtSameUrlFetchNewPixelsAndDoNotContaminatePublicImageCache() = fixture { _, _, _, show ->
        MockWebServer().use { provider ->
            val requests = CopyOnWriteArrayList<RecordedRequest>()
            provider.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return image(when (request.getHeader("X-Image-Key")) { "edition-one" -> Color.RED; "edition-two" -> Color.GREEN; else -> Color.BLUE })
                }
            }
            provider.start(InetAddress.getByName("127.0.0.1"), 0)
            var headers by mutableStateOf(mapOf("X-Image-Key" to "edition-one", "Referer" to "https://provider.invalid/title"))
            show { NativeArtwork(provider.url("/same.png").toString(), "Provider", Modifier.size(140.dp).testTag("provider"), headers = headers) }
            awaitLoaded("provider"); assertPixel("provider", Color.RED)
            compose.runOnIdle { headers = mapOf("X-Image-Key" to "edition-two") }
            awaitLoaded("provider"); assertPixel("provider", Color.GREEN); assertEquals(2, requests.size)
            compose.runOnIdle { headers = emptyMap() }
            awaitLoaded("provider"); assertPixel("provider", Color.BLUE); assertEquals(3, requests.size)
            assertEquals("https://provider.invalid/title", requests[0].getHeader("Referer"))
            assertNull(requests[1].getHeader("Referer")); assertNull(requests[2].getHeader("X-Image-Key"))
            requests.forEach { assertNull(it.getHeader("X-Seanime-Token")); assertNull(it.getHeader("X-Seanime-Client-Id")) }
            capture("provider-current-header-pixels")
        }
    }

    @Test fun providerImagesRejectLocalAddressesAndMarkersWithoutReusingTrustedPixels() = fixture { api, server, requests, show ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { requests += request; return image(Color.GREEN) }
        }
        var providerResult by mutableStateOf(false)
        var source by mutableStateOf(api.baseUrl + "/owned-cover.png")
        show { NativeArtwork(source, "Provider boundary", Modifier.size(140.dp).testTag("boundary"), providerResult = providerResult) }
        awaitLoaded("boundary"); assertPixel("boundary", Color.GREEN)
        assertEquals(1, requests.size)
        compose.runOnIdle { providerResult = true }
        awaitPhase("boundary", "Image unavailable")
        for (blocked in listOf("/api/v1/status", "{{LOCAL_ASSETS}}/private/cover.png", "file:///private/image", "content://private/image")) {
            compose.runOnIdle { source = blocked }
            awaitPhase("boundary", "Image unavailable")
        }
        assertEquals("Untrusted images must make no local requests, including cache hits", 1, requests.size)
        capture("provider-blocked-image-placeholder")
    }

    @Test fun reusingOneCardChangesPixelsAndReturningToItsCachedImageRequiresNoNetwork() = fixture { _, server, requests, show ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { requests += request; return image(if (request.path == "/one.png") Color.RED else Color.GREEN) }
        }
        var url by mutableStateOf<String?>("/one.png")
        show { NativeArtwork(url, "Reused card", Modifier.size(140.dp).testTag("reused")) }
        awaitLoaded("reused"); assertPixel("reused", Color.RED)
        compose.runOnIdle { url = "/two.png" }
        awaitLoaded("reused"); assertPixel("reused", Color.GREEN); assertEquals(2, requests.size)
        compose.runOnIdle { url = null }; awaitPhase("reused", "No artwork")
        // A cached render must succeed even though the network now only returns errors.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { requests += request; return MockResponse().setResponseCode(503) }
        }
        compose.runOnIdle { url = "/one.png" }
        awaitLoaded("reused"); assertPixel("reused", Color.RED)
        assertEquals("The shared memory cache must satisfy the return without another HTTP call", 2, requests.size)
        capture("reused-card-memory-cache")
    }

    @Test fun absentBadAndLoadingImagesHaveStableNonfocusableFallbacks() = fixture { _, server, requests, show ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path) {
                    "/loading.png" -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    "/corrupt.png" -> MockResponse().setHeader("Content-Type", "image/png").setBody("not an image")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        show { Row {
            NativeArtwork(null, "Missing", Modifier.size(120.dp, 180.dp).testTag("missing"))
            NativeArtwork("file:///private.png", "Invalid", Modifier.size(120.dp, 180.dp).testTag("invalid"))
            NativeArtwork("/404.png", "Unavailable", Modifier.size(120.dp, 180.dp).testTag("404"))
            NativeArtwork("/corrupt.png", "Corrupt", Modifier.size(120.dp, 180.dp).testTag("corrupt"))
            NativeArtwork("/loading.png", "Loading", Modifier.size(120.dp, 180.dp).testTag("loading"))
        } }
        awaitPhase("missing", "No artwork"); awaitPhase("invalid", "No artwork")
        awaitPhase("404", "Image unavailable"); awaitPhase("corrupt", "Image unavailable"); awaitPhase("loading", "Loading image")
        for (tag in listOf("missing", "invalid", "404", "corrupt", "loading")) compose.onNodeWithTag(tag)
            .assertWidthIsEqualTo(120.dp).assertHeightIsEqualTo(180.dp).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        assertEquals(3, requests.size)
        capture("missing-invalid-error-loading-fallbacks")
    }

    @Test fun posterCropFillsPortraitBoundsWithTheCorrectCenterPixels() = fixture { _, server, requests, show ->
        val bitmap = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
        for (x in 0 until 300) for (y in 0 until 100) bitmap.setPixel(x, y, when { x < 100 -> Color.RED; x < 200 -> Color.GREEN; else -> Color.BLUE })
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { requests += request; return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)) }
        }
        show { NativeArtwork("/wide.png", "Cropped poster", Modifier.size(100.dp, 180.dp).testTag("crop"), ContentScale.Crop) }
        awaitLoaded("crop")
        assertPixel("crop", Color.GREEN, .05f, .05f); assertPixel("crop", Color.GREEN, .95f, .95f)
        compose.onNodeWithTag("crop").assertWidthIsEqualTo(100.dp).assertHeightIsEqualTo(180.dp)
        capture("poster-center-crop")
    }

    @Test fun productionLibraryAndDiscoveryGridsDisplayCoversFromExistingApiModels() = fixture { api, server, requests, show ->
        val raw = org.json.JSONObject("""{"id":901,"title":{"userPreferred":"Fixture anime"},"coverImage":{"extraLarge":"/route-cover.png"}}""")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path) {
                    "/api/v1/library/collection" -> json(org.json.JSONArray().put(raw))
                    "/api/v1/anilist/list-anime" -> json(org.json.JSONObject().put("Page", org.json.JSONObject().put("media", org.json.JSONArray().put(raw))))
                    else -> image(Color.GREEN)
                }
            }
        }
        val repo = app.seanime.tv.data.SeanimeRepository(api)
        val cards = kotlinx.coroutines.runBlocking { repo.library() }
        val page = kotlinx.coroutines.runBlocking { repo.discoverAnime() }
        var discovery by mutableStateOf(false)
        show { if (discovery) NativeDiscoveryContent(app.seanime.tv.data.AnimeDiscoveryFilters(), 1, page)
            else NativeCollectionGrid(cards, onDetails = {}) }
        awaitImageDescription("Fixture anime"); assertDescriptionPixel("Fixture anime", Color.GREEN)
        capture("production-library-cover")
        compose.runOnIdle { discovery = true }
        awaitImageDescription("Fixture anime"); assertDescriptionPixel("Fixture anime", Color.GREEN)
        capture("production-discovery-cover")
        assertEquals(1, requests.count { it.path == "/route-cover.png" })
    }

    @Test fun productionMangaCollectionDisplaysItsOfflineCover() = fixture { api, server, requests, show ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path) {
                    "/api/v1/manga/collection" -> json(org.json.JSONArray().put(org.json.JSONObject("""{"id":902,"title":"Fixture manga","coverImage":{"large":"{{LOCAL_ASSETS}}/902/cover.png"}}""")))
                    "/api/v1/manga/source-refresh" -> json(org.json.JSONObject.NULL)
                    "/offline-assets/902/cover.png" -> image(Color.GREEN)
                    else -> json(org.json.JSONObject())
                }
            }
        }
        show { MangaScreen(app.seanime.tv.data.SeanimeRepository(api)) }
        awaitImageDescription("Fixture manga cover"); assertDescriptionPixel("Fixture manga cover", Color.GREEN)
        capture("production-manga-offline-cover")
    }

    @Test fun productionOfflineTrackedTitleDisplaysItsStoredArtwork() = fixture { api, server, requests, show ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path) {
                    "/api/v1/local/track" -> json(org.json.JSONArray().put(org.json.JSONObject("""{"mediaId":903,"type":"anime","animeEntry":{"media":{"id":903,"title":"Fixture offline","coverImage":{"medium":"{{LOCAL_ASSETS}}/903/cover.png"}}}}""")))
                    "/api/v1/local/storage/size" -> json("1 MB")
                    "/api/v1/local/updated" -> json(false)
                    "/api/v1/status" -> json(org.json.JSONObject("""{"serverReady":true,"isOffline":true,"user":{"isSimulated":false}}"""))
                    "/offline-assets/903/cover.png" -> image(Color.GREEN)
                    else -> json(org.json.JSONObject())
                }
            }
        }
        show { FeatureScreen(TvFeature.OFFLINE, app.seanime.tv.data.SeanimeRepository(api), {}, {}) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("1 tracked titles · 1 MB stored").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasContentDescription("Fixture offline"))
        awaitImageDescription("Fixture offline"); assertDescriptionPixel("Fixture offline", Color.GREEN)
        capture("production-offline-tracked-cover")
    }

    @Test fun productionPlaylistUsesEpisodeMetadataThumbnail() = fixture { api, server, requests, show ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return if (request.path == "/api/v1/playlists") json(org.json.JSONArray().put(org.json.JSONObject("""{"dbId":7,"name":"Fixture queue","episodes":[{"watchType":"localfile","episode":{"episodeNumber":1,"episodeTitle":"First","baseAnime":{"id":904,"title":"Fixture playlist","coverImage":{"large":"/playlist-cover.png"}},"episodeMetadata":{"image":"/playlist-episode.png"}}}]}""")))
                else image(if (request.path == "/playlist-episode.png") Color.GREEN else Color.RED)
            }
        }
        show { FeatureScreen(TvFeature.PLAYLISTS, app.seanime.tv.data.SeanimeRepository(api), {}, {}) }
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Open").fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            println("Playlist thumbnail fixture request paths: " + requests.map { it.method + " " + it.path })
            runCatching { println(compose.onRoot().printToString()); capture("playlist-open-unavailable") }
                .onFailure(failure::addSuppressed)
            throw failure
        }
        compose.onNodeWithText("Open").performSemanticsAction(SemanticsActions.OnClick) { it() }
        val description = "Fixture playlist episode 1 thumbnail"
        awaitImageDescription(description)
        compose.onNodeWithContentDescription(description, useUnmergedTree = true).performScrollTo()
        assertDescriptionPixel(description, Color.GREEN)
        assertEquals(1, requests.count { it.path == "/playlist-episode.png" })
        assertEquals(0, requests.count { it.path == "/playlist-cover.png" })
        capture("production-playlist-episode-thumbnail")
    }

    @Test fun productionCustomSourceUsesItsExistingCoverImageContract() = fixture { api, server, requests, show ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path) {
                    "/api/v1/extensions/list/custom-source" -> json(org.json.JSONArray().put(org.json.JSONObject("""{"id":"fixture-source","name":"Fixture provider","settings":{"supportsAnime":true}}""")))
                    "/api/v1/custom-source/provider/list/anime" -> json(org.json.JSONObject("""{"media":[{"id":905,"title":"Fixture custom title","coverImage":{"large":"${server.url("/custom-cover.png").newBuilder().host("provider.example").build()}"}}],"totalPages":1}"""))
                    "/custom-cover.png" -> image(Color.GREEN)
                    else -> json(org.json.JSONObject())
                }
            }
        }
        show { NativeCustomSources(app.seanime.tv.data.SeanimeRepository(api), onPlay = {}, onBack = {}) }
        awaitImageDescription("Fixture custom title"); assertDescriptionPixel("Fixture custom title", Color.GREEN)
        capture("production-custom-source-cover")
    }

    @Test fun productionMangaProviderMatchPassesImageHeadersAndDecodesItsCover() = fixture { api, server, requests, show ->
        MockWebServer().use { provider ->
            val providerRequests = CopyOnWriteArrayList<RecordedRequest>()
            provider.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    providerRequests += request
                    return if (request.getHeader("X-Image-Key") == "fixture-provider-key") image(Color.GREEN) else MockResponse().setResponseCode(403)
                }
            }
            provider.start(InetAddress.getByName("127.0.0.1"), 0)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return when (request.path) {
                        "/api/v1/status" -> json(org.json.JSONObject("""{"serverReady":true,"isOffline":false}"""))
                        "/api/v1/extensions/list/manga-provider" -> json(org.json.JSONArray().put(org.json.JSONObject("""{"id":"fixture-provider","name":"Fixture provider"}""")))
                        "/api/v1/manga/chapters" -> json(org.json.JSONObject().put("chapters", org.json.JSONArray()))
                        "/api/v1/manga/search" -> json(org.json.JSONArray().put(org.json.JSONObject().put("id", "match-1").put("title", "Fixture provider match")
                            .put("image", provider.url("/protected-cover.png").newBuilder().host("provider.example").build().toString()).put("imageHeaders", org.json.JSONObject().put("X-Image-Key", "fixture-provider-key"))))
                        else -> json(org.json.JSONObject())
                    }
                }
            }
            show { MangaTitleScreen(app.seanime.tv.data.SeanimeRepository(api), app.seanime.tv.data.MediaCard(906, "Fixture manga"), false, {}) }
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("manga-fix-match") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("manga-fix-match").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("text-entry-save").performSemanticsAction(SemanticsActions.OnClick) { it() }
            val description = "Fixture provider match cover"
            awaitImageDescription(description)
            compose.onNodeWithContentDescription(description, useUnmergedTree = true).performScrollTo()
            assertDescriptionPixel(description, Color.GREEN)
            assertEquals(1, providerRequests.size)
            assertEquals("fixture-provider-key", providerRequests.single().getHeader("X-Image-Key"))
            assertNull(providerRequests.single().getHeader("X-Seanime-Token"))
            capture("production-manga-provider-cover")
        }
    }

    @Test fun productionAnimeEpisodeRowDisplaysDecodedMetadataThumbnail() = fixture { api, server, requests, show ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path) {
                    "/api/v1/library/anime-entry/907" -> json(org.json.JSONObject("""{"media":{"id":907,"title":"Fixture detail","coverImage":{"large":"/detail-cover.png"}},"episodes":[{"episodeNumber":1,"episodeTitle":"Fixture episode","episodeMetadata":{"image":"/episode-thumb.png"}}]}"""))
                    "/episode-thumb.png" -> image(Color.GREEN)
                    "/detail-cover.png" -> image(Color.RED)
                    else -> json(org.json.JSONObject())
                }
            }
        }
        show { AnimeDetailScreen(907, app.seanime.tv.data.SeanimeRepository(api), {}, {}) }
        awaitImageDescription("Fixture episode thumbnail")
        compose.onNodeWithContentDescription("Fixture episode thumbnail", useUnmergedTree = true).performScrollTo()
        assertDescriptionPixel("Fixture episode thumbnail", Color.GREEN)
        capture("production-episode-thumbnail")
    }

    private fun json(value: Any): MockResponse = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(org.json.JSONObject().put("data", value).toString())
    private fun awaitImageDescription(description: String) {
        compose.waitUntil(10_000) {
            val node = compose.onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
            node != null && node.config.getOrNull(SemanticsProperties.StateDescription) == null
        }
        compose.waitForIdle()
    }
    private fun awaitDescriptionPhase(description: String, phase: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
                ?.config?.getOrNull(SemanticsProperties.StateDescription) == phase
        }
    }
    private fun assertNoServerAuthority(request: RecordedRequest) {
        for (name in listOf("X-Seanime-Token", "X-Seanime-Client-Id", "X-Seanime-Client-Id-Proof", "Authorization", "Cookie", "Origin", "Referer"))
            assertNull(name, request.getHeader(name))
    }
    private fun assertDescriptionPixel(description: String, expected: Int) {
        val node = compose.onNodeWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        val bounds = node.boundsInRoot
        compose.runOnUiThread {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val actual = bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt())
            bitmap.recycle()
            assertEquals("$description must contain actual decoded pixels", expected, actual)
        }
    }

    private fun awaitLoaded(tag: String) {
        compose.waitUntil(10_000) {
            val node = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
            node != null && node.config.getOrNull(SemanticsProperties.StateDescription) == null
        }
        compose.waitForIdle()
    }
    private fun awaitPhase(tag: String, label: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(SemanticsProperties.StateDescription) == label
        }
        compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label))
    }
    private fun assertPixel(tag: String, expected: Int, fractionX: Float = .5f, fractionY: Float = .5f) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        val bounds = node.boundsInRoot
        compose.runOnUiThread {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val actual = bitmap.getPixel((bounds.left + bounds.width * fractionX).toInt(), (bounds.top + bounds.height * fractionY).toInt())
            bitmap.recycle()
            assertEquals("$tag must contain decoded image pixels at ($fractionX, $fractionY)", expected, actual)
        }
    }
    private fun capture(name: String) {
        val view = (compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        val directory = File(System.getProperty("seanime.hostEvidenceDir") ?: "build/test-evidence/native-thumbnails")
        directory.mkdirs()
        compose.runOnUiThread {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun image(color: Int): MockResponse {
        val bitmap = Bitmap.createBitmap(100, 140, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        return MockResponse().setHeader("Content-Type", "image/png").setHeader("Cache-Control", "max-age=3600").setBody(Buffer().write(bytes))
    }
    private fun fixture(apiHost: String? = null, test: (SeanimeApiClient, MockWebServer, CopyOnWriteArrayList<RecordedRequest>, (@Composable () -> Unit) -> Unit) -> Unit) {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val serverUrl = server.url("/").newBuilder().apply { apiHost?.let(::host) }.build().toString()
            val apiHttp = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1")) }).build()
            SeanimeApiClient(serverUrl, "fixture-native-token", apiHttp).use { api ->
                val requests = CopyOnWriteArrayList<RecordedRequest>()
                var showing by mutableStateOf(true)
                try {
                    test(api, server, requests) { content -> compose.setContent {
                        if (showing) SeanimeTheme { NativeArtworkProvider(api, transportFactory = { client -> NativeImageTransport(client, object : Dns {
                            override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
                        }) }, content = content) }
                    } }
                } finally { compose.runOnIdle { showing = false }; compose.waitForIdle() }
            }
        }
    }
}
