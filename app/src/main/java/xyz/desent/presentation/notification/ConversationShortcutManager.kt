package xyz.desent.presentation.notification

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.security.MessageDigest

/**
 * Long-lived dynamic conversation shortcuts for email senders, so the
 * system binds the sender's decorated avatar (see [NotificationAvatarFactory])
 * to the notification via `setShortcutId` — that binding is the ONLY way the
 * collapsed conversation card renders the avatar far-left (MessagingStyle
 * alone hides the Person icon/large icon while collapsed).
 *
 * Push happens BEFORE the notification is posted (the system resolves the
 * shortcut icon at post time), and again after the post-notify avatar
 * warm-up so a cold sender's initials fallback upgrades to the real logo.
 *
 * Dynamic slots are limited (~10 per activity by the launcher); on overflow
 * we clear and retry — active senders self-heal because every notification
 * re-pushes its shortcut.
 */
class ConversationShortcutManager(private val context: Context) {

    /** Upsert the sender's shortcut; returns the id, or null if the push failed. */
    fun upsert(shortcutId: String, label: String, threadKey: String, avatar: Bitmap): String? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("desent://email?threadKey=$threadKey")).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val info = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(shortLabelFor(label))
            .setLongLabel(label)
            .setIcon(IconCompat.createWithBitmap(avatar))
            .setIntent(intent)
            .setLongLived(true)
            .build()
        return if (push(info)) shortcutId else null
    }

    private fun push(info: ShortcutInfoCompat): Boolean = try {
        ShortcutManagerCompat.addDynamicShortcuts(context, listOf(info)) || run {
            // Some launchers report false instead of throwing when full.
            removeAllAndRetry(info)
        }
    } catch (e: IllegalStateException) {
        removeAllAndRetry(info)
    } catch (e: Exception) {
        Log.w(TAG, "Shortcut push failed for ${info.id}: ${e.message}")
        false
    }

    private fun removeAllAndRetry(info: ShortcutInfoCompat): Boolean = try {
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        ShortcutManagerCompat.addDynamicShortcuts(context, listOf(info))
    } catch (e: Exception) {
        Log.w(TAG, "Shortcut retry failed for ${info.id}: ${e.message}")
        false
    }

    companion object {
        private const val TAG = "ConversationShortcutMgr"

        private const val SHORT_LABEL_MAX = 10
        private const val LONG_LABEL_MAX = 25
        private const val ID_HASH_CHARS = 16

        /**
         * Deterministic, shortcut-id-safe id for a sender key — shortcut ids
         * must not contain envelope-style characters like '@'.
         */
        fun shortcutIdFor(senderKey: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(senderKey.trim().lowercase().toByteArray())
                .joinToString("") { "%02x".format(it) }
            return "sender-" + digest.take(ID_HASH_CHARS)
        }

        /** Launcher surfaces ellipsize, but stay within the recommended length. */
        fun shortLabelFor(label: String): String =
            label.trim().ifBlank { "Email" }.take(SHORT_LABEL_MAX)

        fun longLabelFor(label: String): String =
            label.trim().ifBlank { "Email" }.take(LONG_LABEL_MAX)
    }
}
