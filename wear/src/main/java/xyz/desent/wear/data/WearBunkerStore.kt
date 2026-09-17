package xyz.desent.wear.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import xyz.desent.data.wearsync.WearBunkerCodec
import xyz.desent.data.wearsync.WearBunkerRequest

/**
 * Stores the bunker prompt state synced from the phone. Persisting (rather
 * than keeping it in memory) means a mid-prompt watch reboot still shows the
 * request until its expiresAt passes; the preview is a short, already
 * phone-truncated snippet delivered only to the paired, screen-locked watch.
 */
class WearBunkerStore(context: Context) {

    companion object {
        private const val TAG = "WearBunkerStore"
        private const val FILE = "desent_wear_bunker_prefs"
        private const val KEY_REQUEST = "request_b64"
        private const val KEY_LAST_NOTIFIED_ID = "last_notified_request_id"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(): WearBunkerRequest? {
        val stored = prefs.getString(KEY_REQUEST, null) ?: return null
        return runCatching {
            WearBunkerCodec.decodeRequest(Base64.decode(stored, Base64.NO_WRAP))
        }
            .onFailure { Log.w(TAG, "Stored bunker request unparseable: ${it.message}") }
            .getOrNull()
    }

    fun save(request: WearBunkerRequest) {
        prefs.edit()
            .putString(KEY_REQUEST, Base64.encodeToString(WearBunkerCodec.encodeRequest(request), Base64.NO_WRAP))
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_REQUEST).apply()
    }

    /**
     * Request id the watch has already notified about — dedupes buzzes when
     * the phone re-pushes the same pending prompt (e.g. config re-sync).
     */
    fun getLastNotifiedRequestId(): String? = prefs.getString(KEY_LAST_NOTIFIED_ID, null)

    fun setLastNotifiedRequestId(requestId: String) {
        prefs.edit().putString(KEY_LAST_NOTIFIED_ID, requestId).apply()
    }
}
