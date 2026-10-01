package app.seanime.tv

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import app.seanime.tv.gomobile.mobile.Mobile
import app.seanime.tv.data.SeanimeApiClient
import app.seanime.tv.platform.NativeHostQueue
import app.seanime.tv.platform.NativeExternalHostLease
import app.seanime.tv.platform.NativeExternalPlaybackService
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

class SeanimeTvApplication : Application(), Application.ActivityLifecycleCallbacks {
    val processSessionId: String = UUID.randomUUID().toString()
    @Volatile private var playbackRecoveryTicket: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var host: NativeHostQueue
    private val foregroundRevision = AtomicLong()
    private var startedActivities = 0
    @Volatile private var serverOwner = 0L
    private var deferredServerStop: Long? = null
    internal val externalPlaybackLease = NativeExternalHostLease { ticket ->
        ticket.grantedDocument?.let { document ->
            // Revoke only our outgoing, exact-document read grant. Persisted tree
            // access belongs to the user and is never released here.
            runCatching { revokeUriPermission(android.net.Uri.parse(document), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onFailure { Log.w("SeanimeTV", "Could not revoke external document access", it) }
        }
    }
    @Volatile private var nativeSession: SeanimeApiClient.SessionSnapshot? = null

    /** Process memory only: Activity recreation must not change the server's playlist owner. */
    fun restoreNativeSession(client: SeanimeApiClient) {
        nativeSession?.let(client::restoreSession)
    }

    fun saveNativeSession(client: SeanimeApiClient) {
        nativeSession = client.snapshotSession()
    }

    @Synchronized
    fun claimPlaybackRecovery(ticket: String) {
        if (ticket.isNotBlank()) playbackRecoveryTicket = ticket
    }

    @Synchronized
    fun ownsPlaybackRecovery(ticket: String): Boolean = ticket.isNotBlank() && playbackRecoveryTicket == ticket

    @Synchronized
    fun releasePlaybackRecovery(ticket: String) {
        if (playbackRecoveryTicket == ticket) playbackRecoveryTicket = ""
    }

    private val pauseBackgroundWork = Runnable {
        if (startedActivities == 0) {
            postForeground(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        installBundledMediaTools()
        // The first gomobile call loads and initializes the Go shared library.
        // On slower TV devices this can exceed the main-thread startup deadline.
        // Keep it ordered ahead of lifecycle work, without blocking app drawing.
        host = NativeHostQueue {
            try {
                Mobile.setAndroidStorageAdapter(AndroidSafStorageAdapter(this))
            } catch (error: Throwable) {
                Log.e("SeanimeTV", "Android runtime initialization failed", error)
                throw error
            }
        }
        registerActivityLifecycleCallbacks(this)
    }

    /** Call from the server startup worker, before any operation can use SAF. */
    fun awaitAndroidRuntime() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Android runtime readiness must be awaited off the main thread" }
        host.awaitInitialized()
    }

    fun claimServerOwner(): Long = host.claimOwner().also { serverOwner = it }
    internal fun currentServerOwner(): Long = serverOwner

    fun startEmbeddedServer(owner: Long, dataPath: String, cachePath: String): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Server startup must run off the main thread" }
        return host.runForOwner(owner) {
            Mobile.startServer(dataPath, cachePath, 43211L)
            check(Mobile.waitForServer(60_000)) { Mobile.serverError().ifBlank { "Server startup timed out. Your files and settings are safe; try again." } }
        }.get()
    }

    fun stopEmbeddedServer(owner: Long) {
        if (externalPlaybackLease.protects(owner)) { deferredServerStop = owner; return }
        host.stopForOwner(owner) { Mobile.setAppInForeground(false); Mobile.stopServer() }
    }

    internal fun beginExternalPlayback(playbackId: String, sourceUrl: String, needsHost: Boolean, sourceGeneration: Int = 0,
        grantedDocument: String? = null, expectedServerOwner: Long = serverOwner): NativeExternalHostLease.Ticket {
        check(expectedServerOwner == serverOwner) { "The server changed. Reopen the source before choosing another player." }
        externalPlaybackLease.current?.let { endExternalPlayback(it.id) }
        val ticket = NativeExternalHostLease.Ticket(UUID.randomUUID().toString(), playbackId, sourceUrl, serverOwner, needsHost, sourceGeneration, grantedDocument)
        externalPlaybackLease.acquire(ticket)
        return ticket
    }

    internal fun endExternalPlaybackForSource(playbackId: String, sourceUrl: String, sourceGeneration: Int, expectedServerOwner: Long): Boolean {
        if (expectedServerOwner != serverOwner) return false
        val ticket = externalPlaybackLease.matchingSource(playbackId, sourceUrl, sourceGeneration, expectedServerOwner) ?: return false
        endExternalPlayback(ticket.id)
        return true
    }

    internal fun endExternalPlayback(id: String) {
        val released = externalPlaybackLease.release(id) ?: return
        if (released.needsHost) stopService(android.content.Intent(this, NativeExternalPlaybackService::class.java))
        val pending = deferredServerStop
        deferredServerStop = null
        if (startedActivities == 0 && pending != null && pending == released.serverOwner && pending == serverOwner) {
            host.stopForOwner(pending) { Mobile.setAppInForeground(false); Mobile.stopServer() }
        }
    }

    private fun postForeground(foreground: Boolean) {
        val revision = foregroundRevision.incrementAndGet()
        host.submit {
            if (foregroundRevision.get() == revision) Mobile.setAppInForeground(foreground)
        }
    }

    private fun installBundledMediaTools() {
        val supportedAbi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: return
        runCatching {
            val version = assets.open("ffmpeg/$supportedAbi/version").bufferedReader().use { it.readText().trim() }
            val binaryDir = File(filesDir, "seanime/bin").apply { mkdirs() }
            val marker = File(binaryDir, "ffmpeg-version")
            val ffmpeg = File(binaryDir, "ffmpeg")
            val ffprobe = File(binaryDir, "ffprobe")
            val nativeDir = File(applicationInfo.nativeLibraryDir)
            val packagedFfmpeg = File(nativeDir, "libffmpeg.so")
            val packagedFfprobe = File(nativeDir, "libffprobe.so")
            if (marker.isFile && marker.readText() == version &&
                ffmpeg.canExecute() && ffmpeg.canonicalFile == packagedFfmpeg.canonicalFile &&
                ffprobe.canExecute() && ffprobe.canonicalFile == packagedFfprobe.canonicalFile) return

            linkExecutable(packagedFfmpeg, ffmpeg)
            linkExecutable(packagedFfprobe, ffprobe)
            marker.writeText(version)
            Log.i("SeanimeTV", "Installed Android media tools: $version")
        }.onFailure { error ->
            Log.e("SeanimeTV", "Could not install bundled Android media tools", error)
        }
    }

    private fun linkExecutable(source: File, target: File) {
        check(source.isFile && source.canExecute()) { "Packaged media tool is missing or not executable: ${source.absolutePath}" }
        // Android 10+ prohibits executing binaries copied to writable app data.
        // Keep the executable inode in the APK's extracted native-library
        // directory and expose only its familiar command name on Go's PATH.
        val temporary = File(target.parentFile, "${target.name}.new")
        temporary.delete()
        Os.symlink(source.absolutePath, temporary.absolutePath)
        Os.rename(temporary.absolutePath, target.absolutePath)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities += 1
        handler.removeCallbacks(pauseBackgroundWork)
        postForeground(true)
        if (externalPlaybackLease.leftApplication) externalPlaybackLease.current?.let { endExternalPlayback(it.id) }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) {
            externalPlaybackLease.markBackground()
            handler.removeCallbacks(pauseBackgroundWork)
            handler.postDelayed(pauseBackgroundWork, 900)
        }
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
