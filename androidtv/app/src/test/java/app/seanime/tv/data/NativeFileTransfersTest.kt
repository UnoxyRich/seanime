package app.seanime.tv.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class NativeFileTransfersTest {
    private val index = """[{"path":"/androidtv/root/Episode.mkv","mediaId":21,"locked":true,"ignored":false}]""".toByteArray()

    @Test fun metadataPreviewRejectsEmptyDuplicateAndMalformedIndexes() {
        val preview = previewNativeMetadata("/androidtv/root/index.json", index)
        assertEquals(1, preview.count); assertEquals(1, preview.matched); assertEquals(1, preview.locked)
        listOf("[]", "{}", "[null]", "[{\"path\":\"/a\"},{\"path\":\"/a\"}]").forEach { raw ->
            assertTrue(raw, runCatching { previewNativeMetadata("/androidtv/root/index.json", raw.toByteArray()) }.isFailure)
        }
    }

    @Test fun changedBackupCannotReachDestructiveImport() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val approved = previewNativeMetadata("/androidtv/root/index.json", index)
                val error = runCatching { importNativeMetadata(SeanimeRepository(api), approved) { index.toString(Charsets.UTF_8).replace("21", "22").toByteArray() } }.exceptionOrNull()
                assertTrue(error?.message.orEmpty().contains("changed"))
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun metadataImportUsesOwnedPathAndVerifiesActiveIndex() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{\"data\":true}")); server.enqueue(MockResponse().setBody("{\"data\":${index.toString(Charsets.UTF_8)}}"))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                importNativeMetadata(SeanimeRepository(api), previewNativeMetadata("/androidtv/root/index.json", index)) { index }
                val request = server.takeRequest()
                assertEquals("POST", request.method); assertEquals("/api/v1/library/local-files/import", request.path)
                assertEquals(setOf("dataFilePath"), JSONObject(request.body.readUtf8()).keys().asSequence().toSet())
                assertEquals("GET", server.takeRequest().method)
            }
        }
    }

    @Test fun profileExportUsesTypedQueryAndOverridesShortDefaultTimeout() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("profile bytes").setBodyDelay(150, TimeUnit.MILLISECONDS))
            val transport = OkHttpClient.Builder().readTimeout(20, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).build()
            SeanimeApiClient(server.url("/").toString(), serverToken = "fixture-token", httpClient = transport).use { api ->
                val result = downloadNativeExport(SeanimeRepository(api), NativeExportKind.CPU, 1)
                assertEquals("seanime-cpu.pprof", result.filename); assertEquals("application/octet-stream", result.mimeType)
                assertEquals("profile bytes", result.bytes.toString(Charsets.UTF_8))
                val request = server.takeRequest()
                assertEquals("/api/v1/memory/cpu?duration=1", request.path)
                assertEquals("fixture-token", request.getHeader("X-Seanime-Token"))
                assertEquals(api.baseUrl, request.getHeader("Origin"))
            }
        }
    }

    @Test fun allProfileKindsUseExactExistingRoutes() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val repo = SeanimeRepository(api)
                listOf(NativeExportKind.HEAP to "/api/v1/memory/profile?heap=true", NativeExportKind.ALLOCATIONS to "/api/v1/memory/profile?allocs=true",
                    NativeExportKind.GOROUTINE to "/api/v1/memory/goroutine", NativeExportKind.LIBRARY_INDEX to "/api/v1/library/local-files/dump").forEach { (kind, path) ->
                    server.enqueue(MockResponse().setBody(if (kind == NativeExportKind.LIBRARY_INDEX) index.toString(Charsets.UTF_8) else "profile"))
                    downloadNativeExport(repo, kind)
                    assertEquals(path, server.takeRequest().path)
                }
                assertTrue(runCatching { downloadNativeExport(repo, NativeExportKind.CPU, 301) }.isFailure)
            }
        }
    }

    @Test fun boundedDownloadRejectsOversizeAndNeverFollowsRedirects() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            SeanimeApiClient(server.url("/").toString()).use { api ->
                server.enqueue(MockResponse().setBody("too large"))
                assertTrue(runCatching { api.download("/api/v1/memory/profile", 3, readTimeoutSeconds = 31) }.isFailure)
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/profile")))
                assertTrue(runCatching { api.download("/api/v1/memory/profile", readTimeoutSeconds = 31) }.isFailure)
                assertEquals(0, other.requestCount)
            }
        } }
    }

    @Test fun torrentMoveRechecksClientIdentityAndDestinationBeforeTypedRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val raw = JSONObject("""{"hash":"hash-21","name":"Series","contentPath":"/old"}""")
            val torrent = DownloadItem("hash-21", "Series", "paused", 0.5, "", "torrent", raw)
            server.enqueue(MockResponse().setBody("""{"data":{"torrent":{"defaultTorrentClient":"seanime"}}}"""))
            server.enqueue(MockResponse().setBody("{\"data\":[$raw]}"))
            server.enqueue(MockResponse().setBody("""{"data":{"exists":true,"fullPath":"/androidtv/root/New"}}"""))
            server.enqueue(MockResponse().setBody("""{"data":true}"""))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                changeNativeTorrent(SeanimeRepository(api), torrent, directory = "/androidtv/root/New")
                assertEquals("/api/v1/settings", server.takeRequest().path)
                assertEquals("/api/v1/torrent-client/list", server.takeRequest().path)
                assertEquals("/api/v1/directory-selector", server.takeRequest().path)
                val change = JSONObject(server.takeRequest().body.readUtf8())
                assertEquals("move-storage", change.getString("action")); assertEquals("/androidtv/root/New", change.getString("dir")); assertFalse(change.has("name"))
            }
        }
    }

    @Test fun changedOrExternalTorrentCannotBeRenamed() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val item = DownloadItem("hash-21", "Series", "paused", 0.5, "", "torrent", JSONObject().put("contentPath", "/old"))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val repo = SeanimeRepository(api)
                server.enqueue(MockResponse().setBody("""{"data":{"torrent":{"defaultTorrentClient":"qbittorrent"}}}"""))
                assertTrue(runCatching { changeNativeTorrent(repo, item, name = "New name") }.isFailure)
                assertEquals(1, server.requestCount)
                server.enqueue(MockResponse().setBody("""{"data":{"torrent":{"defaultTorrentClient":"seanime"}}}"""))
                server.enqueue(MockResponse().setBody("""{"data":[{"hash":"hash-21","name":"Someone renamed this","contentPath":"/old"}]}"""))
                assertTrue(runCatching { changeNativeTorrent(repo, item, name = "New name") }.isFailure)
                assertEquals(3, server.requestCount)
            }
        }
    }
}
