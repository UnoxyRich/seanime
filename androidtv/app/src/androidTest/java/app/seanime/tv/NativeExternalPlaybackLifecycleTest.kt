package app.seanime.tv

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.DocumentsContract
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.platform.NativePlaybackCoordinator
import app.seanime.tv.platform.NativePlaybackBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.net.URLEncoder

class NativeExternalPlaybackLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun exactDocumentGrantFollowsTheLeaseAndRetainedTreeAccessSurvivesEveryRelease() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as SeanimeTvApplication
        val tree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root")
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val readWrite = read or write
        val receiverPackage = instrumentation.context.packageName
        val receiverUid = context.packageManager.getApplicationInfo(receiverPackage, 0).uid
        val existing = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == tree }
        val previousFlags = (if (existing?.isReadPermission == true) read else 0) or (if (existing?.isWritePermission == true) write else 0)
        val addedFlags = readWrite and previousFlags.inv()
        TestDocumentsProvider.grantTree(context, tree, readWrite or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        context.contentResolver.takePersistableUriPermission(tree, readWrite)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        val one = requireNotNull(DocumentsContract.createDocument(context.contentResolver, root, "video/mp4", "external-lease-one-${System.nanoTime()}.mp4"))
        val two = requireNotNull(DocumentsContract.createDocument(context.contentResolver, root, "video/mp4", "external-lease-two-${System.nanoTime()}.mp4"))
        fun allowed(uri: android.net.Uri) = context.checkUriPermission(uri, -1, receiverUid, read) == PackageManager.PERMISSION_GRANTED
        try {
            compose.setContent { }
            assertNull(app.externalPlaybackLease.current)
            context.grantUriPermission(receiverPackage, one, read)
            var first = ""
            instrumentation.runOnMainSync { first = app.beginExternalPlayback("a", "url-a", false, 1, one.toString()).id }
            assertTrue(allowed(one))
            context.grantUriPermission(receiverPackage, two, read)
            var next = ""
            instrumentation.runOnMainSync { next = app.beginExternalPlayback("b", "url-b", false, 2, two.toString()).id }
            assertFalse("Source replacement retained the old document grant", allowed(one))
            assertTrue(allowed(two))
            instrumentation.runOnMainSync { app.endExternalPlayback(first) }
            assertTrue("A stale callback revoked the replacement document", allowed(two))
            instrumentation.runOnMainSync { app.endExternalPlayback(next) }
            assertFalse(allowed(two))
            for (reason in listOf("failed-launch", "task-removed", "return")) {
                context.grantUriPermission(receiverPackage, one, read)
                instrumentation.runOnMainSync {
                    val ticket = app.beginExternalPlayback(reason, "url-$reason", false, 3, one.toString())
                    app.endExternalPlayback(ticket.id)
                }
                assertFalse("$reason retained the temporary document grant", allowed(one))
            }
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission })
            context.contentResolver.openFileDescriptor(one, "r")!!.close()
            context.contentResolver.openFileDescriptor(two, "r")!!.close()
        } finally {
            instrumentation.runOnMainSync { app.externalPlaybackLease.current?.let { app.endExternalPlayback(it.id) } }
            context.revokeUriPermission(one, read); context.revokeUriPermission(two, read)
            DocumentsContract.deleteDocument(context.contentResolver, one); DocumentsContract.deleteDocument(context.contentResolver, two)
            if (addedFlags != 0) {
                context.contentResolver.releasePersistableUriPermission(tree, addedFlags)
                context.revokeUriPermission(tree, addedFlags)
            }
        }
    }

    @Test fun remoteTerminationReleasesOnlyItsCurrentSourceWithoutALiveMedia3Player() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as SeanimeTvApplication
        val recoveryRoot = File(instrumentation.targetContext.cacheDir, "external-termination-${UUID.randomUUID()}").apply { mkdirs() }
        val recovery = PlaybackRecoverySnapshot("native:owned-termination", "url-current", app.processSessionId, "Owned", "[]", "{}", 3000, false, false,
            1f, 1f, 1f, false, "", JSONObject().put("id", "current").toString())
        compose.setContent { }
        SeanimeApiClient().use { api ->
            var coordinator: NativePlaybackCoordinator? = null
            instrumentation.runOnMainSync {
                assertNull(NativePlayerActivity.activeActivity())
                coordinator = NativePlaybackCoordinator(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single(), api, recoveryFilesDir = recoveryRoot)
            }
            val owner = requireNotNull(coordinator)
            fun state(id: String, url: String, generation: Int) {
                field(owner, "info").set(owner, JSONObject().put("id", id).put("streamUrl", url))
                field(owner, "expectedPlaybackUrl").set(owner, url)
                field(owner, "latest").set(owner, JSONObject().put("url", url).put("paused", true).put("checkpointId", recovery.checkpointId))
                field(owner, "launchGeneration").setInt(owner, generation)
            }
            fun terminate() = NativePlaybackCoordinator::class.java.getDeclaredMethod("handleCommand", String::class.java, Any::class.java)
                .apply { isAccessible = true }.invoke(owner, "terminate", null)
            try {
                instrumentation.runOnMainSync {
                    val ticket = app.beginExternalPlayback("current", "url-current", true, 7)
                    PlaybackRecoverySnapshot.write(recoveryRoot, recovery)
                    state("stale", "url-current", 7); terminate()
                    assertEquals(ticket, app.externalPlaybackLease.current)
                    assertEquals(recovery, PlaybackRecoverySnapshot.read(recoveryRoot))
                    state("current", "url-current", 6); terminate()
                    assertEquals(ticket, app.externalPlaybackLease.current)
                    state("current", "url-current", 7); terminate()
                    assertNull(app.externalPlaybackLease.current)
                    assertNull("A reclaimed Activity's recovery survived remote termination", PlaybackRecoverySnapshot.read(recoveryRoot))
                    PlaybackRecoverySnapshot.write(recoveryRoot, recovery)
                    assertNull("A queued old-source write revived terminated recovery", PlaybackRecoverySnapshot.read(recoveryRoot))
                    assertTrue((field(owner, "latest").get(owner) as JSONObject).getBoolean("closed"))
                    val next = app.beginExternalPlayback("next", "url-next", false, 8)
                    assertFalse(app.endExternalPlaybackForSource("next", "url-next", 8, app.currentServerOwner() + 1))
                    assertEquals(next, app.externalPlaybackLease.current)
                    state("current", "url-current", 7); terminate()
                    assertEquals(next, app.externalPlaybackLease.current)
                }
            } finally {
                instrumentation.runOnMainSync { owner.close(); app.externalPlaybackLease.current?.let { app.endExternalPlayback(it.id) } }
                recoveryRoot.deleteRecursively()
            }
        }
    }

    @Test fun aRealPendingRangeProbeIsCancelledOnStopAndCannotLaunchOnResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as SeanimeTvApplication
        // The final real stop exercises checkpoint deletion. The runner must
        // preserve retained recovery first; this fixture never removes it.
        assumeFalse("Preserve retained recovery before this stopped-source fixture", File(context.filesDir, "androidtv-playback-recovery.json").exists() ||
            File(context.filesDir, "androidtv-playback-recovery.json.tmp").exists())
        val started = CountDownLatch(1)
        val response = CountDownLatch(1)
        val previousListener = NativePlaybackBus.listener
        val previousInfo = NativePlaybackBus.playbackInfoJson
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Range") == "bytes=0-0") {
                        started.countDown()
                        response.await(15, TimeUnit.SECONDS)
                        return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/100").setBody("x")
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
                val fixtureId = UUID.randomUUID().toString()
                val ownedRoot = File(context.cacheDir, "native-external-probe-$fixtureId").apply { mkdirs() }
                val media = File(ownedRoot, "probe-source.mp4").apply { writeBytes(byteArrayOf(0)) }
                val playbackId = "native-external-probe-$fixtureId"
                val url = api.baseUrl + "/api/v1/mediastream/file?path=" + URLEncoder.encode(media.canonicalPath, "UTF-8")
                val manifestFile = File(ownedRoot, "manifest.json")
                val manifest = JSONObject().put("kind", "native-external-lifecycle-probe-v1").put("fixtureId", fixtureId)
                    .put("method", "aRealPendingRangeProbeIsCancelledOnStopAndCannotLaunchOnResume").put("mediaUri", url).put("mediaPath", media.canonicalPath)
                    .put("streamPath", media.canonicalPath).put("playbackId", playbackId).put("outcome", "started")
                manifestFile.writeText(manifest.toString())
                NativePlaybackBus.playbackInfoJson = ""
                val scenario = ActivityScenario.launch<NativePlayerActivity>(NativePlayerActivity.intent(context, Uri.parse(url), "Owned delayed range", "[]", 0, "{}", """{"paused":true}"""))
                var coordinator: NativePlaybackCoordinator? = null
                var job: Job? = null
                var call: Call? = null
                try {
                    scenario.onActivity { activity ->
                        val owner = NativePlaybackCoordinator(activity, api)
                        coordinator = owner
                        val playbackInfo = JSONObject().put("id", playbackId).put("streamUrl", url).put("streamPath", media.canonicalPath).put("playbackType", "localfile")
                        field(owner, "info").set(owner, playbackInfo)
                        NativePlaybackBus.playbackInfoJson = playbackInfo.toString()
                        field(owner, "expectedPlaybackUrl").set(owner, url)
                        NativePlaybackBus.listener = owner
                        NativePlayerActivity::class.java.getDeclaredMethod("openInAnotherPlayer").apply { isAccessible = true }.invoke(activity)
                        job = field(activity, "externalLaunchJob").get(activity) as Job
                    }
                    assertTrue("The production external preparation never made its range probe", started.await(10, TimeUnit.SECONDS))
                    scenario.onActivity { activity -> call = field(activity, "externalProbeCall").get(activity) as Call }
                    scenario.moveToState(Lifecycle.State.CREATED)
                    assertTrue("Stop did not cancel the network probe", requireNotNull(call).isCanceled())
                    response.countDown()
                    runBlocking { withTimeout(10_000) { requireNotNull(job).join() } }
                    assertTrue(requireNotNull(job).isCancelled)
                    assertNull(app.externalPlaybackLease.current)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    scenario.onActivity { activity ->
                        assertTrue(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                        assertNull(app.externalPlaybackLease.current)
                    }
                    // A stopped decoder still has a source Activity that remote stop must close.
                    scenario.moveToState(Lifecycle.State.CREATED)
                    instrumentation.runOnMainSync { NativePlayerActivity.control("different-source", "stop", 0.0) }
                    assertEquals(Lifecycle.State.CREATED, scenario.state)
                    instrumentation.runOnMainSync { NativePlayerActivity.control(url, "stop", 0.0) }
                    compose.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
                    manifestFile.writeText(manifest.put("outcome", "passed").toString())
                } finally {
                    response.countDown(); scenario.close()
                    instrumentation.runOnMainSync { coordinator?.close(); app.externalPlaybackLease.current?.let { app.endExternalPlayback(it.id) } }
                    NativePlaybackBus.listener = previousListener; NativePlaybackBus.playbackInfoJson = previousInfo
                }
            }
        }
    }

    private fun field(value: Any, name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }
}
