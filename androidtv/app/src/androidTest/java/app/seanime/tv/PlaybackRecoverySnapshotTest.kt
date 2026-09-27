package app.seanime.tv

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoverySnapshotTest {
    @Test
    fun persistedSnapshotRetainsDecoderStateAndTrackPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "playback-recovery-${System.nanoTime()}").apply { mkdirs() }
        val trackPreferences = Bundle().apply {
            putString("preferredAudioLanguage", "ja")
            putString("preferredTextLanguage", "en")
            putInt("disabledTrackType", 4)
        }
        val snapshot = PlaybackRecoverySnapshot(
            checkpointId = "opaque-checkpoint",
            mediaUri = "http://127.0.0.1:43211/api/v1/directstream/stream?id=old",
            processSessionId = "previous-process",
            title = "Episode 1",
            subtitleTracksJson = "[]",
            subtitleStyleJson = "{}",
            positionMs = 125_000,
            playWhenReady = false,
            completed = false,
            speed = 1.25f,
            pitch = 1f,
            volume = 0.4f,
            muted = true,
            trackSelection = PlaybackRecoverySnapshot.encodeBundle(trackPreferences),
        )

        try {
            PlaybackRecoverySnapshot.write(directory, snapshot)
            val restored = PlaybackRecoverySnapshot.read(directory)
            assertNotNull(restored)
            assertEquals(snapshot, restored)
            val decodedTracks = PlaybackRecoverySnapshot.decodeBundle(requireNotNull(restored).trackSelection)
            assertNotNull(decodedTracks)
            assertEquals("ja", decodedTracks?.getString("preferredAudioLanguage"))
            assertEquals("en", decodedTracks?.getString("preferredTextLanguage"))
            assertEquals(4, decodedTracks?.getInt("disabledTrackType"))

            val bridgePayload = JSONObject(requireNotNull(restored).toBridgeJson())
            assertEquals("opaque-checkpoint", bridgePayload.getString("checkpointId"))
            assertEquals("only the opaque ticket should cross the WebView bridge", 1, bridgePayload.length())
            assertFalse(bridgePayload.has("mediaUri"))
            assertFalse(bridgePayload.has("subtitleTracksJson"))
            assertFalse(bridgePayload.has("trackSelection"))

            PlaybackRecoverySnapshot.clear(directory)
            assertNull(PlaybackRecoverySnapshot.read(directory))
            assertFalse(File(directory, "androidtv-playback-recovery.json").exists())
            assertTrue(directory.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun freshMainActivityDiscoversPersistedPlaybackRecoveryTicket() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = PlaybackRecoverySnapshot.read(context.filesDir)
        val staleProcessId = "previous-process-for-cold-launch-test"
        val snapshot = PlaybackRecoverySnapshot(
            checkpointId = "cold-launch-opaque-ticket",
            mediaUri = "http://127.0.0.1:43211/api/v1/directstream/stream?id=source-secret",
            processSessionId = staleProcessId,
            title = "Persisted episode",
            subtitleTracksJson = "[]",
            subtitleStyleJson = "{}",
            positionMs = 12_000,
            playWhenReady = false,
            completed = false,
            speed = 1f,
            pitch = 1f,
            volume = 1f,
            muted = false,
            trackSelection = "",
        )
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            PlaybackRecoverySnapshot.write(context.filesDir, snapshot)
            val launchedScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = launchedScenario
            var pendingJson = ""
            launchedScenario.onActivity { activity ->
                assertFalse(
                    "the test snapshot should represent a previous app process",
                    (context.applicationContext as SeanimeTvApplication).processSessionId == staleProcessId,
                )
                pendingJson = activity.pendingPlaybackRecoveryJson()
            }

            val bridgePayload = JSONObject(pendingJson)
            assertEquals(snapshot.checkpointId, bridgePayload.getString("checkpointId"))
            assertEquals("only the opaque checkpoint ID crosses the bridge", 1, bridgePayload.length())
            assertFalse(bridgePayload.has("mediaUri"))
            assertFalse(bridgePayload.has("subtitleTracksJson"))
        } finally {
            scenario?.close()
            if (previous == null) {
                PlaybackRecoverySnapshot.clear(context.filesDir)
            } else {
                PlaybackRecoverySnapshot.write(context.filesDir, previous)
            }
        }
    }
}
