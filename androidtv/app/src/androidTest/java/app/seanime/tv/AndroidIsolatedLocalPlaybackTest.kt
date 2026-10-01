package app.seanime.tv

import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import app.seanime.tv.data.jsonObject
import app.seanime.tv.gomobile.mobile.Mobile
import app.seanime.tv.platform.NativePlaybackBus
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * OPT IN ONLY: invoke this one test after force-stopping app.seanime.tv, with both
 * isolatedNativeGoFixture=true and freshInstrumentationProcess=true. Do not append
 * other tests to that invocation. No retained Go database/config is opened.
 *
 * The unchanged Go host has process-global caches. After this test, force-stop the
 * process before any cleanup or normal launch. Inspect the emitted fixture.json;
 * retain failed-run evidence. Only after verifying its kind/root may the exact UUID
 * directory be removed. A generated androidtv-playback-recovery.json may be removed
 * only if its nested playbackInfoJson.streamPath equals the manifest's mediaPath.
 * Never remove a pre-existing recovery file: this test refuses to start with one.
 * Finally cold-launch MainActivity and verify /status dataDir is files/seanime/data,
 * not the fixture directory. Do not switch back to retained data in this process.
 *
 * AniList public metadata is required to populate a simulated local collection.
 * Normal Animap enrichment can also make public network requests. Metadata failure
 * is reported as a named external gate, not a successful local-media test. The media
 * bytes are generated here; no provider, account, plugin or database injection is used.
 */
