package app.seanime.tv

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
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
import app.seanime.tv.gomobile.mobile.Mobile
import app.seanime.tv.platform.NativePlaybackBus
import app.seanime.tv.platform.NativePlaybackCoordinator
import app.seanime.tv.platform.NativeHostQueue
import app.seanime.tv.platform.NativeExternalPlaybackService
import app.seanime.tv.ui.awaitTvWindowFocus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Separate from AndroidIsolatedLocalPlaybackTest's externally gated scan/matching
 * workflow. This tests the existing library-root-gated GET /mediastream/file route
 * and the real MainActivity coordinator with generated video and no metadata lookup.
 * Normal server startup can still launch background public update work.
 *
 * OPT IN ONLY: invoke this single method in a force-stopped, fresh process with
 * isolatedNativeGoRawMediaFixture=true and freshInstrumentationProcess=true.
 * Preserve any pre-existing recovery and pending recovery outside this test first;
 * this test refuses to run with either present and never deletes retained state.
 *
 * After the run, force-stop before inspecting its native-isolated-go-raw-media-v1
 * fixture.json or cleaning its exact UUID root. Preserve failed evidence. A generated
 * recovery may only be handled after its nested playbackInfoJson.streamPath equals
 * the manifest's exact mediaPath. Restore any prior recovery byte-for-byte, cold-launch
 * normal MainActivity, then verify signed /status dataDir is files/seanime/data.
 * Never switch from fixture data back to retained Go data in this process.
 * The external-player method requires isolatedNativeGoExternalPlayerFixture=true
 * instead, writes kind native-isolated-go-external-player-v1, and must likewise
 * run alone in a cold process. Its receiver is owned test APK code, never a
 * downloaded player, and requests only this fixture's exact generated video.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@OptIn(ExperimentalTestApi::class)
class AndroidIsolatedRawMediaPlaybackTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun generatedVideoUsesSignedGoRangeRouteAndNativePlayerWithoutMetadata() = exerciseOwnedRawMedia(external = false)

    @Test fun nativeMoreHandsOwnedGoVideoToSeparatePlayerAcrossBackgroundAndReturnsPaused() = exerciseOwnedRawMedia(external = true)

    private fun exerciseOwnedRawMedia(external: Boolean) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in only: requires its own cold instrumentation process",
            args.getString(if (external) "isolatedNativeGoExternalPlayerFixture" else "isolatedNativeGoRawMediaFixture") == "true" &&
                args.getString("freshInstrumentationProcess") == "true")
        val context = instrumentation.targetContext
        (context.applicationContext as SeanimeTvApplication).awaitAndroidRuntime()
        assertEquals("A running Go host is not an isolated fresh process", "stopped", Mobile.serverStatus())
        instrumentation.runOnMainSync {
            for (stage in listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)) {
                assertTrue("Run this method alone in a fresh process", ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).isEmpty())
            }
        }
        val recovery = File(context.filesDir, "androidtv-playback-recovery.json")
        val pendingRecovery = File(context.filesDir, "androidtv-playback-recovery.json.tmp")
        assertFalse("Preserve retained recovery outside this test before running", recovery.exists())
        assertFalse("Preserve retained pending recovery outside this test before running", pendingRecovery.exists())
        assertEquals("Retained picture settings must already have Anime4K off", "off",
            context.getSharedPreferences("native-player-settings", 0).getString("anime4k", "off"))

        val root = File(context.filesDir, "native-go-fixture-${UUID.randomUUID()}")
        check(root.parentFile!!.canonicalFile == context.filesDir.canonicalFile && root.mkdir())
        val data = File(root, "data")
        val cache = File(root, "cache")
        val library = File(root, "library").apply { check(mkdir()) }
        val video = File(library, "Generated owned raw video.mp4")
        val playbackId = "raw-media-${UUID.randomUUID()}"
        val manifest = JSONObject().put("kind", if (external) "native-isolated-go-external-player-v1" else "native-isolated-go-raw-media-v1").put("root", root.absolutePath)
            .put("dataDir", data.absolutePath).put("cacheDir", cache.absolutePath).put("libraryDir", library.absolutePath)
            .put("mediaPath", video.absolutePath).put("retainedDataDir", File(context.filesDir, "seanime/data").absolutePath)
            .put("processId", Process.myPid()).put("requiresColdRestart", true).put("recoveryPath", recovery.absolutePath)
            .put("outcome", "running").put("scope", "raw Go media route; scan, matching and list continuity are separate")
        fun checkpoint(stage: String) {
            manifest.put("stage", stage)
            File(root, "fixture.json").writeText(manifest.toString(2))
            Log.i("NativeGoRawFixture", "stage=$stage root=${root.absolutePath}")
        }
        checkpoint("created")
        val client = SeanimeApiClient()
        val repo = SeanimeRepository(client)
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        var scenario: ActivityScenario<MainActivity>? = null
        var started = false
        var expectedUri: Uri? = null
        fun verifyIsolation() {
            val status = apiCall { repo.status() }
            assertEquals("Refusing to act on a server outside the owned fixture", data.canonicalPath, File(status.raw.getString("dataDir")).canonicalPath)
            assertTrue("Only the isolated simulated account is permitted", status.isSimulated && !status.serverHasPassword)
            assertFalse("The real Go status handshake did not issue a client proof", client.snapshotSession().identityProof.isNullOrBlank())
        }
        try {
            val bin = File(root, "bin").apply { check(mkdir()) }
            for (tool in listOf("ffmpeg", "ffprobe")) {
                val bundled = File(context.filesDir, "seanime/bin/$tool")
                check(bundled.isFile && bundled.canExecute())
                Os.symlink(bundled.absolutePath, File(bin, tool).absolutePath)
            }
            Mobile.startServer(data.absolutePath, cache.absolutePath, 43211L)
            started = true
            assertTrue("Isolated Go startup failed: ${Mobile.serverError()}", Mobile.waitForServer(60_000))
            verifyIsolation()
            assertEquals("A fresh fixture must not reuse saved settings", 0, apiCall { repo.status() }.settings.length())
            checkpoint("isolated-server-ready")
            verifyIsolation()
            val setup = SeanimeRepository.setupPayload(library.absolutePath, enableOnline = false, enableTorrent = false).apply {
                getJSONObject("library").put("disableUpdateCheck", true)
            }
            apiCall { client.request("POST", "/api/v1/start", setup) }
            verifyIsolation()
            val configuredLibrary = apiCall { repo.settings() }.getJSONObject("library")
            assertEquals(library.canonicalPath, File(configuredLibrary.getString("libraryPath")).canonicalPath)
            assertEquals("Only the owned library root may be configured", 0, configuredLibrary.optJSONArray("libraryPaths")?.length() ?: 0)
            assertTrue("The isolated setup must suppress unrelated update checks", configuredLibrary.getBoolean("disableUpdateCheck"))
            assertEquals(0, (apiCall { repo.request("GET", "/api/v1/library/local-files") } as JSONArray).length())

            checkpoint("generating-owned-h264")
            generateVideo(File(context.filesDir, "seanime/bin/ffmpeg"), root, video)
            assertTrue(video.isFile && video.length() > 256)
            val source = client.absoluteUrl("/api/v1/mediastream/file").toHttpUrl().newBuilder()
                .addQueryParameter("path", video.absolutePath).build()
            expectedUri = Uri.parse(source.toString())
            verifyIsolation()
            val rangeRequest = Request.Builder().url(source).header("Range", "bytes=0-255")
                .apply { client.requestHeaders().forEach { (name, value) -> header(name, value) } }.build()
            http.newCall(rangeRequest).execute().use { response ->
                assertEquals("The registered raw-media GET must support a real range request", 206, response.code)
                assertEquals("bytes 0-255/${video.length()}", response.header("Content-Range"))
                val expected = video.inputStream().use { it.readBytes().copyOfRange(0, 256) }
                assertArrayEquals("Go returned bytes from a different source", expected, requireNotNull(response.body).bytes())
                manifest.put("rangeStatus", response.code).put("rangeBytes", expected.size)
            }
            // Negative probe is also owned fixture data, never a retained user file.
            val outside = File(root, "outside-library.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val denied = source.newBuilder().setQueryParameter("path", outside.absolutePath).build()
            http.newCall(Request.Builder().url(denied).apply {
                client.requestHeaders().forEach { (name, value) -> header(name, value) }
            }.build()).execute().use { assertEquals("Raw media access escaped the configured library", 404, it.code) }
            checkpoint("signed-go-range-and-library-boundary-verified")

            val mainScenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            scenario = mainScenario
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav-LIBRARY").fetchSemanticsNodes().isNotEmpty() }
            verifyIsolation()
            var owner: NativePlaybackCoordinator? = null
            var ownerApi: SeanimeApiClient? = null
            mainScenario.onActivity { activity ->
                owner = field(activity, "playback") as NativePlaybackCoordinator
                ownerApi = field(activity, "api") as SeanimeApiClient
            }
            val playbackApi = requireNotNull(ownerApi)
            apiCall { playbackApi.awaitEventsReady() }
            assertFalse("MainActivity must hold the real Go-issued signed identity", playbackApi.snapshotSession().identityProof.isNullOrBlank())
            val headers = requireNotNull(NativePlaybackBus.headerProvider).invoke(source.toString())
            assertEquals(playbackApi.clientId, headers["X-Seanime-Client-Id"])
            assertTrue("The media header provider must retain MainActivity's signed identity",
                playbackApi.snapshotSession().identityProof == headers["X-Seanime-Client-Id-Proof"])
            val info = JSONObject().put("id", playbackId).put("streamUrl", source.toString()).put("streamPath", video.absolutePath)
                .put("playbackType", "url").put("streamType", "native").put("subtitleTracks", JSONArray())
                .put("media", JSONObject().put("id", 0).put("title", JSONObject().put("userPreferred", "Generated raw media")))
            verifyIsolation()
            val first = openPausedAfterFrame(requireNotNull(expectedUri), playbackId, video.absolutePath) {
                mainScenario.onActivity { requireNotNull(owner).playPlaybackInfo(info) }
            }
            assertEquals(160, first.width)
            assertEquals(90, first.height)
            assertTrue(first.duration in 29_000..31_000)
            if (compose.onAllNodesWithTag("native-player-seek").fetchSemanticsNodes().isEmpty()) key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("native-player-seek").performSemanticsAction(SemanticsActions.RequestFocus)
            verifyIsolation()
            compose.onNodeWithTag("native-player-seek").performKeyInput { pressKey(Key.DirectionRight) }
            val sought = awaitPlayer(requireNotNull(expectedUri)) { it.paused && it.position >= 9_000 }
            verifyIsolation()
            key(KeyEvent.KEYCODE_MEDIA_PLAY)
            try { awaitPlayer(requireNotNull(expectedUri)) { !it.paused && it.position >= sought.position + 250 } }
            finally { key(KeyEvent.KEYCODE_MEDIA_PAUSE) }
            val paused = awaitPlayer(requireNotNull(expectedUri)) { it.paused }
            manifest.put("pausedPositionMs", paused.position).put("renderedWidth", paused.width).put("renderedHeight", paused.height)
            compose.onNodeWithTag("native-player-previous").assertIsNotEnabled()
            compose.onNodeWithTag("native-player-next").assertIsNotEnabled()
            NativeScreenshotEvidence.capture("isolated-go-raw-route-native-player")
            checkpoint("signed-go-native-frame-pause-seek-verified")
            if (external) {
                verifyExternalPlayback(requireNotNull(expectedUri), video, paused.position, manifest)
                checkpoint("native-external-background-ranges-and-return-verified")
                verifyIsolation()
            }

            var saved: PlaybackRecoverySnapshot? = null
            compose.waitUntil(15_000) {
                saved = PlaybackRecoverySnapshot.read(context.filesDir)
                saved?.mediaUri == source.toString() && saved?.positionMs?.let { it >= 9_000 } == true && saved?.playWhenReady == false
            }
            val snapshot = requireNotNull(saved)
            assertTrue(snapshot.checkpointId.startsWith("native:"))
            val savedInfo = JSONObject(snapshot.playbackInfoJson)
            assertEquals(video.absolutePath, savedInfo.getString("streamPath"))
            assertEquals(playbackId, savedInfo.getString("id"))
            manifest.put("recoverySourceValidated", true)
            verifyIsolation()
            key(KeyEvent.KEYCODE_BACK)
            if (compose.onAllNodesWithTag("native-player-presentation").fetchSemanticsNodes().isNotEmpty()) key(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("nav-LIBRARY").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { !recovery.exists() && !pendingRecovery.exists() }
            assertFalse("Dismissed raw playback remained active", NativePlayerActivity.isVisible())
            verifyIsolation()
            assertEquals("Raw media playback must not inject scanned or matched records", 0,
                (apiCall { repo.request("GET", "/api/v1/library/local-files") } as JSONArray).length())
            manifest.put("outcome", "passed").put("recoveryClearedByPlayer", true)
                .put("verified", JSONArray(listOf("generated-h264", "signed-go-range-get", "library-root-boundary",
                    "main-coordinator-media3-rendered-frame", "remote-play-pause-seek", "owned-recovery-write-and-dismissal")))
            checkpoint("raw-media-route-verified")
        } catch (failure: Throwable) {
            manifest.put("outcome", "failed").put("failedAt", manifest.optString("stage")).put("failureType", failure.javaClass.simpleName)
            checkpoint(manifest.optString("stage") + "-terminal")
            throw failure
        } finally {
            instrumentation.runOnMainSync {
                (context.applicationContext as SeanimeTvApplication).externalPlaybackLease.current?.let {
                    (context.applicationContext as SeanimeTvApplication).endExternalPlayback(it.id)
                }
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<NativePlayerActivity>()
                    .filter { findPlayerView(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri == expectedUri }
                    .forEach { it.finish() }
            }
            runCatching { scenario?.close() }
            client.close()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            if (started) Mobile.stopServer()
            manifest.put("hostStatusAfterRun", Mobile.serverStatus())
            checkpoint("stopped-awaiting-force-stop-and-reviewed-cleanup")
        }
    }

    private fun verifyExternalPlayback(uri: Uri, video: File, pausedPosition: Long, manifest: JSONObject) {
        val context = instrumentation.targetContext
        val app = context.applicationContext as SeanimeTvApplication
        val results = CopyOnWriteArrayList<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra("source") == uri.toString()) results.add(Intent(intent))
            }
        }
        fun command(finish: Boolean = false) = context.sendBroadcast(Intent(TestStreamingExternalPlayerActivity.COMMAND)
            .setPackage(instrumentation.context.packageName).putExtra("source", uri.toString()).putExtra("finish", finish))
        ContextCompat.registerReceiver(context, receiver, IntentFilter(TestStreamingExternalPlayerActivity.RESULT), ContextCompat.RECEIVER_EXPORTED)
        try {
            val candidates = context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*"), PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue("The installed test APK must resolve the actual HTTP video handoff", candidates.any {
                it.activityInfo.packageName == instrumentation.context.packageName &&
                    it.activityInfo.name == TestStreamingExternalPlayerActivity::class.java.name
            })
            // The raw-route exercise leaves the real timeline focused. Reach
            // More and its last choice through the remote, waiting for the
            // exact focused Android window before sending Select.
            awaitNativeFocus("native-player-seek")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            awaitNativeFocus("native-player-play")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            moveNativeFocus("native-player-options", KeyEvent.KEYCODE_DPAD_RIGHT, 3)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitNativeFocus("native-player-choice-speed")
            for (id in listOf("volume", "auto-next", "translate", "screenshot", "external")) {
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                awaitNativeFocus("native-player-choice-$id")
            }
            NativeScreenshotEvidence.capture("isolated-go-external-open-focus")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-player-dialog").fetchSemanticsNodes().isEmpty() }
            waitExternal("Android did not show the owned player in its chooser") {
                if (results.any { it.getStringExtra("type") == "ready" }) return@waitExternal true
                val matches = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Owned test player").orEmpty()
                val target = matches.firstOrNull()
                if (target == null) false else {
                    var clickable: android.view.accessibility.AccessibilityNodeInfo = target
                    while (!clickable.isClickable) clickable = clickable.parent ?: break
                    clickable.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            waitExternal("The separate-process player did not receive the native handoff") { results.any { it.getStringExtra("type") == "ready" } }
            val ready = results.first { it.getStringExtra("type") == "ready" }
            assertNotEquals("Receiver must run outside the Seanime process", Process.myPid(), ready.getIntExtra("pid", -1))
            assertNotEquals("Receiver must not inherit Seanime's app UID or file access", Process.myUid(), ready.getIntExtra("uid", -1))
            waitExternal("Seanime did not enter background during real external playback") {
                var stopped = false
                instrumentation.runOnMainSync {
                    stopped = (field(app, "startedActivities") as Int) == 0 && app.externalPlaybackLease.leftApplication
                }
                stopped
            }
            // Wait through the application's real 900 ms background policy, then
            // drain the serialized Go host queue so ranges exercise that state.
            SystemClock.sleep(1_100)
            instrumentation.runOnMainSync { assertEquals(0, field(app, "startedActivities")) }
            (field(app, "host") as NativeHostQueue).submit { Unit }.get(10, TimeUnit.SECONDS)
            assertEquals("ready", Mobile.serverStatus())
            assertTrue(requireNotNull(app.externalPlaybackLease.current).needsHost)
            @Suppress("DEPRECATION")
            val services = context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(100)
            assertTrue("Loopback streaming needs an active foreground host service", services.any {
                it.service.className == NativeExternalPlaybackService::class.java.name && it.foreground
            })
            command()
            waitExternal("Owned external player did not complete sustained Go range reads") {
                results.firstOrNull { it.getStringExtra("type") == "error" }?.let { error("External receiver failed: ${it.getStringExtra("failure")}") }
                results.count { it.getStringExtra("type") == "range" } == 3
            }
            val ranges = results.filter { it.getStringExtra("type") == "range" }.sortedBy { it.getIntExtra("index", -1) }
            val expected = video.inputStream().use { it.readBytes().copyOfRange(0, 768) }
            ranges.forEachIndexed { index, result ->
                assertEquals(206, result.getIntExtra("status", -1))
                assertEquals("bytes ${index * 256}-${index * 256 + 255}/${video.length()}", result.getStringExtra("contentRange"))
                assertArrayEquals(expected.copyOfRange(index * 256, (index + 1) * 256), android.util.Base64.decode(result.getStringExtra("bytes"), android.util.Base64.DEFAULT))
                assertFalse(result.getBooleanExtra("authorityHeaders", true))
            }
            assertTrue(ranges.last().getLongExtra("elapsed", 0) - ranges.first().getLongExtra("elapsed", 0) >= 2_900)
            manifest.put("externalReceiverDifferentUid", true).put("externalAnonymousGoRanges", 3).put("foregroundHostVerified", true)
            NativeScreenshotEvidence.capture("isolated-go-external-receiver-streaming")
            command(finish = true)
            val returned = awaitPlayer(uri) { it.ready && it.paused }
            assertEquals("Returning must preserve the native pause checkpoint", pausedPosition, returned.position)
            awaitNativeFocus("native-player-options")
            assertNull("External session lease survived native return", app.externalPlaybackLease.current)
            waitExternal("Foreground host service survived native return") {
                @Suppress("DEPRECATION")
                val running = context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(100)
                running.none { it.service.className == NativeExternalPlaybackService::class.java.name }
            }
            NativeScreenshotEvidence.capture("isolated-go-external-return-paused")
            manifest.put("externalReturnPaused", true).put("externalHostReleased", true)
        } finally { command(finish = true); context.unregisterReceiver(receiver) }
    }

    private fun hasNativeFocus(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()

    private fun awaitNativeFocus(tag: String) {
        compose.waitUntil(15_000) { hasNativeFocus(tag) }
        compose.onNodeWithTag(tag).awaitTvWindowFocus(15_000).assertIsDisplayed().assertIsFocused()
    }

    private fun moveNativeFocus(tag: String, direction: Int, maximumPresses: Int) {
        repeat(maximumPresses) {
            if (hasNativeFocus(tag)) { awaitNativeFocus(tag); return }
            key(direction)
        }
        awaitNativeFocus(tag)
    }

    private fun waitExternal(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; SystemClock.sleep(50) }
        assertTrue(message, condition())
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        if (code != KeyEvent.KEYCODE_MEDIA_PLAY) { instrumentation.waitForIdleSync(); compose.waitForIdle() }
    }
    private fun <T> apiCall(block: suspend () -> T): T = runBlocking { withTimeout(90_000) { block() } }
    private fun field(value: Any, name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
    private data class Frame(val ready: Boolean, val rendered: Boolean, val paused: Boolean, val position: Long,
        val duration: Long, val width: Int, val height: Int)

    private fun openPausedAfterFrame(uri: Uri, playbackId: String, mediaPath: String, open: () -> Unit): Frame {
        val pausedAfterFrame = CountDownLatch(1)
        val autoplay = AtomicBoolean(false)
        var observed: ExoPlayer? = null
        var firstFrame = false
        fun owned(player: ExoPlayer): Boolean {
            val info = runCatching { JSONObject(NativePlaybackBus.playbackInfoJson) }.getOrNull()
            return player.currentMediaItem?.localConfiguration?.uri == uri && info?.optString("id") == playbackId && info?.optString("streamPath") == mediaPath
        }
        val listener = object : Player.Listener {
            private fun pauseReadyFrame() {
                val player = observed ?: return
                player.videoDecoderCounters?.ensureUpdated()
                if (pausedAfterFrame.count == 0L || !owned(player) || player.playbackState != Player.STATE_READY ||
                    (!firstFrame && (player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) == 0)) return
                autoplay.set(player.playWhenReady)
                player.removeListener(this)
                player.pause()
                pausedAfterFrame.countDown()
            }
            override fun onPlaybackStateChanged(playbackState: Int) = pauseReadyFrame()
            override fun onRenderedFirstFrame() { firstFrame = true; pauseReadyFrame() }
        }
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val lifecycle = ActivityLifecycleCallback { activity, stage ->
            if (activity is NativePlayerActivity && observed == null && (stage == Stage.STARTED || stage == Stage.RESUMED)) {
                val player = findPlayerView(activity.window.decorView)?.player as? ExoPlayer
                if (player != null && owned(player)) { observed = player; player.addListener(listener); listener.onPlaybackStateChanged(player.playbackState) }
            }
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(lifecycle) }
        try {
            open()
            assertTrue("The owned Go HTTP source did not render a READY frame", pausedAfterFrame.await(30, TimeUnit.SECONDS))
            assertTrue("The coordinator lost initial autoplay", autoplay.get())
            return awaitPlayer(uri) { it.ready && it.rendered && it.paused }
        } finally { instrumentation.runOnMainSync { monitor.removeLifecycleCallback(lifecycle); observed?.removeListener(listener) } }
    }

    private fun awaitPlayer(uri: Uri, condition: (Frame) -> Boolean): Frame {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var frame: Frame? = null
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<NativePlayerActivity>().singleOrNull()
                val player = activity?.let { findPlayerView(it.window.decorView)?.player } as? ExoPlayer
                if (player != null) {
                    assertNull("Native decoder failed: ${player.playerError?.errorCodeName}", player.playerError)
                    assertEquals("Playback escaped its exact owned Go route", uri, player.currentMediaItem?.localConfiguration?.uri)
                    player.videoDecoderCounters?.ensureUpdated()
                    frame = Frame(player.playbackState == Player.STATE_READY, (player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0,
                        !player.playWhenReady, player.currentPosition, player.duration, player.videoSize.width, player.videoSize.height)
                }
            }
            frame?.let { if (condition(it)) return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("The owned raw-media player did not reach the required state")
    }

    private fun generateVideo(ffmpeg: File, root: File, video: File) {
        val raw = File(root, "generated-source.yuv")
        raw.outputStream().use { output -> repeat(300) { frame ->
            output.write(ByteArray(160 * 90) { (40 + (frame % 6) * 20).toByte() })
            output.write(ByteArray(160 * 90 / 2) { 128.toByte() })
        } }
        val process = ProcessBuilder(ffmpeg.absolutePath, "-v", "error", "-f", "rawvideo", "-pixel_format", "yuv420p",
            "-video_size", "160x90", "-framerate", "10", "-i", raw.absolutePath, "-threads", "1", "-c:v", "libx264",
            "-pix_fmt", "yuv420p", "-an", "-movflags", "+faststart", video.absolutePath)
            .redirectErrorStream(true).redirectOutput(File(root, "ffmpeg.log")).start()
        try {
            val deadline = SystemClock.elapsedRealtime() + 60_000
            var result: Int? = null
            while (result == null && SystemClock.elapsedRealtime() < deadline) {
                result = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                if (result == null) SystemClock.sleep(50)
            }
            assertEquals("Bundled H264 encoder failed; inspect the owned ffmpeg.log", 0, result)
        } finally { process.destroy() }
    }

    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findPlayerView(view.getChildAt(index))?.let { return it }
        return null
    }
}
