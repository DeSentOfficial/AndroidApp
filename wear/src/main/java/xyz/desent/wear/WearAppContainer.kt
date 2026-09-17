package xyz.desent.wear

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import xyz.desent.data.wearsync.WearBunkerCodec
import xyz.desent.data.wearsync.WearBunkerDecision
import xyz.desent.data.wearsync.WearBunkerPrefs
import xyz.desent.data.wearsync.WearBunkerRequest
import xyz.desent.data.wearsync.WearCalendar
import xyz.desent.data.wearsync.WearCalendarEvent
import xyz.desent.data.wearsync.WearEmail
import xyz.desent.data.wearsync.WearInbox
import xyz.desent.data.wearsync.WearInboxPrefs
import xyz.desent.data.wearsync.WearInboxPrefsCodec
import xyz.desent.data.wearsync.WearSyncPaths
import xyz.desent.wear.data.WearBunkerStore
import xyz.desent.wear.data.WearCalendarStore
import xyz.desent.wear.data.WearConfigStore
import xyz.desent.wear.data.WearInboxStore
import xyz.desent.wear.ui.theme.WearThemeMode

/** Manual DI container for the wear app (mirrors the phone's AppContainer). */
class WearAppContainer(private val appContext: Application) {

    companion object {
        private const val TAG = "WearAppContainer"
    private const val INBOX_CHANNEL_ID = "desent_inbox_v2"
    private const val INBOX_NOTIFICATION_ID = 1001
    private const val CALENDAR_CHANNEL_ID = "desent_calendar_v2"
    private const val CALENDAR_NOTIFICATION_ID = 1002
    private const val BUNKER_CHANNEL_ID = "desent_bunker_v2"
    private const val BUNKER_NOTIFICATION_ID = 1003

    // Pre-sound channel ids: channel settings are frozen at creation, so the
    // chime required new ids; the old ones are deleted on channel (re)create.
    private val LEGACY_CHANNEL_IDS = listOf("desent_inbox", "desent_calendar")

        /** Intent extra routing the launch to a screen (e.g. "bunker"). */
        const val EXTRA_NAVIGATE = "navigate"

        @Volatile
        private var instance: WearAppContainer? = null

        fun getInstance(context: Application): WearAppContainer =
            instance ?: synchronized(this) {
                instance ?: WearAppContainer(context).also { instance = it }
            }
    }

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val configStore: WearConfigStore by lazy { WearConfigStore(appContext) }
    val inboxStore: WearInboxStore by lazy { WearInboxStore(appContext) }
    val calendarStore: WearCalendarStore by lazy { WearCalendarStore(appContext) }
    val bunkerStore: WearBunkerStore by lazy { WearBunkerStore(appContext) }

    private val _themeMode = MutableStateFlow(WearThemeMode.DARK)

    /** Theme synced from the phone's preference (DARK until a push says otherwise). */
    val themeMode: StateFlow<WearThemeMode> = _themeMode.asStateFlow()

    private val _inbox = MutableStateFlow<WearInbox?>(null)

    /** Inbox snapshot synced from the phone (null until the first push/loaded store). */
    val inbox: StateFlow<WearInbox?> = _inbox.asStateFlow()

    private val _calendar = MutableStateFlow<WearCalendar?>(null)

    /** Calendar snapshot synced from the phone (null until the first push/loaded store). */
    val calendar: StateFlow<WearCalendar?> = _calendar.asStateFlow()

    private val _bunkerRequest = MutableStateFlow<WearBunkerRequest?>(null)

    /**
     * Bunker prompt state synced from the phone. `requestId == null` (or the
     * whole payload null before the first push) means nothing is pending.
     * The watch never signs — decisions go back as messages and the phone
     * holds the keys.
     */
    val bunkerRequest: StateFlow<WearBunkerRequest?> = _bunkerRequest.asStateFlow()

    private val _initiallyLoaded = MutableStateFlow(false)

    /**
     * False until the first (background) read of the persisted stores has
     * completed. The UI gates on this to avoid flashing the "set up on
     * phone" empty-state while preferences are still opening.
     */
    val initiallyLoaded: StateFlow<Boolean> = _initiallyLoaded.asStateFlow()

    init {
        applicationScope.launch {
            try {
                reloadConfig()
                reloadInbox()
                reloadCalendar()
                reloadBunker()
            } catch (e: Exception) {
                Log.w(TAG, "Initial store load failed: ${e.message}")
            } finally {
                _initiallyLoaded.value = true
            }
        }
    }

