package xyz.desent.presentation.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.nostr.NostrEventProcessor
import xyz.desent.domain.model.EmailType
import xyz.desent.R

/**
 * Posts system-tray (notification drawer) notifications for inbound events —
 * emails, login-security alerts, NIP-46 sign requests and badge awards — for the
 * lifetime of the app process, regardless of which screen (if any) is in the
 * foreground.
 *
 * Runs on the application [scope] started from `DeSentApplication.onCreate`,
 * so it keeps firing while the app is open or backgrounded, as long as the OS
 * hasn't killed the process. (For extended background life, enable the optional
 * `NostrForegroundService`.)
 *
 * Per-event toggles live in [PreferencesManager]; the system-level notification
 * permission / per-channel settings are honoured via [NotificationManagerCompat].
 */
class SystemNotificationDispatcher(
    private val context: Context,
    private val eventProcessor: NostrEventProcessor,
    private val preferencesManager: PreferencesManager,
    private val nip46BunkerService: xyz.desent.data.nip46.Nip46BunkerService,
    private val avatarFactory: NotificationAvatarFactory,
    private val conversationShortcutManager: ConversationShortcutManager,
    private val scope: CoroutineScope
) {
    init {
        createChannels()
    }

    fun start() {
        scope.launch { collectEmailArrivals() }
        scope.launch { collectSecurityAlerts() }
        scope.launch { collectBadgeAwardNotices() }
        scope.launch { observeNip46Prompts() }
    }

    // ---------------- Emails ----------------

    private suspend fun collectEmailArrivals() {
        eventProcessor.emailArrivals.collect { email ->
            // Skip SYSTEM bridge receipts and our own outbound rows.
            if (email.emailType == EmailType.SYSTEM) return@collect
            if (email.direction == xyz.desent.domain.model.EmailDirection.OUTBOUND) return@collect
            // Spam arrives pre-classified (NostrEventProcessor stamps the verdict
            // before emitting) — quarantined mail must not raise a notification.
            if (email.isSpam) return@collect
            if (!preferencesManager.areEmailNotificationsEnabled.first()) return@collect

            notifyEmail(email)
            // Warm the sender's profile/favicon Room caches + Coil bytes so
            // the NEXT notification from them renders the avatar instantly —
            // then re-push the conversation shortcut so THIS notification's
            // collapsed avatar upgrades from initials to the real logo.
            scope.launch {
                runCatching {
                    avatarFactory.warmUp(email)
                    val refreshed = avatarFactory.avatarBitmapFor(email)
                    conversationShortcutManager.upsert(
                        ConversationShortcutManager.shortcutIdFor(email.senderEmail),
                        ConversationShortcutManager.longLabelFor(email.displaySender),
                        email.threadKey,
                        refreshed
                    )
                }
            }
        }
    }

    // ---------------- Login-security alerts ----------------

    /**
     * Notify on live arrival of a login-security alert (kind-1010
     * `direction: "security"`). The processor only emits on the FIRST
     * successful insert of an event id, so re-hydrated backlog after a
     * reconnect or process restart is silent by construction — no extra
     * seen-set needed here. The alert stays `isSeen = false` until the user
     * opens it, so the avatar shield badge keeps signalling in the background.
     */
    private suspend fun collectSecurityAlerts() {
        eventProcessor.securityAlertArrivals.collect { alert ->
            notifySecurityAlert(alert)
        }
    }

    // ---------------- Badge awards ----------------

    /**
     * Notify on FIRST persistence of a badge award notice (kind-1010
     * `direction: "badge"`, relay-sealed — ANDROID_BADGES.md §7). The
     * processor only emits when the wrap event id inserts new into
     * `badge_notices`, so login re-downloads of the relay's 30-day backlog
     * are silent by construction. Tapping opens the notifications tray.
     */
    private suspend fun collectBadgeAwardNotices() {
        eventProcessor.badgeAwardArrivals.collect { notice ->
            notifyBadgeAward(notice)
        }
    }

    // ---------------- Notification builders ----------------

    /**
     * Telegram/Messages-style email notification: the sender's decorated
     * avatar (picture or initials + DeSent badge, see
     * [NotificationAvatarFactory]) renders FAR-LEFT in the header via a
     * [androidx.core.app.Person] + MessagingStyle. MessagingStyle hides the
     * avatar while COLLAPSED unless the notification binds to a conversation
     * shortcut — so [ConversationShortcutManager.upsert] registers a
     * long-lived shortcut (icon = the same decorated avatar) before posting
     * and we attach it via `setShortcutId`, making the avatar visible in
     * both the collapsed and expanded layouts. Title is the sender's
     * display name; the subject is the message line.
     */
    private suspend fun notifyEmail(email: xyz.desent.domain.model.Email) {
        if (!canPost()) return
        val body = email.subject.ifBlank { "(no subject)" }.truncate(NOTIFICATION_BODY_MAX)
        val avatar = runCatching { avatarFactory.avatarBitmapFor(email) }.getOrNull()
        // Push BEFORE the notification posts — the system resolves the
        // conversation avatar from the shortcut at post time.
        val shortcutId = avatar?.let {
            runCatching {
                conversationShortcutManager.upsert(
                    ConversationShortcutManager.shortcutIdFor(email.senderEmail),
                    ConversationShortcutManager.longLabelFor(email.displaySender),
                    email.threadKey,
                    it
                )
            }.getOrNull()
        }
        val person = androidx.core.app.Person.Builder()
            .setName(email.displaySender)
            .setKey(email.senderEmail.trim().lowercase())
            .apply { avatar?.let { setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(it)) } }
            .build()
        val notification = baseBuilder(CHANNEL_EMAIL, NotificationCompat.CATEGORY_EMAIL)
            .setContentTitle(email.displaySender)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .apply { avatar?.let { setLargeIcon(it) } }
            .apply { shortcutId?.let { setShortcutId(it) } }
            .setStyle(NotificationCompat.MessagingStyle(person).addMessage(body, email.createdAt, person))
            .setContentIntent(openDeepLink("desent://email?threadKey=${email.threadKey}", REQUEST_EMAIL_BASE + email.threadKey.hashCode()))
            .setGroup(GROUP_EMAIL)
            .build()
        post(NOTIF_EMAIL_BASE + email.threadKey.hashCode(), notification)
    }

    /** Surface tag → human label (ANDROID_SECURITY_ALERTS.md §2). */
    private fun surfaceLabel(surface: String): String = when (surface) {
        "ws" -> "relay connection"
        "http" -> "web / API"
        "admin" -> "admin panel"
        else -> "account"
    }

    /** Short human summary of a raw User-Agent ("Firefox on Linux"). */
    private fun summarizeUa(ua: String?): String? {
        if (ua == null) return null
        val browser = when {
            ua.contains("Firefox", ignoreCase = true) -> "Firefox"
            ua.contains("Edg/", ignoreCase = true) -> "Edge"
            ua.contains("Chrome", ignoreCase = true) -> "Chrome"
            ua.contains("Safari", ignoreCase = true) -> "Safari"
            ua.contains("OkHttp", ignoreCase = true) -> "DeSent app"
            else -> null
        }
        val os = when {
            ua.contains("Android", ignoreCase = true) -> "Android"
            ua.contains("iPhone", ignoreCase = true) ||
                ua.contains("iPad", ignoreCase = true) -> "iOS"
            ua.contains("Windows", ignoreCase = true) -> "Windows"
            ua.contains("Mac OS", ignoreCase = true) -> "macOS"
            ua.contains("Linux", ignoreCase = true) -> "Linux"
            else -> null
        }
        return when {
            browser != null && os != null -> "$browser on $os"
            else -> browser ?: os
        }
    }

    private fun notifySecurityAlert(alert: xyz.desent.domain.model.SecurityAlert) {
        if (!canPost()) return
        val where = listOfNotNull(alert.geo, alert.ip).joinToString(" · ").ifBlank { null }
        val device = summarizeUa(alert.ua)
        val contextLine = listOfNotNull(where, device, surfaceLabel(alert.surface).takeIf { alert.surface.isNotEmpty() })
            .joinToString(" · ")
        val notification = baseBuilder(CHANNEL_SECURITY, NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentTitle(alert.subject.ifBlank { "New sign-in to your account" })
            .setContentText(contextLine.truncate(NOTIFICATION_BODY_MAX))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(
                openDeepLink(
                    "desent://security?alertId=${alert.eventId}",
                    REQUEST_SECURITY_BASE + alert.eventId.hashCode()
                )
            )
            .setGroup(GROUP_SECURITY)
            .build()
        post(NOTIF_SECURITY_BASE + alert.eventId.hashCode(), notification)
    }

    private fun notifyBadgeAward(notice: xyz.desent.domain.model.BadgeAwardNotice) {
        if (!canPost()) return
        val title = notice.subject.ifBlank { "You earned a badge" }
        val notification = baseBuilder(CHANNEL_BADGE, NotificationCompat.CATEGORY_SOCIAL)
            .setContentTitle(title)
            .setContentText(notice.body.truncate(NOTIFICATION_BODY_MAX))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(
                openDeepLink(
                    "desent://notifications",
                    REQUEST_BADGE_BASE + notice.eventId.hashCode()
                )
            )
            .setGroup(GROUP_BADGE)
            .build()
        post(NOTIF_BADGE_BASE + notice.eventId.hashCode(), notification)
    }

    // ---------------- NIP-46 sign requests ----------------

    /** Post a notification when a sign request needs the user's decision; clear it once resolved. */
    private suspend fun observeNip46Prompts() {
        var lastId: String? = null
        nip46BunkerService.pendingSignPrompt.collect { prompt ->
            if (prompt == null) {
                if (lastId != null) {
                    NotificationManagerCompat.from(context).cancel(NOTIF_NIP46)
                    lastId = null
                }
            } else if (prompt.requestId != lastId) {
                lastId = prompt.requestId
                notifyNip46Prompt(prompt.label, prompt.method, prompt.eventKind)
            }
        }
    }

    private fun notifyNip46Prompt(label: String, method: String, kind: Long?) {
        if (!canPost()) return
        val body = method + (kind?.let { " · kind $it" } ?: "")
        val notification = baseBuilder(CHANNEL_NIP46, NotificationCompat.CATEGORY_STATUS)
            .setContentTitle("Sign request from $label")
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppLauncher(REQUEST_NIP46))
            .setAutoCancel(true)
            .build()
        post(NOTIF_NIP46, notification)
    }

    // ---------------- Helpers ----------------

    private fun canPost(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun baseBuilder(channelId: String, category: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setColor(ContextCompat.getColor(context, R.color.desent_amber))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(category)
            .setAutoCancel(true)

    private fun openDeepLink(uri: String, requestCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppLauncher(requestCode: Int): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply { setPackage(context.packageName) }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun post(id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission denied: ${e.message}")
        }
    }

    private data class ChannelSpec(
        val id: String,
        val name: String,
        val desc: String,
        val soundRes: Int?
    )

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Channel settings are immutable once created, so the channels that
        // gained custom sounds use new (_v2) ids; the pre-sound ids (and the
        // removed follower channel) are deleted below.
        listOf(
            ChannelSpec(CHANNEL_EMAIL, "Email", "New emails", R.raw.notification_chime),
            ChannelSpec(
                CHANNEL_NIP46,
                "Sign requests",
                "Shown when another device asks this phone to sign a Nostr event.",
                R.raw.sign_request_ding
            ),
            ChannelSpec(
                CHANNEL_SECURITY,
                "Security alerts",
                "Sign-in alerts for your DeSent account.",
                R.raw.security_alert_pulse
            ),
            // Badges are dormant; keep the channel on the system-default sound.
            ChannelSpec(CHANNEL_BADGE, "Badges", "Shown when you earn a new DeSent badge.", null)
        ).forEach { spec ->
            nm.createNotificationChannel(
                NotificationChannel(spec.id, spec.name, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = spec.desc
                    setShowBadge(true)
                    enableVibration(true)
                    enableLights(true)
                    spec.soundRes?.let { resId ->
                        setSound(
                            Uri.parse("android.resource://${context.packageName}/$resId"),
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                    }
                }
            )
        }
        LEGACY_CHANNEL_IDS.forEach(nm::deleteNotificationChannel)
    }

    private fun String.truncate(max: Int): String =
        if (length > max) take(max) + "..." else this

    companion object {
        private const val TAG = "SystemNotificationDispatcher"

        private const val CHANNEL_EMAIL = "email_notifications_v2"
        private const val CHANNEL_NIP46 = "nip46_sign_requests_v2"
        private const val CHANNEL_SECURITY = "security_alerts_v2"
        private const val CHANNEL_BADGE = "badge_notifications"

        // Pre-sound channel ids (plus the removed follower channel): channel
        // settings are frozen at creation, so the sound change needed new ids.
        private val LEGACY_CHANNEL_IDS = listOf(
            "email_notifications",
            "follower_notifications",
            "nip46_sign_requests",
            "security_alerts"
        )

        private const val GROUP_EMAIL = "desent_email"
        private const val GROUP_SECURITY = "desent_security"
        private const val GROUP_BADGE = "desent_badge"

        // Distinct ranges so notification IDs never collide across types.
        private const val NOTIF_EMAIL_BASE = 3000
        private const val NOTIF_NIP46 = 5000
        private const val NOTIF_SECURITY_BASE = 6000
        private const val NOTIF_BADGE_BASE = 7000

        private const val REQUEST_EMAIL_BASE = 3000
        private const val REQUEST_NIP46 = 5000
        private const val REQUEST_SECURITY_BASE = 6000
        private const val REQUEST_BADGE_BASE = 7000

        private const val NOTIFICATION_BODY_MAX = 80
    }
}
