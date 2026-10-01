package app.seanime.tv.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import app.seanime.tv.SeanimeTvApplication
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Keeps the existing loopback media host alive during an explicitly selected external playback. */
class NativeExternalPlaybackService : Service() {
    private var ticketId: String? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("lease").orEmpty()
        val app = application as SeanimeTvApplication
        val ticket = app.externalPlaybackLease.current
        if (intent?.action == STOP) {
            app.endExternalPlayback(id)
            if (app.externalPlaybackLease.current == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (ticket == null || ticket.id != id || !ticket.needsHost) {
            pending.remove(id)?.takeIf { it.isActive }?.resumeWithException(IllegalStateException("The selected source changed"))
            // An obsolete start must not leave a newly created service waiting
            // for its foreground deadline. Preserve an already-running newer lease.
            if (ticket == null || !ticket.needsHost || ticketId != ticket.id) stopSelf(startId)
            return START_NOT_STICKY
        }
        ticketId = id
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "External playback", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(STOP)
                .setData(android.net.Uri.parse("seanime-external-session:$id")).putExtra("lease", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
            val notification = builder.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Playing in another app")
                .setContentText("Seanime is serving the selected video. Return to Seanime to resume here.")
                .setOngoing(true).setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Done", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIFICATION, notification)
            pending.remove(id)?.takeIf { it.isActive }?.resume(Unit)
        } catch (error: Exception) {
            pending.remove(id)?.takeIf { it.isActive }?.resumeWithException(IllegalStateException("Android could not keep this video available to another player", error))
            app.endExternalPlayback(id)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        ticketId?.let { (application as SeanimeTvApplication).endExternalPlayback(it) }
        stopSelf()
    }

    override fun onDestroy() {
        val id = ticketId
        if (id != null) {
            pending.remove(id)?.takeIf { it.isActive }?.resumeWithException(IllegalStateException("External playback hosting stopped"))
            (application as SeanimeTvApplication).endExternalPlayback(id)
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "external-playback"
        private const val NOTIFICATION = 203
        private const val STOP = "app.seanime.tv.STOP_EXTERNAL_HOST"
        private val pending = ConcurrentHashMap<String, CancellableContinuation<Unit>>()
        internal suspend fun start(context: Context, ticket: NativeExternalHostLease.Ticket): Unit = withTimeout(10_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                pending[ticket.id] = continuation
                continuation.invokeOnCancellation { pending.remove(ticket.id, continuation) }
                try {
                    ContextCompat.startForegroundService(context, Intent(context, NativeExternalPlaybackService::class.java).putExtra("lease", ticket.id))
                } catch (error: Exception) {
                    pending.remove(ticket.id, continuation)
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    }
}