    /** Re-read the stored config (called after a Data Layer push). */
    fun reloadConfig() {
        val fresh = configStore.get()
        fresh?.let { _themeMode.value = WearThemeMode.fromSynced(it.themeMode) }
    }

    /** Re-read the stored inbox (called after a Data Layer push). */
    fun reloadInbox() {
        _inbox.value = inboxStore.get()
    }

    /** Re-read the stored calendar (called after a Data Layer push). */
    fun reloadCalendar() {
        _calendar.value = calendarStore.get()
    }

    /**
     * Store a calendar push and reactively expose it. Captures the prior
     * snapshot first so [notifyNewCalendarEvents] can distinguish genuinely
     * new events from edits of known ones.
     */
    fun applyCalendarPush(calendar: WearCalendar) {
        val prior = _calendar.value ?: calendarStore.get()
        calendarStore.save(calendar)
        _calendar.value = calendar
        notifyNewCalendarEvents(prior, calendar)
    }

    /** Re-read the stored bunker state (startup + after a Data Layer push). */
    fun reloadBunker() {
        _bunkerRequest.value = bunkerStore.get()
    }

    /** Drop the stored bunker state entirely (DataItem deleted from the phone). */
    fun clearBunker() {
        bunkerStore.clear()
        _bunkerRequest.value = null
        cancelBunkerRequestNotification()
    }

    /**
     * Store a bunker push and reactively expose it. A fresh pending request
     * (id not yet notified) buzzes; a cleared payload cancels the notification.
     */
    fun applyBunkerPush(request: WearBunkerRequest) {
        bunkerStore.save(request)
        _bunkerRequest.value = request
        val requestId = request.requestId
        if (requestId != null) {
            if (requestId != bunkerStore.getLastNotifiedRequestId()) {
                postBunkerRequestNotification(request)
                bunkerStore.setLastNotifiedRequestId(requestId)
            }
        } else {
            cancelBunkerRequestNotification()
        }
    }

    /** Ask the phone to push the current bunker state. Best-effort. */
    fun requestBunkerFromPhone() {
        sendMessageToPhone(WearSyncPaths.REQUEST_BUNKER_PATH, ByteArray(0))
    }

    /**
     * Send the user's accept/deny to the phone, which resolves the pending
     * NIP-46 prompt and signs. Fire-and-forget; the phone's re-push clears
     * this screen.
     */
    fun sendBunkerDecision(accept: Boolean, alwaysAllow: Boolean, requestId: String) {
        val decision = WearBunkerDecision(requestId = requestId, accept = accept, alwaysAllow = alwaysAllow)
        sendMessageToPhone(WearSyncPaths.BUNKER_DECISION_PATH, WearBunkerCodec.encodeDecision(decision))
    }

    /**
     * Ask the phone to flip the bunker sync toggle. The phone persists it and
     * re-pushes the bunker state, whose [WearBunkerRequest.bunkerEnabled]
     * field is the single source of truth the UI renders.
     */
    fun setBunkerEnabled(enabled: Boolean) {
        sendMessageToPhone(WearSyncPaths.BUNKER_PREFS_PATH, WearBunkerCodec.encodePrefs(WearBunkerPrefs(enabled)))
    }

    /** Ask the phone to push a fresh config. Best-effort, fire-and-forget. */
    fun requestConfigFromPhone() {
        sendMessageToPhone(WearSyncPaths.REQUEST_CONFIG_PATH, ByteArray(0))
    }

    /** Ask the phone to push a fresh inbox snapshot. Best-effort, fire-and-forget. */
    fun requestInboxFromPhone() {
        sendMessageToPhone(WearSyncPaths.REQUEST_INBOX_PATH, ByteArray(0))
    }

    /** Ask the phone to push a fresh calendar snapshot. Best-effort, fire-and-forget. */
    fun requestCalendarFromPhone() {
        sendMessageToPhone(WearSyncPaths.REQUEST_CALENDAR_PATH, ByteArray(0))
    }

    /**
     * Ask the phone to flip the spam sync toggle. The phone persists it and
     * re-pushes the inbox, whose [WearInbox.spamEnabled] field is the single
     * source of truth the UI renders.
     */
    fun setSpamEnabled(enabled: Boolean) {
        sendMessageToPhone(WearSyncPaths.INBOX_PREFS_PATH, WearInboxPrefsCodec.encode(WearInboxPrefs(enabled)))
    }

