package app.seanime.tv

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
            if (marker.isFile && marker.readText() == version && ffmpeg.canExecute() && ffprobe.canExecute()) return

            val nativeDir = File(applicationInfo.nativeLibraryDir)
            copyExecutable(File(nativeDir, "libffmpeg.so"), ffmpeg)
            copyExecutable(File(nativeDir, "libffprobe.so"), ffprobe)
            marker.writeText(version)
            Log.i("SeanimeTV", "Installed Android media tools: $version")
        }.onFailure { error ->
            Log.e("SeanimeTV", "Could not install bundled Android media tools", error)
        }
    }

    private fun copyExecutable(source: File, target: File) {
        check(source.isFile) { "Packaged media tool is missing: ${source.absolutePath}" }
        val temporary = File(target.parentFile, "${target.name}.new")
        source.copyTo(temporary, overwrite = true)
        check(temporary.setExecutable(true, false) || temporary.canExecute()) {
            "Could not make media tool executable: ${temporary.absolutePath}"
        }
        if (target.exists()) check(target.delete()) { "Could not replace media tool: ${target.absolutePath}" }
        check(temporary.renameTo(target)) { "Could not install media tool: ${target.absolutePath}" }
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
