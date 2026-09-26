package app.seanime.tv

import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
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
