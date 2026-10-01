package app.seanime.tv.platform

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import app.seanime.tv.AndroidSafStorageAdapter
import app.seanime.tv.AuthWebViewActivity
import app.seanime.tv.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors

/** SAF, OAuth, reports and Android package-installer entry points for native settings. */
class NativePlatformActions(
    private val activity: Activity,
    private val onStorageChanged: (String, JSONObject?) -> Unit = { _, _ -> },
    private val onError: (String) -> Unit = {},
    private val onPrompt: (NativePrompt) -> Unit = {},
    private val onMessage: (String) -> Unit = {},
) : ContextWrapper(activity) {
    private val storage = getSharedPreferences("android-tv-storage", MODE_PRIVATE)
    private val updatePreferences = getSharedPreferences("android-tv-updates", MODE_PRIVATE)
    private val auth = getSharedPreferences("native-oauth", MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val filesExecutor = Executors.newSingleThreadExecutor()
    private var closed = false
    private var receiverRegistered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                finishUpdateDownload(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L))
            }
        }
    }

    init {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        // DownloadProvider may run under a privileged non-system UID. This receiver
        // trusts neither the sender nor extras: finishUpdateDownload accepts only our
        // stored ID and re-queries DownloadManager before verifying the private APK.
        androidx.core.content.ContextCompat.registerReceiver(activity, receiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    fun openStoragePicker(purpose: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (!hasDocumentPicker(intent)) {
            onPrompt(NativePrompt(getString(R.string.storage_picker_unavailable_title), getString(R.string.storage_picker_unavailable_message)))
            return
        }
        storage.edit().putString("pending-purpose", purpose).apply()
        runCatching { activity.startActivityForResult(intent, STORAGE_PICK_REQUEST) }.onFailure { fail("Android could not open the folder picker") }
    }

    private fun hasDocumentPicker(intent: Intent): Boolean {
        val name = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.name ?: return false
        return !name.endsWith("DocumentsStub", true)
    }

    fun storageRoots(): JSONArray {
        val roots = runCatching { JSONArray(storage.getString("roots", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until roots.length()) {
            val item = roots.optJSONObject(i) ?: continue
            val uri = Uri.parse(item.optString("uri"))
            val granted = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            item.put("granted", granted).put("available", granted && runCatching { DocumentFile.fromTreeUri(this, uri)?.canRead() == true }.getOrDefault(false))
        }
        return roots
    }

    fun removeStorageTree(uriString: String) {
        val uri = Uri.parse(uriString)
        contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }?.let { permission ->
            val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            runCatching { contentResolver.releasePersistableUriPermission(uri, flags) }
        }
        val roots = storageRoots()
        val updated = JSONArray()
        for (i in 0 until roots.length()) roots.optJSONObject(i)?.takeIf { it.optString("uri") != uriString }?.let(updated::put)
        val edit = storage.edit().putString("roots", updated.toString())
        if (storage.getString(SCREENSHOT_TREE_URI, null) == uriString) edit.remove(SCREENSHOT_TREE_URI)
        edit.apply()
        onStorageChanged("library-main", null)
    }

    fun onStorageTreeSelected(uri: Uri?, grantedFlags: Int, purpose: String) {
        if (uri == null) { onStorageChanged(purpose, null); return }
        val flags = grantedFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (flags != 0) runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        val directory = DocumentFile.fromTreeUri(this, uri)
        val grant = contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        if (grant?.isReadPermission != true || directory?.canRead() != true) {
            fail("Folder access was not granted. Select the folder again.")
            onStorageChanged(purpose, null)
            return
        }
        if (purpose == "screenshot" && (grant.isWritePermission != true || directory.canWrite() != true)) {
            fail("The screenshot folder must be writable")
            return
        }
        val root = JSONObject().put("uri", uri.toString()).put("id", AndroidSafStorageAdapter.storageId(uri))
            .put("path", AndroidSafStorageAdapter.virtualRoot(uri)).put("name", directory.name ?: "Removable storage")
            .put("granted", true).put("available", true).put("purpose", purpose)
        val roots = storageRoots()
        val updated = JSONArray()
        for (i in 0 until roots.length()) roots.optJSONObject(i)?.takeIf { it.optString("uri") != uri.toString() }?.let(updated::put)
        updated.put(root)
        val edit = storage.edit().putString("roots", updated.toString())
        if (purpose == "screenshot") edit.putString(SCREENSHOT_TREE_URI, uri.toString())
        edit.apply()
        onStorageChanged(purpose, root)
    }

    /** Uses the system create-document UI; bytes stay in private cache until the user chooses a file. */
    fun saveReport(filename: String, mimeType: String, bytes: ByteArray): Boolean {
        if (bytes.size > 32 * 1024 * 1024) { fail("This file is too large to export"); return false }
        if (storage.contains("pending-report")) { fail("Another file save is already open"); return false }
        val safeName = filename.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\r\\n]"), "_").take(160).ifBlank { "seanime-report.json" }
        val type = mimeType.takeIf { it.matches(Regex("^[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+$")) } ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type)
            .putExtra(Intent.EXTRA_TITLE, safeName).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (!hasDocumentPicker(intent)) { fail(getString(R.string.storage_save_unavailable_message)); return false }
        val pending = File(cacheDir, "native-pending-report")
        storage.edit().putString("pending-report", pending.path).apply()
        filesExecutor.execute {
            runCatching { pending.writeBytes(bytes) }.onSuccess {
                handler.post {
                    if (closed) { storage.edit().remove("pending-report").apply(); pending.delete() }
                    else runCatching { activity.startActivityForResult(intent, REPORT_SAVE_REQUEST) }
                        .onFailure { storage.edit().remove("pending-report").apply(); pending.delete(); fail("Android could not open the file save dialog. Try again.") }
                }
            }.onFailure { storage.edit().remove("pending-report").apply(); handler.post { fail("Could not prepare the report") } }
        }
        return true
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {
            STORAGE_PICK_REQUEST -> {
                val purpose = storage.getString("pending-purpose", "library-main") ?: "library-main"
                storage.edit().remove("pending-purpose").apply()
                onStorageTreeSelected(if (resultCode == Activity.RESULT_OK) data?.data else null, data?.flags ?: 0, purpose)
            }
            REPORT_SAVE_REQUEST -> {
                val pending = File(cacheDir, "native-pending-report")
                storage.edit().remove("pending-report").apply()
                if (resultCode != Activity.RESULT_OK || data?.data == null) { pending.delete(); return true }
                val destination = data.data!!
                filesExecutor.execute {
                    runCatching {
                        check(pending.isFile) { "Report export was interrupted; export it again" }
                        contentResolver.openOutputStream(destination, "wt")?.use { output -> pending.inputStream().use { it.copyTo(output) } }
                            ?: error("Android could not open the selected file")
                    }.onSuccess { handler.post { toast("Report saved") } }
                        .onFailure { error -> handler.post { fail(error.message ?: "Could not save report") } }
                    pending.delete()
                }
            }
            else -> return false
        }
        return true
    }

    fun openExternalUrl(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val scheme = uri.scheme?.lowercase()
        if (scheme.isNullOrBlank() || scheme in setOf("file", "content", "javascript", "data", "intent", "about", "android-app")) {
            fail("This external link is not supported"); return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
            .onFailure { fail("No installed app can open this link") }
    }

    /** Only provider login pages are permitted in the isolated OAuth WebView. */
    fun openOAuth(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host !in OAUTH_HOSTS || uri.userInfo != null) { fail("Unsupported login provider"); return }
        val state = randomToken()
        auth.edit().putString("state", state).putLong("started-at", System.currentTimeMillis()).apply()
        val target = uri.buildUpon().clearQuery().apply {
            for (key in uri.queryParameterNames.filter { it != "state" }) {
                for (value in uri.getQueryParameters(key)) appendQueryParameter(key, value)
            }
            appendQueryParameter("state", state)
        }.build()
        activity.startActivity(AuthWebViewActivity.intent(activity, target))
    }

    fun beginMALLogin() {
        val verifier = randomToken() + randomToken()
        auth.edit().putString("mal-verifier", verifier).apply()
        openOAuth("https://myanimelist.net/v1/oauth2/authorize?response_type=code&client_id=51cb4294feb400f3ddc66a30f9b9a00f&code_challenge=$verifier&code_challenge_method=plain")
    }

    fun consumeOAuthReturn(intent: Intent): String? {
        val value = intent.getStringExtra(OAUTH_RETURN_EXTRA) ?: return null
        intent.removeExtra(OAUTH_RETURN_EXTRA)
        val local = NativePlaybackBus.localUrl(value) ?: return null
        val uri = Uri.parse(local)
        val returnedState = uri.getQueryParameter("state") ?: uri.fragment?.let { Uri.parse("https://callback.invalid/?$it").getQueryParameter("state") }
        val state = auth.getString("state", null)
        if (state.isNullOrBlank() || state != returnedState || System.currentTimeMillis() - auth.getLong("started-at", 0) > 20 * 60_000) {
            fail("Login expired or could not be verified. Start login again.")
            return null
        }
        auth.edit().remove("state").remove("started-at").apply()
        return local
    }

    fun consumeMalVerifier(uri: Uri): String {
        require(!uri.getQueryParameter("code").isNullOrBlank()) { "The provider did not return a login code" }
        val verifier = auth.getString("mal-verifier", null)
        auth.edit().remove("mal-verifier").apply()
        return verifier ?: error("The MAL login session expired. Start login again.")
    }

    fun checkForUpdate() {
        toast("Checking for Android TV updates…")
        filesExecutor.execute {
            runCatching {
                val connection = java.net.URL("https://api.github.com/repos/UnoxyRich/seanime/releases/latest").openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "Seanime-TV")
                val release = try {
                    if (connection.responseCode == 404) error("No published Android TV release is available yet")
                    check(connection.responseCode in 200..299) { "Release service returned ${connection.responseCode}" }
                    val text = connection.inputStream.bufferedReader().use { it.readText() }
                    JSONObject(text)
                } finally { connection.disconnect() }
                val assets = release.optJSONArray("assets") ?: JSONArray()
                val abi = supportedAbi()
                val asset = (0 until assets.length()).mapNotNull(assets::optJSONObject).firstOrNull {
                    val name = it.optString("name")
                    name.endsWith(".apk", true) && name.contains(abi, true) && !name.contains("unsigned", true)
                } ?: error("The latest release has no signed Android TV APK for $abi")
                val tag = release.optString("tag_name").removePrefix("v")
                @Suppress("DEPRECATION")
                val installed = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
                val candidate = versionCode(tag)
                val existing = versionCode(installed)
                handler.post {
                    if (closed) return@post
                    if (candidate != null && existing != null && candidate <= existing) {
                        toast("Seanime TV is up to date ($installed)")
                    } else {
                        val size = asset.optLong("size") / 1_048_576.0
                        onPrompt(NativePrompt("Seanime TV $tag",
                            "Download ${asset.optString("name")} (${"%.1f".format(size)} MB)? Android will ask you to confirm installation.",
                            actions = listOf(NativePromptAction("Download update") { downloadAndInstallUpdate(asset.optString("browser_download_url"), asset.optString("name")) }),
                            dismissLabel = "Cancel"))
                    }
                }
            }.onFailure { error -> handler.post { fail(error.message ?: "Could not check for updates") } }
        }
    }

    private fun versionCode(version: String): Long? {
        val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)").find(version.removePrefix("v")) ?: return null
        val (major, minor, patch) = match.destructured
        return major.toLongOrNull()?.times(1_000_000)?.plus((minor.toLongOrNull() ?: return null) * 1000)?.plus(patch.toLongOrNull() ?: return null)
    }

    fun installUpdate(filePath: String) {
        val apk = runCatching { File(filePath).canonicalFile }.getOrNull() ?: return
        val roots = listOfNotNull(filesDir.resolve("seanime/updates"), cacheDir.resolve("seanime"), getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
            .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
        if (!apk.isFile || apk.extension.lowercase() != "apk" || roots.none { apk.path.startsWith("$it/") }) {
            fail("Choose an APK from Seanime's update cache"); return
        }
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val candidate = packageManager.getPackageArchiveInfo(apk.path, flags)
        @Suppress("DEPRECATION")
        val installed = packageManager.getPackageInfo(packageName, flags)
        if (candidate?.packageName != packageName) { fail("The downloaded APK is not a valid Seanime TV update"); return }
        @Suppress("DEPRECATION")
        val candidateSignatures = if (Build.VERSION.SDK_INT >= 28) candidate.signingInfo?.apkContentsSigners else candidate.signatures
        @Suppress("DEPRECATION")
        val installedSignatures = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.signingCertificateHistory else installed.signatures
        if (candidateSignatures.isNullOrEmpty() || installedSignatures.isNullOrEmpty() || candidateSignatures.none { signer -> installedSignatures.any { it == signer } }) {
            fail("This APK is unsigned or uses a different signing key. It cannot safely replace this installation.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            updatePreferences.edit().putString(PENDING_UPDATE_INSTALL_PATH, apk.path).apply()
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                .onFailure { fail("Android did not open install permissions") }
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        updatePreferences.edit().remove(PENDING_UPDATE_INSTALL_PATH).apply()
        runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { fail("Unable to start the Android package installer") }
    }

    fun downloadAndInstallUpdate(url: String, filename: String) {
        val uri = Uri.parse(url)
        val abi = supportedAbi()
        if (uri.scheme != "https" || uri.host != "github.com" || uri.userInfo != null ||
            !uri.encodedPath.orEmpty().lowercase().startsWith("/unoxyrich/seanime/releases/download/") ||
            !filename.matches(Regex("^[A-Za-z0-9._-]+\\.apk$", RegexOption.IGNORE_CASE)) ||
            Uri.decode(uri.lastPathSegment.orEmpty()) != filename || abi.isBlank() || !filename.contains(abi, true)) {
            fail("This release does not contain a compatible Seanime TV APK"); return
        }
        val destination = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: run { fail("Android update storage is unavailable"); return }
        val manager = getSystemService(DownloadManager::class.java)
        val old = updatePreferences.getLong(PENDING_UPDATE_DOWNLOAD_ID, -1L)
        if (old >= 0) manager.remove(old)
        val output = File(destination, "seanime-tv-update-$filename")
        if (output.exists()) output.delete()
        val request = DownloadManager.Request(uri).setTitle("Seanime TV update").setDescription("Downloading $filename")
            .setMimeType(APK_MIME_TYPE).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, output.name)
        runCatching { manager.enqueue(request) }.onSuccess { id ->
            updatePreferences.edit().putLong(PENDING_UPDATE_DOWNLOAD_ID, id).putString(PENDING_UPDATE_DOWNLOAD_PATH, output.path).apply()
            handler.postDelayed({ finishUpdateDownload(id) }, 1000)
            toast("Downloading Seanime TV update")
        }.onFailure { fail(it.message ?: "Could not download update") }
    }

    private fun finishUpdateDownload(id: Long) {
        if (closed || id < 0 || updatePreferences.getLong(PENDING_UPDATE_DOWNLOAD_ID, -1L) != id) return
        getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) { clearPendingUpdateDownload(); fail("The update download was not found"); return }
            when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val path = updatePreferences.getString(PENDING_UPDATE_DOWNLOAD_PATH, null)
                    clearPendingUpdateDownload()
                    if (path != null) installUpdate(path) else fail("The downloaded update could not be opened")
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    clearPendingUpdateDownload(); fail("Update download failed ($reason)")
                }
            }
        }
    }

    private fun clearPendingUpdateDownload() { updatePreferences.edit().remove(PENDING_UPDATE_DOWNLOAD_ID).remove(PENDING_UPDATE_DOWNLOAD_PATH).apply() }
    fun onResume() {
        finishUpdateDownload(updatePreferences.getLong(PENDING_UPDATE_DOWNLOAD_ID, -1L))
        updatePreferences.getString(PENDING_UPDATE_INSTALL_PATH, null)?.let {
            if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) installUpdate(it)
        }
    }
    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) { unregisterReceiver(receiver); receiverRegistered = false }
        filesExecutor.shutdown()
    }
    private fun onUi(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { if (!closed) action() }
        else handler.post { if (!closed) action() }
    }
    private fun fail(message: String) { onUi { onError(message) } }
    private fun toast(message: String) { onUi { onMessage(message) } }

    companion object {
        const val SCREENSHOT_TREE_URI = "screenshot-tree-uri"
        const val STORAGE_PICK_REQUEST = 521
        const val REPORT_SAVE_REQUEST = 522
        const val OAUTH_RETURN_EXTRA = "native-oauth-return"
        val OAUTH_HOSTS = AuthBrowserPolicy.providerHosts
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val PENDING_UPDATE_DOWNLOAD_ID = "pending-download-id"
        private const val PENDING_UPDATE_DOWNLOAD_PATH = "pending-download-path"
        private const val PENDING_UPDATE_INSTALL_PATH = "pending-install-path"
        fun supportedAbi(): String = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }.orEmpty()
        private fun randomToken(): String = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
