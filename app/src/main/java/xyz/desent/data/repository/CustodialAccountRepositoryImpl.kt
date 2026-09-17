package xyz.desent.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.desent.crypto.CustodialCrypto
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.registration.RegistrationClient
import xyz.desent.data.registration.model.CustodialCredentialsRequest
import xyz.desent.data.registration.model.CustodialLoginChallengeRequest
import xyz.desent.data.registration.model.CustodialLoginRequest
import xyz.desent.data.registration.model.CustodialRegisterRequest
import xyz.desent.data.registration.model.RegistrationError
import xyz.desent.domain.model.CustodialKey
import xyz.desent.domain.model.CustodialSignupResult
import xyz.desent.domain.repository.CustodialAccountRepository
import java.util.Base64

/**
 * Client-custodied account implementation. All KDF/AEAD work happens here via
 * [CustodialCrypto] and [xyz.desent.crypto.Nip49]; the wire client handles
 * transport only.
 *
 * Envelope generations (CUSTODIAL_ACCOUNTS.md §1/§3): `v: 2` accounts use the
 * NIP-49 ncryptsec blob + an independent argon2id verifier; `v: 1` accounts
 * (pre-2026-08-28) keep the legacy 64-byte-split derivation for login until
 * the web client re-wraps them. Signups and password changes only ever emit
 * v2.
 *
 * Memory hygiene: key buffers are zeroized in `finally` blocks and no key
 * material, password, verifier, or blob content is ever logged.
 */
