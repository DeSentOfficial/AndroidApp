package xyz.desent.wear.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import xyz.desent.data.wearsync.WearInbox
import xyz.desent.data.wearsync.WearInboxCodec

/**
 * Stores the inbox snapshot synced from the phone. Plain preferences, like
 * the config store: the payload is already-decrypted reading copies that the
 * Data Layer delivers only to the paired, screen-locked watch.
 */
class WearInboxStore(context: Context) {

    companion object {
        private const val TAG = "WearInboxStore"
        private const val FILE = "desent_wear_inbox_prefs"
        private const val KEY_INBOX = "inbox_b64"
        private const val KEY_LAST_NOTIFIED_AT = "last_notified_at"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(): WearInbox? {
        val stored = prefs.getString(KEY_INBOX, null) ?: return null
        return runCatching {
            WearInboxCodec.decode(Base64.decode(stored, Base64.NO_WRAP))
        }
            .onFailure { Log.w(TAG, "Stored inbox unparseable: ${it.message}") }
            .getOrNull()
    }

    fun save(inbox: WearInbox) {
        prefs.edit()
            .putString(KEY_INBOX, Base64.encodeToString(WearInboxCodec.encode(inbox), Base64.NO_WRAP))
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_INBOX).apply()
    }

    /**
     * Watermark of the newest email the watch has already notified about.
     * Zero means "never notified" — the first sync after install silently
     * adopts the backlog instead of buzzing for old mail.
     */
    fun getLastNotifiedAt(): Long = prefs.getLong(KEY_LAST_NOTIFIED_AT, 0L)

    fun setLastNotifiedAt(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_NOTIFIED_AT, timestamp).apply()
    }
}
