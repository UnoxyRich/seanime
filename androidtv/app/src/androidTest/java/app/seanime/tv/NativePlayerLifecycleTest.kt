package app.seanime.tv

import android.net.Uri
import android.os.SystemClock
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Rule
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.platform.NativePlaybackBus
import app.seanime.tv.platform.NativePlaybackCoordinator
import app.seanime.tv.platform.NativeSkipState
import app.seanime.tv.ui.performTvClick
import app.seanime.tv.ui.awaitTvWindowFocus
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class NativePlayerLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test
    fun dismissingPlaybackDoesNotRecreateItsRecoverySnapshotOnStop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val snapshot = PlaybackRecoverySnapshot(
            checkpointId = "dismissed-playback-test",
            mediaUri = "http://127.0.0.1:43211/api/v1/directstream/stream?id=dismissed-playback-test",
            processSessionId = (context.applicationContext as SeanimeTvApplication).processSessionId,
            title = "Dismissed playback",
            subtitleTracksJson = "[]",
            subtitleStyleJson = "{}",
            positionMs = 1_000,
            playWhenReady = false,
            completed = false,
            speed = 1f,
            pitch = 1f,
            volume = 1f,
            muted = false,
            trackSelection = "",
        )
        PlaybackRecoverySnapshot.clear(context.filesDir)
        PlaybackRecoverySnapshot.write(context.filesDir, snapshot)
        val scenario = ActivityScenario.launch<NativePlayerActivity>(
            NativePlayerActivity.recoveryIntent(context, snapshot),
        )
        try {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            SystemClock.sleep(250)
            if (scenario.state != Lifecycle.State.DESTROYED) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            // Allow the player's single-threaded checkpoint writer to finish
            // the stop callback that used to recreate the cleared snapshot.
            SystemClock.sleep(500)
            assertNull("explicitly dismissed playback must not be restored later", PlaybackRecoverySnapshot.read(context.filesDir))
        } finally {
            scenario.close()
            PlaybackRecoverySnapshot.clear(context.filesDir)
        }
    }

    @Test
    fun failedSourceCanRetryWithRemoteAndKeepPausedPosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "native-retry-${System.nanoTime()}.wav")
        val scenario = ActivityScenario.launch<NativePlayerActivity>(
            NativePlayerActivity.intent(context, Uri.fromFile(fixture), "Unavailable source", "[]", 2_000, "{}",
                """{"speed":1.25,"volume":0.4,"paused":true}"""),
        )
        try {
            awaitError(scenario)
            compose.onNodeWithTag("native-player-retry").assertIsFocused()
            compose.onNodeWithTag("native-player-error-reason").assertTextEquals("The source file couldn’t be found.")
            compose.onNodeWithTag("native-player-error-help").assertTextEquals("Check the source or storage connection, then try again.")
            NativeScreenshotEvidence.capture("player-error-retry-focus")
            scenario.onActivity { activity ->
                assertFalse(requireNotNull(findPlayerView(activity.window.decorView)).isShown)
            }
            writeSilentWav(fixture)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitReady(scenario)
            compose.onNodeWithTag("native-player-error").assertDoesNotExist()
            scenario.onActivity { activity ->
                val playerView = requireNotNull(findPlayerView(activity.window.decorView))
                val player = requireNotNull(playerView.player)
                assertTrue(playerView.isShown)
                assertEquals(2_000L, player.currentPosition)
                assertFalse("retry resumed a paused stream", player.playWhenReady)
                assertEquals(1.25f, player.playbackParameters.speed, 0.001f)
                assertEquals(0.4f, player.volume, 0.001f)
            }
        } finally {
            scenario.close()
            fixture.delete()
        }
    }

    @Test
    fun failedSourceOffersRemoteConvertAndReturnToNativeLibrary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "native-missing-${System.nanoTime()}.wav")
        val scenario = ActivityScenario.launch<NativePlayerActivity>(
            NativePlayerActivity.intent(context, Uri.fromFile(fixture), "Unavailable source", "[]", 0, "{}"),
        )
        try {
            awaitError(scenario)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("native-player-convert").assertIsFocused()
            NativeScreenshotEvidence.capture("player-error-convert-focus")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("native-player-external").assertIsFocused().assertIsDisplayed()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("native-player-exit").assertIsFocused()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.waitForIdleSync()
            // Finishing crosses the system activity manager; an idle app looper
            // does not mean its stop/destroy callbacks have arrived yet.
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            assertFalse(NativePlayerActivity.isVisible())
        } finally {
            scenario.close()
        }
    }

    @Test
    fun nativeCommandsControlOnlyTheMatchingSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "native-controls.wav")
        writeSilentWav(fixture)
        val url = Uri.fromFile(fixture).toString()
        val scenario = ActivityScenario.launch<NativePlayerActivity>(
            NativePlayerActivity.intent(context, Uri.parse(url), "Controls", "[]", 2_000, "{}",
                """{"speed":1.25,"volume":0.4,"muted":true,"paused":true}"""),
        )
        try {
            awaitReady(scenario)
            var pausedPosition = 0L
            scenario.onActivity { activity ->
                val player = requireNotNull(findPlayerView(activity.window.decorView)?.player)
                assertFalse(player.playWhenReady)
                assertEquals(1.25f, player.playbackParameters.speed, 0.001f)
                assertEquals(0f, player.volume, 0.001f)
                NativePlayerActivity.control("file:///previous-episode.wav", "seekTo", 9_000.0)
                assertEquals(2_000L, player.currentPosition)
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false).build()
                NativePlayerActivity.selectMediaCaptionTrack("file:///previous-episode.wav", -1)
                assertFalse("An obsolete source changed caption selection",
                    player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT))
                NativePlayerActivity.selectMediaCaptionTrack(url, -1)
                assertTrue(player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT))
                NativePlayerActivity.control(url, "seekTo", 5_000.0)
                assertEquals(5_000L, player.currentPosition)
                NativePlayerActivity.control(url, "speed", 1.5)
                assertEquals(1.5f, player.playbackParameters.speed, 0.001f)
                NativePlayerActivity.control(url, "speed", Double.MIN_VALUE)
                NativePlayerActivity.control(url, "speed", Double.NaN)
                assertEquals(1.5f, player.playbackParameters.speed, 0.001f)
                NativePlayerActivity.control(url, "muted", 0.0)
                assertEquals(0.4f, player.volume, 0.001f)
                NativePlayerActivity.control(url, "volume", 0.6)
                assertEquals(0.6f, player.volume, 0.001f)
                NativePlayerActivity.control(url, "play", 0.0)
                assertTrue(player.playWhenReady)
                NativePlayerActivity.control(url, "pause", 0.0)
                assertFalse(player.playWhenReady)
                pausedPosition = player.currentPosition
                NativePlayerActivity.updateMedia(url, "Refreshed URL", "[]", -1, "{}")
            }
            awaitReady(scenario)
            scenario.onActivity { activity ->
                val player = requireNotNull(findPlayerView(activity.window.decorView)?.player)
                assertEquals(pausedPosition, player.currentPosition)
                assertFalse("refreshing an episode resumed paused playback", player.playWhenReady)
            }
        } finally {
            scenario.close()
            fixture.delete()
        }
    }

    @Test
    fun serverStatusAndRelativeSeekReadLivePositionInsteadOfCachedProgress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "native-live-commands-${System.nanoTime()}.wav")
        writeSilentWav(fixture)
        val url = Uri.fromFile(fixture).toString()
        val previousListener = NativePlaybackBus.listener
        val api = SeanimeApiClient()
        val socket = RecordingSocket()
        privateField(api, "socket").set(api, socket)
        var coordinator: NativePlaybackCoordinator? = null
        val snapshotCount = AtomicInteger()
        val snapshotOnMain = AtomicBoolean()
        val scenario = ActivityScenario.launch<NativePlayerActivity>(NativePlayerActivity.intent(context,
            Uri.parse(url), "Live commands", "[]", 1000, "{}", """{"paused":true}"""))
        try {
            awaitReady(scenario)
            scenario.onActivity { activity ->
                val current = requireNotNull(findPlayerView(activity.window.decorView)?.player)
                val owner = NativePlaybackCoordinator(activity, api).also { coordinator = it }
                val latest = NativePlaybackCoordinator::class.java.getDeclaredField("latest").apply { isAccessible = true }
                val command = NativePlaybackCoordinator::class.java.getDeclaredMethod("handleCommand", String::class.java, Any::class.java)
                    .apply { isAccessible = true }
                NativePlaybackCoordinator::class.java.getDeclaredField("info").apply { isAccessible = true }
                    .set(owner, JSONObject().put("id", "live-command-fixture").put("streamUrl", url))
                privateField(owner, "expectedPlaybackUrl").set(owner, url)
                privateField(owner, "globalPlaylist").set(owner, true)
                assertTrue("An incomplete global snapshot must not disable valid Next", owner.episodeNavigation(url).next)
                assertFalse("Another source inherited global Next", owner.episodeNavigation("file:///previous-episode.wav").next)
                privateField(owner, "globalPlaylist").set(owner, false)
                assertFalse("Raw media without an episode list exposed Next", owner.episodeNavigation(url).next)
                NativePlaybackBus.listener = object : NativePlaybackBus.Listener {
                    override fun onSnapshot(snapshot: JSONObject) {
                        snapshotCount.incrementAndGet()
                        snapshotOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                        owner.onSnapshot(snapshot)
                    }
                    override fun onPlayerEvent(type: String, payload: JSONObject) = Unit
                    override fun onAction(action: String) = Unit
                }
                fun cached(position: Long, source: String = url) {
                    latest.set(owner, JSONObject().put("url", source).put("positionMs", position).put("paused", true))
                }
                current.seekTo(5000)
                // Deliberately stale progress models the interval between the
                // periodic status ticks; all assertions run in one main turn.
                cached(1000)
                command.invoke(owner, "get-status", null)
                assertEquals(5000L, (latest.get(owner) as JSONObject).getLong("positionMs"))
                cached(1000)
                command.invoke(owner, "seek", 2.0)
                assertEquals(7000L, current.currentPosition)
                cached(1000)
                command.invoke(owner, "seek", -1.5)
                assertEquals(5500L, current.currentPosition)

                cached(700, "file:///obsolete-episode.wav")
                val before = snapshotCount.get()
                command.invoke(owner, "get-status", null)
                command.invoke(owner, "seek", 2.0)
                assertEquals("An obsolete source published the current player's status", before, snapshotCount.get())
                assertEquals(700L, (latest.get(owner) as JSONObject).getLong("positionMs"))
                assertEquals("An obsolete source changed playback", 5500L, current.currentPosition)
                cached(1000)
            }
            // Calls from socket/background threads must still read Media3 on
            // its application looper rather than querying it off-thread.
            snapshotOnMain.set(false)
            val inactive = AtomicBoolean()
            NativePlayerActivity.requestStatus(url) { inactive.set(true) }
            instrumentation.waitForIdleSync()
            assertFalse("A live player incorrectly used inactive fallback", inactive.get())
            assertTrue("Live snapshot was not delivered on the player looper", snapshotOnMain.get())
            scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val owner = requireNotNull(coordinator)
                val finalSnapshot = privateField(owner, "latest").get(owner) as JSONObject
                assertEquals(url, finalSnapshot.getString("url"))
                assertFalse("Release callbacks replaced the stopped source's inactive snapshot", finalSnapshot.getBoolean("active"))
                assertTrue("Stopped playback must retain its paused checkpoint", finalSnapshot.getBoolean("paused"))
                assertEquals(5500L, finalSnapshot.getLong("positionMs"))
                socket.events.clear()
                playerCommand(owner, "get-status")
                assertEquals("A stopped source must emit exactly one requested status reply", 1, socket.events.size)
                val stopped = socket.lastVideoEvent()
                assertEquals("video-status", stopped.getString("type"))
                assertEquals(5.5, stopped.getJSONObject("payload").getDouble("currentTime"), 0.0)
                assertTrue("Stopped source did not report paused", stopped.getJSONObject("payload").getBoolean("paused"))
                val app = context.applicationContext as SeanimeTvApplication
                val snapshot = requireNotNull(PlaybackRecoverySnapshot.fromJson(JSONObject()
                    .put("checkpointId", "failed-native-recovery").put("mediaUri", url)))
                app.claimPlaybackRecovery(snapshot.checkpointId)
                privateField(owner, "recovering").set(owner, snapshot)
                NativePlayerActivity.markLaunchPending()
                NativePlaybackCoordinator::class.java.getDeclaredMethod("reportError", String::class.java).apply { isAccessible = true }
                    .invoke(owner, "Invalid stream URL")
                assertFalse("Failed recovery retained its claim", app.ownsPlaybackRecovery(snapshot.checkpointId))
                assertNull(privateField(owner, "recovering").get(owner))
                assertFalse("Failed launch blocked the next player", NativePlayerActivity.isVisible())
            }
        } finally {
            scenario.close()
            NativePlaybackBus.listener = previousListener
            coordinator?.close()
            api.close()
            fixture.delete()
        }
    }

    @Test
    fun pluginSkipDataDrivesNativeHudAndCaptionQueriesUseExactWireSchema() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "native-plugin-controls-${System.nanoTime()}.wav")
        writeSilentWav(fixture)
        val url = Uri.fromFile(fixture).toString()
        val previousListener = NativePlaybackBus.listener
        val api = SeanimeApiClient()
        val socket = RecordingSocket()
        privateField(api, "socket").set(api, socket)
        var owner: NativePlaybackCoordinator? = null
        val scenario = ActivityScenario.launch<NativePlayerActivity>(NativePlayerActivity.intent(context,
            Uri.parse(url), "Plugin controls", "[]", 1000, "{}", """{"paused":true}"""))
        try {
            awaitReady(scenario)
            scenario.onActivity { activity ->
                val coordinator = NativePlaybackCoordinator(activity, api).also { owner = it }
                NativePlaybackBus.listener = coordinator
                privateField(coordinator, "info").set(coordinator, JSONObject().put("id", "skip-fixture").put("streamUrl", url))
                privateField(coordinator, "expectedPlaybackUrl").set(coordinator, url)
                (privateField(coordinator, "skipState").get(coordinator) as NativeSkipState).reset("skip-fixture")
                playerCommand(coordinator, "set-skip-data", JSONObject("""{"op":{"interval":{"startTime":0.5,"endTime":3}},"ed":{"interval":{"startTime":6,"endTime":9}}}"""))
                socket.events.clear()
                playerCommand(coordinator, "get-skip-data")
                val skip = socket.lastVideoEvent()
                assertEquals("video-skip-data", skip.getString("type"))
                assertEquals(3.0, skip.getJSONObject("payload").getJSONObject("skipData").getJSONObject("op").getJSONObject("interval").getDouble("endTime"), 0.0)
                assertNull("Skip data leaked to another source", coordinator.skipTarget("file:///another-episode.wav", 1000, 12000))

                privateField(coordinator, "trackState").set(coordinator, JSONObject().put("subtitleTrack", 1031).put("subtitleIndex", 2))
                socket.events.clear()
                playerCommand(coordinator, "get-media-caption-track")
                val caption = socket.lastVideoEvent()
                assertEquals("video-media-caption-track", caption.getString("type"))
                assertEquals(2, caption.getJSONObject("payload").getInt("trackIndex"))
                assertFalse(caption.getJSONObject("payload").has("trackNumber"))
                socket.events.clear()
                playerCommand(coordinator, "get-subtitle-track")
                assertEquals("video-subtitle-track", socket.lastVideoEvent().getString("type"))
                assertEquals(1031, socket.lastVideoEvent().getJSONObject("payload").getInt("trackNumber"))
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-player-skip").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-player-skip").assertTextEquals("Skip intro").performTvClick()
            scenario.onActivity {
                val current = requireNotNull(findPlayerView(it.window.decorView)?.player)
                assertEquals(3000L, current.currentPosition)
                assertFalse("Skipping resumed a paused source", current.playWhenReady)
                NativePlayerActivity.control(url, "seekTo", 7000.0)
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Skip ending").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-player-skip").assertTextEquals("Skip ending")
            compose.onNodeWithTag("native-player-play").assertIsFocused()
            scenario.onActivity {
                playerCommand(requireNotNull(owner), "set-skip-data", null)
                socket.events.clear()
                playerCommand(requireNotNull(owner), "get-skip-data")
                assertTrue(socket.lastVideoEvent().getJSONObject("payload").isNull("skipData"))
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-player-skip").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("native-player-play").assertIsFocused()
        } finally {
            scenario.close()
            NativePlaybackBus.listener = previousListener
            owner?.close()
            api.close()
            fixture.delete()
        }
    }

    @Test
    fun stoppedAndRecreatedPlayerRestoresLatestMediaAndPlaybackSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val first = File(context.cacheDir, "native-lifecycle-first.wav")
        val next = File(context.cacheDir, "native-lifecycle-next.wav")
        writeSilentWav(first)
        writeSilentWav(next)
        val firstUri = Uri.fromFile(first)
        val nextUri = Uri.fromFile(next)
        val evidence = NativePlayerLifecycleEvidence(context.cacheDir)
        evidence.begin("initial-autoplay")
        val firstReady = CountDownLatch(1)
        val initialAutoplay = AtomicBoolean(false)
        val initialError = AtomicReference<PlaybackException?>()
        var initialPlayer: Player? = null
        // ActivityScenario.onActivity waits for UI idle. Continuous autoplay
        // updates can prevent idle on a slow emulator even after the clip ends.
        // Observe READY directly, then establish the paused lifecycle checkpoint
        // before asking ActivityScenario to inspect the player.
        val readyListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                initialError.compareAndSet(null, error)
                evidence.record("error", initialPlayer, firstUri)
                firstReady.countDown()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val current = initialPlayer ?: return
                if (playbackState != Player.STATE_READY || firstReady.count == 0L ||
                    current.currentMediaItem?.localConfiguration?.uri != firstUri) return
                initialAutoplay.set(current.playWhenReady)
                current.removeListener(this)
                current.pause()
                current.seekTo(4_000)
                firstReady.countDown()
            }
        }
        val lifecycleMonitor = ActivityLifecycleMonitorRegistry.getInstance()
        val lifecycleCallback = ActivityLifecycleCallback { activity, stage ->
            if (activity is NativePlayerActivity && initialPlayer == null &&
                (stage == Stage.STARTED || stage == Stage.RESUMED)) {
                val current = findPlayerView(activity.window.decorView)?.player
                if (current != null && current.currentMediaItem?.localConfiguration?.uri == firstUri) {
                    initialPlayer = current
                    current.addListener(readyListener)
                    current.playerError?.let(readyListener::onPlayerError)
                    readyListener.onPlaybackStateChanged(current.playbackState)
                }
            }
        }
        instrumentation.runOnMainSync { lifecycleMonitor.addLifecycleCallback(lifecycleCallback) }
        var scenario: ActivityScenario<NativePlayerActivity>? = null
        try {
            scenario = ActivityScenario.launch<NativePlayerActivity>(
                NativePlayerActivity.intent(context, firstUri, "First episode", "[]", 3_000, "{}"),
            )
            assertTrue("No READY callback for the exact autoplay fixture", firstReady.await(10, TimeUnit.SECONDS))
            assertEquals("initial autoplay fixture failed", null, initialError.get())
            assertTrue("initial autoplay was lost", initialAutoplay.get())
            awaitReady(scenario, firstUri, evidence)
            var previousPlayer: Player? = null
            scenario.onActivity { activity ->
                val player = requireNotNull(findPlayerView(activity.window.decorView)?.player)
                previousPlayer = player
                assertFalse(player.playWhenReady)
                assertEquals(4_000L, player.currentPosition)
                player.setPlaybackSpeed(1.25f)
                player.volume = 0.35f
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage("ja")
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                    .build()
            }
            evidence.begin("resume-after-stop")
            scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse("stopped player still reports itself visible", NativePlayerActivity.isVisible())
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitReady(scenario, firstUri, evidence)
            scenario.onActivity { activity ->
                val player = requireNotNull(findPlayerView(activity.window.decorView)?.player)
                assertNotSame("a released decoder was reused", previousPlayer, player)
                assertEquals(4_000L, player.currentPosition)
                assertFalse("paused playback resumed itself", player.playWhenReady)
                assertEquals(1.25f, player.playbackParameters.speed, 0.001f)
                assertEquals(0.35f, player.volume, 0.001f)
                assertEquals(listOf("ja"), player.trackSelectionParameters.preferredAudioLanguages)
                assertTrue(player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT))
            }

            // A playlist handoff changes the current media without replacing
            // the launch intent. Recreation must restore that newer source.
            evidence.begin("paused-media-handoff")
            NativePlayerActivity.updateMedia(nextUri.toString(), "Next episode", "[]", 0, "{}", paused = true)
            awaitReady(scenario, nextUri, evidence)
            scenario.onActivity { activity ->
                val current = requireNotNull(findPlayerView(activity.window.decorView)?.player)
                assertEquals("Explicit zero was replaced by old progress", 0L, current.currentPosition)
                assertFalse("Explicit paused handoff started playback", current.playWhenReady)
            }
            evidence.begin("playing-media-handoff")
            scenario.onActivity { activity ->
                NativePlayerActivity.updateMedia(nextUri.toString(), "Next episode", "[]", 6_000, "{}", paused = false)
                requireNotNull(findPlayerView(activity.window.decorView)?.player).apply {
                    assertTrue("Explicit playing handoff stayed paused", playWhenReady)
                    pause()
                    seekTo(6_000)
                }
            }
            awaitReady(scenario, nextUri, evidence)
            scenario.onActivity { previousPlayer = requireNotNull(findPlayerView(it.window.decorView)?.player) }
            evidence.begin("activity-recreation")
            scenario.recreate()
            awaitReady(scenario, nextUri, evidence)
            scenario.onActivity { activity ->
                val player = requireNotNull(findPlayerView(activity.window.decorView)?.player)
                assertNotSame("recreation reused the released decoder", previousPlayer, player)
                assertEquals(nextUri, player.currentMediaItem?.localConfiguration?.uri)
                assertEquals(6_000L, player.currentPosition)
                assertFalse(player.playWhenReady)
                assertEquals(1.25f, player.playbackParameters.speed, 0.001f)
                assertEquals(0.35f, player.volume, 0.001f)
                assertEquals(listOf("ja"), player.trackSelectionParameters.preferredAudioLanguages)
                assertTrue(player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT))
                assertTrue(NativePlayerActivity.isVisible())
            }
            evidence.finish("passed")
        } catch (error: Throwable) {
            evidence.finish("failed")
            throw error
        } finally {
            instrumentation.runOnMainSync {
                lifecycleMonitor.removeLifecycleCallback(lifecycleCallback)
                initialPlayer?.removeListener(readyListener)
            }
            scenario?.close()
            first.delete()
            next.delete()
        }
    }

    @Test
    fun freshComposeControlsKeepMediaKeysAndBackNavigationIndependent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "native-tv-focus-${System.nanoTime()}.wav")
        writeSilentWav(fixture)
        ActivityScenario.launch<NativePlayerActivity>(NativePlayerActivity.intent(context, Uri.fromFile(fixture),
            "Remote focus", "[]", 1000, "{}", """{"paused":true}""")).use { scenario ->
            try {
                awaitReady(scenario)
                awaitHudFocus(scenario, "native-player-play")
                NativeScreenshotEvidence.capture("player-fresh-hud-play-focus")
                scenario.onActivity { assertFalse(requireNotNull(findPlayerView(it.window.decorView)).useController) }
                // Disabled Previous keeps repeated Left on Rewind. The options
                // row remains reachable with Down, then Left from Subtitles.
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
                awaitHudFocus(scenario, "native-player-rewind")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
                awaitHudFocus(scenario, "native-player-rewind")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                awaitHudFocus(scenario, "native-player-subtitles")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
                awaitHudFocus(scenario, "native-player-audio")
                NativeScreenshotEvidence.capture("player-fresh-hud-audio-focus")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.onNodeWithTag("native-player-dialog").assertIsDisplayed()
                compose.onNodeWithTag("native-player-choice-0-0").awaitTvWindowFocus().assertIsFocused()
                NativeScreenshotEvidence.capture("player-fresh-audio-dialog")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY)
                scenario.onActivity { assertTrue(requireNotNull(findPlayerView(it.window.decorView)?.player).playWhenReady) }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                awaitTrackDialogClosed(scenario, "native-player-audio")
                // Reopening creates another dialog window and must transfer
                // focus again without disturbing the remembered HUD control.
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.onNodeWithTag("native-player-choice-0-0").awaitTvWindowFocus().assertIsFocused()
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                awaitTrackDialogClosed(scenario, "native-player-audio")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-player-play").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("native-player-play").assertDoesNotExist()
                scenario.onActivity { assertFalse(requireNotNull(findPlayerView(it.window.decorView)?.player).playWhenReady) }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitHudFocus(scenario, "native-player-audio")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-player-play").fetchSemanticsNodes().isEmpty() }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
                assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            } finally { fixture.delete() }
        }
    }

    private fun awaitTrackDialogClosed(scenario: ActivityScenario<NativePlayerActivity>, tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-player-dialog").fetchSemanticsNodes().isEmpty() }
        awaitHudFocus(scenario, tag)
    }

    private fun awaitHudFocus(scenario: ActivityScenario<NativePlayerActivity>, tag: String) {
        compose.waitUntil(5_000) {
            var windowFocused = false
            scenario.onActivity { windowFocused = it.window.decorView.hasWindowFocus() }
            windowFocused && compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsFocused()
    }

    private fun awaitReady(
        scenario: ActivityScenario<NativePlayerActivity>,
        expectedUri: Uri? = null,
        evidence: NativePlayerLifecycleEvidence? = null,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var lastState = "player absent"
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val player = findPlayerView(activity.window.decorView)?.player
                val matches = expectedUri == null || player?.currentMediaItem?.localConfiguration?.uri == expectedUri
                // Preserve the rapid READY-to-lifecycle transition: an added dwell
                // could hide a real renderer race. Only the exact source may advance.
                ready = player?.playbackState == Player.STATE_READY && matches && player.playerError == null
                evidence?.record(if (player?.playerError != null) "error" else if (ready) "ready" else "waiting",
                    player, expectedUri)
                assertEquals("native player failed to open the fixture", null, player?.playerError)
                lastState = player?.let { "state=${it.playbackState}, position=${it.currentPosition}, duration=${it.duration}, playWhenReady=${it.playWhenReady}, mediaMatches=$matches" }
                    ?: "player absent"
            }
            if (ready) return
            SystemClock.sleep(50)
        }
        evidence?.timeout()
        throw AssertionError("native player did not prepare the local WAV fixture: $lastState")
    }

    private fun awaitError(scenario: ActivityScenario<NativePlayerActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var displayed = false
            scenario.onActivity { activity ->
                displayed = findPlayerView(activity.window.decorView)?.player?.playerError != null
            }
            if (displayed) { compose.onNodeWithTag("native-player-error").assertIsDisplayed(); return }
            SystemClock.sleep(50)
        }
        throw AssertionError("native player did not show recovery controls for a missing source")
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

    private fun privateField(value: Any, name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun playerCommand(owner: NativePlaybackCoordinator, action: String, value: Any? = null) {
        NativePlaybackCoordinator::class.java.getDeclaredMethod("handleCommand", String::class.java, Any::class.java)
            .apply { isAccessible = true }.invoke(owner, action, value)
    }

    private class RecordingSocket : WebSocket {
        val events = mutableListOf<JSONObject>()
        override fun request(): Request = Request.Builder().url("http://127.0.0.1/").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { events.add(JSONObject(text)); return true }
        override fun send(bytes: ByteString): Boolean = false
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
        fun lastVideoEvent(): JSONObject = events.last().getJSONObject("payload")
    }

    private fun writeSilentWav(file: File, seconds: Int = 12) {
        val sampleRate = 8_000
        val audioBytes = sampleRate * 2 * seconds
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + audioBytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(audioBytes).array()
        file.outputStream().use { output ->
            output.write(header)
            output.write(ByteArray(audioBytes))
        }
    }
}