class CustodialAccountRepositoryImpl(
    private val registrationClient: RegistrationClient,
    private val secureKeyManager: SecureKeyManager
) : CustodialAccountRepository {

    override suspend fun suggestUsernames(count: Int): Result<List<String>> =
        registrationClient.suggestUsernames(count).map { it.names }

    override suspend fun signup(
        username: String,
        password: String,
        nsec: String,
        displayName: String?,
        referralCode: String?
    ): Result<CustodialSignupResult> {
        val built = deriveInBackground("signup blob build") {
            CustodialCrypto.buildBlob(nsec, password)
        }
        return registrationClient.custodialRegister(
            CustodialRegisterRequest(
                username = username.trim(),
                verifier = built.verifier,
                blob = built.blob,
                // The server compares verbatim after trimming — always uppercase.
                referralCode = referralCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
                displayName = displayName?.takeIf { it.isNotBlank() }
            )
        ).map {
            CustodialSignupResult(username = it.username, nip05 = it.nip05, npub = it.npub)
        }
    }

    override suspend fun fetchKeyWithPassword(
        username: String,
        password: String
    ): Result<CustodialKey> {
        val trimmed = username.trim()

        // Phase 1 — challenge (always 200; unknown users get a decoy salt).
        val challenge = registrationClient.custodialLoginChallenge(
            CustodialLoginChallengeRequest(username = trimmed)
        ).getOrElse { return Result.failure(it) }

        if (challenge.kdf != CustodialCrypto.KDF_ARGON2ID) {
            Log.w(TAG, "Challenge returned unsupported KDF '${challenge.kdf}'")
            return Result.failure(
                RegistrationError.Unknown("Unsupported key derivation: ${challenge.kdf}")
            )
        }

        val salt = try {
            Base64.getDecoder().decode(challenge.salt)
        } catch (e: IllegalArgumentException) {
            return Result.failure(RegistrationError.Unknown("Malformed challenge salt"))
        }

        // v: 1 also covers unknown usernames (decoy responses report v: 1);
        // their phase-2 fails with the same uniform 401 as a wrong password.
        return if (challenge.v >= 2) {
            fetchKeyV2(trimmed, password, challenge.kdfParams, salt)
        } else {
            fetchKeyV1(trimmed, password, challenge.kdfParams, challenge.salt, salt)
        }
    }

    /** v2: independent argon2id verifier + NIP-49 ncryptsec blob. */
    private suspend fun fetchKeyV2(
        username: String,
        password: String,
        params: CustodialCrypto.KdfParams,
        salt: ByteArray
    ): Result<CustodialKey> {
        val verifier = deriveInBackground("v2 verifier derivation") {
            CustodialCrypto.deriveVerifier(password, salt, params)
        }
        val response = registrationClient.custodialLogin(
            CustodialLoginRequest(username = username, verifier = verifier)
        ).getOrElse { return Result.failure(it) }

        // Phase 3 — local decrypt. The Poly1305 tag failure IS the on-device
        // password check.
        val nsec = try {
            CustodialCrypto.decryptBlob(response.blob, password)
        } catch (e: CustodialCrypto.WrongPasswordException) {
            return Result.failure(RegistrationError.InvalidCredentials)
        } catch (e: IllegalArgumentException) {
            return Result.failure(RegistrationError.Unknown("Malformed key blob"))
        }

        return finishLogin(nsec, response.npub, response.username, response.nip05, legacyEnvelope = false)
    }

    /** v1 legacy: argon2id 64-byte master split (kept until the web re-wrap). */
    private suspend fun fetchKeyV1(
        username: String,
        password: String,
        params: CustodialCrypto.KdfParams,
        challengeSaltB64: String,
        salt: ByteArray
    ): Result<CustodialKey> {
        val master = deriveInBackground("v1 login derivation") {
            CustodialCrypto.deriveMaster(password, salt, params)
        }
        try {
            val verifier = CustodialCrypto.verifierFromMaster(master)
            val response = registrationClient.custodialLogin(
                CustodialLoginRequest(username = username, verifier = verifier)
            ).getOrElse { return Result.failure(it) }

            val nsec = try {
                if (response.blob.matchesChallenge(challengeSaltB64, params)) {
                    CustodialCrypto.decryptBlobWithMaster(response.blob, master)
                } else {
                    // Envelope disagrees with the challenge — derive from the
                    // envelope's own salt/params (spec: envelope is authoritative
                    // for existing accounts).
                    CustodialCrypto.decryptBlob(response.blob, password)
                }
            } catch (e: CustodialCrypto.WrongPasswordException) {
                return Result.failure(RegistrationError.InvalidCredentials)
            } catch (e: IllegalArgumentException) {
                return Result.failure(RegistrationError.Unknown("Malformed key blob"))
            }

            return finishLogin(nsec, response.npub, response.username, response.nip05, legacyEnvelope = true)
        } finally {
            CustodialCrypto.zeroize(master)
        }
    }

    /** Swapped-blob guard + the domain result. */
    private suspend fun finishLogin(
        nsec: String,
        expectedNpub: String,
        username: String,
        nip05: String,
        legacyEnvelope: Boolean
    ): Result<CustodialKey> {
        val derivedNpub = secureKeyManager.validateNSEC(nsec).getOrNull()
            ?: return Result.failure(RegistrationError.Unknown("Key blob contains an invalid key"))
        if (derivedNpub != expectedNpub) {
            Log.w(TAG, "Swapped-blob guard failed for user $username")
            return Result.failure(RegistrationError.Unknown("Key blob integrity check failed"))
        }
        return Result.success(
            CustodialKey(
                nsec = nsec,
                npub = derivedNpub,
                username = username,
                nip05 = nip05,
                legacyEnvelope = legacyEnvelope
            )
        )
    }

    /**
     * The old verifier under the CURRENT envelope's salt and generation —
     * fetched from the same challenge endpoint as login. Shared by password
     * change and key rotation (the rotate call's `old_verifier`).
     */
    override suspend fun deriveCurrentVerifier(username: String, password: String): Result<String> {
        val challenge = registrationClient.custodialLoginChallenge(
            CustodialLoginChallengeRequest(username = username.trim())
        ).getOrElse { return Result.failure(it) }

        if (challenge.kdf != CustodialCrypto.KDF_ARGON2ID) {
            return Result.failure(
                RegistrationError.Unknown("Unsupported key derivation: ${challenge.kdf}")
            )
        }

        val salt = try {
            Base64.getDecoder().decode(challenge.salt)
        } catch (e: IllegalArgumentException) {
            return Result.failure(RegistrationError.Unknown("Malformed challenge salt"))
        }

        return Result.success(
            deriveInBackground("old verifier derivation") {
                if (challenge.v >= 2) {
                    CustodialCrypto.deriveVerifier(password, salt, challenge.kdfParams)
                } else {
                    val master = CustodialCrypto.deriveMaster(password, salt, challenge.kdfParams)
                    try {
                        CustodialCrypto.verifierFromMaster(master)
                    } finally {
                        CustodialCrypto.zeroize(master)
                    }
                }
            }
        )
    }

    override suspend fun changePassword(
        username: String,
        oldPassword: String,
        newPassword: String,
        nsec: String
    ): Result<Unit> {
        val oldVerifier = deriveCurrentVerifier(username, oldPassword)
            .getOrElse { return Result.failure(it) }

        // Fresh salt + v2 envelope for the new password; the nsec never changes.
        val built = deriveInBackground("new blob build") {
            CustodialCrypto.buildBlob(nsec, newPassword)
        }

        return registrationClient.changeCustodialCredentials(
            CustodialCredentialsRequest(
                oldVerifier = oldVerifier,
                newVerifier = built.verifier,
                newBlob = built.blob
            )
        ).map { Unit }
    }

    /** Argon2 (64 MiB) is CPU-bound — always off the main thread. */
    private suspend fun <T> deriveInBackground(what: String, block: () -> T): T {
        Log.d(TAG, "KDF derivation: $what")
        return withContext(Dispatchers.Default) { block() }
    }

    companion object {
        private const val TAG = "CustodialAccountRepo"
    }
}
