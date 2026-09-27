package app.seanime.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileNotFoundException
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

        try {
            val root = DocumentFile.fromTreeUri(targetContext, treeUri)
                ?: throw AssertionError("Could not open test storage root")
            val journal = JSONObject()

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
        } finally {
            storagePreferences.edit().putString("roots", previousRoots).commit()
            journalPreferences.edit().putString("transactions", previousJournal).commit()
            runCatching { targetContext.contentResolver.releasePersistableUriPermission(treeUri, readWriteFlags) }
            targetContext.revokeUriPermission(treeUri, readWriteFlags)
            TestDocumentsProvider.reset(targetContext)
        }
    }

    private fun encode(value: String): String = Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
    private fun decode(value: String): String = String(Base64.decode(value, Base64.NO_WRAP))

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
