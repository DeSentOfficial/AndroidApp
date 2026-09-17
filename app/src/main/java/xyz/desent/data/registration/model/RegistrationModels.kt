package xyz.desent.data.registration.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.desent.crypto.CustodialCrypto

@Serializable
data class RegisterRequest(
    val local: String,
    val domain: String? = null,
    @SerialName("display_name") val displayName: String?,
    val picture: String? = null,
    val about: String? = null,
    @SerialName("referral_code") val referralCode: String? = null
)

@Serializable
data class UpdateProfileRequest(
    @SerialName("display_name") val displayName: String? = null,
    val about: String? = null,
    val picture: String? = null,
    val website: String? = null
)

/**
 * `/api/me` + `/api/register` response. Modeled conservatively; the server may
 * return additional fields (ignored via ignoreUnknownKeys). Field names follow
 * REGISTRATION_API_REFERENCE.md (nip05_username/nip05_domain/email_address/
 * picture_url/userlevel); the legacy local/picture/tier aliases are kept so an
 * older server shape still parses.
 */
@Serializable
data class MeResponse(
    val local: String? = null,
    @SerialName("nip05_username") val nip05Username: String? = null,
    @SerialName("nip05_domain") val nip05Domain: String? = null,
    @SerialName("email_address") val emailAddress: String? = null,
    @SerialName("nip05_verified") val nip05Verified: Boolean? = null,
    @SerialName("display_name") val displayName: String? = null,
    val picture: String? = null,
    @SerialName("picture_url") val pictureUrl: String? = null,
    val about: String? = null,
    val tier: String? = null,
    val userlevel: String? = null,
    val pubkey: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("created_at") val createdAt: Long? = null
) {
    val resolvedLocal: String? get() = nip05Username ?: local
    val resolvedPicture: String? get() = pictureUrl ?: picture
    val resolvedTier: String? get() = userlevel ?: tier
    val nip05: String? get() = resolvedLocal?.let { user ->
        nip05Domain?.let { domain -> "$user@$domain" } ?: "$user@desent.xyz"
    }
}

/** GET /register/mode — `{"mode": "open" | "referral" | "disabled"}` (public). */
@Serializable
data class RegisterModeResponse(
    val mode: String? = null
)

/** GET /register/referral?code= — `{"valid": true|false}` (public, rate-limited). */
@Serializable
data class ReferralValidityResponse(
    val valid: Boolean = false
)

/** One invite code row from GET /referrals. `used_by` is a raw hex pubkey. */
@Serializable
data class ReferralCodeResponse(
    val code: String,
    val used: Boolean = false,
    @SerialName("used_by") val usedBy: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("used_at") val usedAt: String? = null
)

/** GET /referrals — the caller's invite codes plus the per-user cap. */
@Serializable
data class ReferralsResponse(
    val codes: List<ReferralCodeResponse> = emptyList(),
    val cap: Int = 0
)

@Serializable
data class AvailableResponse(
    val available: Boolean = false,
    val local: String? = null,
    /** One-time price in sats for short (vanity) names; 0 = free name. */
    @SerialName("price_sats") val priceSats: Long = 0,
    val vanity: Boolean = false
)

@Serializable
data class ProfilePictureResponse(
    val url: String? = null,
    val picture: String? = null
) {
    val resolvedUrl: String? get() = url ?: picture
}

// ---------------------------------------------------------------------------
// Client-custodied accounts (refs/FromServer/CUSTODIAL_ACCOUNTS.md)
// ---------------------------------------------------------------------------

/** POST /api/custodial/register (NIP-98 with the fresh local key). */
@Serializable
data class CustodialRegisterRequest(
    val username: String,
    val domain: String? = null,
    val verifier: String,
    val blob: CustodialCrypto.Blob,
    @SerialName("referral_code") val referralCode: String? = null,
    @SerialName("display_name") val displayName: String? = null
)

/** `201` from POST /api/custodial/register. */
@Serializable
data class CustodialRegisterResponse(
    val registered: Boolean = false,
    val username: String,
    val domain: String,
    val nip05: String,
    val pubkey: String,
    val npub: String,
    val mode: String? = null,
    @SerialName("referral_redeemed") val referralRedeemed: Boolean? = null
)

/** POST /api/custodial/login/challenge — phase 1 (fetch KDF salt + params). */
@Serializable
data class CustodialLoginChallengeRequest(
    val username: String,
    val domain: String? = null
)

/**
 * Challenge response — always `200`, even for unknown usernames (decoy salt),
 * so the payload shape is identical either way.
 *
 * [v] is the envelope generation: `2` = current (NIP-49 ncryptsec), `1` = a
 * pre-2026-08-28 account (or an unknown username — indistinguishable by
 * design). Defaults to 1 so an older server without the field parses safely.
 */
@Serializable
data class CustodialLoginChallengeResponse(
    val kdf: String = CustodialCrypto.KDF_ARGON2ID,
    @SerialName("kdf_params") val kdfParams: CustodialCrypto.KdfParams = CustodialCrypto.KdfParams(),
    val salt: String,
    val v: Int = 1,
    val domain: String? = null
)

