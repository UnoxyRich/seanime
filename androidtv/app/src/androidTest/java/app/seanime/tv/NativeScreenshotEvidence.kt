package app.seanime.tv

import android.graphics.Bitmap
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.File

/** Screenshots of generated fixtures/native remote states, kept in the test app's private cache. */
object NativeScreenshotEvidence {
    fun capture(name: String) {
        require(name.matches(Regex("[a-z0-9_-]+")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "No screenshot available for $name" }
        val directory = File(instrumentation.targetContext.cacheDir, "native-acceptance-screenshots").apply { mkdirs() }
        try {
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val metadata = JSONObject().put("scenario", name).put("width", bitmap.width).put("height", bitmap.height)
                .put("sdk", Build.VERSION.SDK_INT).put("device", Build.MODEL).put("capturedAtMs", System.currentTimeMillis())
                .put("note", "Native UI test fixture; correlate with installed APK hashes in the acceptance runner log")
            File(directory, "$name.json").writeText(metadata.toString(2))
        } finally { bitmap.recycle() }
    }
}
