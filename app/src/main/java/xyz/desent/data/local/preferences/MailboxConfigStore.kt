package xyz.desent.data.local.preferences

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import xyz.desent.domain.model.MailboxConfig

/**
 * Per-account local cache of the user's kind-35050 Mailbox Configuration
 * (NIP-EMAIL). The relay event itself is the source of truth; this mirror
 * makes the config observable offline immediately after login. Keyed by the
 * owner's npub, same dynamic-key pattern as [NoteDraftStore].
 *
 * The source event's `created_at` (seconds) is kept alongside the config so
 * the addressable LWW comparison survives restarts.
 */
class MailboxConfigStore(private val preferencesManager: PreferencesManager) {

    private val dataStore = preferencesManager.dataStore

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun configKey(npub: String) = stringPreferencesKey("mailbox_config_${npub.take(12)}")
    private fun timeKey(npub: String) = longPreferencesKey("mailbox_config_time_${npub.take(12)}")

    /** The cached config merged with the addressable staleness clock. */
    data class Cached(val config: MailboxConfig, val eventCreatedAtSeconds: Long)

    fun observe(npub: String): Flow<MailboxConfig?> =
        preferencesManager.dataStore.data.map { prefs ->
            prefs[configKey(npub)]?.let { decode(it) }
        }

    suspend fun get(npub: String): MailboxConfig? =
        preferencesManager.dataStore.data.firstOrNull()?.get(configKey(npub))?.let { decode(it) }

    suspend fun getCached(npub: String): Cached? {
        val prefs = preferencesManager.dataStore.data.firstOrNull() ?: return null
        val config = prefs[configKey(npub)]?.let { decode(it) } ?: return null
        return Cached(config, prefs[timeKey(npub)] ?: 0L)
    }

    suspend fun save(npub: String, config: MailboxConfig, eventCreatedAtSeconds: Long) {
        preferencesManager.dataStore.edit {
            it[configKey(npub)] = json.encodeToString(MailboxConfig.serializer(), config)
            it[timeKey(npub)] = eventCreatedAtSeconds
        }
    }

    suspend fun clear(npub: String) {
        preferencesManager.dataStore.edit {
            it.remove(configKey(npub))
            it.remove(timeKey(npub))
        }
    }

    private fun decode(stored: String): MailboxConfig? = runCatching {
        json.decodeFromString(MailboxConfig.serializer(), stored)
    }.getOrNull()
}
