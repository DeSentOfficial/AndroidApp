package xyz.desent.domain.repository

import android.content.Context
import xyz.desent.domain.model.AccountCreationRequest
import xyz.desent.domain.model.AccountCreationResult
import xyz.desent.domain.model.CustodialAccountCreationRequest
import xyz.desent.domain.model.CustodialLoginResult

/**
 * Result of [AuthRepository.logout].
 *
 * Multi-account support means "logout" is no longer necessarily a transition
 * to the logged-out state — it may instead be a removal of the active account
 * followed by an automatic switch to the next saved one.
 */
sealed class LogoutResult {
    /** The active account was removed and [switchedTo] is now active. */
    data class Switched(val switchedTo: String) : LogoutResult()

    /** The active account was the last one; the device is now logged out. */
    object FullyLoggedOut : LogoutResult()
}

/**
 * Outcome of [AuthRepository.probeLinkedNostrAccount].
 */
sealed interface LinkedNostrProbe {
    /**
     * The key IS linked to a custodial account — hold these fields for the
     * password phase ([AuthRepository.completeLinkedNostrLogin]).
     */
    data class Linked(
        /** The pasted (linked) key's npub — for UI display only. */
        val linkedNpub: String,
        val handoff: String,
        val kdf: String,
        val kdfParams: xyz.desent.crypto.CustodialCrypto.KdfParams,
        /** base64 verifier salt from the login challenge. */
        val saltB64: String,
        /** Envelope generation (2 = NIP-49 ncryptsec). */
        val v: Int
    ) : LinkedNostrProbe

    /** The key is an ordinary keyholder — use the plain key-import login. */
    object NotLinked : LinkedNostrProbe
}

/**
 * A memory-only keypair for the pre-account vanity checkout
 * (CUSTODIAL_ACCOUNTS.md §4.1): the vanity request row is keyed to the
 * pubkey, so every retry of a paid signup MUST reuse the same key. The key
 * is generated but NOT stored or activated — it only lives in the caller's
 * scope until the register call stores it.
 */
data class PreparedSignupKey(
    val nsec: String,
    val npub: String,
    /** Prebuilt NIP-98 signer for the vanity + payments calls. */
    val identity: nostr.id.Identity
)

interface AuthRepository {

    suspend fun login(nsec: String, rememberMe: Boolean, enableBiometrics: Boolean): Result<String>

    /**
     * NIP-49 key login: decrypt the pasted `ncryptsec1…` string with
     * [password] locally (scrypt, ~1–2 s of CPU) and provision the resulting
     * key through the ordinary [login] pipeline. The password is used only
     * for decryption and is never persisted; failures surface as
     * [xyz.desent.crypto.Nip49.WrongPasswordException] or
     * [xyz.desent.crypto.Nip49.MalformedNcryptsecException] for UI mapping.
     */
    suspend fun loginWithNcryptsec(
        ncryptsec: String,
        password: String,
        rememberMe: Boolean,
        enableBiometrics: Boolean
    ): Result<String>

    /**
     * Client-custodied (username & password) login — a one-time-per-device
     * provisioning step: the encrypted key blob is fetched, decrypted locally,
     * and handed to the ordinary [login] pipeline. On success the account is
     * marked with its custodial username on this device.
     * [CustodialLoginResult.legacyEnvelope] flags pre-NIP-49 (v1) accounts
     * that should be upgraded via a one-time web sign-in.
     */
    suspend fun custodialLogin(
        username: String,
        password: String,
        rememberMe: Boolean,
        enableBiometrics: Boolean
    ): Result<CustodialLoginResult>

    /**
     * Generate a memory-only keypair for the pre-account vanity checkout —
     * nothing is stored, activated, or published (see [PreparedSignupKey]).
     * The caller keeps it across checkout retries and finally hands its nsec
     * to [createAccount]/[createCustodialAccount] via `existingNsec`.
     */
    suspend fun prepareSignupKey(): Result<PreparedSignupKey>

    /**
     * @param existingNsec reuse a [PreparedSignupKey]'s key instead of
     * generating a fresh one — required after a paid vanity checkout, whose
     * approval is bound to that key's pubkey.
     */
    suspend fun createAccount(
        request: AccountCreationRequest,
        context: Context,
        existingNsec: String? = null
    ): Result<AccountCreationResult>

    /**
     * Username & password signup: generates the keypair locally, uploads the
     * password-encrypted blob + verifier, and provisions the account exactly
     * like [createAccount] (relay connections, profile row, kind-0 publish).
     *
     * @param existingNsec reuse a [PreparedSignupKey]'s key instead of
     * generating a fresh one — required after a paid vanity checkout, whose
     * approval is bound to that key's pubkey.
     */
    suspend fun createCustodialAccount(
        request: CustodialAccountCreationRequest,
        context: Context,
        existingNsec: String? = null
    ): Result<AccountCreationResult>

    /**
     * Change the custodial password for the ACTIVE account (no-op failure if
     * it is not a custodial account). The key itself never changes; other
     * devices must re-login with the new password.
     */
    suspend fun changeCustodialPassword(oldPassword: String, newPassword: String): Result<Unit>

    /**
     * Linked Nostr-identity login, probe phase (NOSTR_CUSTODIAL.md §3): does
     * [nsec]'s key open a linked custodial account? The probe signs the
     * kind-22242 challenge with the pasted key and asks `/api/nostr/login`.
     *
     * - [LinkedNostrProbe.Linked] → show the password prompt; finish with
     *   [completeLinkedNostrLogin].
     * - [LinkedNostrProbe.NotLinked] → fall back to the ordinary
     *   key-import [login].
     */
    suspend fun probeLinkedNostrAccount(nsec: String): Result<LinkedNostrProbe>

    /**
     * Linked Nostr-identity login, password phase: verify the password
     * against the probe's salt (argon2id verifier), fetch + decrypt the
     * linked account's blob, and provision it through the ordinary [login]
     * pipeline. The handoff is single-use and expires within 5 minutes.
     */
    suspend fun completeLinkedNostrLogin(
        probe: LinkedNostrProbe.Linked,
        password: String,
        rememberMe: Boolean,
        enableBiometrics: Boolean
    ): Result<CustodialLoginResult>

    suspend fun logout(): LogoutResult

    suspend fun validateNsec(nsec: String): Result<String>

    suspend fun getNpubFromNsec(nsec: String): String?

    suspend fun isNsecStored(): Boolean

    suspend fun getStoredNpub(): String?

    suspend fun reconnectRelays()
}
