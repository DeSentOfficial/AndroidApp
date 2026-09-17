package xyz.desent.domain.model

/**
 * Client-custodied account models (refs/FromServer/CUSTODIAL_ACCOUNTS.md).
 *
 * These are the post-decryption domain shapes — the encrypted blob, salt and
 * verifier never leave the data layer.
 */

/**
 * The decrypted key material fetched by a custodial (username + password)
 * login. [nsec] is sensitive: it exists only in memory between the blob
 * decrypt and hand-off to the ordinary key-storage pipeline.
 *
 * [legacyEnvelope] is true when the server still holds a v1 (pre-NIP-49)
 * blob for this account — the UI uses it to suggest a one-time web sign-in
 * that upgrades the envelope.
 */
data class CustodialKey(
    val nsec: String,
    val npub: String,
    val username: String,
    val nip05: String,
    val legacyEnvelope: Boolean = false
)

/** Result of [xyz.desent.domain.repository.AuthRepository.custodialLogin]. */
data class CustodialLoginResult(
    val npub: String,
    val legacyEnvelope: Boolean = false
)

/** Result of a successful `POST /api/custodial/register`. */
data class CustodialSignupResult(
    val username: String,
    val nip05: String,
    val npub: String
)

/**
 * Signup request for the username & password onboarding path (alternative to
 * [AccountCreationRequest]). The password never leaves the device — only the
 * Argon2id verifier and the locally-encrypted nsec blob are transmitted.
 */
data class CustodialAccountCreationRequest(
    val username: String,
    val password: String,
    val displayName: String? = null,
    val referralCode: String? = null
)
