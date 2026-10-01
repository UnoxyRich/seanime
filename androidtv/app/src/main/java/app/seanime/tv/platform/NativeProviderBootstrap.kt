package app.seanime.tv.platform

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Seeds external provider files before Go starts, without evaluating any provider code.
 * Call only from NativeHostQueue, which serializes this with server/lifecycle work.
 * The ledger lives outside extensions and survives provider removal and app upgrades.
 */
internal class NativeProviderBootstrap(
    private val readAsset: (String) -> ByteArray,
    private val providers: List<BundledProviderSpec> = BundledEnglishProviders.entries,
    private val files: ProviderBootstrapFiles = ProviderBootstrapFiles(),
    private val environment: (String) -> String? = System::getenv,
) {
    data class Report(val installed: List<String>, val preserved: List<String>, val warnings: List<String>)

    fun beforeServerStart(dataDir: File, startServer: () -> Unit): Report {
        val report = seed(dataDir)
        startServer()
        return report
    }

    fun seed(dataDir: File): Report {
        val installed = mutableListOf<String>()
        val preserved = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        try {
            val extensionDir = try {
                ProviderExtensionDirectory.resolve(dataDir, environment)
            } catch (error: Exception) {
                throw IOException("Cannot safely read extensions.dir: ${error.message}. " +
                    "Install providers through Extensions or use the default private directory with ordinary TOML configuration", error)
            }
            val privateDefault = File(dataDir.canonicalFile, "extensions")
            check(extensionDir == privateDefault) {
                "Automatic provider installation is skipped for a custom or redirected extensions.dir. " +
                    "Install providers through Extensions; the configured directory and its files were left unchanged"
            }
            val ledgerFile = File(dataDir, LEDGER_NAME)
            val ledger = readLedger(ledgerFile)
            val entries = ledger.getJSONObject("providers")
            // Validate every bundled identity and byte hash before creating any provider.
            val payloads = providers.associateWith(::prepareProvider)
            val existingIds = scanExistingIds(extensionDir)
            providers.forEach { provider ->
                try {
                    val previous = entries.optJSONObject(provider.id)
                    val target = File(extensionDir, "${provider.id}.json")
                    if (previous?.getString("state") == "complete") {
                        preserved += provider.id
                        return@forEach
                    }
                    if (provider.id in existingIds || target.exists()) {
                        // Disabled, invalid, edited, older and newer copies all belong to the user.
                        complete(entries, provider.id)
                        files.writeLedger(ledgerFile, ledger.toString().toByteArray(Charsets.UTF_8))
                        preserved += provider.id
                        return@forEach
                    }
                    val staged = if (previous != null) {
                        // A directory change is a user choice; never repopulate the new location.
                        if (previous.getString("directory") != extensionDir.canonicalPath) {
                            complete(entries, provider.id)
                            files.writeLedger(ledgerFile, ledger.toString().toByteArray(Charsets.UTF_8))
                            preserved += provider.id
                            return@forEach
                        }
                        val stage = File(extensionDir, previous.getString("stage"))
                        if (!stage.exists()) {
                            // rename removes the stage atomically. It may have completed before
                            // a crash/ledger failure, followed by a user removal. Never reinstall.
                            complete(entries, provider.id)
                            files.writeLedger(ledgerFile, ledger.toString().toByteArray(Charsets.UTF_8))
                            preserved += provider.id
                            return@forEach
                        }
                        check(stage.isFile && sha256(stage.readBytes()) == previous.getString("sha256")) {
                            "Interrupted provider stage changed for ${provider.id}; leaving it untouched"
                        }
                        // A still-present stage proves publication never happened. Retry those
                        // exact bytes, even if the APK was updated since the interrupted attempt.
                        stage
                    } else {
                        val bytes = payloads.getValue(provider)
                        val stage = files.stage(extensionDir, provider.id, bytes)
                        entries.put(provider.id, JSONObject().put("state", "pending")
                            .put("directory", extensionDir.canonicalPath).put("stage", stage.name)
                            .put("sha256", sha256(bytes)))
                        // Write-ahead state is durable before publication. The non-JSON stage
                        // cannot be read by Go's recursive extension loader.
                        files.writeLedger(ledgerFile, ledger.toString().toByteArray(Charsets.UTF_8))
                        stage
                    }
                    files.publish(staged, target)
                    existingIds += provider.id
                    installed += provider.id
                    complete(entries, provider.id)
                    files.writeLedger(ledgerFile, ledger.toString().toByteArray(Charsets.UTF_8))
                } catch (error: Exception) {
                    warnings += "${provider.id}: ${error.message ?: error.javaClass.simpleName}"
                    // Stop after a filesystem/ledger failure. Keeping other entries untouched
                    // avoids accidentally committing an in-memory state whose write failed.
                    return Report(installed, preserved, warnings)
                }
            }
        } catch (error: Exception) {
            warnings += "Bundled providers were not seeded: ${error.message ?: error.javaClass.simpleName}"
        }
        return Report(installed, preserved, warnings)
    }

    private fun prepareProvider(spec: BundledProviderSpec): ByteArray {
        check(spec.id.matches(Regex("[a-z][a-z0-9-]*"))) { "Invalid bundled provider ID" }
        val manifestBytes = readAsset("providers/${spec.id}/manifest.json")
        val payloadBytes = readAsset("providers/${spec.id}/provider.js")
        check(sha256(manifestBytes) == spec.manifestSha256) { "Bundled manifest checksum mismatch: ${spec.id}" }
        check(sha256(payloadBytes) == spec.payloadSha256) { "Bundled payload checksum mismatch: ${spec.id}" }
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        check(manifest.getString("id") == spec.id && manifest.getString("version") == spec.version &&
            manifest.getString("type") == spec.type && manifest.getString("lang") == "en" &&
            manifest.getString("language") == "javascript" && !manifest.optBoolean("isDevelopment", false)) {
            "Bundled provider identity mismatch: ${spec.id}"
        }
        check(payloadBytes.isNotEmpty()) { "Empty bundled provider: ${spec.id}" }
        manifest.put("manifestURI", spec.manifestURI)
            .put("payloadURI", "").put("payload", payloadBytes.toString(Charsets.UTF_8))
            .put("isDevelopment", false)
        return manifest.toString().toByteArray(Charsets.UTF_8)
    }

    private fun readLedger(file: File): JSONObject {
        if (!file.exists()) return JSONObject().put("schema", 1).put("providers", JSONObject())
        val ledger = JSONObject(file.readText())
        check(ledger.getInt("schema") == 1) { "Unsupported provider bootstrap ledger; leaving providers untouched" }
        val entries = ledger.getJSONObject("providers")
        entries.keys().forEach { id ->
            val entry = entries.getJSONObject(id)
            val state = entry.getString("state")
            check(state == "complete" || state == "pending") { "Invalid provider bootstrap ledger state" }
            if (state == "pending") {
                val stage = entry.getString("stage")
                check(id.matches(Regex("[a-z][a-z0-9-]*")) &&
                    stage.matches(Regex("\\.seanime-${Regex.escape(id)}-[A-Za-z0-9-]+\\.pending")) &&
                    File(entry.getString("directory")).isAbsolute &&
                    entry.getString("sha256").matches(Regex("[a-f0-9]{64}"))) { "Invalid provider bootstrap pending record" }
            }
        }
        return ledger
    }

    private fun complete(entries: JSONObject, id: String) { entries.put(id, JSONObject().put("state", "complete")) }

    private fun scanExistingIds(directory: File): MutableSet<String> {
        val ids = mutableSetOf<String>()
        if (!directory.exists()) return ids
        val visited = mutableSetOf<String>()
        fun scan(folder: File) {
            check(folder.isDirectory) { "Configured extensions path is not a directory" }
            if (!visited.add(folder.canonicalPath)) return
            val children = folder.listFiles() ?: throw IOException("Cannot inspect configured extensions directory")
            children.forEach { child ->
                if (child.isDirectory) {
                    // Match Go WalkDir: do not descend into symbolic-link directories.
                    if (child.canonicalFile == File(folder.canonicalFile, child.name)) scan(child)
                } else if (child.extension == "json") {
                    // If malformed JSON hides an ID, abstain rather than risk shadowing it.
                    val value = JSONObject(child.readText())
                    (value.opt("id") as? String)?.takeIf { it.isNotBlank() }?.let(ids::add)
                }
            }
        }
        scan(directory)
        return ids
    }

    companion object {
        const val LEDGER_NAME = ".android-bundled-provider-ledger"
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Same-directory, fsynced temporary files; Android supplies directory fsync too.
 * Publication is restricted to the app-private default directory by the caller.
 * API 23 rename can replace a destination, so shared/custom directories are not
 * safe targets. NativeHostQueue excludes the app's own concurrent server work.
 */
internal open class ProviderBootstrapFiles(private val syncDirectory: (File) -> Unit = {}) {
    open fun stage(directory: File, id: String, bytes: ByteArray): File {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create configured extensions directory" }
        val stage = File.createTempFile(".seanime-$id-", ".pending", directory)
        writeAndSync(stage, bytes)
        syncDirectory(directory)
        return stage
    }

    open fun writeLedger(target: File, bytes: ByteArray) {
        val directory = requireNotNull(target.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create provider ledger directory" }
        val temporary = File.createTempFile(".provider-ledger-", ".pending", directory)
        try {
            writeAndSync(temporary, bytes)
            check(temporary.renameTo(target)) { "Cannot commit provider bootstrap ledger" }
            syncDirectory(directory)
        } finally { temporary.delete() }
    }

    open fun publish(stage: File, target: File) {
        check(!target.exists()) { "Provider destination already exists; leaving it untouched" }
        check(stage.renameTo(target)) { "Cannot publish bundled provider; staged data will be retried" }
        syncDirectory(requireNotNull(target.parentFile))
    }

    private fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
    }
}
