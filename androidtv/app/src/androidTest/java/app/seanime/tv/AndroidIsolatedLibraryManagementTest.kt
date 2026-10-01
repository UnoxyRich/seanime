package app.seanime.tv

import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.data.SeanimeRepository
import app.seanime.tv.gomobile.mobile.Mobile
import app.seanime.tv.ui.performTvClick
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * OPT IN, one cold method/process only:
 * isolatedNativeGoLibraryManagementFixture=true, freshInstrumentationProcess=true.
 * Uses the same isolation boundary as AndroidIsolatedRawMediaPlaybackTest but never
 * starts playback, scans media, matches AniList, injects a database, or changes accounts.
 *
 * The runner must preserve prior recovery/.tmp before running. This test refuses
 * either, retains the exact UUID root on success/failure, and stops Go in finally.
 * After force-stop, inspect kind/root/mediaPath, restore prior recovery byte-for-byte,
 * then cold-launch normal MainActivity and verify signed status.dataDir is files/seanime/data.
 */
class AndroidIsolatedLibraryManagementTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun importedOwnedUnmatchedIndexSupportsNativeBulkRenameExplorerAndDelete() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in only: requires its own cold instrumentation process",
            args.getString("isolatedNativeGoLibraryManagementFixture") == "true" && args.getString("freshInstrumentationProcess") == "true")
        val context = instrumentation.targetContext
        (context.applicationContext as SeanimeTvApplication).awaitAndroidRuntime()
        assertEquals("A running Go host is not an isolated fresh process", "stopped", Mobile.serverStatus())
        instrumentation.runOnMainSync {
            listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).forEach { stage ->
                assertTrue("Run this method alone in a fresh process", ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).isEmpty())
            }
        }
        val recovery = File(context.filesDir, "androidtv-playback-recovery.json")
        val pendingRecovery = File(context.filesDir, "androidtv-playback-recovery.json.tmp")
        assertFalse("Preserve retained recovery outside this test before running", recovery.exists())
        assertFalse("Preserve retained pending recovery outside this test before running", pendingRecovery.exists())
        val root = File(context.filesDir, "native-go-fixture-${UUID.randomUUID()}")
        check(root.parentFile!!.canonicalFile == context.filesDir.canonicalFile && root.mkdir())
        val data = File(root, "data")
        val cache = File(root, "cache")
        val library = File(root, "library").apply { check(mkdir()) }
        val original = File(library, "Original generated video.mp4")
        val first = File(library, "Owned editable copy.mp4")
        val second = File(library, "Owned retained copy.mp4")
        val renamed = File(library, "Renamed owned copy.mp4")
        val indexFile = File(library, "Owned unmatched index.json")
        val ownedPaths = setOf(first.absolutePath, second.absolutePath, renamed.absolutePath)
        val readbacks = JSONArray()
        val manifest = JSONObject().put("kind", "native-isolated-go-library-management-v1").put("root", root.absolutePath)
            .put("dataDir", data.absolutePath).put("cacheDir", cache.absolutePath).put("libraryDir", library.absolutePath)
            .put("mediaPath", original.absolutePath).put("indexPath", indexFile.absolutePath).put("ownedCopyPaths", JSONArray(ownedPaths.toList()))
            .put("retainedDataDir", File(context.filesDir, "seanime/data").absolutePath).put("processId", Process.myPid())
            .put("requiresColdRestart", true).put("recoveryPath", recovery.absolutePath).put("outcome", "running")
            .put("scope", "existing import endpoint; unmatched owned native library actions; no scan or external metadata")
            .put("indexReadbacks", readbacks)
        fun checkpoint(stage: String) {
            manifest.put("stage", stage)
            File(root, "fixture.json").writeText(manifest.toString(2))
            Log.i("NativeGoLibraryFixture", "stage=$stage root=${root.absolutePath}")
        }
        checkpoint("created")
        val client = SeanimeApiClient()
        val repo = SeanimeRepository(client)
        var scenario: ActivityScenario<MainActivity>? = null
        var started = false
        var originalHash: String? = null
        fun verifyIsolation() {
            val status = apiCall { repo.status() }
            assertEquals("Refusing to act on a server outside the owned fixture", data.canonicalPath, File(status.raw.getString("dataDir")).canonicalPath)
            assertTrue("Only the isolated simulated account is permitted", status.isSimulated && !status.serverHasPassword)
            assertFalse("A real Go-issued client proof is required", client.snapshotSession().identityProof.isNullOrBlank())
            originalHash?.let { expected -> assertEquals("The original generated video must remain untouched", expected, digest(original)) }
            assertFalse("Library actions must not create playback recovery", recovery.exists())
            assertFalse("Library actions must not create pending playback recovery", pendingRecovery.exists())
        }
        fun readIndex(stage: String): List<JSONObject> {
            verifyIsolation()
            val values = apiCall { repo.request("GET", "/api/v1/library/local-files") } as JSONArray
            val rows = (0 until values.length()).map(values::getJSONObject)
            rows.forEach { file ->
                assertTrue("Unexpected indexed path outside the generated copies", file.getString("path") in ownedPaths)
                assertEquals("This workflow must remain unmatched", 0L, file.getLong("mediaId"))
            }
            readbacks.put(JSONObject().put("stage", stage).put("files", values))
            return rows
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
            assertEquals("A fresh fixture must not reuse saved settings", 0, apiCall { repo.status() }.settings.length())
            val setup = SeanimeRepository.setupPayload(library.absolutePath, enableOnline = false, enableTorrent = false).apply {
                getJSONObject("library").put("disableUpdateCheck", true)
            }
            verifyIsolation()
            apiCall { client.request("POST", "/api/v1/start", setup) }
            verifyIsolation()
            val settings = apiCall { repo.settings() }.getJSONObject("library")
            assertEquals(library.canonicalPath, File(settings.getString("libraryPath")).canonicalPath)
            assertEquals(0, settings.optJSONArray("libraryPaths")?.length() ?: 0)
            assertTrue(settings.getBoolean("disableUpdateCheck"))
            assertTrue(readIndex("fresh-empty-index").isEmpty())
            checkpoint("isolated-server-and-empty-index-verified")

            generateOwnedVideo(File(context.filesDir, "seanime/bin/ffmpeg"), root, original)
            originalHash = digest(original)
            manifest.put("originalSha256", originalHash)
            verifyIsolation()
            listOf(first, second).forEach { target ->
                assertEquals(library.canonicalFile, target.parentFile!!.canonicalFile)
                check(!target.exists()); original.copyTo(target)
                assertEquals(originalHash, digest(target))
            }
            val records = JSONArray(listOf(first, second).map { file -> JSONObject()
                .put("path", file.absolutePath).put("name", file.name).put("mediaId", 0L).put("locked", false).put("ignored", false)
                .put("parsedInfo", JSONObject().put("original", file.name).put("title", "Owned unmatched fixture"))
                .put("parsedFolderInfo", JSONArray()).put("metadata", JSONObject().put("episode", 0).put("aniDBEpisode", "").put("type", "main")) })
            indexFile.writeText(records.toString(2))
            checkpoint("generated-files-and-index-ready")
            verifyIsolation()
            assertEquals(true, apiCall { repo.request("POST", "/api/v1/library/local-files/import", JSONObject().put("dataFilePath", indexFile.absolutePath)) })
            assertEquals(setOf(first.absolutePath, second.absolutePath), readIndex("imported").map { it.getString("path") }.toSet())
            checkpoint("existing-import-endpoint-verified")

            val main = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            scenario = main
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav-LIBRARY").fetchSemanticsNodes().isNotEmpty() }
            verifyIsolation()
            var hostClient: SeanimeApiClient? = null
            main.onActivity { activity -> hostClient = activity.javaClass.getDeclaredField("api").apply { isAccessible = true }.get(activity) as SeanimeApiClient }
            assertFalse("MainActivity must use a Go-issued client proof", requireNotNull(hostClient).snapshotSession().identityProof.isNullOrBlank())
            compose.onNodeWithText("Manage").performTvClick()
            awaitTag("library-file-select-${first.absolutePath}")
            scrollMain("library-file-select-${first.absolutePath}").performTvClick()
            scrollMain("library-file-select-${second.absolutePath}").performTvClick()
            scrollMain("library-selected-actions").performTvClick()
            compose.onNodeWithTag("library-bulk-actions").performScrollToNode(hasTestTag("library-bulk-ignore"))
            compose.onNodeWithTag("library-bulk-ignore").performTvClick()
            awaitTag("library-bulk-confirmation")
            verifyIsolation()
            compose.onNodeWithTag("library-bulk-apply").performTvClick()
            awaitClosed("library-bulk-dialog")
            awaitLibraryReady()
            val ignored = readIndex("native-bulk-ignore")
            assertEquals(2, ignored.size); assertTrue(ignored.all { it.getBoolean("ignored") && !it.getBoolean("locked") })
            NativeScreenshotEvidence.capture("isolated-go-library-bulk-ignore")
            checkpoint("native-bulk-ignore-and-signed-readback-verified")

            scrollMain("library-file-rename-${first.absolutePath}").performTvClick()
            awaitTag("library-rename-edit")
            compose.onNodeWithTag("library-rename-edit").performScrollTo().performTvClick()
            compose.onNodeWithTag("library-rename-input").performTextReplacement(renamed.name)
            compose.onNodeWithTag("library-rename-input").performImeAction()
            compose.onNodeWithTag("text-entry-save").performTvClick()
            verifyIsolation()
            compose.onNodeWithTag("library-rename-confirm").performTvClick()
            awaitClosed("library-rename-dialog")
            awaitLibraryReady()
            val renamedRows = readIndex("native-rename")
            assertEquals(setOf(renamed.absolutePath, second.absolutePath), renamedRows.map { it.getString("path") }.toSet())
            assertFalse(first.exists()); assertEquals(originalHash, digest(renamed)); assertEquals(originalHash, digest(second))
            assertTrue(renamedRows.single { it.getString("path") == renamed.absolutePath }.getBoolean("ignored"))
            checkpoint("native-rename-index-and-owned-bytes-verified")

            scrollMain("library-tab-Explorer").performTvClick()
            awaitLibraryReady()
            // The explorer's exact tree is also read via the signed Go API; no path is fabricated in its UI.
            verifyIsolation()
            val tree = apiCall { repo.request("GET", "/api/v1/library/explorer/file-tree") } as JSONObject
            fun contains(node: JSONObject, target: String): Boolean = node.optString("path") == target ||
                node.optJSONArray("children")?.let { children -> (0 until children.length()).any { contains(children.getJSONObject(it), target) } } == true
            assertTrue(contains(tree.getJSONObject("root"), renamed.absolutePath))
            assertTrue(contains(tree.getJSONObject("root"), original.absolutePath))
            NativeScreenshotEvidence.capture("isolated-go-library-explorer")
            scrollMain("library-tab-Files").performTvClick()
            awaitLibraryReady()
            scrollMain("library-file-delete-${renamed.absolutePath}").performTvClick()
            awaitTag("file-delete-dialog")
            verifyIsolation()
            assertEquals(originalHash, digest(renamed))
            compose.onNodeWithTag("file-delete-confirm").performTvClick()
            awaitClosed("file-delete-dialog")
            awaitLibraryReady()
            assertEquals(listOf(second.absolutePath), readIndex("native-delete").map { it.getString("path") })
            assertFalse(renamed.exists()); assertFalse(first.exists()); assertEquals(originalHash, digest(second))
            verifyIsolation()
            manifest.put("outcome", "passed").put("originalPreserved", true).put("retainedCopyPreserved", true)
                .put("recoveryAbsent", true).put("verified", JSONArray(listOf("generated-owned-video-copies", "existing-index-import-api",
                    "main-native-library-route", "multi-file-ignore", "native-rename", "explorer-tree", "native-delete", "signed-go-index-readback")))
            NativeScreenshotEvidence.capture("isolated-go-library-delete-verified")
            checkpoint("owned-library-workflow-verified")
        } catch (failure: Throwable) {
            manifest.put("outcome", "failed").put("failedAt", manifest.optString("stage")).put("failureType", failure.javaClass.simpleName)
                .put("failure", failure.message.orEmpty().take(1000))
            checkpoint(manifest.optString("stage") + "-terminal")
            throw failure
        } finally {
            runCatching { scenario?.close() }
            client.close()
            if (started) Mobile.stopServer()
            manifest.put("hostStatusAfterRun", Mobile.serverStatus())
            checkpoint("stopped-awaiting-force-stop-and-reviewed-cleanup")
        }
    }

    private fun scrollMain(tag: String): SemanticsNodeInteraction {
        compose.onAllNodes(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .onLast().performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }
    private fun awaitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitLibraryReady() = compose.waitUntil(30_000) {
        compose.onAllNodes(hasTestTag("library-tools-refresh") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitClosed(tag: String) = compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    private fun <T> apiCall(block: suspend () -> T): T = runBlocking { withTimeout(90_000) { block() } }
    private fun digest(file: File): String {
        check(file.isFile && file.length() in 257L..(8L * 1024 * 1024)) { "Generated media is missing or outside its bounded size" }
        return MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }
    private fun generateOwnedVideo(ffmpeg: File, root: File, target: File) {
        val raw = File(root, "generated-source.yuv")
        raw.outputStream().use { output -> repeat(20) { frame ->
            output.write(ByteArray(160 * 90) { (40 + (frame % 6) * 20).toByte() })
            output.write(ByteArray(160 * 90 / 2) { 128.toByte() })
        } }
        val process = ProcessBuilder(ffmpeg.absolutePath, "-v", "error", "-f", "rawvideo", "-pixel_format", "yuv420p", "-video_size", "160x90",
            "-framerate", "10", "-i", raw.absolutePath, "-threads", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-an", "-movflags", "+faststart", target.absolutePath)
            .redirectErrorStream(true).redirectOutput(File(root, "ffmpeg.log")).start()
        try {
            val deadline = SystemClock.elapsedRealtime() + 30_000
            var result: Int? = null
            while (result == null && SystemClock.elapsedRealtime() < deadline) {
                result = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                if (result == null) SystemClock.sleep(50)
            }
            assertEquals("Bundled encoder failed; inspect the owned ffmpeg.log", 0, result)
        } finally { process.destroy() }
    }
}
