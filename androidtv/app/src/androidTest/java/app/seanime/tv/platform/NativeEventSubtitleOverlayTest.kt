package app.seanime.tv.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.os.Handler
import android.util.Base64
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Rule
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.seanime.tv.ui.performTvClick
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.NativePlayerActivity
import app.seanime.tv.PlaybackRecoverySnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real video-only HLS; original events render independently of Media3 text tracks. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class NativeEventSubtitleOverlayTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun queuedSubtitleFramesCannotReappearAfterSeekOrPauseResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var nativePlayer: ExoPlayer
        lateinit var plane: NativeEventSubtitleOverlay
        lateinit var main: Handler
        lateinit var worker: ExecutorService
        lateinit var renderClock: Runnable
        val errors = mutableListOf<String>()
        instrumentation.runOnMainSync {
            nativePlayer = ExoPlayer.Builder(context).build()
            plane = NativeEventSubtitleOverlay(context, FrameLayout(context), { nativePlayer }) { errors.add(it) }
            (field(plane, "view") as View).layout(0, 0, 640, 360)
            main = field(plane, "main") as Handler
            worker = field(plane, "worker") as ExecutorService
            renderClock = field(plane, "clock") as Runnable
            plane.configure(JSONObject().put("id", "event-fixture").put("mkvMetadata", JSONObject()
                .put("subtitleTracks", JSONArray().put(JSONObject().put("number", 3).put("codecID", "S_HDMV/PGS")))), 3)
            plane.receive(batch(1, pgsEvent()).toString())
            // Drive the real renderer explicitly so the callback ordering is
            // deterministic, without racing a continuously scheduled clock.
            main.removeCallbacks(renderClock)
        }
        try {
            worker.submit {}.get(5, TimeUnit.SECONDS)
            fun queueFrameThen(transition: () -> Unit) {
                instrumentation.runOnMainSync {
                    renderClock.run()
                    main.removeCallbacks(renderClock)
                    // The worker finishes rendering while the main looper is
                    // occupied here. Its frame callback is now queued before
                    // the seek/stop, but cannot run until this block returns.
                    worker.submit {}.get(5, TimeUnit.SECONDS)
                    assertTrue("Fixture PGS was not decoded", (field(plane, "images") as Map<*, *>).isNotEmpty())
                    transition()
                    main.removeCallbacks(renderClock)
                    assertNull(state(plane))
                }
                instrumentation.runOnMainSync {
                    assertNull("A pre-transition render callback repopulated the cleared plane", state(plane))
                    assertTrue("Subtitle worker failed: $errors", errors.isEmpty())
                }
            }
            queueFrameThen { plane.seek() }
            // Even a fresh render with no post-seek events represents a clear
            // plane, rather than publishing an empty but non-null draw state.
            worker.submit {}.get(5, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { renderClock.run(); main.removeCallbacks(renderClock) }
            worker.submit {}.get(5, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { assertNull("An empty subtitle plane must stay clear", state(plane)) }
            instrumentation.runOnMainSync { plane.receive(batch(2, pgsEvent()).toString()) }
            worker.submit {}.get(5, TimeUnit.SECONDS)
            queueFrameThen { plane.pause(); plane.resume() }
        } finally {
            instrumentation.runOnMainSync { plane.close(); nativePlayer.release() }
        }
    }

    @Test fun hlsRendersAuthoredAssAndPgsAcrossSeekAndLifecycle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = PlaybackRecoverySnapshot.read(context.filesDir)
        val previousInfo = NativePlaybackBus.playbackInfoJson
        val preferences = context.getSharedPreferences("native-player-settings", android.content.Context.MODE_PRIVATE)
        val oldPreset = preferences.getString("anime4k", null)
        preferences.edit().putString("anime4k", "off").commit()
        val directory = File(context.cacheDir, "native-event-test-${System.nanoTime()}").apply { mkdirs() }
        val source = File(directory, "master.m3u8")
        val videoPlaylist = File(directory, "video.m3u8")
        try {
            val raw = File(directory, "black.yuv")
            raw.outputStream().use { out -> repeat(40) {
                out.write(ByteArray(160 * 90) { 16 }); out.write(ByteArray(160 * 90 / 2) { 128.toByte() })
            } }
            val log = File(directory, "encode.log")
            val process = ProcessBuilder(File(context.filesDir, "seanime/bin/ffmpeg").path, "-v", "error",
                "-f", "rawvideo", "-pixel_format", "yuv420p", "-video_size", "160x90", "-framerate", "10", "-i", raw.path,
                "-threads", "1", "-c:v", "libx264", "-g", "10", "-pix_fmt", "yuv420p", "-an", "-sn",
                "-hls_time", "1", "-hls_list_size", "0", "-hls_segment_filename", File(directory, "part%02d.ts").path, videoPlaylist.path)
                .redirectErrorStream(true).redirectOutput(log).start()
            try {
                val deadline = SystemClock.elapsedRealtime() + 60_000
                var exit: Int? = null
                while (exit == null && SystemClock.elapsedRealtime() < deadline) {
                    exit = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                    if (exit == null) SystemClock.sleep(50)
                }
                assertEquals("HLS encoding: ${log.readText()}", 0, exit)
            } finally { process.destroy() }
            // Match the converted stream's real cassette master. Opening the
            // child playlist directly leaves caption declarations unknown, so
            // Media3 exposes a synthetic CEA-608 track even for this -sn video.
            source.writeText("""
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=160x90,CLOSED-CAPTIONS=NONE
                video.m3u8
            """.trimIndent() + "\n")
            val master = source.inputStream().use {
                HlsPlaylistParser().parse(Uri.fromFile(source), it) as HlsMultivariantPlaylist
            }
            assertNotNull("Fixture must explicitly declare caption absence", master.muxedCaptionFormats)
            assertTrue("Fixture declares unexpected captions", master.muxedCaptionFormats!!.isEmpty())
            val url = Uri.fromFile(source).toString()
            val info = JSONObject().put("id", "event-fixture").put("streamUrl", url).put("streamType", "hls")
                .put("mkvMetadata", JSONObject().put("subtitleTracks", JSONArray()
                    .put(JSONObject().put("number", 2).put("codecID", "S_TEXT/ASS").put("default", true).put("codecPrivate", NativeSubtitleProtocol.fallbackHeader))
                    .put(JSONObject().put("number", 3).put("codecID", "S_HDMV/PGS"))))
            NativePlaybackBus.playbackInfoJson = info.toString()
            ActivityScenario.launch<NativePlayerActivity>(NativePlayerActivity.intent(context, Uri.fromFile(source),
                "HLS original subtitles", "[]", 1100, "{}", """{"paused":true}""")).use { scenario ->
                await(scenario, "HLS decoder") { player(it).playbackState == Player.STATE_READY }
                scenario.onActivity {
                    val tracks = player(it).currentTracks.groups
                    val diagnostics = tracks.flatMap { group -> (0 until group.length).map { index ->
                        val format = group.getTrackFormat(index)
                        "type=${group.type}, mime=${format.sampleMimeType}, id=${format.id}, " +
                            "channel=${format.accessibilityChannel}, selected=${group.isTrackSelected(index)}"
                    } }.joinToString("; ")
                    assertTrue("Converted fixture unexpectedly exposes text tracks: $diagnostics",
                        tracks.none { group -> group.type == C.TRACK_TYPE_TEXT })
                    NativePlayerActivity.configureEventSubtitles(info.toString(), 2)
                    NativePlayerActivity.receiveEventSubtitles(batch(1, assEvent()).toString())
                }
                val first = awaitPixels(scenario)
                val firstCenter = center(first); first.recycle()
                awaitReadyPresentation(scenario)
                NativeScreenshotEvidence.capture("player-hls-original-ass")
                scenario.onActivity {
                    NativePlayerActivity.control(url, "seekTo", 2900.0)
                    NativePlayerActivity.receiveEventSubtitles(batch(1, assEvent()).toString())
                }
                await(scenario, "seek") { player(it).currentPosition == 2900L }
                scenario.onActivity {
                    assertNull("Obsolete generation remained on screen", state(overlay(it)))
                    NativePlayerActivity.receiveEventSubtitles(batch(2, assEvent()).toString())
                }
                val moved = awaitPixels(scenario)
                assertTrue("Authored move did not follow HLS time", center(moved) > firstCenter + 20); moved.recycle()
                scenario.onActivity {
                    NativePlayerActivity.selectTrack(C.TRACK_TYPE_TEXT, 3)
                    NativePlayerActivity.receiveEventSubtitles(batch(2, pgsEvent()).toString())
                }
                val pgs = awaitPixels(scenario, true)
                assertEquals(0, Color.alpha(pgs.getPixel(0, 0))); pgs.recycle()
                awaitReadyPresentation(scenario)
                NativeScreenshotEvidence.capture("player-hls-original-pgs")
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                await(scenario, "lifecycle resume") { player(it).playbackState == Player.STATE_READY }
                scenario.onActivity {
                    assertEquals(2900L, player(it).currentPosition); assertFalse(player(it).playWhenReady)
                    NativePlayerActivity.receiveEventSubtitles(batch(3, pgsEvent()).toString())
                }
                awaitPixels(scenario, true).recycle()
                compose.onNodeWithTag("native-player-picture").performTvClick()
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodesWithTag("native-player-dialog").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("native-player-dialog").assertIsDisplayed()
                compose.onNodeWithText("Anime4K picture enhancement").assertIsDisplayed()
                compose.onNodeWithTag("native-player-choice-off").assertIsDisplayed()
                compose.waitForIdle()
                NativeScreenshotEvidence.capture("player-fresh-anime4k-dialog")
                compose.onNodeWithTag("native-player-choices").performScrollToNode(hasTestTag("native-player-choice-cnn-2x-medium"))
                compose.onNodeWithTag("native-player-choice-cnn-2x-medium").performTvClick()
                scenario.onActivity { assertEquals("cnn-2x-medium", NativePlayerActivity.currentAnime4K()); assertNull(player(it).playerError) }
                scenario.recreate()
                await(scenario, "recreated HLS and original subtitle metadata") { player(it).playbackState == Player.STATE_READY && overlay(it).configured }
                scenario.onActivity {
                    assertEquals("event-fixture", overlay(it).playbackId)
                    assertEquals(3, overlay(it).selectedTrack)
                    assertEquals("cnn-2x-medium", NativePlayerActivity.currentAnime4K())
                    NativePlayerActivity.receiveEventSubtitles(batch(4, pgsEvent()).toString())
                }
                awaitPixels(scenario, true).recycle()
                compose.onNodeWithTag("native-player-picture").performTvClick()
                compose.onNodeWithTag("native-player-choices").performScrollToNode(hasTestTag("native-player-choice-off"))
                compose.onNodeWithTag("native-player-choice-off").performTvClick()
                scenario.onActivity { assertEquals("off", NativePlayerActivity.currentAnime4K()); assertNull(player(it).playerError) }
                val invalid = JSONObject(info.toString())
                invalid.getJSONObject("mkvMetadata").getJSONArray("subtitleTracks").getJSONObject(0).put("codecPrivate", "a".repeat(1024 * 1024 + 1))
                scenario.onActivity { NativePlayerActivity.configureEventSubtitles(invalid.toString(), 2) }
                await(scenario, "bounded header failure") {
                    val plane = overlay(it)
                    (field(plane, "failedRevision") as AtomicLong).get() == (field(plane, "revision") as AtomicLong).get()
                }
                scenario.onActivity { assertNull(state(overlay(it))); assertNull(player(it).playerError) }
            }
        } finally {
            NativePlaybackBus.playbackInfoJson = previousInfo
            preferences.edit().apply { if (oldPreset == null) remove("anime4k") else putString("anime4k", oldPreset) }.commit()
            directory.deleteRecursively()
            if (previous == null) PlaybackRecoverySnapshot.clear(context.filesDir) else PlaybackRecoverySnapshot.write(context.filesDir, previous)
        }
    }

    private fun assEvent() = JSONObject().put("trackNumber", 2).put("codecID", "S_TEXT/ASS").put("startTime", 0).put("duration", 4000)
        .put("text", "{\\move(100,180,540,180)}Authored ASS").put("extraData", JSONObject().put("layer", "2").put("style", "Default")
            .put("marginl", "10").put("marginr", "10").put("marginv", "10"))
    private fun pgsEvent(): JSONObject {
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val data = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); bitmap.recycle()
        return JSONObject().put("trackNumber", 3).put("codecID", "S_HDMV/PGS").put("startTime", 0).put("duration", 4000)
            .put("text", Base64.encodeToString(data, Base64.NO_WRAP)).put("extraData", JSONObject()
                .put("canvas_width", "160").put("canvas_height", "90").put("x", "20").put("y", "30").put("width", "40").put("height", "20")
                .put("crop_x", "2").put("crop_y", "2").put("crop_width", "8").put("crop_height", "4"))
    }
    private fun batch(generation: Int, event: JSONObject) = JSONObject().put("playbackId", "event-fixture").put("generationId", generation).put("events", JSONArray().put(event))
    private fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
    private fun overlay(activity: NativePlayerActivity) = field(activity, "eventSubtitles") as NativeEventSubtitleOverlay
    private fun player(activity: NativePlayerActivity) = (field(activity, "assSession") as NativeAssSession).player
    private fun state(overlay: NativeEventSubtitleOverlay) = field(field(overlay, "view")!!, "state")
    private fun awaitReadyPresentation(scenario: ActivityScenario<NativePlayerActivity>) {
        // Decoder callbacks and the 250 ms HUD snapshot reach Compose on
        // different frames. Capture only after both describe the paused frame.
        await(scenario, "ready player presentation") {
            val current = player(it)
            val presentation = field(it, "presentation") as NativeTvPlayerState
            current.playbackState == Player.STATE_READY && !presentation.buffering &&
                presentation.position == current.currentPosition && presentation.duration == current.duration
        }
        compose.onNodeWithTag("native-player-presentation").assertIsDisplayed()
        compose.onNodeWithText("Buffering…").assertDoesNotExist()
        compose.waitForIdle()
    }
    private fun await(scenario: ActivityScenario<NativePlayerActivity>, description: String, condition: (NativePlayerActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity { assertNull(player(it).playerError); ready = condition(it) }
            if (ready) return
            SystemClock.sleep(80)
        }
        fail("Timed out: $description")
    }
    private fun awaitPixels(scenario: ActivityScenario<NativePlayerActivity>, green: Boolean = false): Bitmap {
        var result: Bitmap? = null
        await(scenario, "native subtitle pixels") {
            val view = field(overlay(it), "view") as View
            if (view.width <= 0 || view.height <= 0 || state(overlay(it)) == null) return@await false
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            if (pixels.any { pixel -> Color.alpha(pixel) > 0 && (!green || Color.green(pixel) > 200 && Color.red(pixel) < 20) }) { result = bitmap; true }
            else { bitmap.recycle(); false }
        }
        return requireNotNull(result)
    }
    private fun center(bitmap: Bitmap): Double {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.indices.filter { Color.alpha(pixels[it]) > 0 }.map { (it % bitmap.width).toDouble() }.average()
    }
}
