package xyz.desent.domain.repository

import xyz.desent.domain.model.CustodialKey
import xyz.desent.domain.model.CustodialSignupResult

/**
 * Client-custodied (username & password) account operations
 * (refs/FromServer/CUSTODIAL_ACCOUNTS.md).
 *
 * The server stores only a password-verifier hash and an opaque
 * client-encrypted nsec blob; all crypto happens on-device.
 */
interface CustodialAccountRepository {

    /**
     * GET /api/custodial/suggest — available adjective+noun username ideas
     * (public, rate-limited 30/hour/IP). Only available names are returned.
     */
    suspend fun suggestUsernames(count: Int = 5): Result<List<String>>

    /**
     * POST /api/custodial/register — NIP-98 authenticated with the *fresh
     * local key*; the caller MUST have stored that key (per-account, mirrored
     * to the active slot) before invoking this, exactly like the nsec-import
     * signup flow. [nsec] is used to build the encrypted blob and is never
     * transmitted in the clear.
     */
    suspend fun signup(
        username: String,
        password: String,
        nsec: String,
        displayName: String?,
        referralCode: String?
    ): Result<CustodialSignupResult>

    /**
     * Two-phase login + local decrypt:
     * challenge (salt/KDF params) → derive verifier → blob → AES-GCM decrypt
     * → swapped-blob guard (`pubkey(nsec) == response.pubkey`).
     *
     * Failures: [xyz.desent.data.registration.model.RegistrationError.InvalidCredentials]
     * for a wrong password or unknown user (uniform), plus the usual
     * rate-limit / lockout / disabled typed errors.
     */
    suspend fun fetchKeyWithPassword(username: String, password: String): Result<CustodialKey>

    /**
     * POST /api/custodial/credentials — password change. Proves knowledge of
     * [oldPassword] via the old verifier; the key itself never changes, so
     * this device's stored copy stays valid. Other devices must re-login.
     *
     * @param nsec the active account's stored key (blob re-encrypted under
     * the new password with a fresh salt)
     */
    suspend fun changePassword(
        username: String,
        oldPassword: String,
        newPassword: String,
        nsec: String
    ): Result<Unit>

    /**
     * Derive the CURRENT password verifier for [username] — the `old_verifier`
     * proof key rotation (and password change) demand. Fetches the live salt
     * from the login challenge and derives under the account's envelope
     * generation (v2 argon2id, or the v1 64-byte split for pre-2026-08
     * accounts). Wrong passwords surface as an ordinary verifier derivation —
     * the server is the ultimate judge.
     */
    suspend fun deriveCurrentVerifier(username: String, password: String): Result<String>
}
