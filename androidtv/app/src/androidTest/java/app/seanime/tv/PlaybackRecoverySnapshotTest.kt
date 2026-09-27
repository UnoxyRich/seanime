package app.seanime.tv

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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

            PlaybackRecoverySnapshot.clear(directory)
            assertNull(PlaybackRecoverySnapshot.read(directory))
            assertFalse(File(directory, "androidtv-playback-recovery.json").exists())
            assertTrue(directory.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
