package xyz.desent.data.registration.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.desent.crypto.CustodialCrypto

// ---------------------------------------------------------------------------
// Key rotation ("Roll Your Keys", migration 040)
// refs/FROM_email.desent.xyz/KEY_ROTATION.md
// ---------------------------------------------------------------------------

/** POST /api/account/key-rotate/challenge → `{nonce, proof_message, expires_in}`. */
@Serializable
data class KeyRotateChallengeResponse(
    val nonce: String,
    @SerialName("proof_message") val proofMessage: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null
)

/**
 * POST /api/account/key-rotate. Every Android rotation ends custodial:
 * `new_verifier` + `new_blob` are always sent (ANDROID_KEY_ROTATION.md §2).
 */
@Serializable
data class KeyRotateRequest(
    val nonce: String,
    @SerialName("new_pubkey") val newPubkey: String,
    @SerialName("new_key_proof") val newKeyProof: String,
    @SerialName("migrate_mail") val migrateMail: Boolean = true,
    /** Required when the account has a custodial row (password re-proof). */
    @SerialName("old_verifier") val oldVerifier: String? = null,
    @SerialName("new_verifier") val newVerifier: String,
    @SerialName("new_blob") val newBlob: CustodialCrypto.Blob,
    /** Non-custodial → custodial conversion: keep the old key as a linked login identity (migration 042). */
    @SerialName("keep_old_identity") val keepOldIdentity: Boolean? = null
)

/** `200` from POST /api/account/key-rotate. */
@Serializable
data class KeyRotateResponse(
    val ok: Boolean = false,
    val pubkey: String? = null,
    val npub: String? = null,
    @SerialName("migrated_mail") val migratedMail: Boolean? = null,
    val custodial: Boolean? = null,
    @SerialName("cleanup_required") val cleanupRequired: Boolean? = null,
    @SerialName("identity_linked") val identityLinked: Boolean? = null
)

/** `200` from POST /api/account/key-rotate/restore. */
@Serializable
data class KeyRotateRestoreResponse(
    val ok: Boolean = false,
    val restored: Int = 0,
    val skipped: Int = 0
)

/** `200` from POST /api/account/key-rotate/cleanup. */
@Serializable
data class KeyRotateCleanupResponse(
    val ok: Boolean = false,
    @SerialName("old_pubkey") val oldPubkey: String? = null
)

/** Typed failures raised by [xyz.desent.data.registration.KeyRotationClient]. */
sealed class KeyRotationError(message: String) : Exception(message) {
    /** 402 `premium_required` — upsell; a lapsed yearly plan is free tier immediately. */
    class PremiumRequired(val tier: String?) :
        KeyRotationError("Key rotation is a premium feature")
    /** One rotation per account per 24 h (chained old→new→newer counts). */
    object RotationCooldown : KeyRotationError("Keys were rolled recently — try again later")
    /** 409 `pubkey_taken` — the freshly generated key collides (retry with a new one). */
    object PubkeyTaken : KeyRotationError("That key is already registered")
    /** 401 `invalid_credentials` — wrong old password (increments the shared lockout). */
    object InvalidCredentials : KeyRotationError("Current password is incorrect")
    /** 401 `invalid_nonce` — challenge expired / single-use / unknown. */
    object InvalidNonce : KeyRotationError("The rotation challenge expired — start again")
    /** 422 `invalid_new_key_proof` — the schnorr proof-of-possession failed. */
    object InvalidNewKeyProof : KeyRotationError("New-key proof rejected")
    /** 403 `key_rotation_disabled` — operator switch off. */
    object KeyRotationDisabled : KeyRotationError("Key rotation is disabled on this server")
    /** 422 `keep_identity_not_allowed` — existing custodial rows cannot keep the old key. */
    object KeepIdentityNotAllowed :
        KeyRotationError("Only key-only accounts can keep their old key as a login")
    /** 422 `keep_identity_needs_conversion` — flag set without the conversion fields. */
    object KeepIdentityNeedsConversion :
        KeyRotationError("Keep-identity requires the password conversion fields")
    /** 403 `nostr_link_disabled`. */
    object NostrLinkDisabled : KeyRotationError("Linked-key sign-in is disabled on this server")
    /** 409 `identity_already_linked` — the old key is linked to another account. */
    object IdentityAlreadyLinked :
        KeyRotationError("That key is already linked to another account")
    /** 403 `custodial_disabled` — conversion branch only. */
    object CustodialDisabled : KeyRotationError("Username & password accounts are disabled on this server")
    /** 423 `account_locked` (shared 5-fail / 15-min lockout). */
    class AccountLocked(val retryAfterSeconds: Long?) :
        KeyRotationError("Too many failed attempts — account temporarily locked")
    class RateLimited(val retryAfterSeconds: Long?) :
        KeyRotationError("Too many attempts — try again later")
    class Server(message: String, val code: Int) : KeyRotationError(message)
    class Unknown(message: String) : KeyRotationError(message)
}
