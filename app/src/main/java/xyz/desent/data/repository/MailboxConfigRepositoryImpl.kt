package xyz.desent.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.desent.crypto.PrivateStorageCrypto
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.preferences.MailboxConfigStore
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.model.MailboxConfig
import xyz.desent.domain.repository.MailboxConfigRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * Implements [MailboxConfigRepository] over NIP-EMAIL kind 35050.
 *
 * Publish path: private rules → JSON → NIP-44 self-encrypt → kind 35050 with
 * the plaintext `auto_purge_days` policy tag → email-bridge relay only.
 * Inbound path (kind 35050 authored by the active user, via the own-pubkey
 * subscription): [xyz.desent.data.mailbox.MailboxConfigHandler] decrypts and
 * refreshes the local cache (addressable LWW).
 */
class MailboxConfigRepositoryImpl(
    private val store: MailboxConfigStore,
    private val secureKeyManager: SecureKeyManager,
    private val nostrRepository: NostrRepository,
    private val preferencesManager: PreferencesManager
) : MailboxConfigRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun observe(ownerNpub: String): Flow<MailboxConfig?> = store.observe(ownerNpub)

    override suspend fun get(ownerNpub: String): MailboxConfig? = store.get(ownerNpub)

    override suspend fun save(ownerNpub: String, config: MailboxConfig): Result<Unit> {
        return try {
            val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrThrow()
            val plaintext = json.encodeToString(MailboxConfig.PrivateRules.serializer(), MailboxConfig.PrivateRules(config))
            val encryptedRules = PrivateStorageCrypto.encryptToSelf(plaintext, identity.privateKey.rawData)

            nostrRepository.publishMailboxConfig(
                autoPurgeDays = config.autoPurgeDays,
                encryptedRules = encryptedRules
            ).getOrThrow()

            // Local mirror; the relay echo will REPLACE this (LWW by created_at
            // — the echo is seconds newer, so the mirror is transient).
            store.save(ownerNpub, config, System.currentTimeMillis() / 1000)
            Log.d(TAG, " Mailbox config saved + mirrored for ${ownerNpub.take(12)}")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, " Failed to save mailbox config: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun refresh(): Result<Unit> = try {
        nostrRepository.subscribeToOwnMailboxConfig()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "refresh failed: ${e.message}")
        Result.failure(e)
    }

    companion object {
        private const val TAG = "MailboxConfigRepo"
    }
}
