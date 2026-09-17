package xyz.desent.wear.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import xyz.desent.data.wearsync.WearCalendar
import xyz.desent.data.wearsync.WearCalendarCodec

/**
 * Stores the calendar snapshot synced from the phone. Plain preferences,
 * like the inbox store: metadata-only payload (titles/times/places)
 * delivered over the paired, screen-locked Data Layer.
 */
class WearCalendarStore(context: Context) {

    companion object {
        private const val TAG = "WearCalendarStore"
        private const val FILE = "desent_wear_calendar_prefs"
        private const val KEY_CALENDAR = "calendar_b64"
        private const val KEY_LAST_NOTIFIED_AT = "last_notified_at"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(): WearCalendar? {
        val stored = prefs.getString(KEY_CALENDAR, null) ?: return null
        return runCatching {
            WearCalendarCodec.decode(Base64.decode(stored, Base64.NO_WRAP))
        }
            .onFailure { Log.w(TAG, "Stored calendar unparseable: ${it.message}") }
            .getOrNull()
    }

    fun save(calendar: WearCalendar) {
        prefs.edit()
            .putString(KEY_CALENDAR, Base64.encodeToString(WearCalendarCodec.encode(calendar), Base64.NO_WRAP))
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_CALENDAR).apply()
    }

    /**
     * Watermark of the newest calendar revision the watch has already
     * notified about. Zero means "never notified" — the first sync after
     * install silently adopts the backlog.
     */
    fun getLastNotifiedAt(): Long = prefs.getLong(KEY_LAST_NOTIFIED_AT, 0L)

    fun setLastNotifiedAt(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_NOTIFIED_AT, timestamp).apply()
    }
}
