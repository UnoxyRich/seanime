package app.seanime.tv.platform

import android.content.Context
import android.net.Uri
import app.seanime.tv.AndroidSafStorageAdapter
import app.seanime.tv.data.NativeExportFile
import app.seanime.tv.data.NATIVE_EXPORT_LIMIT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream

internal data class NativeOwnedFile(val path: String, val name: String, val directory: Boolean)
internal interface NativeLibraryFiles {
    suspend fun roots(): List<NativeOwnedFile>
    suspend fun list(path: String): List<NativeOwnedFile>
    suspend fun read(path: String): ByteArray
    fun save(file: NativeExportFile)
}

/** Browses only existing persisted SAF trees that are also mounted in the Go host. */
internal class AndroidNativeLibraryFiles(private val context: Context, private val platform: NativePlatformActions) : NativeLibraryFiles {
    private val adapter = AndroidSafStorageAdapter(context)
    override suspend fun roots(): List<NativeOwnedFile> = withContext(Dispatchers.IO) {
        val roots = platform.storageRoots()
        (0 until roots.length()).map { roots.getJSONObject(it) }.filter { it.optBoolean("available") && it.optBoolean("granted") }
            .map { NativeOwnedFile(it.getString("path"), it.optString("name", "Storage folder"), true) }.distinctBy { it.path }
    }
    private suspend fun requireOwned(path: String) {
        require(roots().any { path == it.path || path.startsWith(it.path.trimEnd('/') + "/") }) { "Choose a file in a connected storage folder" }
        require(path.split('/').none { it == ".." || it == "." }) { "Invalid storage path" }
    }
    override suspend fun list(path: String): List<NativeOwnedFile> = withContext(Dispatchers.IO) {
        requireOwned(path)
        val rows = JSONArray(adapter.list(path))
        (0 until rows.length()).map { rows.getJSONObject(it) }.mapNotNull { item ->
            val name = item.optString("name")
            if (name.isBlank() || name in setOf(".", "..") || name.contains('/') || name.contains('\\')) null
            else NativeOwnedFile(path.trimEnd('/') + "/" + name, name, item.optBoolean("isDirectory"))
        }.filter { it.directory || it.name.endsWith(".json", true) }.sortedWith(compareByDescending<NativeOwnedFile> { it.directory }.thenBy { it.name.lowercase() })
    }
    override suspend fun read(path: String): ByteArray = withContext(Dispatchers.IO) {
        requireOwned(path)
        val uri = Uri.parse(adapter.uri(path)) // The adapter revalidates the current grant and resolved document.
        context.contentResolver.openInputStream(uri)?.use { input ->
            val result = ByteArrayOutputStream()
            val chunk = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                check(result.size() + count <= NATIVE_EXPORT_LIMIT) { "The selected index is larger than 32 MiB" }
                result.write(chunk, 0, count)
            }
            result.toByteArray()
        } ?: error("The selected file could not be read. Reconnect its folder and try again.")
    }
    override fun save(file: NativeExportFile) {
        check(platform.saveReport(file.filename, file.mimeType, file.bytes)) { "Android could not open a file save. Check folder access and try again." }
    }
}
