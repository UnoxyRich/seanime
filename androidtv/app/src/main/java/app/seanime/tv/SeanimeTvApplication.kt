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
import java.io.File
import java.util.concurrent.Executors

class SeanimeTvApplication : Application(), Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private val lifecycleExecutor = Executors.newSingleThreadExecutor()
    private var startedActivities = 0

    private val pauseBackgroundWork = Runnable {
        if (startedActivities == 0) {
            lifecycleExecutor.execute { runCatching { Mobile.setAppInForeground(false) } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        installBundledMediaTools()
        Mobile.setAndroidStorageAdapter(AndroidSafStorageAdapter(this))
        registerActivityLifecycleCallbacks(this)
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
        lifecycleExecutor.execute { runCatching { Mobile.setAppInForeground(true) } }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) {
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
