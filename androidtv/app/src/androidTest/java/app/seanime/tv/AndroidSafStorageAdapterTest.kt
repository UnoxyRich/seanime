package app.seanime.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.seanime.tv.gomobile.mobile.Mobile
import app.seanime.tv.data.SeanimeApiClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.FileNotFoundException
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidSafStorageAdapterTest {
    @Test
    fun revokedPersistedGrantIsRejectedAndWorksAgainAfterRestoration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        TestDocumentsProvider.reset(targetContext)

        val treeUri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root")
        val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val grantFlags = readWriteFlags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        TestDocumentsProvider.grantTree(targetContext, treeUri, grantFlags)
        targetContext.contentResolver.takePersistableUriPermission(treeUri, readWriteFlags)

        val preferences = targetContext.getSharedPreferences("android-tv-storage", Context.MODE_PRIVATE)
        val previousRoots = preferences.getString("roots", "[]")
        val rootPath = AndroidSafStorageAdapter.virtualRoot(treeUri)
        preferences.edit().putString(
            "roots",
            JSONArray().put(
                JSONObject()
                    .put("id", AndroidSafStorageAdapter.storageId(treeUri))
                    .put("uri", treeUri.toString())
                    .put("path", rootPath)
                    .put("name", "Test USB"),
            ).toString(),
        ).commit()

        try {
            val adapter = AndroidSafStorageAdapter(targetContext)
            assertTrue(JSONObject(adapter.stat(rootPath)).getString("name").isNotBlank())

            targetContext.contentResolver.releasePersistableUriPermission(treeUri, readWriteFlags)
            var rejectedRevokedGrant = false
            try {
                adapter.stat(rootPath)
            } catch (_: SecurityException) {
                rejectedRevokedGrant = true
            }
            assertTrue("adapter continued using a revoked persisted permission", rejectedRevokedGrant)

            targetContext.contentResolver.takePersistableUriPermission(treeUri, readWriteFlags)
            assertTrue(JSONObject(adapter.stat(rootPath)).getString("name").isNotBlank())
        } finally {
            preferences.edit().putString("roots", previousRoots).commit()
            runCatching { targetContext.contentResolver.releasePersistableUriPermission(treeUri, readWriteFlags) }
            targetContext.revokeUriPermission(treeUri, readWriteFlags)
            TestDocumentsProvider.reset(targetContext)
        }
    }

    @Test
    fun selectedTreeSupportsLibraryReadsWritesListingAndRemoval() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        TestDocumentsProvider.reset(targetContext)

        val treeUri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root")
        val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val grantFlags = readWriteFlags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        TestDocumentsProvider.grantTree(targetContext, treeUri, grantFlags)
        targetContext.contentResolver.takePersistableUriPermission(treeUri, readWriteFlags)

        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "root")
        val rootCursor = targetContext.contentResolver.query(rootDocumentUri, null, null, null, null)
        assertNotNull("Document provider did not return a root cursor for $rootDocumentUri", rootCursor)
        rootCursor!!.use { assertTrue("Document provider returned no selected root", it.moveToFirst()) }
        val selectedRoot = DocumentFile.fromTreeUri(targetContext, treeUri)
        assertNotNull("Could not wrap the selected document tree", selectedRoot)
        assertTrue("Selected document tree is unreadable", selectedRoot!!.canRead())
        assertTrue("Selected document tree is not writable", selectedRoot.canWrite())

        val preferences = targetContext.getSharedPreferences("android-tv-storage", Context.MODE_PRIVATE)
        val previousRoots = preferences.getString("roots", "[]")
        val rootId = AndroidSafStorageAdapter.storageId(treeUri)
        preferences.edit().putString(
            "roots",
            JSONArray().put(
                JSONObject()
                    .put("id", rootId)
                    .put("uri", treeUri.toString())
                    .put("path", AndroidSafStorageAdapter.virtualRoot(treeUri))
                    .put("name", "Test USB"),
            ).toString(),
        ).commit()

        try {
            val adapter = AndroidSafStorageAdapter(targetContext)
            val directory = "${AndroidSafStorageAdapter.virtualRoot(treeUri)}/Anime/Season 1"
            val mediaPath = "$directory/episode.mkv"
            adapter.mkdirAll(directory)

            val firstWrite = adapter.beginWrite(mediaPath, true)
            adapter.writeChunk(firstWrite, encode("episode"))
            adapter.finishWrite(firstWrite)
            assertEquals("episode".length.toLong(), JSONObject(adapter.stat(mediaPath)).getLong("size"))
            assertEquals("isod", decode(adapter.readAt(mediaPath, 2, 4)))
            assertTrue(adapter.uri(mediaPath).startsWith("content://${TestDocumentsProvider.AUTHORITY}/"))

            val entries = JSONArray(adapter.list(directory))
            assertEquals(1, entries.length())
            assertEquals("episode.mkv", entries.getJSONObject(0).getString("name"))
            assertEquals("episode".length.toLong(), entries.getJSONObject(0).getLong("size"))

            val appendWrite = adapter.beginWrite(mediaPath, false)
            adapter.writeChunk(appendWrite, encode("-tv"))
            adapter.finishWrite(appendWrite)
            assertEquals("episode-tv", decode(adapter.readAt(mediaPath, 0, 32)))

            adapter.remove(mediaPath)
            assertEquals(0, JSONArray(adapter.list(directory)).length())
            try {
                adapter.stat(mediaPath)
                throw AssertionError("Removed document remained visible")
            } catch (_: FileNotFoundException) {
                // Expected: the adapter no longer resolves the deleted document.
            }
        } finally {
            preferences.edit().putString("roots", previousRoots).commit()
            runCatching { targetContext.contentResolver.releasePersistableUriPermission(treeUri, readWriteFlags) }
            targetContext.revokeUriPermission(treeUri, readWriteFlags)
            TestDocumentsProvider.reset(targetContext)
        }
    }

    @Test
    fun failedFallbackCleanupKeepsJournalUntilPartialDestinationIsRemoved() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        TestDocumentsProvider.reset(targetContext)

        val treeUri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root")
        val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val grantFlags = readWriteFlags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        TestDocumentsProvider.grantTree(targetContext, treeUri, grantFlags)
        targetContext.contentResolver.takePersistableUriPermission(treeUri, readWriteFlags)

        val storagePreferences = targetContext.getSharedPreferences("android-tv-storage", Context.MODE_PRIVATE)
        val journalPreferences = targetContext.getSharedPreferences("android-tv-storage-writes", Context.MODE_PRIVATE)
        val previousRoots = storagePreferences.getString("roots", "[]")
        val previousJournal = journalPreferences.getString("transactions", "{}")
        val rootPath = AndroidSafStorageAdapter.virtualRoot(treeUri)
        storagePreferences.edit().putString(
            "roots",
            JSONArray().put(
                JSONObject()
                    .put("id", AndroidSafStorageAdapter.storageId(treeUri))
                    .put("uri", treeUri.toString())
                    .put("path", rootPath)
                    .put("name", "Test USB"),
            ).toString(),
        ).commit()
        journalPreferences.edit().putString("transactions", "{}").commit()

        try {
            val root = DocumentFile.fromTreeUri(targetContext, treeUri)
                ?: throw AssertionError("Could not open test storage root")
            val destinationName = "partial.mkv"
            val adapter = AndroidSafStorageAdapter(targetContext)
            val handle = adapter.beginWrite("$rootPath/$destinationName", true)
            adapter.writeChunk(handle, encode("new video data"))

            TestDocumentsProvider.setRenameUnavailable(true)
            TestDocumentsProvider.setWriteFailureName(destinationName)
            TestDocumentsProvider.setDeleteDeniedName(destinationName)
            var writeFailed = false
            try {
                adapter.finishWrite(handle)
            } catch (_: IOException) {
                writeFailed = true
            }
            assertTrue("Injected fallback copy failure was not reported", writeFailed)
            assertNotNull("The provider should retain the undeletable partial file", root.findFile(destinationName))
            assertTrue(
                "The recovery journal was cleared while its partial destination remained",
                journalPreferences.getString("transactions", "{}") != "{}",
            )

            val recoveryAdapter = AndroidSafStorageAdapter(targetContext)
            var unsafeRootAccessAllowed = false
            try {
                recoveryAdapter.stat("$rootPath/$destinationName")
                unsafeRootAccessAllowed = true
            } catch (_: IOException) {
                // The root remains blocked while the provider refuses cleanup.
            }
            assertFalse("The adapter exposed an unresolved partial destination", unsafeRootAccessAllowed)

            TestDocumentsProvider.setDeleteDeniedName(null)
            assertEquals(0, JSONArray(recoveryAdapter.list(rootPath)).length())
            assertEquals("{}", journalPreferences.getString("transactions", "{}"))
        } finally {
            TestDocumentsProvider.reset(targetContext)
            storagePreferences.edit().putString("roots", previousRoots).commit()
            journalPreferences.edit().putString("transactions", previousJournal).commit()
            runCatching { targetContext.contentResolver.releasePersistableUriPermission(treeUri, readWriteFlags) }
            targetContext.revokeUriPermission(treeUri, readWriteFlags)
        }
    }

    @Test
    fun abandonedWritesAreRecoveredWithoutLosingCommittedOrOriginalFiles() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        TestDocumentsProvider.reset(targetContext)

        val treeUri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root")
        val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val grantFlags = readWriteFlags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        TestDocumentsProvider.grantTree(targetContext, treeUri, grantFlags)
        targetContext.contentResolver.takePersistableUriPermission(treeUri, readWriteFlags)

        val storagePreferences = targetContext.getSharedPreferences("android-tv-storage", Context.MODE_PRIVATE)
        val journalPreferences = targetContext.getSharedPreferences("android-tv-storage-writes", Context.MODE_PRIVATE)
        val previousRoots = storagePreferences.getString("roots", "[]")
        val previousJournal = journalPreferences.getString("transactions", "{}")
        val rootPath = AndroidSafStorageAdapter.virtualRoot(treeUri)
        val disconnectedRootId = "disconnected-usb"
        val disconnectedRootPath = "/androidtv/$disconnectedRootId"
        val disconnectedTreeUri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "disconnected")
        storagePreferences.edit().putString(
            "roots",
            JSONArray().put(
                JSONObject()
                    .put("id", AndroidSafStorageAdapter.storageId(treeUri))
                    .put("uri", treeUri.toString())
                    .put("path", rootPath)
                    .put("name", "Test USB"),
            ).put(
                JSONObject()
                    .put("id", disconnectedRootId)
                    .put("uri", disconnectedTreeUri.toString())
                    .put("path", disconnectedRootPath)
                    .put("name", "Disconnected USB"),
            ).toString(),
        ).commit()

        try {
            val root = DocumentFile.fromTreeUri(targetContext, treeUri)
                ?: throw AssertionError("Could not open test storage root")
            val journal = JSONObject()
            val writer = AndroidSafStorageAdapter(targetContext)

            val cancelledPath = "$rootPath/cancelled.mkv"
            val cancelledWrite = writer.beginWrite(cancelledPath, true)
            writer.writeChunk(cancelledWrite, encode("cancelled"))
            writer.cancelWrite(cancelledWrite)
            assertFalse(root.listFiles().any { it.name == ".cancelled.mkv.seanime-$cancelledWrite.part" })
            assertEquals("{}", journalPreferences.getString("transactions", "{}"))

            assertNotNull("Could not create the destination directory for failure coverage", root.createDirectory("blocked.mkv"))
            val failedWrite = writer.beginWrite("$rootPath/blocked.mkv", true)
            writer.writeChunk(failedWrite, encode("must not replace a directory"))
            var failedAsExpected = false
            try {
                writer.finishWrite(failedWrite)
            } catch (_: IOException) {
                failedAsExpected = true
            }
            assertTrue("A write replaced a destination directory", failedAsExpected)
            assertTrue(root.findFile("blocked.mkv")?.isDirectory == true)
            assertFalse(root.listFiles().any { it.name?.contains("blocked.mkv.seanime-") == true })
            assertEquals("{}", journalPreferences.getString("transactions", "{}"))

            val orphanId = "10000000-0000-4000-8000-000000000001"
            val orphanTemp = ".orphan.mkv.seanime-$orphanId.part"
            writeDocument(targetContext, root, orphanTemp, "partial")
            journal.put(orphanId, writeJournalEntry("$rootPath/orphan.mkv", orphanTemp, ".orphan.mkv.seanime-$orphanId.backup", "writing"))

            val replacingId = "10000000-0000-4000-8000-000000000002"
            val original = writeDocument(targetContext, root, "replacement.mkv", "original")
            val replacingBackup = ".replacement.mkv.seanime-$replacingId.backup"
            assertTrue("Could not seed the replacement backup", original.renameTo(replacingBackup))
            val replacingTemp = ".replacement.mkv.seanime-$replacingId.part"
            writeDocument(targetContext, root, replacingTemp, "uncommitted")
            journal.put(
                replacingId,
                writeJournalEntry(
                    "$rootPath/replacement.mkv",
                    replacingTemp,
                    replacingBackup,
                    "replacing",
                ),
            )

            val committedId = "10000000-0000-4000-8000-000000000003"
            val committedBackup = ".committed.mkv.seanime-$committedId.backup"
            val oldCommitted = writeDocument(targetContext, root, "committed.mkv", "old")
            assertTrue("Could not seed the committed backup", oldCommitted.renameTo(committedBackup))
            writeDocument(targetContext, root, "committed.mkv", "new")
            journal.put(
                committedId,
                writeJournalEntry(
                    "$rootPath/committed.mkv",
                    ".committed.mkv.seanime-$committedId.part",
                    committedBackup,
                    "replacing",
                ),
            )

            val copyingId = "10000000-0000-4000-8000-000000000004"
            val incomplete = writeDocument(targetContext, root, "incomplete.mkv", "partial-copy")
            val copyingTemp = ".incomplete.mkv.seanime-$copyingId.part"
            writeDocument(targetContext, root, copyingTemp, "complete-source")
            journal.put(
                copyingId,
                writeJournalEntry(
                    "$rootPath/incomplete.mkv",
                    copyingTemp,
                    ".incomplete.mkv.seanime-$copyingId.backup",
                    "copying",
                    incomplete.uri.toString(),
                ),
            )
            journalPreferences.edit().putString("transactions", journal.toString()).commit()

            val adapter = AndroidSafStorageAdapter(targetContext)
            val entries = JSONArray(adapter.list(rootPath))
            val names = (0 until entries.length()).map { entries.getJSONObject(it).getString("name") }.toSet()

            assertTrue(names.contains("replacement.mkv"))
            assertEquals("original", decode(adapter.readAt("$rootPath/replacement.mkv", 0, 32)))
            assertEquals("new", decode(adapter.readAt("$rootPath/committed.mkv", 0, 32)))
            assertFalse(names.contains("incomplete.mkv"))
            assertFalse(names.contains(orphanTemp))
            assertFalse(names.contains(replacingTemp))
            assertFalse(names.contains(replacingBackup))
            assertFalse(names.contains(committedBackup))
            assertFalse(names.contains(copyingTemp))
            assertEquals("{}", journalPreferences.getString("transactions", "{}"))

            val disconnectedWriteId = "10000000-0000-4000-8000-000000000005"
            val disconnectedTemp = ".queued.mkv.seanime-$disconnectedWriteId.part"
            val disconnectedJournal = JSONObject().put(
                disconnectedWriteId,
                writeJournalEntry(
                    "$disconnectedRootPath/queued.mkv",
                    disconnectedTemp,
                    ".queued.mkv.seanime-$disconnectedWriteId.backup",
                    "writing",
                ),
            )
            journalPreferences.edit().putString("transactions", disconnectedJournal.toString()).commit()
            val isolatedAdapter = AndroidSafStorageAdapter(targetContext)
            assertTrue(JSONArray(isolatedAdapter.list(rootPath)).length() > 0)
            var disconnectedRootBlocked = false
            try {
                isolatedAdapter.list(disconnectedRootPath)
            } catch (_: IOException) {
                disconnectedRootBlocked = true
            }
            assertTrue("A disconnected storage root was not blocked", disconnectedRootBlocked)
            assertEquals(disconnectedJournal.toString(), journalPreferences.getString("transactions", "{}"))
            assertTrue("An unavailable root blocked another selected root", JSONArray(isolatedAdapter.list(rootPath)).length() > 0)

            val protectedDocument = writeDocument(targetContext, root, "protected.mkv", "preserve")
            val invalidJournal = JSONObject().put(
                "not-a-transaction-id",
                writeJournalEntry(
                    "$rootPath/protected.mkv",
                    ".protected.mkv.seanime-invalid.part",
                    ".protected.mkv.seanime-invalid.backup",
                    "writing",
                ),
            )
            journalPreferences.edit().putString("transactions", invalidJournal.toString()).commit()
            val corruptJournalAdapter = AndroidSafStorageAdapter(targetContext)
            var refusedUnsafeAccess = false
            try {
                corruptJournalAdapter.stat("$rootPath/protected.mkv")
            } catch (_: IOException) {
                refusedUnsafeAccess = true
            }
            assertTrue("Storage access continued with an invalid write journal", refusedUnsafeAccess)
            assertEquals(invalidJournal.toString(), journalPreferences.getString("transactions", "{}"))
            val protectedBytes = targetContext.contentResolver.openInputStream(protectedDocument.uri)
                ?.use { String(it.readBytes()) }
            assertEquals("preserve", protectedBytes)

            journalPreferences.edit().putString("transactions", "{}").commit()
            assertEquals(8L, JSONObject(corruptJournalAdapter.stat("$rootPath/protected.mkv")).getLong("size"))
        } finally {
            storagePreferences.edit().putString("roots", previousRoots).commit()
            journalPreferences.edit().putString("transactions", previousJournal).commit()
            runCatching { targetContext.contentResolver.releasePersistableUriPermission(treeUri, readWriteFlags) }
            targetContext.revokeUriPermission(treeUri, readWriteFlags)
            TestDocumentsProvider.reset(targetContext)
        }
    }

    @Test
    fun goDirectorySelectorUsesThePersistedSafAdapter() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        TestDocumentsProvider.reset(targetContext)

        val treeUri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root")
        val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val grantFlags = readWriteFlags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        TestDocumentsProvider.grantTree(targetContext, treeUri, grantFlags)
        targetContext.contentResolver.takePersistableUriPermission(treeUri, readWriteFlags)

        val storagePreferences = targetContext.getSharedPreferences("android-tv-storage", Context.MODE_PRIVATE)
        val journalPreferences = targetContext.getSharedPreferences("android-tv-storage-writes", Context.MODE_PRIVATE)
        val previousRoots = storagePreferences.getString("roots", "[]")
        val previousJournal = journalPreferences.getString("transactions", "{}")
        val rootPath = AndroidSafStorageAdapter.virtualRoot(treeUri)
        storagePreferences.edit().putString(
            "roots",
            JSONArray().put(
                JSONObject()
                    .put("id", AndroidSafStorageAdapter.storageId(treeUri))
                    .put("uri", treeUri.toString())
                    .put("path", rootPath)
                    .put("name", "Test USB"),
            ).toString(),
        ).commit()
        journalPreferences.edit().putString("transactions", "{}").commit()

        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val root = DocumentFile.fromTreeUri(targetContext, treeUri)
                ?: throw AssertionError("Could not open test storage root")
            val anime = root.createDirectory("Anime")
                ?: throw AssertionError("Could not create Anime directory")
            anime.createDirectory("Season 1")
                ?: throw AssertionError("Could not create Season 1 directory")

            Mobile.setAndroidStorageAdapter(AndroidSafStorageAdapter(targetContext))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            assertTrue("Seanime server did not start", Mobile.waitForServer(60_000))

            SeanimeApiClient().use { api ->
                runBlocking { withTimeout(15_000) { api.request("GET", "/api/v1/status") } }
                assertTrue("The real server must issue a signed native client identity", !api.snapshotSession().identityProof.isNullOrBlank())
                val rootResponse = postDirectorySelector(api, rootPath)
                assertDirectory(rootResponse, rootPath, "Anime", "$rootPath/Anime")
                val animeResponse = postDirectorySelector(api, "$rootPath/Anime")
                assertDirectory(animeResponse, "$rootPath/Anime", "Season 1", "$rootPath/Anime/Season 1")
            }
        } finally {
            scenario?.close()
            Mobile.stopServer()
            storagePreferences.edit().putString("roots", previousRoots).commit()
            journalPreferences.edit().putString("transactions", previousJournal).commit()
            runCatching { targetContext.contentResolver.releasePersistableUriPermission(treeUri, readWriteFlags) }
            targetContext.revokeUriPermission(treeUri, readWriteFlags)
            TestDocumentsProvider.reset(targetContext)
        }
    }

    private fun encode(value: String): String = Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
    private fun decode(value: String): String = String(Base64.decode(value, Base64.NO_WRAP))

    private fun postDirectorySelector(api: SeanimeApiClient, input: String): JSONObject = runBlocking {
        withTimeout(15_000) {
            api.request("POST", "/api/v1/directory-selector", JSONObject().put("input", input)) as? JSONObject
                ?: throw AssertionError("Go did not return directory data for the owned SAF tree")
        }
    }

    private fun assertDirectory(response: JSONObject, expectedPath: String, childName: String, childPath: String) {
        assertTrue("Go did not recognize the owned SAF directory", response.getBoolean("exists"))
        assertEquals(expectedPath, response.getString("fullPath"))
        val children = response.getJSONArray("content")
        assertTrue("Go did not return the exact SAF child: $response", (0 until children.length()).any { index ->
            val child = children.getJSONObject(index)
            child.optString("folderName") == childName && child.optString("fullPath") == childPath
        })
    }

    private fun writeDocument(context: Context, parent: DocumentFile, name: String, content: String): DocumentFile {
        val document = parent.createFile("application/octet-stream", name)
            ?: throw AssertionError("Could not create test document $name")
        context.contentResolver.openOutputStream(document.uri, "wt")
            ?.use { it.write(content.toByteArray()) }
            ?: throw AssertionError("Could not write test document $name")
        return document
    }

    private fun writeJournalEntry(
        path: String,
        temporaryName: String,
        backupName: String,
        phase: String,
        destinationUri: String? = null,
    ): JSONObject = JSONObject()
        .put("path", path)
        .put("temporaryName", temporaryName)
        .put("backupName", backupName)
        .put("phase", phase)
        .apply { if (destinationUri != null) put("destinationUri", destinationUri) }
}
