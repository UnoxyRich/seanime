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
    @Test fun terminatedSourceCannotBeRewrittenAndNeverClearsANewerCheckpoint() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "external-recovery-${System.nanoTime()}").apply { mkdirs() }
        val first = PlaybackRecoverySnapshot("native:first", "https://media.example/a", "owned-process", "Owned A", "[]", "{}",
            1000, false, false, 1f, 1f, 1f, false, "", JSONObject().put("id", "source-a").toString())
        val next = first.copy(checkpointId = "native:next", playbackInfoJson = JSONObject().put("id", "source-b").toString())
        try {
            PlaybackRecoverySnapshot.write(directory, first)
            assertFalse(PlaybackRecoverySnapshot.clearSource(directory, "wrong-id", first.mediaUri, first.checkpointId))
            PlaybackRecoverySnapshot.write(directory, first.copy(positionMs = 2000))
            assertEquals(2000L, PlaybackRecoverySnapshot.read(directory)?.positionMs)
            assertTrue(PlaybackRecoverySnapshot.clearSource(directory, "source-a", first.mediaUri, first.checkpointId))
            PlaybackRecoverySnapshot.write(directory, first) // Previously queued Activity write.
            assertNull(PlaybackRecoverySnapshot.read(directory))
            PlaybackRecoverySnapshot.write(directory, next)
            assertFalse(PlaybackRecoverySnapshot.clearSource(directory, "source-a", first.mediaUri, first.checkpointId))
            assertFalse(PlaybackRecoverySnapshot.clearCheckpoint(directory, first.checkpointId, first.mediaUri))
            PlaybackRecoverySnapshot.write(directory, first)
            assertEquals(next, PlaybackRecoverySnapshot.read(directory))
            // Even a refreshed URL using the same opaque ticket is a different write target.
            val refreshed = first.copy(mediaUri = first.mediaUri + "?refresh=1")
            PlaybackRecoverySnapshot.write(directory, refreshed)
            assertEquals(refreshed, PlaybackRecoverySnapshot.read(directory))
        } finally { directory.deleteRecursively() }
    }

    @Test fun customMediaIdsSurviveOwnedRecoveryDiskAndSourceRefresh() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "playback-long-identity-${System.nanoTime()}").apply { mkdirs() }
        try {
            for (id in listOf(2_147_483_648L, 4_294_967_297L, 9_007_199_254_740_991L)) {
                val source = "https://media.example/$id.mp4"
                val info = JSONObject().put("id", "playback-$id").put("streamUrl", source).put("media", JSONObject().put("id", id))
                    .put("episode", JSONObject().put("baseAnime", JSONObject().put("id", id)).put("episodeNumber", 3).put("progressNumber", 3))
                    .put("onlinestreamParams", JSONObject().put("mediaId", id).put("episodeNumber", 13))
                val original = PlaybackRecoverySnapshot("native:$id", source, "fixture", "Owned identity", "[]", "{}",
                    12_000, false, false, 1f, 1f, 1f, false, "", info.toString())
                PlaybackRecoverySnapshot.write(directory, original)
                val restored = requireNotNull(PlaybackRecoverySnapshot.read(directory)).withStream(source + "?refresh=1", "native:next-$id", "next-process")
                val retained = JSONObject(restored.playbackInfoJson)
                assertEquals(id, retained.getJSONObject("media").getLong("id"))
                assertEquals(id, retained.getJSONObject("episode").getJSONObject("baseAnime").getLong("id"))
                assertEquals(id, retained.getJSONObject("onlinestreamParams").getLong("mediaId"))
                assertEquals("playback-$id", retained.getString("id"))
                assertEquals(3, retained.getJSONObject("episode").getInt("episodeNumber"))
                assertEquals(13, retained.getJSONObject("onlinestreamParams").getInt("episodeNumber"))
                assertFalse(restored.playWhenReady)
            }
        } finally { directory.deleteRecursively() }
    }

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
