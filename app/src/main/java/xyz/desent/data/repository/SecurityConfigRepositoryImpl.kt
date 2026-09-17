package xyz.desent.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.desent.data.local.preferences.SecurityConfigStore
import xyz.desent.data.repository.NostrRepository
import xyz.desent.domain.model.SecurityAlertMode
import xyz.desent.domain.model.SecurityConfig
import xyz.desent.domain.model.UserSettingsPayload
import xyz.desent.domain.repository.SecurityConfigRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * Implements [SecurityConfigRepository] over the kind-30079 user-settings
 * event (refs/FromServer/USER_SETTINGS_PROTOCOL.md).
 *
 * Publish path: partial plaintext payload `{"security_alerts": "<mode>"}` →
 * kind 30079 (`d = "desent_user_settings"`) → email-bridge relay only.
 * Quota-exempt server-side, so the toggle works over storage cap.
 * Inbound path: [xyz.desent.data.security.SecurityConfigHandler] refreshes
 * the local cache (replaceable LWW).
 */
class SecurityConfigRepositoryImpl(
    private val store: SecurityConfigStore,
    private val nostrRepository: NostrRepository
) : SecurityConfigRepository {

    /**
     * Kicked after a successful `dm_fanout=true` save so the backup fetch
     * from the user's NIP-65 relays runs immediately. Wired post-construction
     * by AppContainer (avoids a repository dependency cycle); null = no-op.
     */
    var onDmFanoutEnabled: (suspend () -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    override fun observe(ownerNpub: String): Flow<SecurityConfig?> = store.observe(ownerNpub)

    override suspend fun get(ownerNpub: String): SecurityConfig? = store.get(ownerNpub)

    override suspend fun setAlertMode(
        ownerNpub: String,
        mode: SecurityAlertMode
    ): Result<Unit> {
        return try {
            // Partial payload: security slice ONLY — the relay's UPSERT touches
            // just security_alerts_mode; auto-purge / admin columns untouched.
            val payload = json.encodeToString(UserSettingsPayload(securityAlerts = mode.wireValue))

            nostrRepository.publishUserSettings(payload).getOrThrow()

            // Local mirror; the relay echo will REPLACE this (LWW by
            // created_at — the echo is seconds newer, so the mirror is transient).
            store.save(ownerNpub, SecurityConfig(alertMode = mode), System.currentTimeMillis() / 1000)
            Log.d(TAG, " Security config saved + mirrored for ${ownerNpub.take(12)} (mode=${mode.wireValue})")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, " Failed to save security config: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun setPgpAutoEncrypt(ownerNpub: String, enabled: Boolean): Result<Unit> {
        return try {
            // Partial payload: ONLY pgp_auto_encrypt — the other 30079 fields
            // stay untouched relay-side (ANDROID_PGP.md §5).
            val payload = json.encodeToString(UserSettingsPayload(pgpAutoEncrypt = enabled))

            nostrRepository.publishUserSettings(payload).getOrThrow()

            val cached = store.get(ownerNpub) ?: SecurityConfig()
            store.save(
                ownerNpub,
                cached.copy(pgpAutoEncrypt = enabled),
                System.currentTimeMillis() / 1000
            )
            Log.d(TAG, " pgp_auto_encrypt=$enabled saved + mirrored for ${ownerNpub.take(12)}")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, " Failed to save pgp_auto_encrypt: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun setDmFanout(ownerNpub: String, enabled: Boolean): Result<Unit> {
        return try {
            // Partial payload: ONLY dm_fanout (ANDROID_DM_FANOUT.md §2) — the
            // verdict-awaiting publish surfaces the relay's premium gate.
            val payload = json.encodeToString(UserSettingsPayload(dmFanout = enabled))

            nostrRepository.publishUserSettingsAwaitingVerdict(payload).getOrThrow()

            val cached = store.get(ownerNpub) ?: SecurityConfig()
            store.save(
                ownerNpub,
                cached.copy(dmFanout = enabled),
                System.currentTimeMillis() / 1000
            )
            if (enabled) {
                runCatching { onDmFanoutEnabled?.invoke() }
                    .onFailure { Log.w(TAG, "backup sync kick failed: ${it.message}") }
            }
            Log.d(TAG, " dm_fanout=$enabled saved + mirrored for ${ownerNpub.take(12)}")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, " Failed to save dm_fanout: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun refresh(): Result<Unit> = try {
        nostrRepository.subscribeToOwnUserSettings()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "refresh failed: ${e.message}")
        Result.failure(e)
    }

    companion object {
        private const val TAG = "SecurityConfigRepo"
    }
}
