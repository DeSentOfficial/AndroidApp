package xyz.desent.data.pgp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/pgp/config` — public feature gate (fail-closed client-side). */
@Serializable
data class PgpConfigResponse(
    @SerialName("pgp_enabled") val pgpEnabled: Boolean = false
)

/** `PUT /api/pgp/key` request body: the armored PUBLIC half only. */
@Serializable
data class PgpPutKeyRequest(
    @SerialName("public_key") val publicKey: String
)

/** `GET /api/pgp/key` / `PUT` success — the caller's registered key. */
@Serializable
data class PgpKeyResponse(
    val fingerprint: String? = null,
    @SerialName("public_key") val publicKey: String = "",
    @SerialName("created_at") val createdAt: String? = null
)

/**
 * `GET /api/pgp/wkd-lookup?email=` — recipient key discovery through the
 * relay's WKD proxy. Serviced domains answer `armored`; external domains
 * answer `key_base64` (binary OpenPGP packets, base64). `found: false` on
 * any miss.
 */
@Serializable
data class PgpWkdLookupResponse(
    val found: Boolean = false,
    val armored: String? = null,
    @SerialName("key_base64") val keyBase64: String? = null,
    val domain: String? = null
)

// RFC 7807-ish error envelope: { "detail": { "error": "...", "message": "..." } }
@Serializable
data class PgpErrorDetail(
    val error: String? = null,
    /** User-safe validation message accompanying 422 invalid_key. */
    val message: String? = null
)

@Serializable
data class PgpErrorResponse(
    val detail: PgpErrorDetail? = null
)

/** Typed failures raised by [xyz.desent.data.pgp.PgpClient]. */
sealed class PgpError(message: String) : Exception(message) {
    /** 403 pgp_disabled — the relay's master switch is off. */
    object PgpDisabled : PgpError("PGP is disabled on this relay")

    /** 404 no_pgp_key — nothing registered for WKD on this account. */
    object NoPgpKey : PgpError("No PGP key registered")

    /** 422 invalid_key — server-side validation failed; [reason] is user-safe. */
    class InvalidKey(val reason: String?) : PgpError(reason ?: "Invalid PGP key")

    /** 429 — 10 key writes / 5 min (key endpoints), 30 lookups / min (WKD). */
    object RateLimited : PgpError("Too many PGP requests — try again later")

    object Unauthorized : PgpError("Authentication failed — try re-login")
    class Server(message: String, val code: Int) : PgpError(message)
    class Unknown(message: String) : PgpError(message)
}
