package app.seanime.tv

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Test APK only, a distinct UID/process: never counts as a started Seanime Activity. */
class TestStreamingExternalPlayerActivity : Activity() {
    private val reading = AtomicBoolean()
    private lateinit var source: String
    private val commands = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra("source") != source) return
            if (intent.getBooleanExtra("finish", false)) { finish(); return }
            if (!reading.compareAndSet(false, true)) return
            Thread {
                try {
                    repeat(3) { index ->
                        if (index > 0) SystemClock.sleep(1_500)
                        val start = index * 256
                        val connection = URL(source).openConnection() as HttpURLConnection
                        try {
                            connection.instanceFollowRedirects = false
                            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
                            connection.setRequestProperty("Range", "bytes=$start-${start + 255}")
                            val forbidden = listOf("Authorization", "Cookie", "X-Seanime-Token", "X-Seanime-Client-Id", "X-Seanime-Client-Id-Proof")
                            check(forbidden.none { connection.getRequestProperty(it) != null })
                            val code = connection.responseCode
                            val bytes = connection.inputStream.use { input ->
                                val out = java.io.ByteArrayOutputStream()
                                repeat(256) { val byte = input.read(); if (byte >= 0) out.write(byte) }
                                out.toByteArray()
                            }
                            report("range").putExtra("index", index).putExtra("status", code)
                                .putExtra("contentRange", connection.getHeaderField("Content-Range"))
                                .putExtra("bytes", Base64.encodeToString(bytes, Base64.NO_WRAP))
                                .putExtra("authorityHeaders", false).also(::sendBroadcast)
                        } finally { connection.disconnect() }
                    }
                } catch (error: Exception) { report("error").putExtra("failure", error.javaClass.simpleName).also(::sendBroadcast) }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = requireNotNull(intent.data)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 43211 && uri.path == "/api/v1/mediastream/file")
        val path = uri.getQueryParameter("path").orEmpty()
        require(Regex(".*/native-go-fixture-[0-9a-f-]{36}/library/[^/]+\\.mp4").matches(path))
        require(intent.action == Intent.ACTION_VIEW && intent.type?.startsWith("video/") == true)
        require(intent.extras?.keySet().orEmpty().none { it.contains("header", ignoreCase = true) || it.contains("proof", ignoreCase = true) || it.contains("token", ignoreCase = true) })
        source = uri.toString()
        setContentView(TextView(this).apply {
            text = "Owned external streaming test\nReading only the generated video from Seanime"
            setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(16, 23, 35)); textSize = 26f; setPadding(48, 48, 48, 48)
        })
        ContextCompat.registerReceiver(this, commands, IntentFilter(COMMAND), ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onResume() { super.onResume(); report("ready").also(::sendBroadcast) }
    override fun onDestroy() { unregisterReceiver(commands); super.onDestroy() }
    private fun report(type: String) = Intent(RESULT).setPackage("app.seanime.tv")
        .putExtra("type", type).putExtra("source", source).putExtra("pid", Process.myPid()).putExtra("uid", Process.myUid())
        .putExtra("elapsed", SystemClock.elapsedRealtime())

    companion object {
        const val COMMAND = "app.seanime.tv.test.EXTERNAL_PLAYER_COMMAND"
        const val RESULT = "app.seanime.tv.test.EXTERNAL_PLAYER_RESULT"
    }
}
