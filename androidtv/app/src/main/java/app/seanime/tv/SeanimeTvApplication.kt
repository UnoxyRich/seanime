package app.seanime.tv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import app.seanime.tv.gomobile.mobile.Mobile
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
        Mobile.setAndroidStorageAdapter(AndroidSafStorageAdapter(this))
        registerActivityLifecycleCallbacks(this)
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
