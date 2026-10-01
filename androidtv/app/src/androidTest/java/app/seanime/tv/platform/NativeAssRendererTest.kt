package app.seanime.tv.platform

import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.seanime.tv.NativePlayerActivity
import app.seanime.tv.PlaybackRecoverySnapshot
import io.github.peerless2012.ass.AssTexType
import io.github.peerless2012.ass.media.AssHandler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises actual JNI parsing/rendering, not merely a Media3 decoder without ASS. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class NativeAssRendererTest {
    @Test fun embeddedAssRendersAnimatedGlyphsAndSurvivesSeekAndLifecycle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousRecovery = PlaybackRecoverySnapshot.read(context.filesDir)
        val directory = File(context.cacheDir, "native-ass-test-${System.nanoTime()}").apply { mkdirs() }
        val raw = File(directory, "black.yuv")
        val subtitle = File(directory, "authored.ass")
        val video = File(directory, "animated-font.mkv")
        try {
            raw.outputStream().use { output ->
                repeat(40) {
                    output.write(ByteArray(160 * 90) { 16 })
                    output.write(ByteArray(160 * 90 / 2) { 128.toByte() })
                }
            }
            subtitle.writeText("""
                [Script Info]
                ScriptType: v4.00+
                PlayResX: 640
                PlayResY: 360
                ScaledBorderAndShadow: yes
                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                Style: Default,Roboto,34,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,1,5,10,10,10,1
                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:00.00,0:00:04.00,Default,,0,0,0,,{\move(120,180,520,180)\fad(150,150)}Native ASS
            """.trimIndent())
            val font = listOf(File("/system/fonts/Roboto-Regular.ttf"), File("/system/fonts/RobotoStatic-Regular.ttf"))
                .firstOrNull { it.isFile } ?: File("/system/fonts").listFiles()?.firstOrNull { it.extension == "ttf" }
                ?: error("Android does not expose a fixture font")
            val log = File(directory, "ffmpeg.log")
            val command = listOf(File(context.filesDir, "seanime/bin/ffmpeg").path, "-v", "error",
                "-f", "rawvideo", "-pixel_format", "yuv420p", "-video_size", "160x90", "-framerate", "10", "-i", raw.path,
                "-i", subtitle.path, "-map", "0:v:0", "-map", "1:s:0", "-threads", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:s", "ass", "-disposition:s:0", "default", "-attach", font.path, "-metadata:s:t:0", "mimetype=application/x-truetype-font",
                "-metadata:s:t:0", "filename=fixture-font.ttf", video.path)
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
            try {
                val deadline = SystemClock.elapsedRealtime() + 60_000
                var exitCode: Int? = null
                while (exitCode == null && SystemClock.elapsedRealtime() < deadline) {
                    exitCode = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                    if (exitCode == null) SystemClock.sleep(50)
                }
                assertEquals("Fixture encoding failed: ${log.readText()}", 0, exitCode)
            } finally { process.destroy() }
            assertTrue(video.isFile && video.length() > 0)

            ActivityScenario.launch<NativePlayerActivity>(NativePlayerActivity.intent(context, Uri.fromFile(video),
                "Native libass fixture", "[]", 1000, "{}", """{"paused":true}""")).use { scenario ->
                awaitAss(scenario)
                scenario.onActivity { activity ->
                    val session = session(activity)
                    val ass = handler(session)
                    val renderer = requireNotNull(ass.render)
                    // These are libass-produced RGBA glyph bitmaps at two authored animation times.
                    val first = requireNotNull(renderer.renderFrame(1100, AssTexType.BITMAP_RGBA))
                    val second = requireNotNull(renderer.renderFrame(2900, AssTexType.BITMAP_RGBA))
                    val firstImages = requireNotNull(first.images)
                    val secondImages = requireNotNull(second.images)
                    assertTrue("libass did not produce glyph images", firstImages.isNotEmpty() && secondImages.isNotEmpty())
                    assertNotEquals("ASS move tag did not change rendered glyph position", firstImages.minOf { it.x }, secondImages.minOf { it.x })
                    val bitmap = firstImages.firstNotNullOfOrNull { it.bitmap } ?: error("No rendered RGBA glyph bitmap")
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    assertTrue("Rendered glyph bitmap is transparent", pixels.any { Color.alpha(it) > 0 })
                    NativePlayerActivity.control(Uri.fromFile(video).toString(), "seekTo", 2500.0)
                }
                awaitAss(scenario)
                scenario.onActivity { assertEquals(2500L, session(it).player.currentPosition) }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitAss(scenario)
                scenario.onActivity {
                    assertEquals(2500L, session(it).player.currentPosition)
                    assertFalse(session(it).player.playWhenReady)
                }
            }
        } finally {
            directory.deleteRecursively()
            if (previousRecovery == null) PlaybackRecoverySnapshot.clear(context.filesDir)
            else PlaybackRecoverySnapshot.write(context.filesDir, previousRecovery)
        }
    }

    private fun session(activity: NativePlayerActivity): NativeAssSession = NativePlayerActivity::class.java
        .getDeclaredField("assSession").apply { isAccessible = true }.get(activity) as NativeAssSession
    private fun handler(session: NativeAssSession): AssHandler = NativeAssSession::class.java
        .getDeclaredField("handler").apply { isAccessible = true }.get(session) as AssHandler
    private fun awaitAss(scenario: ActivityScenario<NativePlayerActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val session = session(activity)
                assertNull("Media3/libass runtime failed", session.player.playerError)
                val handler = handler(session)
                ready = session.player.playbackState == Player.STATE_READY && handler.hasTracks() && handler.track != null && handler.render != null
            }
            if (ready) return
            SystemClock.sleep(80)
        }
        fail("Native libass did not parse and select the embedded ASS track")
    }
}
