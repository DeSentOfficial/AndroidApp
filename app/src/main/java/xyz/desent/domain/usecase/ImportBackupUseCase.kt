package xyz.desent.domain.usecase

import android.util.Log
import xyz.desent.data.RelayConfig
import xyz.desent.domain.model.Relay
import xyz.desent.domain.repository.AccountRepository
import xyz.desent.domain.repository.AuthRepository
import xyz.desent.domain.repository.KeyBackupRepository
import xyz.desent.domain.repository.RelayRepository

/**
 * Restores accounts and relays from a decrypted backup blob.
 *
 * Each account is added through [AccountRepository.addAccount] (the same path a
 * fresh login uses) with its cached profile, and the relay list is re-seeded via
 * [RelayRepository.saveRelays]. When [activateFirst] is true (post-reinstall
 * recovery flow), the first account is then fully activated through
 * [AuthRepository.login] so the session, relay connections and Nostr identity
 * come online exactly as they would after a manual login.
 *
 * The per-account `requiresBiometrics` flag is restored as-is from the blob;
 * the activation login deliberately passes `enableBiometrics = false` since
 * biometric bindings do not transfer across devices and we must not lock a
 * freshly-restored user out.
 */
class ImportBackupUseCase(
    private val keyBackupRepository: KeyBackupRepository,
    private val accountRepository: AccountRepository,
    private val authRepository: AuthRepository,
    private val relayRepository: RelayRepository,
) {

    /** Outcome of a successful [execute]. */
    data class Outcome(
        val restoredNpubs: List<String>,
        /** Npub that was fully activated, or null when [activateFirst] was false. */
        val activatedNpub: String?,
    )

    suspend fun execute(
        blob: ByteArray,
        passphrase: String,
        activateFirst: Boolean,
    ): Result<Outcome> = runCatching {
        val contents = keyBackupRepository.decrypt(blob, passphrase).getOrThrow()

        val restored = mutableListOf<String>()
        for (account in contents.accounts) {
            accountRepository.addAccount(
                npub = account.npub,
                nsec = account.nsec,
                requireBiometrics = account.requiresBiometrics,
            ).getOrThrow()

            if (account.displayName != null || account.picture != null || account.nip05 != null) {
                accountRepository.updateAccountProfile(
                    npub = account.npub,
                    displayName = account.displayName,
                    picture = account.picture,
                    nip05 = account.nip05,
                )
            }
            restored += account.npub
        }

        if (contents.relays.isNotEmpty()) {
            // Relay policy: DeSent is a consumer, not a poster. A restored
            // backup must not reintroduce third-party relays into the pool —
            // only DeSent service-relay rows survive the restore (legacy
            // hosts normalized), mirroring Room MIGRATION_47_48.
            val allowedRelays = contents.relays
                .map { it.copy(url = RelayConfig.normalizeLegacyRelayUrl(it.url)) }
                .filter { it.url == RelayConfig.EMAIL_RELAY_URL }
                .distinctBy { it.url }
            val droppedCount = contents.relays.size - allowedRelays.size
            if (droppedCount > 0) {
                Log.w(TAG, "Dropped $droppedCount non-DeSent relay(s) from backup per relay policy")
            }
            if (allowedRelays.isNotEmpty()) {
                relayRepository.saveRelays(
                    allowedRelays.map { Relay(url = it.url, isWrite = it.isWrite, isPersistent = true) }
                )
            }
        }

        val activatedNpub = if (activateFirst && contents.accounts.isNotEmpty()) {
            val first = contents.accounts.first()
            authRepository.login(first.nsec, rememberMe = true, enableBiometrics = false)
                .getOrElse {
                    Log.e(TAG, "Activation login failed for ${first.npub}, accounts still restored", it)
                    null
                }
        } else {
            null
        }

        Outcome(restoredNpubs = restored, activatedNpub = activatedNpub)
    }.onFailure { Log.e(TAG, "Backup import failed", it) }

    companion object {
        private const val TAG = "ImportBackupUseCase"
    }
}
