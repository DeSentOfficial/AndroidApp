package xyz.desent.data.local.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import xyz.desent.domain.model.SecurityConfig

/**
 * Per-account local cache of the user's security-configuration slice of the
 * kind-30079 user-settings event (refs/FromServer/USER_SETTINGS_PROTOCOL.md)
 * — `security_alerts` + `pgp_auto_encrypt`. The relay event is the source of
 * truth; this mirror makes the settings observable offline immediately after
 * login. Same dynamic-key pattern as [MailboxConfigStore].
 *
 * The source event's `created_at` (seconds) is kept alongside the config so the
 * replaceable-LWW comparison survives restarts.
 */
class SecurityConfigStore(private val preferencesManager: PreferencesManager) {

    private fun modeKey(npub: String) = stringPreferencesKey("security_config_${npub.take(12)}")
    private fun pgpAutoKey(npub: String) = booleanPreferencesKey("security_config_pgp_auto_${npub.take(12)}")
    private fun dmFanoutKey(npub: String) = booleanPreferencesKey("security_config_dm_fanout_${npub.take(12)}")
    private fun timeKey(npub: String) = longPreferencesKey("security_config_time_${npub.take(12)}")

    /** The cached config merged with the replaceable staleness clock. */
    data class Cached(val config: SecurityConfig, val eventCreatedAtSeconds: Long)

    fun observe(npub: String): Flow<SecurityConfig?> =
        preferencesManager.dataStore.data.map { prefs ->
            prefs[modeKey(npub)]?.let { mode ->
                SecurityConfig(
                    alertMode = xyz.desent.domain.model.SecurityAlertMode.fromWire(mode),
                    pgpAutoEncrypt = prefs[pgpAutoKey(npub)] ?: false,
                    dmFanout = prefs[dmFanoutKey(npub)] ?: false
                )
            }
        }

    suspend fun get(npub: String): SecurityConfig? =
        preferencesManager.dataStore.data.firstOrNull()?.let { prefs ->
            prefs[modeKey(npub)]?.let { mode ->
                SecurityConfig(
                    alertMode = xyz.desent.domain.model.SecurityAlertMode.fromWire(mode),
                    pgpAutoEncrypt = prefs[pgpAutoKey(npub)] ?: false,
                    dmFanout = prefs[dmFanoutKey(npub)] ?: false
                )
            }
        }

    suspend fun getCached(npub: String): Cached? {
        val prefs = preferencesManager.dataStore.data.firstOrNull() ?: return null
        val mode = prefs[modeKey(npub)] ?: return null
        val config = SecurityConfig(
            alertMode = xyz.desent.domain.model.SecurityAlertMode.fromWire(mode),
            pgpAutoEncrypt = prefs[pgpAutoKey(npub)] ?: false,
            dmFanout = prefs[dmFanoutKey(npub)] ?: false
        )
        return Cached(config, prefs[timeKey(npub)] ?: 0L)
    }

    suspend fun save(npub: String, config: SecurityConfig, eventCreatedAtSeconds: Long) {
        preferencesManager.dataStore.edit {
            it[modeKey(npub)] = config.alertMode.wireValue
            it[pgpAutoKey(npub)] = config.pgpAutoEncrypt
            it[dmFanoutKey(npub)] = config.dmFanout
            it[timeKey(npub)] = eventCreatedAtSeconds
        }
    }

    suspend fun clear(npub: String) {
        preferencesManager.dataStore.edit {
            it.remove(modeKey(npub))
            it.remove(pgpAutoKey(npub))
            it.remove(dmFanoutKey(npub))
            it.remove(timeKey(npub))
        }
    }
}
