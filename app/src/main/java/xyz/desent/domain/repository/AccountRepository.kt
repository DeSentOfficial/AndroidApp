package xyz.desent.domain.repository

import xyz.desent.domain.model.Account

/**
 * Manages the set of locally-saved accounts.
 *
 * The "active" account (whose key is loaded for signing and whose DMs/emails
 * are subscribed) is tracked separately by
 * [xyz.desent.data.session.SessionManager]; this repository owns the persistent
 * account *list* and per-account data lifecycle.
 */
interface AccountRepository {

    /** Add (or refresh) a saved account row and store its key material. */
    suspend fun addAccount(
        npub: String,
        nsec: String,
        requireBiometrics: Boolean
    ): Result<Unit>

    /** Refresh the cached profile fields (display name/picture/nip05) from a kind-0 update. */
    suspend fun updateAccountProfile(
        npub: String,
        displayName: String?,
        picture: String?,
        nip05: String?
    )

    /** Set/clear the biometrics-required flag for a saved account. */
    suspend fun setAccountBiometricRequirement(npub: String, requireBiometrics: Boolean)

    /**
     * Record the custodial (username & password) username that provisioned
     * this account on this device, or null to clear it. Non-null enables the
     * Settings "change password" entry for this account.
     */
    suspend fun setCustodialUsername(npub: String, username: String?)

    /** The custodial username recorded for this account, or null. */
    suspend fun getCustodialUsername(npub: String): String?

    /** Remove one account's key material, account row, and scoped data. */
    suspend fun removeAccount(npub: String): Result<Unit>

    /**
     * Local half of a key rotation (migration 040): re-point every
     * account-scoped row from [oldNpub] to [newNpub], store [newNsec] as the
     * new account's key (activating it), record the custodial [username] the
     * rotation ends under, and drop the old key material unless
     * [keepOldKeyMaterial] is set (conversion keeps it for linked sign-in).
     *
     * Cached `emails` rows are wiped rather than renamed: restored wraps carry
     * new event ids (the `emails` PK) and the relay re-delivers them to the
     * new key's gift-wrap subscription. Everything else (notes, contacts,
     * calendar, tokens, wallet caches, outbox) is re-owned in place.
     */
    suspend fun rotateLocalAccount(
        oldNpub: String,
        newNpub: String,
        newNsec: String,
        username: String?,
        keepOldKeyMaterial: Boolean
    ): Result<Unit>

    /** Remove every saved account and all per-account data (device reset). */
    suspend fun removeAllAccounts(): Result<Unit>

    /**
     * One-shot migration from the legacy single-account storage: if the old
     * single-slot nsec/npub is present and the `accounts` table is empty,
     * seed an account row from it and stamp the empty `ownerNpub` columns on
     * previously-global tables. Idempotent.
     *
     * @return the npub that was seeded, or null if there was nothing to seed
     * (either no legacy key, or already seeded).
     */
    suspend fun seedFromLegacySingleAccount(): String?

    /** Observe the active account's full record, or null if none active. */
    fun observeAccount(npub: String): kotlinx.coroutines.flow.Flow<Account?>

    /** Observe every saved account ( freshest cached profile per row), most-recently-active first. */
    fun observeAllAccounts(): kotlinx.coroutines.flow.Flow<List<Account>>
}
