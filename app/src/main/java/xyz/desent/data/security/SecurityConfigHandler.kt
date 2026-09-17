package xyz.desent.data.security

import android.util.Log
import nostr.event.impl.GenericEvent
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.preferences.SecurityConfigStore
import xyz.desent.domain.model.SecurityAlertMode
import xyz.desent.domain.model.SecurityConfig
import xyz.desent.domain.model.UserSettingsPayload

/**
 * Inbound handler for the DeSent user-settings event (kind 30079,
 * `d = "desent_user_settings"`; refs/FromServer/USER_SETTINGS_PROTOCOL.md).
 *
 * The content is PLAINTEXT JSON by design (the relay must read it to update
 * `email_settings`); only the security slice is parsed here. Reads are
 * owner-scoped by the relay, and this handler additionally accepts events
 * authored by the active identity only (defence-in-depth).
 *
 * Replaceable LWW by `created_at`: latest wins. This also suppresses the
 * echo of our own publish (the echo carries the same or newer timestamp,
 * and the local mirror written at publish time is seconds older).
 */
class SecurityConfigHandler(
    private val secureKeyManager: SecureKeyManager,
    private val store: SecurityConfigStore
) {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    suspend fun onInboundUserSettingsEvent(event: GenericEvent) {
        try {
            val authorHex = event.pubKey.toHexString()
            val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrNull()
            val activeHex = identity?.publicKey?.toHexString()
            if (activeHex == null || authorHex != activeHex) {
                Log.d(TAG, "onInbound: skipping 30079 not authored by active user (${authorHex.take(8)})")
                return
            }

            val ownerNpub = Bech32Utils.hexToNpub(authorHex)

            // Tombstone (empty content): the relay deleted the settings row,
            // every field reverts to its default.
            if (event.content.isEmpty()) {
                store.save(ownerNpub, SecurityConfig(), event.createdAt)
                Log.d(TAG, " Security config reset to defaults for $ownerNpub (tombstone)")
                return
            }
            val payload = try {
                json.decodeFromString(UserSettingsPayload.serializer(), event.content)
            } catch (e: Exception) {
                Log.w(TAG, "onInbound: parse failed (foreign writer?): ${e.message}")
                return
            }
            // Partial updates: absent fields are untouched, so only apply
            // what this event carries and merge onto the cached config.
            val mode = payload.securityAlerts?.let { SecurityAlertMode.fromWire(it) }
            val pgpAuto = payload.pgpAutoEncrypt
            val dmFanout = payload.dmFanout
            if (mode == null && pgpAuto == null && dmFanout == null) return

            // Replaceable LWW: never let a stale event clobber a newer cache.
            val cached = store.getCached(ownerNpub)
            if (cached != null && event.createdAt < cached.eventCreatedAtSeconds) {
                Log.d(TAG, " Stale user settings for $ownerNpub (${event.createdAt})")
                return
            }
            val base = cached?.config ?: SecurityConfig()
            store.save(
                ownerNpub,
                SecurityConfig(
                    alertMode = mode ?: base.alertMode,
                    pgpAutoEncrypt = pgpAuto ?: base.pgpAutoEncrypt,
                    dmFanout = dmFanout ?: base.dmFanout
                ),
                event.createdAt
            )
            Log.d(TAG, " Security config cached for $ownerNpub (mode=${(mode ?: base.alertMode).wireValue})")
        } catch (e: Exception) {
            Log.e(TAG, " Failed to process user settings: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "SecurityConfigHandler"
    }
}
