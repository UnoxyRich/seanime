package app.seanime.tv

import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidMediaToolsTest {
    @Test
    fun bundledToolsAndNativePlayerEncodeProbeDecodeAndSeekVideo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "media-tools-${System.nanoTime()}").apply { mkdirs() }
        val video = File(directory, "cpu-video.mp4")
        val raw = File(directory, "source.yuv")
        fun runTool(name: String, vararg arguments: String): String {
            val output = File(directory, "$name-output.txt")
            val command = File(context.filesDir, "seanime/bin/$name")
            val process = ProcessBuilder(listOf(command.absolutePath) + arguments)
                .redirectErrorStream(true).redirectOutput(output).start()
            try {
                val deadline = SystemClock.elapsedRealtime() + 30_000
                var exitCode: Int? = null
                while (exitCode == null && SystemClock.elapsedRealtime() < deadline) {
                    exitCode = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                    if (exitCode == null) SystemClock.sleep(50)
                }
                val text = output.readText()
                assertEquals("$name failed or timed out: $text", 0, exitCode)
                return text
            } finally {
                process.destroy()
            }
        }
        try {
            // Generate ten YUV420 frames without depending on libavdevice's
            // synthetic-input devices, which are not part of the Android build.
            raw.outputStream().use { output ->
                repeat(10) { frame ->
                    output.write(ByteArray(160 * 90) { (16 + frame * 20).toByte() })
                    output.write(ByteArray(160 * 90 / 2) { 128.toByte() })
                }
            }
            runTool("ffmpeg", "-v", "error", "-f", "rawvideo", "-pixel_format", "yuv420p", "-video_size", "160x90",
                "-framerate", "10", "-i", raw.absolutePath,
                "-threads", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-an", video.absolutePath)
            assertTrue("CPU encoder did not create a video", video.length() > 0)
            val metadata = JSONObject(runTool("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,width,height,duration",
                "-of", "json", video.absolutePath)).getJSONArray("streams").getJSONObject(0)
            assertEquals("h264", metadata.getString("codec_name"))
            assertEquals(160, metadata.getInt("width"))
            assertEquals(90, metadata.getInt("height"))
            assertEquals(1.0, metadata.getDouble("duration"), 0.05)
            runTool("ffmpeg", "-v", "error", "-i", video.absolutePath, "-f", "null", "-")

            // Exercise the actual Android decoder with the bundled encoder's
            // output, not just FFmpeg's software decoder or an audio fixture.
            ActivityScenario.launch<NativePlayerActivity>(
                NativePlayerActivity.intent(context, Uri.fromFile(video), "CPU fallback video", "[]", 0, "{}", """{"paused":true}"""),
            ).use { scenario ->
                awaitVideoFrame(scenario)
                scenario.onActivity { activity ->
                    val player = requireNotNull(findPlayerView(activity.window.decorView)?.player)
                    assertEquals(160, player.videoSize.width)
                    assertEquals(90, player.videoSize.height)
                    assertEquals(1_000L, player.duration)
                    assertTrue("fixture should start paused", !player.playWhenReady)
                    NativePlayerActivity.control(Uri.fromFile(video).toString(), "seekTo", 500.0)
                }
                awaitVideoFrame(scenario)
                scenario.onActivity { activity ->
                    val player = requireNotNull(findPlayerView(activity.window.decorView)?.player)
                    assertEquals(500L, player.currentPosition)
                    assertTrue("seeking resumed a paused video", !player.playWhenReady)
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun awaitVideoFrame(scenario: ActivityScenario<NativePlayerActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var rendered = false
            scenario.onActivity { activity ->
                val player = findPlayerView(activity.window.decorView)?.player as? ExoPlayer
                assertEquals("native video decoder failed", null, player?.playerError)
                val counters = player?.videoDecoderCounters
                counters?.ensureUpdated()
                rendered = player?.playbackState == Player.STATE_READY && (counters?.renderedOutputBufferCount ?: 0) > 0
            }
            if (rendered) return
            SystemClock.sleep(50)
        }
        throw AssertionError("native decoder did not render an H.264 frame")
    }

    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findPlayerView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    @Test
    fun bundledToolsExecuteFromTheirInstalledNativeLibraryDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (name in listOf("ffmpeg", "ffprobe")) {
            val command = File(context.filesDir, "seanime/bin/$name")
            val packaged = File(context.applicationInfo.nativeLibraryDir, "lib$name.so")
            assertEquals("$name must point to the installed APK binary", packaged.canonicalFile, command.canonicalFile)
            assertTrue("$name is not executable", command.canExecute())

            val process = ProcessBuilder(command.absolutePath, "-version").redirectErrorStream(true).start()
            try {
                val deadline = SystemClock.elapsedRealtime() + 10_000
                var exitCode: Int? = null
                while (exitCode == null && SystemClock.elapsedRealtime() < deadline) {
                    exitCode = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                    if (exitCode == null) SystemClock.sleep(50)
                }
                assertEquals("$name did not exit successfully", 0, exitCode)
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertTrue("unexpected $name output: $output", output.startsWith("$name version"))
            } finally {
                process.destroy()
            }
        }
    }
}
