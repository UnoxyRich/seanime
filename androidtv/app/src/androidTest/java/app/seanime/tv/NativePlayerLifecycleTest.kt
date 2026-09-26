package app.seanime.tv

import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class NativePlayerLifecycleTest {
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
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.native_player_retry).hasFocus())
                assertFalse(requireNotNull(findPlayerView(activity.window.decorView)).isShown)
            }
            writeSilentWav(fixture)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitReady(scenario)
            scenario.onActivity { activity ->
                val playerView = requireNotNull(findPlayerView(activity.window.decorView))
                val player = requireNotNull(playerView.player)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.native_player_error_panel).visibility)
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
    fun failedSourceOffersRemoteReturnToWebPlayer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "native-missing-${System.nanoTime()}.wav")
        val scenario = ActivityScenario.launch<NativePlayerActivity>(
            NativePlayerActivity.intent(context, Uri.fromFile(fixture), "Unavailable source", "[]", 0, "{}"),
        )
        try {
            awaitError(scenario)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.native_player_return).hasFocus())
            }
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
    fun stoppedAndRecreatedPlayerRestoresLatestMediaAndPlaybackSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = File(context.cacheDir, "native-lifecycle-first.wav")
        val next = File(context.cacheDir, "native-lifecycle-next.wav")
        writeSilentWav(first)
        writeSilentWav(next)
        val scenario = ActivityScenario.launch<NativePlayerActivity>(
            NativePlayerActivity.intent(context, Uri.fromFile(first), "First episode", "[]", 3_000, "{}"),
        )
        try {
            awaitReady(scenario)
            var previousPlayer: Player? = null
            scenario.onActivity { activity ->
                val player = requireNotNull(findPlayerView(activity.window.decorView)?.player)
                previousPlayer = player
                player.pause()
                player.seekTo(4_000)
                player.setPlaybackSpeed(1.25f)
                player.volume = 0.35f
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage("ja")
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                    .build()
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse("stopped player still reports itself visible", NativePlayerActivity.isVisible())
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitReady(scenario)
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
            NativePlayerActivity.updateMedia(Uri.fromFile(next).toString(), "Next episode", "[]", 6_000, "{}")
            awaitReady(scenario)
            scenario.onActivity { activity ->
                requireNotNull(findPlayerView(activity.window.decorView)?.player).apply {
                    pause()
                    seekTo(6_000)
                }
            }
            scenario.recreate()
            awaitReady(scenario)
            scenario.onActivity { activity ->
                val player = requireNotNull(findPlayerView(activity.window.decorView)?.player)
                assertEquals(Uri.fromFile(next), player.currentMediaItem?.localConfiguration?.uri)
                assertEquals(6_000L, player.currentPosition)
                assertFalse(player.playWhenReady)
                assertEquals(1.25f, player.playbackParameters.speed, 0.001f)
                assertTrue(NativePlayerActivity.isVisible())
            }
        } finally {
            scenario.close()
            first.delete()
            next.delete()
        }
    }

    private fun awaitReady(scenario: ActivityScenario<NativePlayerActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val player = findPlayerView(activity.window.decorView)?.player
                assertEquals("native player failed to open the fixture", null, player?.playerError)
                ready = player?.playbackState == Player.STATE_READY
            }
            if (ready) return
            SystemClock.sleep(50)
        }
        throw AssertionError("native player did not prepare the local WAV fixture")
    }

    private fun awaitError(scenario: ActivityScenario<NativePlayerActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var displayed = false
            scenario.onActivity { activity ->
                displayed = findPlayerView(activity.window.decorView)?.player?.playerError != null &&
                    activity.findViewById<View>(R.id.native_player_error_panel).isShown
            }
            if (displayed) return
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

    private fun writeSilentWav(file: File) {
        val sampleRate = 8_000
        val audioBytes = sampleRate * 2 * 12
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
