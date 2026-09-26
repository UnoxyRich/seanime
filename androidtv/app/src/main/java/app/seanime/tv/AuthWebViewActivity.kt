package app.seanime.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Build

/** Shows OAuth and external web destinations inside the TV app. */
class AuthWebViewActivity : Activity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )

        val uri = intent.data
        if (uri == null || uri.scheme !in setOf("http", "https")) {
            finish()
            return
        }

        webView = WebView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url
                    val localSeanime = MainActivity.localPageUrl(target.toString())
                    if (request.isForMainFrame && localSeanime != null) {
                        if (!MainActivity.deliverOAuthReturn(localSeanime)) {
                            startActivity(MainActivity.localPageIntent(this@AuthWebViewActivity, localSeanime))
                        }
                        finish()
                        return true
                    }
                    return target.scheme !in setOf("http", "https")
                }
            }
        }
        setContentView(webView)
        val webState = savedInstanceState?.getBundle(STATE_WEB_VIEW)
        if (webState == null || webView.restoreState(webState) == null) {
            webView.loadUrl(uri.toString())
        } else {
            webView.reload()
        }
        webView.requestFocus(View.FOCUS_DOWN)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) {
            val webState = Bundle()
            if (webView.saveState(webState) != null) outState.putBundle(STATE_WEB_VIEW, webState)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack()) webView.goBack() else finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val STATE_WEB_VIEW = "auth-web-view"

        fun intent(context: Context, uri: Uri): Intent = Intent(context, AuthWebViewActivity::class.java)
            .setData(uri)
    }
}
