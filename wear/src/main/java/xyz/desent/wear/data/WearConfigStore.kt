package xyz.desent.wear.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import xyz.desent.data.wearsync.WearConfig
import xyz.desent.data.wearsync.WearConfigCodec

/**
 * Stores the config synced from the phone. Plain preferences — the payload
 * is non-secret configuration (currently just the theme mode).
 */
class WearConfigStore(context: Context) {

    companion object {
        private const val TAG = "WearConfigStore"
        private const val FILE = "desent_wear_config_prefs"
        private const val KEY_CONFIG = "config_json"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(): WearConfig? {
        val json = prefs.getString(KEY_CONFIG, null) ?: return null
        return runCatching { WearConfigCodec.decode(json.toByteArray(Charsets.UTF_8)) }
            .onFailure { Log.w(TAG, "Stored config unparseable: ${it.message}") }
            .getOrNull()
    }

    fun save(config: WearConfig) {
        prefs.edit()
            .putString(KEY_CONFIG, String(WearConfigCodec.encode(config), Charsets.UTF_8))
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_CONFIG).apply()
    }
}
