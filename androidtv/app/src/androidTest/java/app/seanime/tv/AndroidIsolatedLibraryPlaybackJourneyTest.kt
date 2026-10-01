package app.seanime.tv

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import app.seanime.tv.gomobile.mobile.Mobile
import app.seanime.tv.platform.NativePlaybackBus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Run this method ALONE in a force-stopped, fresh process with
 * isolatedNativeGoLibraryPlaybackJourneyFixture=true and freshInstrumentationProcess=true.
 * Preserve recovery and recovery.tmp outside the process first; this refuses either.
 * Only generated owned media is imported through the existing Go API. No scan,
 * public metadata lookup, matched-anime claim, or decoder/player shortcut is used.
 * After setup every UI action is a real D-pad, Select or Back key. Semantics and
 * Media3 state are observed, never used to scroll, focus, play, seek or select tracks.
 *
 * Retain fixture.json and exact UUID root, including on failure. Force-stop before
 * cleanup; validate kind/root/mediaPath before touching any generated recovery.
 * Restore retained recovery byte-for-byte, cold-launch normal MainActivity and
 * verify signed status.dataDir is files/seanime/data. Never switch data in-process.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class AndroidIsolatedLibraryPlaybackJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val keyTrace = JSONArray()

    @Test fun remoteOnlyLibraryFilesAndExplorerPlayOwnedMultitrackVideo() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in only: one cold instrumentation method/process",
            args.getString("isolatedNativeGoLibraryPlaybackJourneyFixture") == "true" &&
                args.getString("freshInstrumentationProcess") == "true")
        val context = instrumentation.targetContext
        (context.applicationContext as SeanimeTvApplication).awaitAndroidRuntime()
        assertEquals("A running Go host is not a fresh isolated process", "stopped", Mobile.serverStatus())
        instrumentation.runOnMainSync {
            listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).forEach {
                assertTrue("Run this method alone in a fresh process", ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it).isEmpty())
            }
        }
        val recovery = File(context.filesDir, "androidtv-playback-recovery.json")
        val pendingRecovery = File(context.filesDir, "androidtv-playback-recovery.json.tmp")
        assertFalse("Preserve retained recovery outside this test", recovery.exists())
        assertFalse("Preserve retained pending recovery outside this test", pendingRecovery.exists())
        assertEquals("Retained Anime4K settings must already be off", "off",
            context.getSharedPreferences("native-player-settings", 0).getString("anime4k", "off"))
        val root = File(context.filesDir, "native-go-fixture-${UUID.randomUUID()}")
        check(root.parentFile!!.canonicalFile == context.filesDir.canonicalFile && root.mkdir())
        val data = File(root, "data")
        val cache = File(root, "cache")
        val library = File(root, "library").apply { check(mkdir()) }
        val video = File(library, "Owned generated multitrack fixture.mkv")
        val indexFile = File(library, "Owned unmatched index.json")
        val manifest = JSONObject().put("kind", "native-isolated-go-library-playback-journey-v1")
            .put("root", root.absolutePath).put("dataDir", data.absolutePath).put("cacheDir", cache.absolutePath)
            .put("libraryDir", library.absolutePath).put("mediaPath", video.absolutePath).put("indexPath", indexFile.absolutePath)
            .put("retainedDataDir", File(context.filesDir, "seanime/data").absolutePath).put("processId", Process.myPid())
            .put("requiresColdRestart", true).put("recoveryPath", recovery.absolutePath).put("outcome", "running")
            .put("scope", "owned generated video; actual MainActivity remote journey; no scan, matching, live anime or speaker-quality claim")
            .put("keyTrace", keyTrace)
        fun checkpoint(stage: String) {
            manifest.put("stage", stage)
            File(root, "fixture.json").writeText(manifest.toString(2))
            Log.i("NativeGoJourneyFixture", "stage=$stage root=${root.absolutePath}")
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
            assertEquals("Refusing to act outside the owned fixture", data.canonicalPath, File(status.raw.getString("dataDir")).canonicalPath)
            assertTrue("Only the isolated simulated account is permitted", status.isSimulated && !status.serverHasPassword)
            assertFalse("A real signed Go identity is required", client.snapshotSession().identityProof.isNullOrBlank())
        }
        fun verifyIndex() {
            verifyIsolation()
            val files = apiCall { repo.request("GET", "/api/v1/library/local-files") } as JSONArray
            assertEquals(1, files.length())
            assertEquals(video.absolutePath, files.getJSONObject(0).getString("path"))
            assertEquals(0L, files.getJSONObject(0).getLong("mediaId"))
        }
        try {
            val bin = File(root, "bin").apply { check(mkdir()) }
            listOf("ffmpeg", "ffprobe").forEach { tool ->
                val bundled = File(context.filesDir, "seanime/bin/$tool")
                check(bundled.isFile && bundled.canExecute())
                Os.symlink(bundled.absolutePath, File(bin, tool).absolutePath)
            }
            Mobile.startServer(data.absolutePath, cache.absolutePath, 43211L)
            started = true
            assertTrue("Isolated Go startup failed: ${Mobile.serverError()}", Mobile.waitForServer(60_000))
            verifyIsolation()
            assertEquals(0, apiCall { repo.status() }.settings.length())
            val setup = SeanimeRepository.setupPayload(library.absolutePath, enableOnline = false, enableTorrent = false).apply {
                getJSONObject("library").put("disableUpdateCheck", true)
                    .put("enableWatchContinuity", true).put("autoPlayNextEpisode", true)
            }
            apiCall { client.request("POST", "/api/v1/start", setup) }
            verifyIsolation()
            val settings = apiCall { repo.settings() }.getJSONObject("library")
            assertEquals(library.canonicalPath, File(settings.getString("libraryPath")).canonicalPath)
            assertEquals(0, settings.optJSONArray("libraryPaths")?.length() ?: 0)
            assertTrue(settings.getBoolean("disableUpdateCheck"))
            assertTrue(settings.getBoolean("enableWatchContinuity"))
            assertTrue(settings.getBoolean("autoPlayNextEpisode"))
            assertEquals(0, (apiCall { repo.request("GET", "/api/v1/library/local-files") } as JSONArray).length())
            checkpoint("generating-owned-color-video-two-audio-tracks-and-srt")
            generateVideo(File(context.filesDir, "seanime/bin/ffmpeg"), root, video)
            assertTrue(video.isFile && video.length() > 768)
            indexFile.writeText(JSONArray().put(JSONObject().put("path", video.absolutePath).put("name", video.name)
                .put("mediaId", 0L).put("locked", false).put("ignored", false)
                .put("parsedInfo", JSONObject().put("original", video.name).put("title", "Owned generated fixture"))
                .put("parsedFolderInfo", JSONArray()).put("metadata", JSONObject().put("episode", 0).put("aniDBEpisode", "").put("type", "main"))).toString(2))
            verifyIsolation()
            assertEquals(true, apiCall { repo.request("POST", "/api/v1/library/local-files/import", JSONObject().put("dataFilePath", indexFile.absolutePath)) })
            verifyIndex()
            val source = client.absoluteUrl("/api/v1/mediastream/file").toHttpUrl().newBuilder().addQueryParameter("path", video.absolutePath).build()
            val uri = Uri.parse(source.toString())
            expectedUri = uri
            http.newCall(Request.Builder().url(source).header("Range", "bytes=0-255").apply {
                client.requestHeaders().forEach { (name, value) -> header(name, value) }
            }.build()).execute().use {
                assertEquals(206, it.code)
                assertEquals("bytes 0-255/${video.length()}", it.header("Content-Range"))
                assertArrayEquals(video.inputStream().use { input -> ByteArray(256).also { bytes -> check(input.read(bytes) == bytes.size) } }, requireNotNull(it.body).bytes())
            }
            val outside = File(root, "outside-library.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            http.newCall(Request.Builder().url(source.newBuilder().setQueryParameter("path", outside.absolutePath).build()).apply {
                client.requestHeaders().forEach { (name, value) -> header(name, value) }
            }.build()).execute().use { assertEquals("The existing Go library-root boundary must remain enforced", 404, it.code) }
            checkpoint("existing-import-signed-range-and-root-boundary-verified")

            scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            awaitFocus("nav-LIBRARY")
            verifyIsolation()
            remote(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus("anime-search-submit")
            moveTo("anime-manage", KeyEvent.KEYCODE_DPAD_RIGHT, 3)
            NativeScreenshotEvidence.capture("owned-go-journey-library-manage")
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitFocus("library-tab-Files")
            awaitEnabled("library-tools-refresh")
            moveTo("library-file-select-${video.absolutePath}", KeyEvent.KEYCODE_DPAD_DOWN, 7)
            moveTo("library-file-play-${video.absolutePath}", KeyEvent.KEYCODE_DPAD_RIGHT, 2)
            NativeScreenshotEvidence.capture("owned-go-journey-files-play-focus")
            checkpoint("remote-library-manage-files-play-focus")
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            val first = awaitPlayer(uri) { it.ready && it.videoBuffers > 0 && it.audioBuffers > 0 && it.audioLanguage.isNotBlank() && !it.paused }
            assertEquals(320, first.width); assertEquals(180, first.height)
            assertTrue(first.duration in 59_000..61_000)
            assertEquals(2, first.audioChoices.size)
            assertEquals(1, first.subtitleChoices.size)
            awaitFocus("native-player-play")
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            val pausedFirst = awaitPlayer(uri) { it.paused }
            val firstPaint = awaitOwnedVideoPixels(pausedFirst.position)
            val info = JSONObject(NativePlaybackBus.playbackInfoJson)
            assertEquals(video.absolutePath, info.getString("streamPath"))
            assertEquals(source.toString(), info.getString("streamUrl"))
            assertEquals("url", info.getString("playbackType"))
            assertEquals(video.name, info.getJSONObject("media").getJSONObject("title").getString("userPreferred"))
            assertEquals(0, info.getJSONObject("media").getInt("id"))
            assertTrue(info.isNull("episode")); assertTrue(info.getBoolean("disableRestoreFromContinuity"))
            val headers = requireNotNull(NativePlaybackBus.headerProvider).invoke(source.toString())
            assertFalse(headers["X-Seanime-Client-Id"].isNullOrBlank())
            assertFalse(headers["X-Seanime-Client-Id-Proof"].isNullOrBlank())
            assertTrue(requireNotNull(NativePlaybackBus.headerProvider).invoke("https://example.invalid/").isEmpty())
            compose.onNodeWithTag("native-player-previous").assertIsNotEnabled()
            compose.onNodeWithTag("native-player-next").assertIsNotEnabled()
            NativeScreenshotEvidence.capture("owned-go-journey-decoded-video")
            remote(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus("native-player-seek")
            remote(KeyEvent.KEYCODE_DPAD_RIGHT)
            val sought = awaitPlayer(uri) { it.paused && it.position >= 9_000 }
            val soughtPaint = awaitOwnedVideoPixels(sought.position)
            assertTrue("The remote seek must visibly paint a different generated frame",
                firstPaint.rgb.zip(soughtPaint.rgb).sumOf { (before, after) -> abs(before - after) } > 40)
            NativeScreenshotEvidence.capture("owned-go-journey-seek-focus")
            manifest.put("seekPositionMs", sought.position).put("videoPaintBeforeSeek", firstPaint.json())
                .put("videoPaintAfterSeek", soughtPaint.json())

            // Seek -> transport -> left edge -> Audio, entirely through remote keys.
            remote(KeyEvent.KEYCODE_DPAD_DOWN)
            focusAudioFromTransport()
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitFocus("native-player-choice-${first.audioSelected}")
            val otherAudio = first.audioChoices.first { it != first.audioSelected }
            moveTo("native-player-choice-$otherAudio", if (first.audioChoices.indexOf(otherAudio) > first.audioChoices.indexOf(first.audioSelected))
                KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP, 2)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitFocus("native-player-audio")
            awaitPlayer(uri) { it.audioSelected == otherAudio }
            // Decode the chosen track by resuming with the focused transport Play.
            focusPlayFromOptions()
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            val played = awaitPlayer(uri) { !it.paused && it.audioSelected == otherAudio && it.audioLanguage.isNotBlank() && it.audioLanguage != first.audioLanguage &&
                it.audioBuffers > 0 && it.position > sought.position + 300 }
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitPlayer(uri) { it.paused }
            manifest.put("selectedAudioLanguage", played.audioLanguage).put("audioDecodedBuffers", played.audioBuffers)
                .put("videoDecodedBuffers", played.videoBuffers).put("audioOutputVerified", "decoder/selected format only; no speaker-quality claim")
            focusAudioFromTransport()
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitFocus("native-player-choice-$otherAudio")
            NativeScreenshotEvidence.capture("owned-go-journey-audio-second-track")
            remote(KeyEvent.KEYCODE_BACK)
            awaitFocus("native-player-audio")
            remote(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus("native-player-subtitles")
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitTag("native-player-dialog")
            val subtitleChoice = "native-player-choice-${first.subtitleChoices.single()}"
            if (!isFocused(subtitleChoice)) moveTo(subtitleChoice, KeyEvent.KEYCODE_DPAD_DOWN, 2)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitFocus("native-player-subtitles")
            focusPlayFromOptions()
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitPlayer(uri) { !it.paused && it.cue.contains("OWNED FIXTURE SUBTITLE") }
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitPlayer(uri) { it.paused && it.cue.contains("OWNED FIXTURE SUBTITLE") }
            remote(KeyEvent.KEYCODE_BACK) // Hide HUD without exiting or changing decoder state.
            awaitMissing("native-player-play")
            val captionPixels = whiteSubtitlePixels()
            assertTrue("The selected embedded SRT must paint visible white cue pixels", captionPixels > 40)
            NativeScreenshotEvidence.capture("owned-go-journey-subtitle-enabled")
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitFocus("native-player-play")
            focusAudioFromTransport()
            remote(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus("native-player-subtitles")
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitFocus(subtitleChoice)
            moveTo("native-player-choice-off", KeyEvent.KEYCODE_DPAD_UP, 2)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitFocus("native-player-subtitles")
            awaitPlayer(uri) { it.subtitlesDisabled && it.cue.isEmpty() }
            remote(KeyEvent.KEYCODE_BACK)
            awaitMissing("native-player-play")
            val noCaptionPixels = whiteSubtitlePixels()
            assertTrue("Turning subtitles off must remove rendered cue pixels", noCaptionPixels < captionPixels / 2)
            NativeScreenshotEvidence.capture("owned-go-journey-subtitle-off")
            manifest.put("subtitleWhitePixelsOn", captionPixels).put("subtitleWhitePixelsOff", noCaptionPixels)
            checkpoint("remote-decoding-seek-audio-and-embedded-cues-verified")

            compose.waitUntil(15_000) {
                PlaybackRecoverySnapshot.read(context.filesDir)?.let {
                    it.checkpointId.startsWith("native:") && it.mediaUri == source.toString() && it.positionMs >= 9_000 && !it.playWhenReady &&
                        JSONObject(it.playbackInfoJson).optString("streamPath") == video.absolutePath
                } == true
            }
            remote(KeyEvent.KEYCODE_BACK)
            awaitFocus("library-file-play-${video.absolutePath}")
            compose.waitUntil(10_000) { !recovery.exists() && !pendingRecovery.exists() }
            assertFalse(NativePlayerActivity.isVisible())
            verifyIndex()
            assertEquals("Unmatched playback must not create anime continuity", 0,
                (apiCall { repo.request("GET", "/api/v1/continuity/history") } as JSONObject).length())
            NativeScreenshotEvidence.capture("owned-go-journey-files-return-focus")

            // Repeat from the Explorer entry point and prove return stays on that file.
            repeat(2) { remote(KeyEvent.KEYCODE_DPAD_LEFT) }
            moveTo("library-tab-Files", KeyEvent.KEYCODE_DPAD_UP, 7)
            remote(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus("library-tab-Explorer")
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitEnabled("library-tools-refresh")
            // Return to the first column before travelling down the folder list.
            remote(KeyEvent.KEYCODE_DPAD_LEFT)
            moveTo("library-folder-open-${library.absolutePath}", KeyEvent.KEYCODE_DPAD_DOWN, 5)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitFocus("library-tools-refresh")
            awaitEnabled("library-tools-refresh")
            moveTo("library-file-select-${video.absolutePath}", KeyEvent.KEYCODE_DPAD_DOWN, 6)
            moveTo("library-file-play-${video.absolutePath}", KeyEvent.KEYCODE_DPAD_RIGHT, 2)
            NativeScreenshotEvidence.capture("owned-go-journey-explorer-play-focus")
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitPlayer(uri) { it.ready && it.videoBuffers > 0 && !it.paused && it.position < 9_000 }
            awaitFocus("native-player-play")
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitPlayer(uri) { it.paused }
            assertNotEquals("A repeated explicit Play must own a fresh playback identity", info.getString("id"), JSONObject(NativePlaybackBus.playbackInfoJson).getString("id"))
            remote(KeyEvent.KEYCODE_BACK)
            awaitMissing("native-player-play")
            remote(KeyEvent.KEYCODE_BACK)
            awaitFocus("library-file-play-${video.absolutePath}")
            compose.waitUntil(10_000) { !recovery.exists() && !pendingRecovery.exists() }
            NativeScreenshotEvidence.capture("owned-go-journey-explorer-return-focus")
            verifyIndex()
            manifest.put("outcome", "passed").put("recoveryClearedByPlayer", true)
                .put("verified", JSONArray(listOf("owned-generated-multitrack-mkv", "existing-index-import", "signed-go-ranges", "library-root-boundary",
                    "remote-library-manage-files-play", "remote-explorer-play", "decoded-video-and-selected-audio", "embedded-subtitle-cue-pixels",
                    "remote-seek-and-pause", "same-file-return-focus", "fresh-playback-identity", "owned-native-recovery")))
            checkpoint("remote-only-owned-library-playback-verified")
        } catch (failure: Throwable) {
            manifest.put("outcome", "failed").put("failedAt", manifest.optString("stage")).put("failureType", failure.javaClass.simpleName)
            checkpoint(manifest.optString("stage") + "-terminal")
            throw failure
        } finally {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<NativePlayerActivity>()
                    .filter { findPlayerView(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri == expectedUri }
                    .forEach { it.finish() }
            }
            runCatching { scenario?.close() }
            client.close(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
            if (started) Mobile.stopServer()
            manifest.put("hostStatusAfterRun", Mobile.serverStatus())
            checkpoint("stopped-awaiting-force-stop-and-reviewed-cleanup")
        }
    }

    private fun focusPlayFromOptions() {
        remote(KeyEvent.KEYCODE_DPAD_UP)
        repeat(3) { remote(KeyEvent.KEYCODE_DPAD_LEFT) }
        moveTo("native-player-play", KeyEvent.KEYCODE_DPAD_RIGHT, 3)
    }

    private fun focusAudioFromTransport() {
        repeat(3) { remote(KeyEvent.KEYCODE_DPAD_LEFT) }
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        moveTo("native-player-audio", KeyEvent.KEYCODE_DPAD_LEFT, 3)
    }

    private fun remote(code: Int) {
        require(code in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK))
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync(); compose.waitForIdle()
        keyTrace.put(JSONObject().put("key", KeyEvent.keyCodeToString(code)).put("focused", focusedTag()))
    }
    private fun focusedTag() = compose.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrElse(SemanticsProperties.TestTag) { "untagged" }.orEmpty()
    private fun isFocused(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any {
        it.config.getOrElse(SemanticsProperties.Focused) { false }
    }
    private fun awaitFocus(tag: String) {
        compose.waitUntil(15_000) { isFocused(tag) }
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsFocused()
    }
    private fun awaitTag(tag: String) = compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitMissing(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    private fun awaitEnabled(tag: String) = compose.waitUntil(30_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) }
    }
    private fun moveTo(tag: String, direction: Int, maxPresses: Int) {
        repeat(maxPresses) { if (isFocused(tag)) return; remote(direction) }
        awaitFocus(tag)
    }
    private fun <T> apiCall(block: suspend () -> T): T = runBlocking { withTimeout(90_000) { block() } }

    private data class Frame(val ready: Boolean, val paused: Boolean, val position: Long, val duration: Long,
        val width: Int, val height: Int, val videoBuffers: Int, val audioBuffers: Int,
        val audioChoices: List<String>, val audioSelected: String, val audioLanguage: String,
        val subtitleChoices: List<String>, val subtitlesDisabled: Boolean, val cue: String)

    private fun awaitPlayer(uri: Uri, condition: (Frame) -> Boolean): Frame {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        var last: Frame? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<NativePlayerActivity>().singleOrNull()
                val player = activity?.let { findPlayerView(it.window.decorView)?.player } as? ExoPlayer
                if (player != null) {
                    assertNull("Native decoder failed: ${player.playerError?.errorCodeName}", player.playerError)
                    assertEquals("Playback escaped its exact owned Go source", uri, player.currentMediaItem?.localConfiguration?.uri)
                    player.videoDecoderCounters?.ensureUpdated(); player.audioDecoderCounters?.ensureUpdated()
                    fun choices(type: Int) = player.currentTracks.groups.filter { it.type == type }.flatMapIndexed { groupIndex, group ->
                        (0 until group.length).filter { group.isTrackSupported(it) }.map { "$groupIndex-$it" }
                    }
                    var selected = ""
                    player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEachIndexed { groupIndex, group ->
                        (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { selected = "$groupIndex-$it" }
                    }
                    last = Frame(player.playbackState == Player.STATE_READY, !player.playWhenReady, player.currentPosition, player.duration,
                        player.videoSize.width, player.videoSize.height, player.videoDecoderCounters?.renderedOutputBufferCount ?: 0,
                        player.audioDecoderCounters?.renderedOutputBufferCount ?: 0, choices(C.TRACK_TYPE_AUDIO), selected, player.audioFormat?.language.orEmpty(),
                        choices(C.TRACK_TYPE_TEXT), C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes,
                        player.currentCues.cues.joinToString("\n") { it.text.toString() })
                }
            }
            last?.let { if (condition(it)) return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Owned remote player state not reached; last=$last")
    }

    private fun whiteSubtitlePixels(): Int {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            var count = 0
            for (y in (bitmap.height * .7).toInt() until (bitmap.height * .96).toInt()) {
                for (x in (bitmap.width * .1).toInt() until (bitmap.width * .9).toInt()) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.red(pixel) > 225 && Color.green(pixel) > 225 && Color.blue(pixel) > 225) count++
                }
            }
            return count
        } finally { bitmap.recycle() }
    }

    private data class VideoPaint(val positionMs: Long, val rgb: List<Int>, val expectedRgb: List<Int>) {
        fun json() = JSONObject().put("positionMs", positionMs).put("meanRgb", JSONArray(rgb))
            .put("expectedRgb", JSONArray(expectedRgb)).put("channelTolerance", 25)
            .put("region", "center 47%-53% width, 40%-46% height; clear of HUD and captions")
    }

    /** Observe actual compositor pixels; no player/surface mutation or synthetic image. */
    private fun awaitOwnedVideoPixels(positionMs: Long): VideoPaint {
        fun expected(at: Long): List<Int> {
            val second = at.coerceIn(0, 59_999) / 1_000
            val y = 70 + (second % 4).toInt() * 15 - 16
            val u = 75 + (second % 3).toInt() * 25 - 128
            val v = 175 - 128
            // FFmpeg's generated yuv420p uses limited-range SD/BT.601 conversion.
            return listOf(1.164 * y + 1.596 * v, 1.164 * y - .392 * u - .813 * v,
                1.164 * y + 2.017 * u).map { it.toInt().coerceIn(0, 255) }
        }
        // A rendered 10 fps frame may trail the pause timestamp by one frame.
        val expectedFrames = listOf(expected(positionMs - 125), expected(positionMs), expected(positionMs + 125))
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var last = emptyList<Int>()
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val sums = LongArray(3)
                var count = 0
                for (y in (bitmap.height * .40).toInt() until (bitmap.height * .46).toInt() step 2) {
                    for (x in (bitmap.width * .47).toInt() until (bitmap.width * .53).toInt() step 2) {
                        val pixel = bitmap.getPixel(x, y)
                        sums[0] += Color.red(pixel); sums[1] += Color.green(pixel); sums[2] += Color.blue(pixel); count++
                    }
                }
                check(count > 0)
                last = sums.map { (it / count).toInt() }
                val expected = expectedFrames.firstOrNull { colors -> last.zip(colors).all { (actual, target) -> abs(actual - target) <= 25 } }
                if (expected != null) return VideoPaint(positionMs, last, expected)
            } finally { bitmap.recycle() }
            SystemClock.sleep(50)
        }
        throw AssertionError("Owned video did not paint its timestamp-derived color at $positionMs ms: actual=$last expected=$expectedFrames")
    }

    private fun generateVideo(ffmpeg: File, root: File, video: File) {
        val raw = File(root, "generated-source.yuv")
        raw.outputStream().use { output -> repeat(600) { frame ->
            output.write(ByteArray(320 * 180) { (70 + frame / 10 % 4 * 15).toByte() })
            output.write(ByteArray(320 * 180 / 4) { (75 + frame / 10 % 3 * 25).toByte() })
            output.write(ByteArray(320 * 180 / 4) { 175.toByte() })
        } }
        val tones = listOf(220, 440).map { hz -> File(root, "owned-$hz.pcm").apply {
            outputStream().buffered().use { output ->
                val second = ByteArray(48_000 * 2)
                repeat(48_000) { sample ->
                    val value = (sin(2 * PI * hz * sample / 48_000) * 5_000).toInt()
                    second[sample * 2] = value.toByte(); second[sample * 2 + 1] = (value shr 8).toByte()
                }
                repeat(60) { output.write(second) }
            }
        } }
        val srt = File(root, "owned-captions.srt").apply {
            writeText("1\n00:00:00,000 --> 00:01:00,000\nOWNED FIXTURE SUBTITLE\n\n")
        }
        val process = ProcessBuilder(listOf(ffmpeg.absolutePath, "-v", "error", "-f", "rawvideo", "-pixel_format", "yuv420p",
            "-video_size", "320x180", "-framerate", "10", "-i", raw.absolutePath) +
            tones.flatMap { listOf("-f", "s16le", "-ar", "48000", "-ac", "1", "-i", it.absolutePath) } +
            listOf("-i", srt.absolutePath, "-map", "0:v:0", "-map", "1:a:0", "-map", "2:a:0", "-map", "3:s:0",
                "-threads", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "pcm_s16le", "-c:s", "srt",
                "-metadata:s:a:0", "title=Owned tone 220 Hz", "-metadata:s:a:0", "language=eng",
                "-metadata:s:a:1", "title=Owned tone 440 Hz", "-metadata:s:a:1", "language=fra",
                "-metadata:s:s:0", "title=Owned fixture captions", "-metadata:s:s:0", "language=eng",
                "-disposition:a:0", "default", "-disposition:a:1", "0", "-disposition:s:0", "default", video.absolutePath))
            .redirectErrorStream(true).redirectOutput(File(root, "ffmpeg.log")).start()
        try {
            val deadline = SystemClock.elapsedRealtime() + 90_000
            var result: Int? = null
            while (result == null && SystemClock.elapsedRealtime() < deadline) {
                result = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                if (result == null) SystemClock.sleep(50)
            }
            assertEquals("Bundled encoder failed; inspect owned ffmpeg.log", 0, result)
        } finally { process.destroy() }
    }

    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findPlayerView(view.getChildAt(index))?.let { return it }
        return null
    }
}
