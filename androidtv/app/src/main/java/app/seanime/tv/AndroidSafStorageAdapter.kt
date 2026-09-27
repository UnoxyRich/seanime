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
    private val activeWriteIds = ConcurrentHashMap.newKeySet<String>()
    private val journalPreferences = appContext.getSharedPreferences("android-tv-storage-writes", Context.MODE_PRIVATE)
    private val journalLock = Any()
    private val recoveryLock = Any()
    private val recoveredRoots = ConcurrentHashMap.newKeySet<String>()

    override fun list(path: String): String {
        ensureJournalRecovered(path)
        val directory = resolve(path)
        if (!directory.isDirectory) throw IOException("Storage path is not a directory")
        val result = JSONArray()
        directory.listFiles().forEach { child -> result.put(entryJson(child)) }
        return result.toString()
    }

    override fun stat(path: String): String {
        ensureJournalRecovered(path)
        return entryJson(resolve(path)).toString()
    }

    override fun readAt(path: String, offset: Long, length: Long): String {
        ensureJournalRecovered(path)
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
        ensureJournalRecovered(path)
        val (parent, name) = resolveParent(path)
        if (!parent.isDirectory) throw IOException("Storage parent is not a directory")
        val id = UUID.randomUUID().toString()
        val temporaryName = ".${name}.seanime-$id.part"
        val backupName = ".${name}.seanime-$id.backup"
        val transaction = JSONObject()
            .put("path", path)
            .put("temporaryName", temporaryName)
            .put("backupName", backupName)
            .put("phase", "writing")
        activeWriteIds.add(id)

        var temporary: DocumentFile? = null
        var output: OutputStream? = null
        try {
            updateWriteJournal(id, transaction)
            val temporaryDocument = parent.createFile("application/octet-stream", temporaryName)
                ?: throw IOException("Could not create a temporary document")
            temporary = temporaryDocument
            val writeOutput = contentResolver.openOutputStream(temporaryDocument.uri, "wt")
                ?: throw IOException("Could not open document for writing")
            output = writeOutput
            if (!truncate) {
                val existing = parent.findFile(name)
                if (existing?.isFile == true) {
                    contentResolver.openInputStream(existing.uri)?.use { input -> input.copyTo(writeOutput) }
                        ?: throw FileNotFoundException("Could not read existing document")
                }
            }
            pendingWrites[id] = WriteSession(parent, temporaryDocument, name, temporaryName, backupName, writeOutput)
            return id
        } catch (error: Exception) {
            runCatching { output?.close() }
            runCatching { temporary?.delete() }
            clearJournalIfArtifactsAreGone(id, parent, temporaryName, backupName)
            activeWriteIds.remove(id)
            throw error
        }
    }

    override fun writeChunk(handle: String, data: String) {
        val session = pendingWrites[handle] ?: throw IOException("Unknown storage write handle")
        session.output.write(Base64.decode(data, Base64.DEFAULT))
    }

    override fun finishWrite(handle: String) {
        val session = pendingWrites[handle] ?: throw IOException("Unknown storage write handle")
        try {
            session.output.close()
        } catch (error: Exception) {
            pendingWrites.remove(handle)
            runCatching { session.temporary.delete() }
            clearJournalIfArtifactsAreGone(handle, session.parent, session.temporaryName, session.backupName)
            activeWriteIds.remove(handle)
            throw error
        }
        try {
            finishWriteSafely(handle, session)
        } catch (error: Exception) {
            pendingWrites.remove(handle)
            runCatching { session.temporary.delete() }
            clearJournalIfArtifactsAreGone(handle, session.parent, session.temporaryName, session.backupName)
            activeWriteIds.remove(handle)
            throw error
        }
        pendingWrites.remove(handle)
        clearJournalIfArtifactsAreGone(handle, session.parent, session.temporaryName, session.backupName)
        activeWriteIds.remove(handle)
    }

    override fun cancelWrite(handle: String) {
        val session = pendingWrites.remove(handle) ?: return
        runCatching { session.output.close() }
        runCatching { session.temporary.delete() }
        clearJournalIfArtifactsAreGone(handle, session.parent, session.temporaryName, session.backupName)
        activeWriteIds.remove(handle)
    }

    override fun mkdirAll(path: String) {
        ensureJournalRecovered(path)
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
        ensureJournalRecovered(path)
        val segments = validatedSegments(path)
        if (segments.size < 2) throw IOException("Cannot remove a selected storage root")
        val document = resolve(path, requireWrite = true)
        if (!document.delete()) throw IOException("Could not delete document")
    }

    override fun uri(path: String): String {
        ensureJournalRecovered(path)
        return resolve(path).uri.toString()
    }

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

    private fun resolveDirectory(path: String, requireWrite: Boolean): DocumentFile {
        val segments = validatedSegments(path)
        var directory = rootDocument(segments.first(), requireWrite)
        for (segment in segments.drop(1)) {
            directory = directory.findFile(segment)?.takeIf { it.isDirectory }
                ?: throw FileNotFoundException("Storage parent directory not found")
        }
        return directory
    }

    private fun ensureJournalRecovered(path: String) {
        val rootId = validatedSegments(path).first()
        if (recoveredRoots.contains(rootId)) return
        synchronized(recoveryLock) {
            if (!recoveredRoots.contains(rootId)) {
                if (!recoverAbandonedWrites(rootId)) {
                    throw IOException("Could not recover a previous Android TV storage write")
                }
                recoveredRoots.add(rootId)
            }
        }
    }

    private fun recoverAbandonedWrites(rootId: String): Boolean = synchronized(journalLock) {
        val journal = try {
            JSONObject(journalPreferences.getString(WRITE_JOURNAL_KEY, "{}") ?: "{}")
        } catch (_: Exception) {
            return@synchronized false
        }
        if (journal.length() == 0) return@synchronized true

        val remaining = JSONObject()
        var unresolved = false
        val iterator = journal.keys()
        while (iterator.hasNext()) {
            val id = iterator.next()
            val transaction = journal.optJSONObject(id)
            val transactionRootId = transaction?.let { entry ->
                runCatching { validatedSegments(entry.optString("path")).first() }.getOrNull()
            }
            if (transaction == null || transactionRootId == null) {
                remaining.put(id, journal.opt(id) ?: JSONObject.NULL)
                unresolved = true
            } else if (transactionRootId != rootId || activeWriteIds.contains(id) || pendingWrites.containsKey(id)) {
                remaining.put(id, journal.opt(id) ?: JSONObject.NULL)
            } else if (!runCatching { recoverAbandonedWrite(id, transaction) }.getOrDefault(false)) {
                remaining.put(id, journal.opt(id) ?: JSONObject.NULL)
                unresolved = true
            }
        }
        val persisted = journalPreferences.edit().putString(WRITE_JOURNAL_KEY, remaining.toString()).commit()
        persisted && !unresolved
    }

    private fun recoverAbandonedWrite(id: String, transaction: JSONObject): Boolean {
        runCatching { UUID.fromString(id) }.getOrElse { return false }
        val path = transaction.optString("path")
        val segments = validatedSegments(path)
        if (segments.size < 2) return false
        val destinationName = segments.last()
        val temporaryName = ".${destinationName}.seanime-$id.part"
        val backupName = ".${destinationName}.seanime-$id.backup"
        if (transaction.optString("temporaryName") != temporaryName ||
            transaction.optString("backupName") != backupName) return false

        val parentPath = path.substringBeforeLast('/')
        val parent = resolveDirectory(parentPath, requireWrite = true)
        val phase = transaction.optString("phase", "writing")
        val destinationUri = transaction.optString("destinationUri")
        val temporary = parent.findFile(temporaryName)
        val backup = parent.findFile(backupName)
        var destination = parent.findFile(destinationName)

        if (phase == "creating" && destinationUri.isBlank() && destination != null) {
            // The provider created the final name before its URI was durably
            // journaled. We cannot safely tell whether this is our partial
            // file or a concurrent user file, so keep the transaction blocked.
            return false
        }

        if (phase in setOf("creating", "copying") && destinationUri.isNotBlank() && destination != null) {
            if (destination.uri.toString() != destinationUri || !destination.delete()) return false
            destination = null
        }

        if (backup != null) {
            if (destination == null) {
                if (!backup.renameTo(destinationName)) return false
            } else if (temporary == null) {
                if (!backup.delete()) return false
            } else {
                return false
            }
        }

        if (temporary != null) {
            if (!temporary.delete()) return false
        }

        // A final rename that completed before process death leaves the destination intact.
        return true
    }

    private fun updateWriteJournal(id: String, transaction: JSONObject) {
        synchronized(journalLock) {
            val journal = readWriteJournal()
            journal.put(id, JSONObject(transaction.toString()))
            if (!journalPreferences.edit().putString(WRITE_JOURNAL_KEY, journal.toString()).commit()) {
                throw IOException("Could not persist Android TV storage write journal")
            }
        }
    }

    private fun updateWritePhase(id: String, phase: String, destinationUri: String? = null) {
        synchronized(journalLock) {
            val journal = readWriteJournal()
            val transaction = journal.optJSONObject(id)
                ?: throw IOException("Android TV storage write journal entry is missing")
            transaction.put("phase", phase)
            if (destinationUri != null) transaction.put("destinationUri", destinationUri)
            if (!journalPreferences.edit().putString(WRITE_JOURNAL_KEY, journal.toString()).commit()) {
                throw IOException("Could not update Android TV storage write journal")
            }
        }
    }

    private fun removeWriteJournal(id: String): Boolean = runCatching {
        synchronized(journalLock) {
            val journal = readWriteJournal()
            journal.remove(id)
            journalPreferences.edit().putString(WRITE_JOURNAL_KEY, journal.toString()).commit()
        }
    }.getOrDefault(false)

    private fun clearJournalIfArtifactsAreGone(
        id: String,
        parent: DocumentFile,
        temporaryName: String,
        backupName: String,
    ) {
        val artifactsGone = runCatching {
            val transaction = readWriteJournal().optJSONObject(id)
            val phase = transaction?.optString("phase").orEmpty()
            val destinationName = transaction?.optString("path")
                ?.takeIf(String::isNotBlank)
                ?.let { validatedSegments(it).last() }
            val destinationRemainsUnresolved = phase in setOf("creating", "copying") &&
                destinationName?.let(parent::findFile) != null
            parent.findFile(temporaryName) == null &&
                parent.findFile(backupName) == null &&
                !destinationRemainsUnresolved
        }.getOrDefault(false)
        if (!artifactsGone || !removeWriteJournal(id)) recoveredRoots.clear()
    }

    private fun readWriteJournal(): JSONObject = try {
        JSONObject(journalPreferences.getString(WRITE_JOURNAL_KEY, "{}") ?: "{}")
    } catch (error: Exception) {
        throw IOException("Android TV storage write journal is unreadable", error)
    }

    private fun finishWriteSafely(handle: String, session: WriteSession) {
        val existing = session.parent.findFile(session.destinationName)
        if (existing != null) {
            if (existing.isDirectory) throw IOException("A directory already exists at the destination")
            updateWritePhase(handle, "replacing")
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
            runCatching { updateWritePhase(handle, "complete") }
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
        updateWritePhase(handle, "creating")
        val created = session.parent.createFile(session.temporary.type ?: "application/octet-stream", session.destinationName)
            ?: run {
                session.temporary.delete()
                throw IOException("Storage provider cannot create the destination document")
            }
        try {
            updateWritePhase(handle, "copying", created.uri.toString())
            val input = contentResolver.openInputStream(session.temporary.uri)
                ?: throw FileNotFoundException("Could not read the temporary document")
            val output = contentResolver.openOutputStream(created.uri, "wt")
                ?: throw IOException("Could not open the destination document for writing")
            input.use { source -> output.use { destination -> source.copyTo(destination) } }
            updateWritePhase(handle, "complete", created.uri.toString())
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
        val temporaryName: String,
        val backupName: String,
        val output: OutputStream,
    )

    companion object {
        private const val PATH_PREFIX = "/androidtv/"
        private const val MAX_READ_BYTES = 256L * 1024
        private const val WRITE_JOURNAL_KEY = "transactions"

        fun storageId(uri: Uri): String = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        fun virtualRoot(uri: Uri): String = "$PATH_PREFIX${storageId(uri)}"
    }
}
