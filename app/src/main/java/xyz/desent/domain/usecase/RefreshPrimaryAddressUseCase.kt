package xyz.desent.domain.usecase

import android.util.Log
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.database.dao.AccountDao
import xyz.desent.domain.repository.RegistrationRepository

/**
 * Refreshes each account's cached primary `<local>@desent.xyz` address from
 * `GET /api/me` (the authoritative source) into `accounts.primaryAddress`,
 * so the account switcher never falls back to "No address" for registered
 * accounts whose kind-0 profile has no/external nip05.
 *
 * Runs at cold launch (all accounts), and after key-login / account switch
 * (single account) — always fire-and-forget: failures are logged and the
 * cached value is left untouched. Accounts whose key material is
 * unavailable (e.g. biometric-locked) are skipped.
 *
 * Address precedence mirrors the email send path
 * (`EmailRepositoryImpl.resolveFromAlias`): `email_address`, then a
 * desent-domain nip05, then `<local>@desent.xyz`.
 */
class RefreshPrimaryAddressUseCase(
    private val accountDao: AccountDao,
    private val secureKeyManager: SecureKeyManager,
    private val registrationRepository: RegistrationRepository
) {

    /** Refresh every stored account (cold launch). */
    suspend fun refreshAll() {
        val npubs = runCatching { accountDao.getAccounts().map { it.npub } }
            .getOrElse {
                Log.w(TAG, "refreshAll: account list read failed: ${it.message}")
                return
            }
        npubs.forEach { npub -> refreshOne(npub) }
    }

    /** Refresh a single account (key-login / account switch). */
    suspend fun refreshOne(npub: String) {
        val identity = secureKeyManager.getIdentityForAccount(npub).getOrNull()
        if (identity == null) {
            // Key unavailable (biometric-locked / not stored) — can't sign.
            Log.d(TAG, "refreshOne: skipping ${npub.take(8)} - key unavailable")
            return
        }

        val account = registrationRepository.getAccount(identity).getOrNull()
        if (account == null) {
            // Not registered or request failed — keep the cached value.
            return
        }

        val address = account.emailAddress?.takeIf { it.isNotBlank() }
            ?: account.nip05?.takeIf { it.endsWith("@$DOMAIN", ignoreCase = true) }
            ?: account.local?.takeIf { it.isNotBlank() }?.let { "$it@$DOMAIN" }

        if (address != null) {
            accountDao.setPrimaryAddress(npub, address)
            Log.i(TAG, "Primary address for ${npub.take(12)}... -> $address")
        }
    }

    companion object {
        private const val TAG = "RefreshPrimaryAddress"
        private const val DOMAIN = "desent.xyz"
    }
}