@OptIn(UnstableApi::class, ExperimentalTestApi::class)
class AndroidIsolatedLocalPlaybackTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun generatedMediaScansImportsPlaysAndResumesThroughIsolatedGo() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in only: requires a dedicated cold instrumentation process",
            args.getString("isolatedNativeGoFixture") == "true" && args.getString("freshInstrumentationProcess") == "true")
        val context = instrumentation.targetContext
        val app = context.applicationContext as SeanimeTvApplication
        app.awaitAndroidRuntime()
        assertEquals("A running Go host is not an isolated fresh process", "stopped", Mobile.serverStatus())
        instrumentation.runOnMainSync {
            for (stage in listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)) {
                assertTrue("Run this test alone in a fresh instrumentation process",
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).isEmpty())
            }
        }
        val recovery = File(context.filesDir, "androidtv-playback-recovery.json")
        assertFalse("Retained recovery must be preserved outside this test before it can run", recovery.exists())
        assertFalse("Retained pending recovery must be preserved outside this test", File(context.filesDir, "androidtv-playback-recovery.json.tmp").exists())
        assertEquals("Keep retained picture settings untouched; run only with Anime4K already off", "off",
            context.getSharedPreferences("native-player-settings", 0).getString("anime4k", "off"))

        val root = File(context.filesDir, "native-go-fixture-${UUID.randomUUID()}")
        check(root.parentFile!!.canonicalFile == context.filesDir.canonicalFile && root.mkdir())
        val data = File(root, "data")
        val cache = File(root, "cache")
        val library = File(root, "library").apply { mkdirs() }
        val manifest = JSONObject().put("kind", "native-isolated-go-media-v1").put("root", root.absolutePath)
            .put("dataDir", data.absolutePath).put("cacheDir", cache.absolutePath).put("libraryDir", library.absolutePath)
            .put("retainedDataDir", File(context.filesDir, "seanime/data").absolutePath)
            .put("processId", Process.myPid()).put("requiresColdRestart", true)
            .put("recoveryPath", recovery.absolutePath).put("outcome", "running")
        fun checkpoint(stage: String) {
            manifest.put("stage", stage)
            File(root, "fixture.json").writeText(manifest.toString(2))
            Log.i("NativeGoFixture", "stage=$stage root=${root.absolutePath}")
        }
        checkpoint("created")
        var scenario: ActivityScenario<MainActivity>? = null
        val client = SeanimeApiClient()
        val repo = SeanimeRepository(client)
        var started = false

        fun verifyIsolation() {
            val status = apiCall { repo.status() }
            assertEquals("Refusing to mutate a server outside the owned fixture", data.canonicalPath,
                File(status.raw.getString("dataDir")).canonicalPath)
            assertTrue("The fixture must use the local simulated account", status.isSimulated && !status.serverHasPassword)
        }
        fun mutate(path: String, body: JSONObject): Any? {
            verifyIsolation()
            return apiCall { client.request("POST", path, body) }
        }
        try {
            // The Go Android host adds <parent of dataDir>/bin to PATH. These links
            // reference this APK's existing executables, without installing anything.
            val bin = File(root, "bin").apply { mkdirs() }
            for (tool in listOf("ffmpeg", "ffprobe")) {
                val bundled = File(context.filesDir, "seanime/bin/$tool")
                check(bundled.isFile && bundled.canExecute())
                Os.symlink(bundled.absolutePath, File(bin, tool).absolutePath)
            }
            Mobile.startServer(data.absolutePath, cache.absolutePath, 43211L)
            started = true
            assertTrue("Isolated Go startup failed: ${Mobile.serverError()}", Mobile.waitForServer(60_000))
            verifyIsolation()
            assertTrue("A fresh fixture must have no saved settings", apiCall { repo.status() }.settings.length() == 0)
            checkpoint("isolated-server-ready")
            verifyIsolation()
            apiCall { repo.completeSetup(library.absolutePath, enableOnline = false, enableTorrent = false) }
            verifyIsolation()
            val emptyFiles = apiCall { repo.request("GET", "/api/v1/library/local-files") } as JSONArray
            assertEquals("Unexpected files indicate a reused process; stop before importing or scanning", 0, emptyFiles.length())

            val mediaId = 1L // A public metadata identifier, never media supplied by a private account.
            checkpoint("public-anilist-metadata-gate")
            try {
                mutate("/api/v1/anilist/list-entry", jsonObject("mediaId" to mediaId, "status" to "PLANNING", "progress" to 0, "type" to "anime"))
            } catch (failure: Throwable) {
                manifest.put("outcome", "blocked-public-metadata").put("gate", "AniList simulated-collection insertion")
                checkpoint("public-anilist-metadata-failed")
                throw AssertionError("PUBLIC_METADATA_GATE: AniList insertion did not succeed; local scan/playback remains unverified", failure)
            }
            val media = apiCall { repo.animeList() }.single { it.id == mediaId }
            val title = media.raw.optJSONObject("title")?.optString("romaji")?.takeIf { it.isNotBlank() } ?: media.title
            val safeTitle = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), " ").trim()
            val video = File(library, "$safeTitle - 01 [NativeFixture].mp4")
            manifest.put("mediaId", mediaId).put("generatedMediaPath", video.absolutePath)
            checkpoint("generating-owned-media")
            generateVideo(File(context.filesDir, "seanime/bin/ffmpeg"), root, video)
            assertTrue(video.isFile && video.length() > 0)

            checkpoint("scan-public-metadata-stage")
            mutate("/api/v1/library/scan", jsonObject("enhanced" to false, "enhanceWithOfflineDatabase" to false,
                "skipLockedFiles" to true, "skipIgnoredFiles" to true))
            val scanned = apiCall { repo.request("GET", "/api/v1/library/local-files") } as JSONArray
            assertEquals("The isolated scan must discover only the owned generated video", 1, scanned.length())
            val local = scanned.getJSONObject(0)
            assertEquals(video.canonicalPath, File(local.getString("path")).canonicalPath)
            assertEquals("Automatic matching must succeed; no silent manual-match fallback", mediaId, local.getLong("mediaId"))
            assertEquals(1, local.getJSONObject("metadata").getInt("episode"))
            // The scanner resolves Android path aliases; use its exact indexed identity for playback and recovery.
            val indexedMediaPath = local.getString("path")
            manifest.put("mediaPath", indexedMediaPath)
            val exported = File(root, "scanned-local-files.json").apply { writeText(scanned.toString(2)) }
            mutate("/api/v1/library/local-files/import", jsonObject("dataFilePath" to exported.absolutePath))
            val imported = apiCall { repo.request("GET", "/api/v1/library/local-files") } as JSONArray
            assertEquals(scanned.toString(), imported.toString())
            checkpoint("scan-import-verified")

            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav-LIBRARY").fetchSemanticsNodes().isNotEmpty() }
            verifyIsolation() // MainActivity must have reused the already-running fixture Go host.
            var ownerApi: SeanimeApiClient? = null
            requireNotNull(scenario).onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("api").apply { isAccessible = true }
                ownerApi = field.get(activity) as SeanimeApiClient
            }
            val playbackApi = requireNotNull(ownerApi)
            fun openThroughGo() {
                verifyIsolation()
                apiCall {
                    playbackApi.awaitEventsReady()
                    playbackApi.request("POST", "/api/v1/directstream/play/localfile", jsonObject("path" to indexedMediaPath, "clientId" to playbackApi.clientId))
                }
            }
            val first = openPausedThroughGo(indexedMediaPath) { openThroughGo() }
            assertEquals(160, first.width)
            assertEquals(90, first.height)
            assertTrue("Playback must come through the actual Go HTTP stream route", first.uri.scheme == "http" && first.uri.host == "127.0.0.1" &&
                first.uri.port == 43211 && first.uri.path == "/api/v1/directstream/stream")
            assertTrue(first.duration in 29_000..31_000)
            if (compose.onAllNodesWithTag("native-player-seek").fetchSemanticsNodes().isEmpty()) key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("native-player-seek").performSemanticsAction(SemanticsActions.RequestFocus)
            verifyIsolation()
            compose.onNodeWithTag("native-player-seek").performKeyInput { pressKey(Key.DirectionRight) }
            val sought = awaitPlayer { it.paused && it.position >= 9_000 }
            verifyIsolation(); key(KeyEvent.KEYCODE_MEDIA_PLAY)
            try {
                // Keep this bounded play/pause sequence free of UI-idle or HTTP
                // waits. Isolation was checked immediately before its first key.
                awaitPlayer { !it.paused && it.position >= sought.position + 250 }
            } finally { key(KeyEvent.KEYCODE_MEDIA_PAUSE) }
            awaitPlayer { it.paused }
            NativeScreenshotEvidence.capture("isolated-go-generated-local-media")
            checkpoint("go-http-native-decode-pause-seek-verified")
            verifyIsolation(); returnToMain()

            var history: JSONObject? = null
            compose.waitUntil(20_000) {
                history = (apiCall { repo.request("GET", "/api/v1/continuity/item/$mediaId") } as JSONObject).optJSONObject("item")
                history?.optString("filepath") == indexedMediaPath && (history?.optDouble("currentTime") ?: 0.0) >= 9.0
            }
            val seconds = requireNotNull(history).getDouble("currentTime")
            manifest.put("continuitySeconds", seconds)
            val resumed = openPausedThroughGo(indexedMediaPath) { openThroughGo() }
            assertTrue("The Go continuity checkpoint must be applied before autoplay", resumed.position >= (seconds * 1000).toLong() - 1_000)
            manifest.put("resumedPositionMs", resumed.position)
            verifyIsolation(); returnToMain()
            manifest.put("outcome", "passed")
            manifest.put("verified", JSONArray(listOf("generated-h264", "real-scan", "scan-export-import", "go-http-native-decoder",
                "pause-play-seek-return", "go-continuity-readback", "native-resume")))
            checkpoint("continuity-resume-verified")
        } catch (failure: Throwable) {
            if (manifest.optString("outcome") == "running") manifest.put("outcome", "failed")
            manifest.put("failedAt", manifest.optString("stage")).put("failureType", failure.javaClass.simpleName)
            checkpoint(manifest.optString("stage") + "-terminal")
            throw failure
        } finally {
            // Stop only this process's fixture host. Preserve every generated file
            // and record for the owner to inspect and clean after force-stop.
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<NativePlayerActivity>().forEach { it.finish() }
            }
            runCatching { scenario?.close() }
            client.close()
            if (started) Mobile.stopServer()
            manifest.put("hostStatusAfterRun", Mobile.serverStatus())
            checkpoint("stopped-awaiting-force-stop-and-reviewed-cleanup")
        }
    }

    private fun returnToMain() {
        key(KeyEvent.KEYCODE_BACK)
        if (compose.onAllNodesWithTag("native-player-presentation").fetchSemanticsNodes().isNotEmpty()) key(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("nav-LIBRARY").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        // Position updates during autoplay can keep Compose busy past the end
        // of this short clip. Observe Media3 directly, then pause before idling.
        if (code != KeyEvent.KEYCODE_MEDIA_PLAY) {
            instrumentation.waitForIdleSync()
            compose.waitForIdle()
        }
    }
    private fun <T> apiCall(block: suspend () -> T): T = runBlocking { withTimeout(90_000) { block() } }

    private data class PlayerEvidence(val ready: Boolean, val rendered: Boolean, val paused: Boolean, val position: Long,
        val duration: Long, val width: Int, val height: Int, val uri: Uri) {
        override fun toString() = "PlayerEvidence(redacted)"
    }

    private fun openPausedThroughGo(mediaPath: String, open: () -> Unit): PlayerEvidence {
        val pausedAfterFrame = CountDownLatch(1)
        val initialAutoplay = AtomicBoolean(false)
        var observedPlayer: ExoPlayer? = null
        var firstFrame = false
        fun isOwnedSource(player: ExoPlayer): Boolean {
            val uri = player.currentMediaItem?.localConfiguration?.uri ?: return false
            val path = runCatching { JSONObject(NativePlaybackBus.playbackInfoJson).optString("streamPath") }.getOrNull()
            return path == mediaPath && uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 43211 &&
                uri.path == "/api/v1/directstream/stream"
        }
        // Register before the HTTP request: a slow response or UI synchronization
        // must not consume the generated clip before its first-frame checkpoint.
        val listener = object : Player.Listener {
            private fun pauseAtRenderedReady() {
                val current = observedPlayer ?: return
                current.videoDecoderCounters?.ensureUpdated()
                val rendered = firstFrame || (current.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
                if (pausedAfterFrame.count == 0L || !isOwnedSource(current) || !rendered || current.playbackState != Player.STATE_READY) return
                initialAutoplay.set(current.playWhenReady)
                current.removeListener(this)
                current.pause()
                pausedAfterFrame.countDown()
            }
            override fun onPlaybackStateChanged(playbackState: Int) = pauseAtRenderedReady()
            override fun onRenderedFirstFrame() { firstFrame = true; pauseAtRenderedReady() }
        }
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val lifecycle = ActivityLifecycleCallback { activity, stage ->
            if (activity is NativePlayerActivity && observedPlayer == null && (stage == Stage.STARTED || stage == Stage.RESUMED)) {
                val current = findPlayerView(activity.window.decorView)?.player as? ExoPlayer
                if (current != null && isOwnedSource(current)) {
                    observedPlayer = current
                    current.addListener(listener)
                    listener.onPlaybackStateChanged(current.playbackState)
                }
            }
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(lifecycle) }
        try {
            open()
            assertTrue("The exact Go-issued fixture did not render a READY frame", pausedAfterFrame.await(30, TimeUnit.SECONDS))
            assertTrue("Go-issued local playback lost its initial autoplay request", initialAutoplay.get())
            return awaitPlayer { it.ready && it.rendered && it.paused }
        } finally {
            instrumentation.runOnMainSync {
                monitor.removeLifecycleCallback(lifecycle)
                observedPlayer?.removeListener(listener)
            }
        }
    }

    private fun awaitPlayer(condition: (PlayerEvidence) -> Boolean): PlayerEvidence {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var state: PlayerEvidence? = null
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<NativePlayerActivity>().singleOrNull()
                val player = activity?.let { findPlayerView(it.window.decorView)?.player } as? ExoPlayer
                if (player != null) {
                    assertNull("Native decoder failed: ${player.playerError?.errorCodeName}", player.playerError)
                    player.videoDecoderCounters?.ensureUpdated()
                    state = PlayerEvidence(player.playbackState == Player.STATE_READY,
                        (player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0, !player.playWhenReady,
                        player.currentPosition, player.duration, player.videoSize.width, player.videoSize.height,
                        player.currentMediaItem?.localConfiguration?.uri ?: Uri.EMPTY)
                }
            }
            state?.let { if (condition(it)) return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Actual Go-issued native playback did not reach the expected observable state")
    }

    private fun generateVideo(ffmpeg: File, root: File, video: File) {
        val raw = File(root, "generated-source.yuv")
        raw.outputStream().use { stream -> repeat(300) { frame ->
            stream.write(ByteArray(160 * 90) { (40 + (frame % 6) * 20).toByte() })
            stream.write(ByteArray(160 * 90 / 2) { 128.toByte() })
        } }
        val output = File(root, "ffmpeg.log")
        val process = ProcessBuilder(ffmpeg.absolutePath, "-v", "error", "-f", "rawvideo", "-pixel_format", "yuv420p",
            "-video_size", "160x90", "-framerate", "10", "-i", raw.absolutePath, "-threads", "1", "-c:v", "libx264",
            "-pix_fmt", "yuv420p", "-an", "-movflags", "+faststart", video.absolutePath)
            .redirectErrorStream(true).redirectOutput(output).start()
        try {
            val deadline = SystemClock.elapsedRealtime() + 60_000
            var result: Int? = null
            while (result == null && SystemClock.elapsedRealtime() < deadline) {
                result = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                if (result == null) SystemClock.sleep(50)
            }
            assertEquals("Bundled encoder failed; inspect this fixture's ffmpeg.log", 0, result)
        } finally { process.destroy() }
    }

    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findPlayerView(view.getChildAt(index))?.let { return it }
        return null
    }
}
