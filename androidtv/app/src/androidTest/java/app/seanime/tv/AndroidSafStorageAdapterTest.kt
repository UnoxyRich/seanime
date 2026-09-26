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
import org.junit.Test

class AndroidSafStorageAdapterTest {
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

    private fun encode(value: String): String = Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
    private fun decode(value: String): String = String(Base64.decode(value, Base64.NO_WRAP))
}
