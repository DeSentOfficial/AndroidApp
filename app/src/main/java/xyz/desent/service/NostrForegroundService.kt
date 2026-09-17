package xyz.desent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import xyz.desent.DeSentApplication
import xyz.desent.R

/**
 * An opt-in foreground service that keeps the app process (and therefore the
 * relay WebSockets) alive while DeSent is backgrounded. Without it the OS may
 * kill the process within minutes, cutting off email / follower
 * notifications; with it, the process is foreground-priority and survives far
 * longer.
 *
 * The trade-off is a persistent, low-importance "DeSent is connected"
 * notification (an Android requirement for foreground services) and some
 * additional battery use. Toggled by the user via Settings
 * ("Keep running in background") — see [BackgroundServiceController].
 *
 * The service does NOT own the WebSockets (they live in the app container);
 * it only holds foreground priority and ensures connections are open.
 */
class NostrForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Foreground service started")
        scope.launch {
            val app = DeSentApplication.appContainer
            runCatching { app.relayRepository.connectToPersistentRelays() }
                .onFailure { Log.w(TAG, "connectToPersistentRelays failed: ${it.message}") }
            // Re-open the active account's subscriptions so events keep flowing.
            runCatching { app.nostrRepository.subscribeToGiftWraps() }
            runCatching { app.nostrRepository.subscribeToOwnPrivateStorage() }
            runCatching { app.nostrRepository.subscribeToOwnMailboxConfig() }
            runCatching { app.nostrRepository.subscribeToOwnUserSettings() }
            runCatching { app.nostrRepository.subscribeToOwnCalendar() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Foreground service stopped")
        scope.cancel()
    }

    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DeSent")
            .setContentText("Listening for email...")
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.desent_amber))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp())
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun openApp(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, xyz.desent.MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps DeSent connected in the background"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "NostrForegroundService"
        private const val CHANNEL_ID = "background_service"
        private const val NOTIFICATION_ID = 1
    }
}