    private fun sendMessageToPhone(path: String, data: ByteArray) {
        applicationScope.launch {
            runCatching {
                val nodes = Wearable.getNodeClient(appContext).connectedNodes.await()
                nodes.forEach { node ->
                    Wearable.getMessageClient(appContext)
                        .sendMessage(node.id, path, data)
                        .await()
                }
                Log.d(TAG, "Message $path sent to ${nodes.size} node(s)")
            }.onFailure { Log.w(TAG, "Message $path failed: ${it.message}") }
        }
    }

    /**
     * Post a local notification for mail newer than the stored watermark,
     * then advance the watermark. The first-ever sync (watermark 0) adopts
     * the backlog silently instead of buzzing for old mail.
     */
    fun notifyNewMailIfFresh(inbox: WearInbox) {
        val newest = inbox.emails.maxOfOrNull { it.createdAt } ?: 0L
        val watermark = inboxStore.getLastNotifiedAt()
        if (newest <= watermark) return
        if (watermark > 0L) {
            val fresh = inbox.emails.filter { it.createdAt > watermark }
            if (fresh.isNotEmpty()) postNewMailNotification(fresh)
        }
        inboxStore.setLastNotifiedAt(newest)
    }

    private fun postNewMailNotification(fresh: List<WearEmail>) {
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureInboxChannel(manager)

        val newest = fresh.first()
        val title = if (fresh.size == 1) "New email" else "${fresh.size} new emails"
        val openIntent = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, INBOX_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText("${newest.senderName} — ${newest.subject}")
            .setNumber(fresh.size)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(INBOX_NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "New-mail notification failed: ${it.message}") }
    }

    private fun ensureInboxChannel(manager: NotificationManagerCompat) {
        manager.createNotificationChannel(chimeChannel(INBOX_CHANNEL_ID, "New email"))
        LEGACY_CHANNEL_IDS.forEach(manager::deleteNotificationChannel)
    }

    /** Channel playing the synced notification chime (mirrors the phone). */
    private fun chimeChannel(id: String, name: String): NotificationChannel =
        NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(
                Uri.parse("android.resource://${appContext.packageName}/${R.raw.notification_chime}"),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }

    /**
     * Post a local notification for calendar events that are new to the
     * watch (id not in the prior snapshot) and still in the future — e.g.
     * a shared/invited event arriving. Edits of known events, deletions,
     * and the first-ever sync (no prior snapshot) stay silent.
     */
    fun notifyNewCalendarEvents(prior: WearCalendar?, fresh: WearCalendar) {
        if (prior == null) return
        val knownIds = prior.events.map { it.id }.toSet()
        val nowSec = System.currentTimeMillis() / 1000
        val newUpcoming = fresh.events.filter { it.startSec >= nowSec && it.id !in knownIds }
        if (newUpcoming.isNotEmpty()) postNewEventNotification(newUpcoming)
    }

    private fun postNewEventNotification(events: List<WearCalendarEvent>) {
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.createNotificationChannel(chimeChannel(CALENDAR_CHANNEL_ID, "New calendar event"))
        LEGACY_CHANNEL_IDS.forEach(manager::deleteNotificationChannel)

        val first = events.first()
        val title = if (events.size == 1) "New event" else "${events.size} new events"
        val openIntent = PendingIntent.getActivity(
            appContext,
            1,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, CALENDAR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(first.title)
            .setNumber(events.size)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(CALENDAR_NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "New-event notification failed: ${it.message}") }
    }

    /**
     * Post a high-priority notification for a pending bunker (NIP-46 sign)
     * request; tapping it opens the watch's bunker screen.
     */
    private fun postBunkerRequestNotification(request: WearBunkerRequest) {
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(BUNKER_CHANNEL_ID, "Bunker requests", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    Uri.parse("android.resource://${appContext.packageName}/${R.raw.notification_chime}"),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        )

        val openIntent = PendingIntent.getActivity(
            appContext,
            2,
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_NAVIGATE, "bunker"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, BUNKER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Bunker request")
            .setContentText("${request.label.ifBlank { "Paired app" }} · ${request.method}")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(BUNKER_NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "Bunker notification failed: ${it.message}") }
    }

    private fun cancelBunkerRequestNotification() {
        val manager = NotificationManagerCompat.from(appContext)
        runCatching { manager.cancel(BUNKER_NOTIFICATION_ID) }
    }
}
