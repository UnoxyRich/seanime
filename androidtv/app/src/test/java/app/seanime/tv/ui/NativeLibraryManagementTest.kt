package app.seanime.tv.ui

import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import app.seanime.tv.data.jsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeLibraryManagementTest {
    @Test fun folderSelectionContainsOnlyExactIndexedDescendants() {
        val first = file("/owned/one.mkv")
        val nested = file("/owned/sub/two.mkv")
        val folder = jsonObject("path" to "/owned", "kind" to "directory", "children" to JSONArray()
            .put(jsonObject("path" to first.getString("path"), "localFile" to first))
            .put(jsonObject("path" to "/owned/unindexed.mkv", "kind" to "file"))
            .put(jsonObject("path" to "/owned/sub", "kind" to "directory", "children" to JSONArray().put(jsonObject("localFile" to nested)))))
        assertEquals(listOf("/owned/one.mkv", "/owned/sub/two.mkv"), nativeLibraryFolderFiles(folder).map { it.getString("path") })
        assertTrue(nativeLibraryFolderFiles(jsonObject("path" to "/empty")).isEmpty())
    }

    @Test fun everySelectedPathMustStillBeUniquelyIndexed() {
        val one = file("/owned/one.mkv")
        assertTrue(runCatching { nativeLibrarySelectedFiles(emptySet(), listOf(one)) }.isFailure)
        assertTrue(runCatching { nativeLibrarySelectedFiles(setOf("/owned/one.mkv", "/owned/missing.mkv"), listOf(one)) }.isFailure)
        assertTrue(runCatching { nativeLibrarySelectedFiles(setOf("/owned/one.mkv"), listOf(one, one)) }.isFailure)
    }

    @Test fun allSixBulkActionsUseFreshExactPathsAndOnlyMatchSendsMediaId() = runBlocking {
        for (action in NativeLibraryBulkAction.entries) MockWebServer().use { server ->
            val paths = linkedSetOf("/owned/one.mkv", "/owned/sub/two.mkv")
            server.enqueue(data(JSONArray(paths.map(::file)))); server.enqueue(data(true))
            SeanimeApiClient(server.url("/").toString()).use { api -> applyNativeLibraryBulkAction(SeanimeRepository(api), paths, action, 42) }
            assertEquals("GET", server.takeRequest().method)
            val request = server.takeRequest()
            assertEquals("PATCH", request.method); assertEquals("/api/v1/library/local-files", request.path)
            val body = JSONObject(request.body.readUtf8())
            assertEquals(if (action == NativeLibraryBulkAction.MATCH) setOf("paths", "action", "mediaId") else setOf("paths", "action"), body.keys().asSequence().toSet())
            assertEquals(JSONArray(paths.toList()).toString(), body.getJSONArray("paths").toString())
            assertEquals(action.value, body.getString("action"))
            if (action == NativeLibraryBulkAction.MATCH) assertEquals(42, body.getInt("mediaId"))
        }
    }

    @Test fun falseAckRetainsBulkIntentAndStaleSelectionCannotPartiallyApply() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(data(JSONArray().put(file("/owned/one.mkv")))); server.enqueue(data(false))
            server.enqueue(data(JSONArray()))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val repo = SeanimeRepository(api)
                repeat(2) { assertTrue(runCatching { applyNativeLibraryBulkAction(repo, setOf("/owned/one.mkv"), NativeLibraryBulkAction.IGNORE) }.isFailure) }
            }
            assertEquals(3, server.requestCount)
            assertEquals("GET", server.takeRequest().method); assertEquals("PATCH", server.takeRequest().method); assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test fun renameRejectsTraversalUnsafeNamesChangedExtensionsAndUnchangedNames() {
        val path = "/owned/one.mkv"
        for (name in listOf("", ".", "..", ".mkv", "../other.mkv", "folder/other.mkv", "folder\\other.mkv", " bad.mkv", "bad?.mkv", "bad\u0000.mkv", "other.mp4", "one.mkv", "é".repeat(130) + ".mkv"))
            assertTrue("Reject $name", runCatching { nativeLibraryRenameTarget(path, name) }.isFailure)
        assertEquals("/owned/Episode 02.mkv", nativeLibraryRenameTarget(path, "Episode 02.mkv"))
    }

    @Test fun changedIdentityAndKnownDestinationCollisionPreventRename() = runBlocking {
        for (current in listOf(file("/owned/one.mkv").put("name", "changed.mkv"), file("/owned/one.mkv"))) MockWebServer().use { server ->
            val index = JSONArray().put(current)
            if (current.getString("name") == "one.mkv") index.put(file("/owned/TWO.mkv"))
            server.enqueue(data(index))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                assertTrue(runCatching { renameNativeLibraryFile(SeanimeRepository(api), file("/owned/one.mkv"), "two.mkv") }.isFailure)
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun unindexedDestinationInFreshTreePreventsRename() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(data(JSONArray().put(file("/owned/one.mkv")))); server.enqueue(data(true))
            server.enqueue(data(jsonObject("root" to jsonObject("path" to "/owned", "children" to JSONArray().put(jsonObject("path" to "/owned/two.mkv"))))))
            SeanimeApiClient(server.url("/").toString()).use { api ->
                assertTrue(runCatching { renameNativeLibraryFile(SeanimeRepository(api), file("/owned/one.mkv"), "two.mkv") }.isFailure)
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun renameRetryRequiresTrueAckAndNeverCopiesMetadataIntoSuperUpdate() = runBlocking {
        MockWebServer().use { server ->
            for (ack in listOf(false, true)) {
                server.enqueue(data(JSONArray().put(file("/owned/one.mkv").put("mediaId", 99))))
                server.enqueue(data(true)); server.enqueue(data(jsonObject("root" to jsonObject("path" to "/owned"))))
                server.enqueue(data(ack))
            }
            SeanimeApiClient(server.url("/").toString()).use { api ->
                val repo = SeanimeRepository(api)
                assertTrue(runCatching { renameNativeLibraryFile(repo, file("/owned/one.mkv"), "two.mkv") }.isFailure)
                renameNativeLibraryFile(repo, file("/owned/one.mkv"), "two.mkv")
            }
            repeat(2) {
                assertEquals("/api/v1/library/local-files", server.takeRequest().path)
                assertEquals("/api/v1/library/explorer/file-tree/refresh", server.takeRequest().path)
                assertEquals("/api/v1/library/explorer/file-tree", server.takeRequest().path)
                val request = server.takeRequest()
                assertEquals("PATCH", request.method); assertEquals("/api/v1/library/local-files/super-update", request.path)
                val body = JSONObject(request.body.readUtf8())
                assertEquals(setOf("files"), body.keys().asSequence().toSet())
                val change = body.getJSONArray("files").getJSONObject(0)
                assertEquals(setOf("path", "newName"), change.keys().asSequence().toSet())
                assertEquals("/owned/one.mkv", change.getString("path")); assertEquals("two.mkv", change.getString("newName"))
            }
        }
    }

    private fun file(path: String) = jsonObject("path" to path, "name" to path.substringAfterLast('/'), "mediaId" to 21,
        "metadata" to jsonObject("episode" to 1, "type" to "main"))
    private fun data(value: Any) = MockResponse().setBody(jsonObject("data" to value).toString())
}
