package xyz.desent.data.registration.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.desent.crypto.CustodialCrypto

// ---------------------------------------------------------------------------
// Linked Nostr-identity accounts (migration 042)
// refs/FROM_email.desent.xyz/NOSTR_CUSTODIAL.md
// ---------------------------------------------------------------------------

/** POST /api/nostr/challenge (public) → `{nonce, event:{kind, content, tags}}`. */
@Serializable
data class NostrLinkChallengeResponse(
    val nonce: String,
    val event: NostrLinkEventTemplate
)

/** POST /api/nostr/login — the signed 22242 proof goes in as a raw event object. */
@Serializable
data class NostrLinkLoginRequest(
    val nonce: String,
    val event: kotlinx.serialization.json.JsonObject
)

/** POST /api/nostr/login/verify — the handoff + argon2id verifier. */
@Serializable
data class NostrLinkVerifyRequest(
    val handoff: String,
    val verifier: String
)

/** The unsigned kind-22242 proof template the server wants signed. */
@Serializable
data class NostrLinkEventTemplate(
    val kind: Int = 22242,
    val content: String = "desent-nostr-link-v1",
    val tags: List<List<String>> = emptyList()
)

/** POST /api/nostr/login → `200 {kdf, kdf_params, salt, v, handoff}` | `404 not_linked`. */
@Serializable
data class NostrLinkLoginResponse(
    val kdf: String = CustodialCrypto.KDF_ARGON2ID,
    @SerialName("kdf_params") val kdfParams: CustodialCrypto.KdfParams = CustodialCrypto.KdfParams(),
    val salt: String,
    val v: Int = 2,
    /** 5-minute HMAC handoff consumed by /verify. */
    val handoff: String
)

/** POST /api/nostr/login/verify → blob handback, same shape as custodial login. */
@Serializable
data class NostrLinkVerifyResponse(
    val username: String? = null,
    val domain: String? = null,
    val nip05: String? = null,
    val pubkey: String,
    val npub: String,
    val blob: CustodialCrypto.Blob
)

/** Typed failures raised by [xyz.desent.data.registration.NostrLinkClient]. */
sealed class NostrLinkError(message: String) : Exception(message) {
    /** 404 — the key is not linked to any account; fall back to the plain key path. */
    object NotLinked : NostrLinkError("Key is not linked to an account")
    /** 401 — bad signature, wrong kind/content, stale nonce. */
    object InvalidProof : NostrLinkError("The linking proof was rejected")
    object InvalidNonce : NostrLinkError("The challenge expired — try again")
    object InvalidCredentials : NostrLinkError("Incorrect password")
    class AccountLocked(val retryAfterSeconds: Long?) :
        NostrLinkError("Too many failed attempts — account temporarily locked")
    object AccountDisabled : NostrLinkError("Account suspended")
    object NostrLinkDisabled : NostrLinkError("Linked-key sign-in is disabled on this server")
    object CustodialDisabled : NostrLinkError("Username & password accounts are disabled on this server")
    class RateLimited(val retryAfterSeconds: Long?) :
        NostrLinkError("Too many attempts — try again later")
    class Server(message: String, val code: Int) : NostrLinkError(message)
    class Unknown(message: String) : NostrLinkError(message)
}
