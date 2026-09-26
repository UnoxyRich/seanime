package app.seanime.tv

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.FileProvider
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.seanime.tv.gomobile.mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

private data class AndroidTVDownloadDocument(val uri: Uri, val output: OutputStream)

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var loadingProgress: ProgressBar
    private val handler = Handler(Looper.getMainLooper())
    private val serverExecutor = Executors.newSingleThreadExecutor()
    private val serverPort = 43211
    private val androidTvBridgeToken = UUID.randomUUID().toString()
    private var started = false
    private var secureBridgeAvailable = false
    private var serverReadyHandled = false
    private var displayedError: String? = null
    private var activityResumed = false
    private var webPlaybackActive = false
    internal var pendingStoragePurpose = "library-main"
    private var retryButton: Button? = null
    private var updateReceiverRegistered = false
    private var pendingDownloadRequestId: String? = null
    private val downloadDocuments = ConcurrentHashMap<String, AndroidTVDownloadDocument>()
    private val updatePreferences by lazy { getSharedPreferences(UPDATE_PREFERENCES, MODE_PRIVATE) }

    private val updateDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (completedId == updatePreferences.getLong(PENDING_UPDATE_DOWNLOAD_ID, -2L)) {
                finishUpdateDownload(completedId)
            }
        }
    }

    private val readinessPoll = object : Runnable {
        override fun run() {
            when (Mobile.serverStatus()) {
                "ready" -> {
                    handler.removeCallbacks(this)
                    loadSeanime()
                }
                "failed" -> {
                    handler.removeCallbacks(this)
                    showServerError(Mobile.serverError())
                }
                "stopping", "stopped" -> {
                    handler.removeCallbacks(this)
                    showServerError("Seanime server stopped. Select Retry to start it again.")
                }
                else -> {
                    statusText.text = getString(R.string.server_starting)
                    handler.postDelayed(this, 250)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )

        root = FrameLayout(this).apply { setBackgroundColor(0xFF08070D.toInt()) }
        setActiveActivity(this)
        webView = WebView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(0xFF08070D.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                val bridgeToken = JSONObject.quote(androidTvBridgeToken)
                WebViewCompat.addDocumentStartJavaScript(
                    this,
                    "if (window === window.top) Object.defineProperty(window, '__seanimeAndroidTVBridgeToken', {value: $bridgeToken, writable: false, configurable: false});",
                    setOf("http://127.0.0.1:$serverPort"),
                )
                addJavascriptInterface(AndroidTVBridge(this@MainActivity, this, androidTvBridgeToken), "AndroidTVNativeBridge")
                secureBridgeAvailable = true
            } else {
                Log.e("SeanimeWeb", "Secure WebView document-start scripts are unavailable; native bridge disabled")
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d("SeanimeWeb", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.host == "127.0.0.1" && uri.port == serverPort) return false
                    if (!request.isForMainFrame) return false
                    openExternalUrl(uri.toString())
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    view.requestFocus(View.FOCUS_DOWN)
                    statusText.visibility = View.GONE
                    this@MainActivity.loadingProgress.visibility = View.GONE
                    if (!secureBridgeAvailable) {
                        Toast.makeText(
                            this@MainActivity,
                            "Update Android System WebView to enable Seanime TV storage, downloads, and native playback",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError,
                ) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        serverReadyHandled = false
                        showServerError("Seanime's local interface could not load. Select Retry to reconnect.")
                    }
                }
            }
        }
        setActiveWebView(webView)
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        statusText = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 22f
            gravity = Gravity.CENTER
            text = getString(R.string.server_starting)
        }
        loadingProgress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(loadingProgress, FrameLayout.LayoutParams(72, 72, Gravity.CENTER))
        root.addView(statusText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)

        registerUpdateDownloadReceiver()
        serverForegroundStart()
    }

    private fun serverForegroundStart() {
        if (started) return
        started = true
        serverReadyHandled = false
        val dataDir = filesDir.resolve("seanime/data").absolutePath
        val cacheDir = cacheDir.resolve("seanime").absolutePath
        serverExecutor.execute {
            runCatching {
                Mobile.startServer(dataDir, cacheDir, serverPort.toLong())
            }.onFailure { error ->
                runOnUiThread { showServerError(error.message ?: "Unable to start Seanime.") }
            }
        }
        handler.removeCallbacks(readinessPoll)
        handler.post(readinessPoll)
    }

    private fun loadSeanime() {
        if (isFinishing || isDestroyed) return
        if (serverReadyHandled) return
        serverReadyHandled = true
        displayedError = null
        retryButton?.let(root::removeView)
        retryButton = null
        statusText.text = getString(R.string.server_starting)
        statusText.visibility = View.VISIBLE
        loadingProgress.visibility = View.VISIBLE
        val url = "http://127.0.0.1:$serverPort/"
        if (webView.url == url) webView.reload() else webView.loadUrl(url)
    }

    private fun showServerError(message: String) {
        if (isFinishing || isDestroyed) return
        if (displayedError == message && retryButton != null) return
        displayedError = message
        loadingProgress.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = "Seanime TV could not start\n\n$message"
        retryButton?.let(root::removeView)
        val retry = Button(this).apply {
            text = getString(R.string.server_retry)
            isFocusable = true
            setOnClickListener {
                started = false
                serverReadyHandled = false
                displayedError = null
                retryButton?.let(root::removeView)
                retryButton = null
                serverExecutor.execute { Mobile.stopServer() }
                handler.postDelayed({ serverForegroundStart() }, 350)
            }
        }
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        params.topMargin = 150
        root.addView(retry, params)
        retryButton = retry
        retry.requestFocus()
    }

    internal fun openStoragePicker(purpose: String) {
        pendingStoragePurpose = purpose
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        if (!hasUsableDocumentPicker(intent)) {
            showStoragePickerUnavailable(purpose)
            return
        }
        runCatching { startActivityForResult(intent, STORAGE_PICK_REQUEST) }
            .onFailure { showStoragePickerUnavailable(purpose) }
    }

    private fun hasUsableDocumentPicker(intent: Intent): Boolean {
        val activityInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return false
        return !activityInfo.name.endsWith("DocumentsStub", ignoreCase = true)
    }

    private fun showStoragePickerUnavailable(purpose: String) {
        pendingStoragePurpose = "library-main"
        dispatchStorageEvent(null, purpose)
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_picker_unavailable_title)
            .setMessage(R.string.storage_picker_unavailable_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    internal fun openExternalUrl(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                startActivity(AuthWebViewActivity.intent(this, uri))
                return
            }
            "intent" -> {
                openIntentUrl(url)
                return
            }
            "mailto" -> {
                startExternalView(Intent(Intent.ACTION_VIEW, uri))
                return
            }
            null, "file", "content", "javascript", "data", "about", "android-app" -> {
                Toast.makeText(this, "This link cannot be opened by another app", Toast.LENGTH_LONG).show()
                return
            }
        }

        startExternalView(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun openIntentUrl(url: String) {
        val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()
        val scheme = intent?.data?.scheme?.lowercase()
        if (intent == null || intent.action != Intent.ACTION_VIEW || intent.component != null || intent.selector != null ||
            scheme.isNullOrBlank() || scheme in setOf("intent", "file", "content", "javascript", "data", "about", "android-app") ||
            intent.`package` == packageName
        ) {
            Toast.makeText(this, "This external player link is invalid", Toast.LENGTH_LONG).show()
            return
        }

        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            val fallbackUri = fallbackUrl?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (fallbackUri != null && fallbackUri.scheme in setOf("http", "https")) {
                startActivity(AuthWebViewActivity.intent(this, fallbackUri))
            } else {
                Toast.makeText(this, "Install the app configured for this external player link", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startExternalView(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_LONG).show()
        }
    }

    internal fun installUpdate(filePath: String) {
        val apk = runCatching { File(filePath).canonicalFile }.getOrNull() ?: return
        val allowedRoots = listOfNotNull(
            filesDir.resolve("seanime/updates"),
            cacheDir.resolve("seanime"),
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
        )
            .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
        if (!apk.isFile || apk.extension.lowercase() != "apk" || allowedRoots.none { apk.path.startsWith("$it/") }) {
            Toast.makeText(this, "Choose an APK from Seanime's update cache", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            updatePreferences.edit().putString(PENDING_UPDATE_INSTALL_PATH, apk.path).apply()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }

        val apkUri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        }.getOrNull() ?: return
        updatePreferences.edit().remove(PENDING_UPDATE_INSTALL_PATH).apply()
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "Unable to start the Android package installer", Toast.LENGTH_LONG).show()
        }
    }

    internal fun requestDownloadTarget(requestId: String, filename: String, mimeType: String): Boolean {
        if (!requestId.matches(Regex("^[A-Za-z0-9_-]{1,80}$"))) return false
        val safeFilename = filename
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\r\\n]"), "_")
            .take(160)
            .ifBlank { "seanime-download" }
        val safeMimeType = mimeType.takeIf { it.matches(Regex("^[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+$")) }
            ?: "application/octet-stream"

        runOnUiThread {
            if (pendingDownloadRequestId != null) {
                dispatchDownloadTargetEvent(requestId, false, "Another file save is already open")
                return@runOnUiThread
            }
            pendingDownloadRequestId = requestId
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = safeMimeType
                putExtra(Intent.EXTRA_TITLE, safeFilename)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            if (!hasUsableDocumentPicker(intent)) {
                pendingDownloadRequestId = null
                dispatchDownloadTargetEvent(requestId, false, getString(R.string.storage_save_unavailable_message))
                return@runOnUiThread
            }
            runCatching { startActivityForResult(intent, DOWNLOAD_TARGET_REQUEST) }
                .onFailure { error ->
                    pendingDownloadRequestId = null
                    dispatchDownloadTargetEvent(requestId, false, error.message ?: getString(R.string.storage_save_unavailable_message))
                }
        }
        return true
    }

    internal fun writeDownloadChunk(requestId: String, base64Data: String): Boolean {
        val document = downloadDocuments[requestId] ?: return false
        if (base64Data.length > MAX_DOWNLOAD_CHUNK_BASE64_LENGTH) return false
        return runCatching {
            val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
            document.output.write(bytes)
            true
        }.getOrDefault(false)
    }

    internal fun finishDownload(requestId: String): Boolean {
        val document = downloadDocuments.remove(requestId) ?: return false
        val flushed = runCatching { document.output.flush() }.isSuccess
        val closed = runCatching { document.output.close() }.isSuccess
        val result = flushed && closed
        if (!result) DocumentFile.fromSingleUri(this, document.uri)?.delete()
        return result
    }

    internal fun cancelDownload(requestId: String) {
        val document = downloadDocuments.remove(requestId) ?: return
        runCatching { document.output.close() }
        DocumentFile.fromSingleUri(this, document.uri)?.delete()
    }

    private fun dispatchDownloadTargetEvent(requestId: String, ready: Boolean, error: String? = null) {
        val detail = JSONObject()
            .put("requestId", requestId)
            .put("ready", ready)
            .put("error", error ?: JSONObject.NULL)
            .toString()
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('seanime-androidtv-download-target',{detail:$detail}))",
            null,
        )
    }

    internal fun downloadAndInstallUpdate(url: String, filename: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val supportedAbi = supportedAbi()
        val safeFilename = filename.matches(Regex("^[A-Za-z0-9._-]+\\.apk$", RegexOption.IGNORE_CASE))
        val expectedReleasePath = uri?.encodedPath.orEmpty().lowercase()
            .startsWith("/unoxyrich/seanime/releases/download/")
        if (
            uri == null || uri.scheme != "https" || uri.host?.equals("github.com", ignoreCase = true) != true ||
            !expectedReleasePath || !safeFilename || Uri.decode(uri.lastPathSegment.orEmpty()) != filename ||
            supportedAbi.isBlank() || !filename.contains(supportedAbi, ignoreCase = true)
        ) {
            Toast.makeText(this, "This release does not contain a compatible Seanime TV APK", Toast.LENGTH_LONG).show()
            return
        }

        val destination = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (destination == null) {
            Toast.makeText(this, "Android storage is not available for the update", Toast.LENGTH_LONG).show()
            return
        }

        val downloadManager = getSystemService(DownloadManager::class.java)
        val previousId = updatePreferences.getLong(PENDING_UPDATE_DOWNLOAD_ID, -1L)
        if (previousId >= 0) downloadManager.remove(previousId)

        val outputFile = File(destination, "seanime-tv-update-$filename")
        val request = DownloadManager.Request(uri)
            .setTitle("Seanime TV update")
            .setDescription("Downloading $filename")
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, outputFile.name)
            .addRequestHeader("User-Agent", "Seanime TV/0.1.0")

        runCatching { downloadManager.enqueue(request) }
            .onSuccess { downloadId ->
                updatePreferences.edit()
                    .putLong(PENDING_UPDATE_DOWNLOAD_ID, downloadId)
                    .putString(PENDING_UPDATE_DOWNLOAD_PATH, outputFile.absolutePath)
                    .apply()
                handler.postDelayed({ finishUpdateDownload(downloadId) }, 1_000)
                Toast.makeText(this, "Downloading Seanime TV update", Toast.LENGTH_LONG).show()
            }
            .onFailure { error ->
                Toast.makeText(this, error.message ?: "Could not start the update download", Toast.LENGTH_LONG).show()
            }
    }

    private fun registerUpdateDownloadReceiver() {
        if (updateReceiverRegistered) return
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateDownloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(updateDownloadReceiver, filter)
        }
        updateReceiverRegistered = true
    }

    private fun finishUpdateDownload(downloadId: Long) {
        if (updatePreferences.getLong(PENDING_UPDATE_DOWNLOAD_ID, -1L) != downloadId) return
        val downloadManager = getSystemService(DownloadManager::class.java)
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return
        cursor.use {
            if (!it.moveToFirst()) {
                clearPendingUpdateDownload()
                Toast.makeText(this, "The update download was not found", Toast.LENGTH_LONG).show()
                return
            }

            when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val path = updatePreferences.getString(PENDING_UPDATE_DOWNLOAD_PATH, null)
                    clearPendingUpdateDownload()
                    if (path == null || !File(path).isFile) {
                        Toast.makeText(this, "The downloaded update could not be opened", Toast.LENGTH_LONG).show()
                        return
                    }
                    installUpdate(path)
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    clearPendingUpdateDownload()
                    Toast.makeText(this, "Update download failed ($reason)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun clearPendingUpdateDownload() {
        updatePreferences.edit()
            .remove(PENDING_UPDATE_DOWNLOAD_ID)
            .remove(PENDING_UPDATE_DOWNLOAD_PATH)
            .apply()
    }

    private fun resumePendingUpdateInstall() {
        val pendingPath = updatePreferences.getString(PENDING_UPDATE_INSTALL_PATH, null) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) return
        installUpdate(pendingPath)
    }

    internal fun launchNativePlayer(url: String, title: String, subtitleTracksJson: String, startPositionMs: Long, subtitleStyleJson: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme !in setOf("http", "https", "content", "file")) return
        NativePlayerActivity.markLaunchPending()
        startActivity(NativePlayerActivity.intent(this, uri, title, subtitleTracksJson, startPositionMs, subtitleStyleJson))
    }

    internal fun updateNativePlayer(url: String, title: String, subtitleTracksJson: String, startPositionMs: Long, subtitleStyleJson: String) {
        NativePlayerActivity.updateMedia(url, title, subtitleTracksJson, startPositionMs, subtitleStyleJson)
    }

    internal fun updateNativeSubtitleStyle(subtitleStyleJson: String) {
        NativePlayerActivity.updateSubtitleStyle(subtitleStyleJson)
    }

    internal fun setWebPlaybackActive(active: Boolean) {
        runOnUiThread {
            webPlaybackActive = active
            if (active && activityResumed) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    internal fun onStorageTreeSelected(uri: Uri?, grantedFlags: Int, purpose: String) {
        if (uri == null) {
            dispatchStorageEvent(null, purpose)
            return
        }
        val persistableFlags = grantedFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (persistableFlags != 0) {
            runCatching { contentResolver.takePersistableUriPermission(uri, persistableFlags) }
        }
        val directory = DocumentFile.fromTreeUri(this, uri)
        val hasReadGrant = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        val rootItem = JSONObject()
            .put("uri", uri.toString())
            .put("id", AndroidSafStorageAdapter.storageId(uri))
            .put("path", AndroidSafStorageAdapter.virtualRoot(uri))
            .put("name", directory?.name ?: "Removable storage")
            .put("granted", hasReadGrant)
            .put("available", hasReadGrant && directory?.canRead() == true)
        val prefs = getSharedPreferences("android-tv-storage", MODE_PRIVATE)
        val roots = try {
            JSONArray(prefs.getString("roots", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val updated = JSONArray()
        for (index in 0 until roots.length()) {
            val old = roots.optJSONObject(index) ?: continue
            if (old.optString("uri") != uri.toString()) updated.put(old)
        }
        updated.put(rootItem)
        prefs.edit().putString("roots", updated.toString()).apply()
        if (purpose == "screenshot") {
            prefs.edit().putString(SCREENSHOT_TREE_URI, uri.toString()).apply()
        }
        dispatchStorageEvent(rootItem, purpose)
    }

    internal fun removeStorageTree(uriString: String) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        runCatching {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val prefs = getSharedPreferences("android-tv-storage", MODE_PRIVATE)
        val saved = try {
            JSONArray(prefs.getString("roots", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val updated = JSONArray()
        for (index in 0 until saved.length()) {
            val item = saved.optJSONObject(index) ?: continue
            if (item.optString("uri") != uriString) updated.put(item)
        }
        prefs.edit().putString("roots", updated.toString()).apply()
        if (prefs.getString(SCREENSHOT_TREE_URI, null) == uriString) {
            prefs.edit().remove(SCREENSHOT_TREE_URI).apply()
        }
        dispatchStorageEvent(null, "library-main")
    }

    private fun dispatchStorageEvent(root: JSONObject?, purpose: String) {
        val detail = JSONObject()
            .put("purpose", purpose)
            .put("root", root?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
            .toString()
        webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('seanime-androidtv-storage', {detail: $detail}))", null)
    }

    @Deprecated("Activity Result APIs are not required for this single legacy picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == STORAGE_PICK_REQUEST) {
            val purpose = pendingStoragePurpose
            pendingStoragePurpose = "library-main"
            onStorageTreeSelected(if (resultCode == RESULT_OK) data?.data else null, data?.flags ?: 0, purpose)
        } else if (requestCode == DOWNLOAD_TARGET_REQUEST) {
            val requestId = pendingDownloadRequestId
            pendingDownloadRequestId = null
            if (requestId != null && resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                val output = runCatching { contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                if (output == null) {
                    dispatchDownloadTargetEvent(requestId, false, "Android could not open the selected file")
                } else {
                    downloadDocuments[requestId] = AndroidTVDownloadDocument(uri, output)
                    dispatchDownloadTargetEvent(requestId, true)
                }
            } else if (requestId != null) {
                dispatchDownloadTargetEvent(requestId, false, "Download canceled")
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            webView.evaluateJavascript(
                "(() => { const dialog = document.querySelector('[role=dialog],[role=alertdialog],[data-radix-dialog-content]'); " +
                    "if (dialog) { document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true})); return 'dialog'; } " +
                    "if (window.history.length > 1) { window.history.back(); return 'history'; } return 'exit'; })()",
            ) { result ->
                if (result?.contains("exit") == true && webView.canGoBack()) {
                    webView.goBack()
                } else if (result?.contains("exit") == true) {
                    finish()
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        resumePendingUpdateInstall()
        val pendingDownloadId = updatePreferences.getLong(PENDING_UPDATE_DOWNLOAD_ID, -1L)
        if (pendingDownloadId >= 0) finishUpdateDownload(pendingDownloadId)
        activityResumed = true
        if (webPlaybackActive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scheduleServerForeground(true)
        webView.onResume()
        serverForegroundStart()
    }

    override fun onPause() {
        activityResumed = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!NativePlayerActivity.isVisible()) {
            webView.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        downloadDocuments.keys.toList().forEach(::cancelDownload)
        if (updateReceiverRegistered) {
            unregisterReceiver(updateDownloadReceiver)
            updateReceiverRegistered = false
        }
        webView.removeJavascriptInterface("AndroidTVNativeBridge")
        setActiveWebView(null)
        setActiveActivity(null)
        webView.destroy()
        if (isFinishing) {
            serverExecutor.execute {
                Mobile.setAppInForeground(false)
                Mobile.stopServer()
            }
            serverExecutor.shutdown()
        }
        super.onDestroy()
    }

    companion object {
        const val STORAGE_PICK_REQUEST = 521
        private const val UPDATE_PREFERENCES = "android-tv-updates"
        private const val PENDING_UPDATE_DOWNLOAD_ID = "pending-download-id"
        private const val PENDING_UPDATE_DOWNLOAD_PATH = "pending-download-path"
        private const val PENDING_UPDATE_INSTALL_PATH = "pending-install-path"
        internal const val SCREENSHOT_TREE_URI = "screenshot-tree-uri"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val DOWNLOAD_TARGET_REQUEST = 522
        private const val MAX_DOWNLOAD_CHUNK_BASE64_LENGTH = 400_000
        private val serverLifecycleExecutor = Executors.newSingleThreadExecutor()
        @Volatile private var activeWebView: WeakReference<WebView>? = null
        @Volatile private var activeActivity: WeakReference<MainActivity>? = null

        fun setActiveWebView(view: WebView?) {
            activeWebView = view?.let(::WeakReference)
        }

        private fun setActiveActivity(activity: MainActivity?) {
            activeActivity = activity?.let(::WeakReference)
        }

        private fun scheduleServerForeground(foreground: Boolean) {
            serverLifecycleExecutor.execute { Mobile.setAppInForeground(foreground) }
        }

        fun supportedAbi(): String = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }.orEmpty()

        fun notifyNativePlayerStopped() {
            val activity = activeActivity?.get() ?: return
            activity.runOnUiThread {
                if (!activity.activityResumed && !activity.isDestroyed) {
                    activity.webView.onPause()
                    activity.handler.postDelayed({
                        if (!activity.activityResumed && !NativePlayerActivity.isVisible()) {
                            scheduleServerForeground(false)
                        }
                    }, 500)
                }
            }
        }

        fun notifyNativePlayerStarted() {
            val activity = activeActivity?.get() ?: return
            activity.runOnUiThread {
                if (!activity.isDestroyed) {
                    activity.webView.onResume()
                    scheduleServerForeground(true)
                }
            }
        }

        fun deliverOAuthReturn(url: String): Boolean {
            val view = activeWebView?.get() ?: return false
            view.post { view.loadUrl(url) }
            return true
        }

        fun notifyNativePlaybackProgress(positionMs: Long, completed: Boolean = false) {
            val payload = JSONObject().put("positionMs", positionMs).put("completed", completed)
            activeWebView?.get()?.post {
                activeWebView?.get()?.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('seanime-androidtv-player-progress',{detail:$payload}))",
                    null,
                )
            }
        }
    }
}

private class AndroidTVBridge(
    private val activity: MainActivity,
    private val webView: WebView,
    private val expectedToken: String,
) {
    private fun isAuthorized(token: String): Boolean = token == expectedToken

    @JavascriptInterface
    fun serverStatus(token: String): String = if (isAuthorized(token)) Mobile.serverStatus() else ""

    @JavascriptInterface
    fun serverError(token: String): String = if (isAuthorized(token)) Mobile.serverError() else ""

    @JavascriptInterface
    fun requestMediaFolder(token: String, purpose: String) {
        if (!isAuthorized(token)) return
        val normalizedPurpose = purpose.takeIf { it in setOf("library-main", "library-additional", "manga-local", "torrent-stream", "screenshot") } ?: "library-main"
        activity.runOnUiThread { activity.openStoragePicker(normalizedPurpose) }
    }

    @JavascriptInterface
    fun getStorageRoots(token: String): String {
        if (!isAuthorized(token)) return "[]"
        val saved = try {
            JSONArray(activity.getSharedPreferences("android-tv-storage", Activity.MODE_PRIVATE).getString("roots", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val grantedUris = activity.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        val current = JSONArray()
        for (index in 0 until saved.length()) {
            val item = saved.optJSONObject(index) ?: continue
            val uriString = item.optString("uri")
            val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            val available = uriString in grantedUris && uri?.let { DocumentFile.fromTreeUri(activity, it)?.canRead() } == true
            val root = JSONObject(item.toString())
            if (uri != null) {
                root.put("id", AndroidSafStorageAdapter.storageId(uri))
                    .put("path", AndroidSafStorageAdapter.virtualRoot(uri))
            }
            current.put(root.put("available", available).put("granted", uriString in grantedUris))
        }
        return current.toString()
    }

    @JavascriptInterface
    fun removeStorageFolder(token: String, uri: String) {
        if (!isAuthorized(token)) return
        activity.runOnUiThread { activity.removeStorageTree(uri) }
    }

    @JavascriptInterface
    fun openExternalUrl(token: String, url: String) {
        if (!isAuthorized(token)) return
        activity.runOnUiThread { activity.openExternalUrl(url) }
    }

    @JavascriptInterface
    fun requestDownloadTarget(token: String, requestId: String, filename: String, mimeType: String): Boolean =
        isAuthorized(token) && activity.requestDownloadTarget(requestId, filename, mimeType)

    @JavascriptInterface
    fun writeDownloadChunk(token: String, requestId: String, base64Data: String): Boolean =
        isAuthorized(token) && activity.writeDownloadChunk(requestId, base64Data)

    @JavascriptInterface
    fun finishDownload(token: String, requestId: String): Boolean =
        isAuthorized(token) && activity.finishDownload(requestId)

    @JavascriptInterface
    fun cancelDownload(token: String, requestId: String) {
        if (!isAuthorized(token)) return
        activity.cancelDownload(requestId)
    }

    @JavascriptInterface
    fun installUpdate(token: String, filePath: String) {
        if (!isAuthorized(token)) return
        activity.runOnUiThread { activity.installUpdate(filePath) }
    }

    @JavascriptInterface
    fun supportedAbi(token: String): String = if (isAuthorized(token)) MainActivity.supportedAbi() else ""

    @JavascriptInterface
    fun downloadAndInstallUpdate(token: String, url: String, filename: String) {
        if (!isAuthorized(token)) return
        activity.runOnUiThread { activity.downloadAndInstallUpdate(url, filename) }
    }

    @JavascriptInterface
    fun playNative(token: String, url: String, title: String, subtitleTracksJson: String, startPositionMs: Long, subtitleStyleJson: String) {
        if (!isAuthorized(token)) return
        activity.runOnUiThread { activity.launchNativePlayer(url, title, subtitleTracksJson, startPositionMs, subtitleStyleJson) }
    }

    @JavascriptInterface
    fun updateNativePlayer(token: String, url: String, title: String, subtitleTracksJson: String, startPositionMs: Long, subtitleStyleJson: String) {
        if (!isAuthorized(token)) return
        activity.updateNativePlayer(url, title, subtitleTracksJson, startPositionMs, subtitleStyleJson)
    }

    @JavascriptInterface
    fun updateNativeSubtitleStyle(token: String, subtitleStyleJson: String) {
        if (!isAuthorized(token)) return
        activity.updateNativeSubtitleStyle(subtitleStyleJson)
    }

    @JavascriptInterface
    fun nativePlayerActive(token: String): Boolean = isAuthorized(token) && NativePlayerActivity.isVisible()

    @JavascriptInterface
    fun setPlaybackActive(token: String, active: Boolean) {
        if (!isAuthorized(token)) return
        activity.setWebPlaybackActive(active)
    }
}