/** POST /api/custodial/login — phase 2 (verifier exchange → blob). */
@Serializable
data class CustodialLoginRequest(
    val username: String,
    val domain: String? = null,
    val verifier: String
)

/** `200` from POST /api/custodial/login; the blob is returned verbatim. */
@Serializable
data class CustodialLoginResponse(
    val username: String,
    val domain: String,
    val nip05: String,
    val pubkey: String,
    val npub: String,
    val blob: CustodialCrypto.Blob
)

/** GET /api/custodial/suggest — available adjective+noun names (≥ 8 chars). */
@Serializable
data class CustodialSuggestResponse(
    val names: List<String> = emptyList(),
    val domain: String? = null,
    @SerialName("min_length") val minLength: Int = 8
)

/** POST /api/custodial/credentials (NIP-98 + old verifier — password change). */
@Serializable
data class CustodialCredentialsRequest(
    @SerialName("old_verifier") val oldVerifier: String,
    @SerialName("new_verifier") val newVerifier: String,
    @SerialName("new_blob") val newBlob: CustodialCrypto.Blob
)

/** `200 {"ok": true}` from POST /api/custodial/credentials. */
@Serializable
data class CustodialCredentialsOkResponse(
    val ok: Boolean = false,
    val username: String? = null,
    val domain: String? = null
)

// RFC 7807-ish: { "detail": { "error": "...", ... } } (or a plain string).
@Serializable
data class RegistrationErrorDetail(
    val error: String? = null,
    val detail: String? = null,
    val message: String? = null,
    val domain: String? = null,
    val length: Int? = null,
    val tier: String? = null,
    @SerialName("price_sats") val priceSats: Long? = null,
    val ladder: Map<String, Long>? = null,
    @SerialName("free_length") val freeLength: Int? = null,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Long? = null
)

@Serializable
data class RegistrationErrorResponse(
    val detail: RegistrationErrorDetail? = null
)

/** Typed failures raised by [xyz.desent.data.registration.RegistrationClient]. */
sealed class RegistrationError(message: String) : Exception(message) {
    object NotRegistered : RegistrationError("Not registered")
    object Taken : RegistrationError("That address is already taken")
    object Reserved : RegistrationError("That local-part is reserved")
    object InvalidLocalPart : RegistrationError("Invalid local-part format")
    object InvalidDomain : RegistrationError("That domain is not available")
    object Unauthorized : RegistrationError("Authentication failed")
    object RegistrationDisabled : RegistrationError("Registration is closed")
    class RegistrationDisabledDomain(val domain: String?) :
        RegistrationError("Registration is closed on ${domain ?: "that domain"}")
    object ReferralRequired : RegistrationError("An invite code is required")
    object InvalidReferralCode : RegistrationError("Invalid or already used invite code")
    object AccountDisabled : RegistrationError("Account suspended")
    object PubkeyNotRegistered : RegistrationError("No DeSent address claimed on this relay")
    object AlreadyRegistered : RegistrationError("This key already owns an address")
    class RateLimited(val retryAfterSeconds: Long?) :
        RegistrationError("Too many attempts, try again later")
    /**
     * 402 `vanity_price` from POST /register: the short local-part is priced.
     * Approval is operator-mediated and out-of-band — surface an explanation,
     * not a generic error.
     */
    class VanityPrice(
        val length: Int,
        val priceSats: Long,
        val ladder: Map<String, Long>,
        val freeLength: Int
    ) : RegistrationError("Short addresses carry a one-time price")
    class Server(message: String, val code: Int) : RegistrationError(message)
    class Unknown(message: String) : RegistrationError(message)

    // -- Custodial-account failures -----------------------------------------
    /** 403 `custodial_disabled` — the operator turned the feature off. */
    object CustodialDisabled : RegistrationError("Username & password accounts are disabled on this server")
    /** 401 `invalid_credentials` — uniform for unknown user / wrong password. */
    object InvalidCredentials : RegistrationError("Incorrect username or password")
    /** 423 `account_locked` — 5 consecutive failures lock for 15 minutes. */
    class AccountLocked(val retryAfterSeconds: Long?) :
        RegistrationError("Too many failed attempts — account temporarily locked")
    /** 422 `too_short` — custodial usernames must be ≥ 8 characters. */
    data class TooShort(val minLength: Int) :
        RegistrationError("Username must be at least $minLength characters")
    /** 413 `blob_too_large` — envelope exceeds the server's 8 KiB cap. */
    object BlobTooLarge : RegistrationError("Encrypted key blob is too large")
    /** 404 `not_custodial` — password change on a non-custodial account. */
    object NotCustodial : RegistrationError("This account has no password")
    /** 422 `invalid_verifier` — malformed verifier hex. */
    object InvalidVerifier : RegistrationError("Invalid verifier")
}
