package app.seanime.tv.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeJson
import app.seanime.tv.data.SeanimeRepository
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

/** Exercises actual offline URL mapping, authenticated image transport, Coil decode and TV rendering. */
class NativeOfflineArtworkTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offlinePosterLoadsThroughTheExistingAssetRouteAndRemainsVisibleWithRemoteFocus() {
        val assetRequest = AtomicReference<RecordedRequest>()
        val png = posterFixture()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/offline-assets/42/cover.png") {
                        assetRequest.set(request)
                        return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                    }
                    val media = JSONObject().put("id", 42).put("title", "Offline poster fixture")
                        .put("coverImage", JSONObject().put("large", "{{LOCAL_ASSETS}}/42/cover.png"))
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(JSONObject().put("data", JSONArray().put(media)).toString())
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
            try {
                val status = SeanimeJson.status(JSONObject().put("version", "fixture").put("serverReady", true)
                    .put("isOffline", true).put("settings", JSONObject().put("library", JSONObject())))
                compose.setContent { SeanimeTheme { SeanimeTvApp(SeanimeRepository(api), status, {}, {}, {}) } }
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("media-42").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("media-42").performSemanticsAction(SemanticsActions.RequestFocus)
                compose.onNodeWithTag("media-42").assertIsFocused()
                compose.waitUntil(15_000) {
                    val nodes = compose.onAllNodesWithContentDescription("Offline poster fixture", useUnmergedTree = true).fetchSemanticsNodes()
                    val bounds = nodes.firstOrNull()?.boundsInRoot ?: return@waitUntil false
                    val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return@waitUntil false
                    try {
                        val pixel = bitmap.getPixel(bounds.center.x.toInt().coerceIn(0, bitmap.width - 1), bounds.center.y.toInt().coerceIn(0, bitmap.height - 1))
                        Color.green(pixel) > 160 && Color.red(pixel) < 110 && Color.blue(pixel) in 100..190
                    } finally { bitmap.recycle() }
                }
                assertEquals(api.baseUrl, assetRequest.get().getHeader("Origin"))
                assertEquals("androidtv", assetRequest.get().getHeader("X-Seanime-Client-Platform"))
                NativeScreenshotEvidence.capture("offline-poster-native-render-focus")
            } finally { server.shutdown() }
        }
    }

    private fun posterFixture(): ByteArray {
        val bitmap = Bitmap.createBitmap(180, 270, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { isAntiAlias = false }
        canvas.drawColor(Color.rgb(44, 72, 105))
        paint.color = Color.rgb(72, 190, 148)
        canvas.drawRect(18f, 55f, 162f, 215f, paint)
        paint.color = Color.rgb(224, 225, 181)
        canvas.drawRect(28f, 227f, 152f, 241f, paint)
        return try { ByteArrayOutputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray() } }
        finally { bitmap.recycle() }
    }
}
