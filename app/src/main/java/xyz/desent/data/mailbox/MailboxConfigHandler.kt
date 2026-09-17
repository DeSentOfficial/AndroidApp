package xyz.desent.data.mailbox

import android.util.Log
import nostr.event.impl.GenericEvent
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.PrivateStorageCrypto
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.preferences.MailboxConfigStore
import xyz.desent.domain.model.MailboxConfig

/**
 * Inbound handler for the NIP-EMAIL Mailbox Configuration (kind 35050).
 * See refs/FromServer/NIP-EMAIL.md § Kind 35050.
 *
 * The event is addressable (`d` = user pubkey): relay-readable policy tags are
 * plaintext (e.g. `["auto_purge_days","30"]` — how the operator enforces the
 * retention TTL without decrypting), while the `content` is NIP-44
 * self-encrypted private rules (see [PrivateStorageCrypto]). Only the user's
 * own key can read it, and this handler only accepts events authored by the
 * active identity (defence-in-depth on top of the relay's owner scoping).
 *
 * Mirrors [xyz.desent.data.relay.SearchRelayListHandler]: decrypt → parse →
 * cache locally. Addressable LWW: latest `created_at` wins.
 */
class MailboxConfigHandler(
    private val secureKeyManager: SecureKeyManager,
    private val store: MailboxConfigStore
) {

    suspend fun onInboundMailboxConfigEvent(event: GenericEvent) {
        try {
            val authorHex = event.pubKey.toHexString()
            val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrNull()
            val activeHex = identity?.publicKey?.toHexString()
            if (activeHex == null || authorHex != activeHex) {
                Log.d(TAG, "onInbound: skipping 35050 not authored by active user (${authorHex.take(8)})")
                return
            }

            val ownerNpub = Bech32Utils.hexToNpub(authorHex)

            // Relay-readable policy tag (plaintext by design).
            val autoPurgeDays = event.tags
                .firstOrNull { (it as? nostr.event.tag.GenericTag)?.getCode() == MailboxConfig.AUTO_PURGE_TAG }
                ?.let { (it as nostr.event.tag.GenericTag).getParams()?.firstOrNull() }
                ?.trim()
                ?.toIntOrNull()

            // Private rules: NIP-44 self-encrypted content. Empty content is a
            // valid "no private rules" configuration (policy tags only).
            val rules = if (event.content.isEmpty()) {
                MailboxConfig.PrivateRules()
            } else {
                val priv = identity.privateKey.rawData
                val plaintext = try {
                    PrivateStorageCrypto.decryptFromSelf(event.content, priv)
                } catch (e: Exception) {
                    Log.w(TAG, "onInbound: decrypt failed: ${e.message}")
                    return
                }
                try {
                    json.decodeFromString(MailboxConfig.PrivateRules.serializer(), plaintext)
                } catch (e: Exception) {
                    Log.w(TAG, "onInbound: parse failed: ${e.message}")
                    return
                }
            }

            val config = MailboxConfig.fromPrivateRules(autoPurgeDays, rules)

            // Addressable LWW: never let a stale event clobber a newer cache.
            val cached = store.getCached(ownerNpub)
            if (cached != null && event.createdAt < cached.eventCreatedAtSeconds) {
                Log.d(TAG, " Stale mailbox config for $ownerNpub (${event.createdAt})")
                return
            }
            store.save(ownerNpub, config, event.createdAt)
            Log.d(TAG, " Mailbox config cached for $ownerNpub (autoPurgeDays=$autoPurgeDays)")
        } catch (e: Exception) {
            Log.e(TAG, " Failed to process mailbox config: ${e.message}", e)
        }
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "MailboxConfigHandler"
    }
}
