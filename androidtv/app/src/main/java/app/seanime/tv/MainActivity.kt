package app.seanime.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
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
import app.seanime.tv.gomobile.mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private val handler = Handler(Looper.getMainLooper())
    private val serverExecutor = Executors.newSingleThreadExecutor()
    private val serverPort = 43211
    private var started = false
    private var serverReadyHandled = false
    private var displayedError: String? = null
    private var activityResumed = false
    private var webPlaybackActive = false
    private var retryButton: Button? = null

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
            addJavascriptInterface(AndroidTVBridge(this@MainActivity, this), "AndroidTV")
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
                    openExternalUrl(uri.toString())
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    view.requestFocus(View.FOCUS_DOWN)
                    statusText.visibility = View.GONE
                    progress.visibility = View.GONE
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
        progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, FrameLayout.LayoutParams(72, 72, Gravity.CENTER))
        root.addView(statusText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)

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
                val ready = Mobile.waitForServer(45_000L)
                runOnUiThread {
                    if (ready) loadSeanime() else showServerError(Mobile.serverError().ifBlank { "Seanime could not start." })
                }
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
        progress.visibility = View.VISIBLE
        val url = "http://127.0.0.1:$serverPort/"
        if (webView.url == url) webView.reload() else webView.loadUrl(url)
    }

    private fun showServerError(message: String) {
        if (isFinishing || isDestroyed) return
        if (displayedError == message && retryButton != null) return
        displayedError = message
        progress.visibility = View.GONE
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

    internal fun openExternalUrl(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme !in setOf("http", "https", "mailto")) return
        if (uri.scheme == "http" || uri.scheme == "https") {
            startActivity(AuthWebViewActivity.intent(this, uri))
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_LONG).show()
        }
    }

    internal fun installUpdate(filePath: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val apk = runCatching { File(filePath).canonicalFile }.getOrNull() ?: return
        val allowedRoots = listOf(filesDir.resolve("seanime/updates"), cacheDir.resolve("seanime"))
            .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
        if (!apk.isFile || apk.extension.lowercase() != "apk" || allowedRoots.none { apk.path.startsWith("$it/") }) {
            Toast.makeText(this, "Choose an APK from Seanime's update cache", Toast.LENGTH_LONG).show()
            return
        }
        val apkUri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "Unable to start the Android package installer", Toast.LENGTH_LONG).show()
        }
    }

    internal fun launchNativePlayer(url: String, title: String, subtitleTracksJson: String, startPositionMs: Long) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme !in setOf("http", "https", "content", "file")) return
        NativePlayerActivity.markLaunchPending()
        startActivity(NativePlayerActivity.intent(this, uri, title, subtitleTracksJson, startPositionMs))
    }

    internal fun updateNativePlayer(url: String, title: String, subtitleTracksJson: String, startPositionMs: Long) {
        NativePlayerActivity.updateMedia(url, title, subtitleTracksJson, startPositionMs)
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

    internal fun onStorageTreeSelected(uri: Uri?, grantedFlags: Int) {
        if (uri == null) {
            dispatchStorageEvent(null)
            return
        }
        val persistableFlags = grantedFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (persistableFlags != 0) {
            runCatching { contentResolver.takePersistableUriPermission(uri, persistableFlags) }
        }
        val directory = DocumentFile.fromTreeUri(this, uri)
        val rootItem = JSONObject()
            .put("uri", uri.toString())
            .put("name", directory?.name ?: "Removable storage")
            .put("available", directory?.canRead() == true)
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
        dispatchStorageEvent(rootItem)
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
        dispatchStorageEvent(null)
    }

    private fun dispatchStorageEvent(root: JSONObject?) {
        val detail = root?.toString() ?: "null"
        webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('seanime-androidtv-storage', {detail: $detail}))", null)
    }

    @Deprecated("Activity Result APIs are not required for this single legacy picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == STORAGE_PICK_REQUEST && resultCode == RESULT_OK) {
            onStorageTreeSelected(data?.data, data?.flags ?: 0)
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
        activityResumed = true
        if (webPlaybackActive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        webView.onResume()
        serverForegroundStart()
    }

    override fun onPause() {
        activityResumed = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!NativePlayerActivity.isVisible()) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.removeJavascriptInterface("AndroidTV")
        setActiveWebView(null)
        setActiveActivity(null)
        webView.destroy()
        if (isFinishing) serverExecutor.execute { Mobile.stopServer() }
        super.onDestroy()
    }

    companion object {
        const val STORAGE_PICK_REQUEST = 521
        @Volatile private var activeWebView: WeakReference<WebView>? = null
        @Volatile private var activeActivity: WeakReference<MainActivity>? = null

        fun setActiveWebView(view: WebView?) {
            activeWebView = view?.let(::WeakReference)
        }

        private fun setActiveActivity(activity: MainActivity?) {
            activeActivity = activity?.let(::WeakReference)
        }

        fun notifyNativePlayerStopped() {
            val activity = activeActivity?.get() ?: return
            activity.runOnUiThread {
                if (!activity.activityResumed && !activity.isDestroyed) activity.webView.onPause()
            }
        }

        fun notifyNativePlaybackEnded(positionMs: Long, completed: Boolean) {
            notifyNativePlaybackProgress(positionMs, completed)
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

private class AndroidTVBridge(private val activity: MainActivity, private val webView: WebView) {
    @JavascriptInterface
    fun serverStatus(): String = Mobile.serverStatus()

    @JavascriptInterface
    fun serverError(): String = Mobile.serverError()

    @JavascriptInterface
    fun requestMediaFolder() {
        activity.runOnUiThread {
            val intent = Intent(DocumentsContract.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            activity.startActivityForResult(intent, MainActivity.STORAGE_PICK_REQUEST)
        }
    }

    @JavascriptInterface
    fun getStorageRoots(): String {
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
            current.put(JSONObject(item.toString()).put("available", available).put("granted", uriString in grantedUris))
        }
        return current.toString()
    }

    @JavascriptInterface
    fun removeStorageFolder(uri: String) {
        activity.runOnUiThread { activity.removeStorageTree(uri) }
    }

    @JavascriptInterface
    fun openExternalUrl(url: String) {
        activity.runOnUiThread { activity.openExternalUrl(url) }
    }

    @JavascriptInterface
    fun installUpdate(filePath: String) {
        activity.runOnUiThread { activity.installUpdate(filePath) }
    }

    @JavascriptInterface
    fun playNative(url: String, title: String, subtitleTracksJson: String, startPositionMs: Long) {
        activity.runOnUiThread { activity.launchNativePlayer(url, title, subtitleTracksJson, startPositionMs) }
    }

    @JavascriptInterface
    fun updateNativePlayer(url: String, title: String, subtitleTracksJson: String, startPositionMs: Long) {
        activity.updateNativePlayer(url, title, subtitleTracksJson, startPositionMs)
    }

    @JavascriptInterface
    fun nativePlayerActive(): Boolean = NativePlayerActivity.isVisible()

    @JavascriptInterface
    fun setPlaybackActive(active: Boolean) {
        activity.setWebPlaybackActive(active)
    }
}
