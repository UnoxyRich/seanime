package app.seanime.tv

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import app.seanime.tv.gomobile.mobile.AndroidStorageAdapter
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** SAF-backed filesystem operations exposed to the Go server through gomobile. */
class AndroidSafStorageAdapter(context: Context) : AndroidStorageAdapter {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    private val pendingWrites = ConcurrentHashMap<String, WriteSession>()

    override fun list(path: String): String {
        val directory = resolve(path)
        if (!directory.isDirectory) throw IOException("Storage path is not a directory")
        val result = JSONArray()
        directory.listFiles().forEach { child -> result.put(entryJson(child)) }
        return result.toString()
    }

    override fun stat(path: String): String = entryJson(resolve(path)).toString()

    override fun readAt(path: String, offset: Long, length: Long): String {
        if (offset < 0 || length < 0 || length > MAX_READ_BYTES) {
            throw IOException("Invalid storage read range")
        }
        val document = resolve(path)
        if (!document.isFile) throw IOException("Storage path is not a file")
        val buffer = ByteArray(length.toInt())
        var bytesRead = -1
        val descriptor = runCatching { contentResolver.openFileDescriptor(document.uri, "r") }.getOrNull()
        if (descriptor != null) {
            try {
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    val channel = input.channel
                    channel.position(offset)
                    val byteBuffer = ByteBuffer.wrap(buffer)
                    while (byteBuffer.hasRemaining()) {
                        val count = channel.read(byteBuffer)
                        if (count < 0) break
                        if (count == 0) break
                    }
                    bytesRead = byteBuffer.position()
                }
            } catch (_: Exception) {
                bytesRead = -1
            } finally {
                runCatching { descriptor.close() }
            }
        }
        if (bytesRead < 0) {
            contentResolver.openInputStream(document.uri)?.use { input ->
                skipFully(input, offset)
                bytesRead = readUpTo(input, buffer)
            } ?: throw FileNotFoundException("Could not open document")
        }
        return Base64.encodeToString(buffer.copyOf(bytesRead), Base64.NO_WRAP)
    }

    override fun beginWrite(path: String, truncate: Boolean): String {
        val (parent, name) = resolveParent(path)
        if (!parent.isDirectory) throw IOException("Storage parent is not a directory")
        val id = UUID.randomUUID().toString()
        val temporaryName = ".${name}.seanime-$id.part"
        val backupName = ".${name}.seanime-$id.backup"
        val temporary = parent.createFile("application/octet-stream", temporaryName)
            ?: throw IOException("Could not create a temporary document")
        val output = contentResolver.openOutputStream(temporary.uri, "wt")
            ?: run {
                temporary.delete()
                throw IOException("Could not open document for writing")
            }
        if (!truncate) {
            val existing = parent.findFile(name)
            if (existing?.isFile == true) {
                try {
                    contentResolver.openInputStream(existing.uri)?.use { input -> input.copyTo(output) }
                        ?: throw FileNotFoundException("Could not read existing document")
                } catch (error: Exception) {
                    runCatching { output.close() }
                    temporary.delete()
                    throw error
                }
            }
        }
        pendingWrites[id] = WriteSession(parent, temporary, name, backupName, output)
        return id
    }

    override fun writeChunk(handle: String, data: String) {
        val session = pendingWrites[handle] ?: throw IOException("Unknown storage write handle")
        session.output.write(Base64.decode(data, Base64.DEFAULT))
    }

    override fun finishWrite(handle: String) {
        val session = pendingWrites.remove(handle) ?: throw IOException("Unknown storage write handle")
        try {
            session.output.close()
        } catch (error: Exception) {
            session.temporary.delete()
            throw error
        }
        try {
            finishWriteSafely(session)
        } catch (error: Exception) {
            session.temporary.delete()
            throw error
        }
    }

    override fun cancelWrite(handle: String) {
        val session = pendingWrites.remove(handle) ?: return
        runCatching { session.output.close() }
        session.temporary.delete()
    }

    override fun mkdirAll(path: String) {
        val segments = validatedSegments(path)
        var current = rootDocument(segments.first(), requireWrite = true)
        for (segment in segments.drop(1)) {
            val child = current.findFile(segment)
            current = when {
                child == null -> current.createDirectory(segment)
                    ?: throw IOException("Could not create directory: $segment")
                child.isDirectory -> child
                else -> throw IOException("A file already exists at $segment")
            }
        }
    }

    override fun remove(path: String) {
        val segments = validatedSegments(path)
        if (segments.size < 2) throw IOException("Cannot remove a selected storage root")
        val document = resolve(path, requireWrite = true)
        if (!document.delete()) throw IOException("Could not delete document")
    }

    override fun uri(path: String): String = resolve(path).uri.toString()

    private fun resolve(path: String, requireWrite: Boolean = false): DocumentFile {
        val segments = validatedSegments(path)
        var current = rootDocument(segments.first(), requireWrite)
        for (segment in segments.drop(1)) {
            current = current.findFile(segment) ?: throw FileNotFoundException("Document not found: $segment")
        }
        return current
    }

    private fun resolveParent(path: String): Pair<DocumentFile, String> {
        val segments = validatedSegments(path)
        if (segments.size < 2) throw IOException("A filename is required")
        val name = segments.last()
        var parent = rootDocument(segments.first(), requireWrite = true)
        for (segment in segments.subList(1, segments.lastIndex)) {
            parent = parent.findFile(segment)?.takeIf { it.isDirectory }
                ?: throw FileNotFoundException("Storage parent directory not found")
        }
        return parent to name
    }

    private fun finishWriteSafely(session: WriteSession) {
        val existing = session.parent.findFile(session.destinationName)
        if (existing != null) {
            if (existing.isDirectory) throw IOException("A directory already exists at the destination")
            if (!existing.renameTo(session.backupName)) {
                throw IOException("Storage provider cannot safely replace the existing document")
            }
            if (!session.temporary.renameTo(session.destinationName)) {
                val backup = session.parent.findFile(session.backupName)
                val restored = backup?.renameTo(session.destinationName) == true
                session.temporary.delete()
                if (!restored) {
                    throw IOException("Could not finalize the new document; the existing document remains as ${session.backupName}")
                }
                throw IOException("Could not finalize the new document; the existing document was restored")
            }
            session.parent.findFile(session.backupName)?.delete()
            return
        }

        if (session.temporary.renameTo(session.destinationName)) return

        // Some providers cannot rename documents. For a new destination, create
        // the final document and copy into it; there is no existing data to risk.
        if (session.parent.findFile(session.destinationName) != null) {
            session.temporary.delete()
            throw IOException("Storage provider could not safely finalize the document")
        }
        val created = session.parent.createFile(session.temporary.type ?: "application/octet-stream", session.destinationName)
            ?: run {
                session.temporary.delete()
                throw IOException("Storage provider cannot create the destination document")
            }
        try {
            val input = contentResolver.openInputStream(session.temporary.uri)
                ?: throw FileNotFoundException("Could not read the temporary document")
            val output = contentResolver.openOutputStream(created.uri, "wt")
                ?: throw IOException("Could not open the destination document for writing")
            input.use { source -> output.use { destination -> source.copyTo(destination) } }
        } catch (error: Exception) {
            created.delete()
            session.temporary.delete()
            throw error
        }
        session.temporary.delete()
    }

    private fun rootDocument(id: String, requireWrite: Boolean = false): DocumentFile {
        val preferences = appContext.getSharedPreferences("android-tv-storage", Context.MODE_PRIVATE)
        val roots = runCatching { JSONArray(preferences.getString("roots", "[]") ?: "[]") }
            .getOrElse { throw IOException("Saved storage roots are unreadable", it) }
        val root = (0 until roots.length())
            .mapNotNull { roots.optJSONObject(it) }
            .firstOrNull { item ->
                val storedId = item.optString("id")
                val uri = runCatching { Uri.parse(item.optString("uri")) }.getOrNull()
                storedId == id || (storedId.isBlank() && uri?.let { storageId(it) } == id)
            } ?: throw FileNotFoundException("Selected storage root is no longer available")
        val uriString = root.optString("uri")
        val treeUri = Uri.parse(uriString)
        val hasGrant = contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isReadPermission && (!requireWrite || permission.isWritePermission)
        }
        if (!hasGrant) throw SecurityException("Storage permission was revoked or is read-only")
        return DocumentFile.fromTreeUri(appContext, treeUri)?.takeIf { it.canRead() && (!requireWrite || it.canWrite()) }
            ?: throw FileNotFoundException("Selected storage root is disconnected")
    }

    private fun validatedSegments(path: String): List<String> {
        if (!path.startsWith(PATH_PREFIX)) throw IOException("Not an Android TV storage path")
        val segments = path.removePrefix(PATH_PREFIX).split('/')
        if (segments.isEmpty() || segments.any { it.isBlank() || it == "." || it == ".." || it.contains('/') }) {
            throw IOException("Invalid Android TV storage path")
        }
        return segments
    }

    private fun entryJson(document: DocumentFile): JSONObject = JSONObject()
        .put("name", document.name ?: "")
        .put("isDirectory", document.isDirectory)
        .put("size", if (document.isFile) document.length().coerceAtLeast(0) else 0)
        .put("modTime", document.lastModified().coerceAtLeast(0))

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(32 * 1024)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("Reached end of document while seeking")
                remaining -= read
            }
        }
    }

    private fun readUpTo(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private data class WriteSession(
        val parent: DocumentFile,
        val temporary: DocumentFile,
        val destinationName: String,
        val backupName: String,
        val output: OutputStream,
    )

    companion object {
        private const val PATH_PREFIX = "/androidtv/"
        private const val MAX_READ_BYTES = 256L * 1024

        fun storageId(uri: Uri): String = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        fun virtualRoot(uri: Uri): String = "$PATH_PREFIX${storageId(uri)}"
    }
}
