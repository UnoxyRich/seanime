package app.seanime.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.platform.AuthBrowserPolicy
import app.seanime.tv.platform.NativePlaybackBus
import app.seanime.tv.platform.NativePlatformActions
import app.seanime.tv.platform.NativePrompt
import app.seanime.tv.platform.NativePromptAction
import app.seanime.tv.ui.ActionButton
import app.seanime.tv.ui.ActionRow
import app.seanime.tv.ui.PlatformPromptDialog
import app.seanime.tv.ui.SeanimeTheme

/** New TV Compose browser chrome. Only the provider's own login page uses a WebView. */
class AuthWebViewActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var loading by mutableIntStateOf(0)
    private var canGoBack by mutableStateOf(false)
    private var browsing by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var confirmClose by mutableStateOf(false)
    private val chromeFocus = FocusRequester()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null || !AuthBrowserPolicy.isProviderUrl(uri.toString())) { finish(); return }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val provider = if (uri.host == "anilist.co") "AniList" else "MyAnimeList"
        webView = createProviderBrowser()
        val saved = savedInstanceState?.getBundle(STATE_BROWSER)
        if (saved == null || webView.restoreState(saved) == null) webView.loadUrl(uri.toString())
        setContent {
            SeanimeTheme {
                BackHandler {
                    when {
                        confirmClose -> confirmClose = false
                        browsing -> returnToControls()
                        else -> confirmClose = true
                    }
                }
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 40.dp, vertical = 24.dp)
                    .testTag("native-auth-root"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connect $provider", style = MaterialTheme.typography.headlineMedium)
                    Text("${uri.host} · Use the login page with your remote or keyboard. Back returns to these controls.",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ActionRow {
                        ActionButton("Use login page", modifier = Modifier.focusRequester(chromeFocus).testTag("auth-enter-page")) {
                            browsing = true
                            webView.requestFocus(View.FOCUS_DOWN)
                        }
                        ActionButton("Previous page", canGoBack) { error = null; webView.goBack() }
                        ActionButton("Reload page") { error = null; webView.reload() }
                        ActionButton("Close login", modifier = Modifier.testTag("auth-close")) { confirmClose = true }
                    }
                    if (loading in 1..99) LinearProgressIndicator(progress = { loading / 100f }, modifier = Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
                    AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f)
                        .border(if (browsing) 3.dp else 1.dp, if (browsing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f),
                            RoundedCornerShape(8.dp)).testTag("provider-login-page"))
                }
                if (confirmClose) PlatformPromptDialog(NativePrompt("Close $provider login?",
                    "You can start again from Settings. No account changes are made by closing this screen.",
                    actions = listOf(NativePromptAction("Close login") { finish() }), dismissLabel = "Keep signing in")) { confirmClose = false }
                LaunchedEffect(Unit) { withFrameNanos { }; chromeFocus.requestFocus() }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createProviderBrowser() = WebView(this).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        onFocusChangeListener = View.OnFocusChangeListener { _, focused -> browsing = focused }
        settings.apply {
            javaScriptEnabled = true // Required by provider OAuth forms; never app presentation.
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, progress: Int) { loading = progress; canGoBack = view.canGoBack() }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url
                val callback = NativePlaybackBus.localUrl(target.toString())
                if (request.isForMainFrame && callback != null) {
                    startActivity(Intent().setClassName(packageName, "app.seanime.tv.MainActivity")
                        .putExtra(NativePlatformActions.OAUTH_RETURN_EXTRA, callback)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                    return true
                }
                val blocked = if (request.isForMainFrame) !AuthBrowserPolicy.isProviderUrl(target.toString())
                    else target.scheme != "https" || target.userInfo != null
                if (blocked && request.isForMainFrame) error = "This login can only open the provider's secure pages. Use its account login form, or close and try again."
                return blocked
            }
            override fun onPageFinished(view: WebView, url: String) { canGoBack = view.canGoBack(); loading = 100 }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                if (request.isForMainFrame) { error = "The provider page could not load. Check the connection, then reload or close login."; loading = 100 }
            }
        }
    }

    private fun returnToControls() {
        browsing = false
        webView.clearFocus()
        chromeFocus.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && ::webView.isInitialized) {
            returnToControls(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) {
            val state = Bundle()
            if (webView.saveState(state) != null) outState.putBundle(STATE_BROWSER, state)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) { (webView.parent as? android.view.ViewGroup)?.removeView(webView); webView.destroy() }
        super.onDestroy()
    }

    companion object {
        private const val STATE_BROWSER = "native-provider-browser-state"
        fun intent(context: Context, uri: Uri): Intent = Intent(context, AuthWebViewActivity::class.java).setData(uri)
    }
}
