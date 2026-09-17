package xyz.desent.data.nip46

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shared JSON config for NIP-46 envelopes (lenient: payloads vary by method). */
internal val nip46Json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

// ---------------------------------------------------------------------------
// Envelopes
// ---------------------------------------------------------------------------

@Serializable
data class Nip46Request(
    val id: String,
    val method: String,
    val params: List<JsonElement> = emptyList()
)

@Serializable
data class Nip46Response(
    val id: String,
    val result: JsonElement? = null,
    val error: Nip46Error? = null
)

@Serializable
data class Nip46Error(
    val code: String,
    val message: String
)

object Nip46Method {
    const val CONNECT = "connect"
    const val GET_PUBLIC_KEY = "get_public_key"
    const val SIGN_EVENT = "sign_event"
    const val NIP04_ENCRYPT = "nip04_encrypt"
    const val NIP04_DECRYPT = "nip04_decrypt"
    const val NIP44_ENCRYPT = "nip44_encrypt"
    const val NIP44_DECRYPT = "nip44_decrypt"
    const val NIP59_UNWRAP = "nip59_unwrap"
    const val DELEGATE = "delegate"
    const val DISCONNECT = "disconnect"
    const val PING = "ping"
    const val GET_RELAYS = "get_relays"
    const val SWITCH_RELAYS = "switch_relays"
    const val LOGOUT = "logout"
}

object Nip46ErrorCode {
    const val DENIED = "denied"
    const val POLICY = "policy"
    const val UNPAIRED = "unpaired"
    const val EXPIRED = "expired"
    const val UNSUPPORTED = "unsupported"
    const val BAD_REQUEST = "bad_request"
}

/**
 * Which wire transport a NIP-46 conversation rides:
 *  - [GIFT_WRAP]: DeSent-internal 3-layer kind-1059 chain on the desent relay
 *    (metadata-private; used by the web inbox and relay-initiated signing).
 *  - [RAW]: standard NIP-46 kind-24133 events (NIP-44 v2 content, visible
 *    p-tags) on whatever relays the pairing agreed on — the transport every
 *    external client (Amethyst/NDK, Damus, iris, nsec.app…) speaks.
 */
enum class Nip46Transport {
    GIFT_WRAP,
    RAW;

    val isRaw: Boolean get() = this == RAW
}

/**
 * A semantic (transport-neutral) response. Encoded to the wire format by
 * [encodeResponse], which applies the per-transport conventions:
 *  - GIFT_WRAP keeps the DeSent-internal shape (object `result`, object error
 *    `{code,message}`) that the web inbox parses.
 *  - RAW follows the standard NIP-46 shape: `result` is a string (a JSON
 *    stringified object for structured results) and `error` is a plain string.
 */
data class Nip46Payload(
    val id: String,
    val result: JsonElement? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    val isError: Boolean get() = errorCode != null
}

/** Build a successful payload with a structured (object/array) result. */
fun nip46PayloadResult(id: String, result: JsonElement): Nip46Payload =
    Nip46Payload(id = id, result = result)

/** Build a successful payload with a plain-string result (hex, "ack", ciphertext…). */
fun nip46PayloadString(id: String, value: String): Nip46Payload =
    Nip46Payload(id = id, result = JsonPrimitive(value))

/** Build a null-result payload (e.g. `get_relays`/`switch_relays` with nothing to change). */
fun nip46PayloadNull(id: String): Nip46Payload =
    Nip46Payload(id = id, result = JsonNull)

/** Build an error payload. */
fun nip46PayloadError(id: String, code: String, message: String): Nip46Payload =
    Nip46Payload(id = id, errorCode = code, errorMessage = message)

/**
 * Encode [payload] for the given transport.
 *
 * RAW (standard NIP-46, NDK and friends): structured results are serialized to
 * a JSON string, errors become `"code: message"` strings.
 * GIFT_WRAP (DeSent internal): results pass through verbatim, errors stay
 * `{code, message}` objects.
 */
fun encodeResponse(payload: Nip46Payload, transport: Nip46Transport): String {
    val obj = kotlinx.serialization.json.buildJsonObject {
        put("id", JsonPrimitive(payload.id))
        when {
            payload.isError -> if (transport.isRaw) {
                put("error", JsonPrimitive("${payload.errorCode}: ${payload.errorMessage}"))
            } else {
                put(
                    "error",
                    kotlinx.serialization.json.buildJsonObject {
                        put("code", JsonPrimitive(payload.errorCode))
                        put("message", JsonPrimitive(payload.errorMessage))
                    }
                )
            }
            else -> {
                val element = payload.result ?: JsonNull
                if (transport.isRaw && element !is JsonPrimitive && element !is JsonNull) {
                    // Structured result → JSON stringified per the spec's wire format.
                    put("result", JsonPrimitive(nip46Json.encodeToString(JsonElement.serializer(), element)))
                } else {
                    put("result", element)
                }
            }
        }
    }
    return nip46Json.encodeToString(JsonObject.serializer(), obj)
}

// Legacy gift-wrap-path builders (kept for callers/tests that build the
// DeSent-internal object-shape responses directly).
/** Build a `result` response JSON string. */
fun nip46Result(id: String, result: JsonElement): String =
    nip46Json.encodeToString(Nip46Response.serializer(), Nip46Response(id = id, result = result))

/** Build an `error` response JSON string (object error — gift-wrap transport only). */
fun nip46Error(id: String, code: String, message: String): String =
    nip46Json.encodeToString(Nip46Response.serializer(), Nip46Response(id = id, error = Nip46Error(code, message)))

/** Convenience for a plain-string result (e.g. get_public_key). */
fun nip46StringResult(id: String, value: String): String =
    nip46Result(id, JsonPrimitive(value))

// ---------------------------------------------------------------------------
// Requested permissions ("perms" / connect params[2])
// ---------------------------------------------------------------------------

/** One entry of a `perms=` / requested-perms list, e.g. `sign_event:13`. */
data class Nip46Perm(val method: String, val eventKind: Long? = null)

/** Parse a comma-separated `method[:kind]` permission list. Blank entries are skipped. */
fun parsePerms(raw: String?): List<Nip46Perm> =
    raw?.split(',')?.mapNotNull { entry ->
        val trimmed = entry.trim()
        when {
            trimmed.isEmpty() -> null
            else -> {
                val parts = trimmed.split(':', limit = 2)
                Nip46Perm(
                    method = parts[0].trim().lowercase(),
                    eventKind = parts.getOrNull(1)?.trim()?.toLongOrNull()
                )
            }
        }
    } ?: emptyList()

// ---------------------------------------------------------------------------
// Permission profiles + policy engine (NIP46_BUNKER_CLIENT.md §4)
// ---------------------------------------------------------------------------

enum class Nip46PermissionProfile {
    DESENT_INBOX,
    DESENT_MANAGE,
    GENERAL_NOSTR,
    IDENTITY_ONLY;

    companion object {
        /** Parse the `permissions=` value from a DeSent QR; null → unknown (caller decides default). */
        fun fromQr(value: String?): Nip46PermissionProfile? = when (value?.lowercase()) {
            "desent-inbox" -> DESENT_INBOX
            "desent-manage" -> DESENT_MANAGE
            "general-nostr" -> GENERAL_NOSTR
            "identity-only" -> IDENTITY_ONLY
            null, "" -> null
            else -> null
        }

        val wireName: Nip46PermissionProfile.() -> String = {
            when (this) {
                DESENT_INBOX -> "desent-inbox"
                DESENT_MANAGE -> "desent-manage"
                GENERAL_NOSTR -> "general-nostr"
                IDENTITY_ONLY -> "identity-only"
            }
        }

        /**
         * Suggested profile for a scanned pairing URI. DeSent-internal QRs carry
         * `permissions=`; standard external QRs carry at most a `perms=` list.
         * Default for external (RAW) transport is `general-nostr` — the profile
         * external clients expect — and `desent-inbox` for the gift-wrapped path.
         */
        fun suggested(permissionsParam: String?, perms: List<Nip46Perm>, transport: Nip46Transport): Nip46PermissionProfile {
            fromQr(permissionsParam)?.let { return it }
            if (perms.isEmpty()) {
                return if (transport.isRaw) GENERAL_NOSTR else DESENT_INBOX
            }
            // Any requested method/kind outside the inbox auto-approve set ⇒ external client.
            val inboxKinds = setOf(0L, 5L, 22242L, 27235L)
            val external = perms.any { perm ->
                when {
                    perm.method == Nip46Method.SIGN_EVENT && perm.eventKind != null ->
                        perm.eventKind !in inboxKinds
                    perm.method == Nip46Method.SIGN_EVENT -> true
                    perm.method == Nip46Method.GET_PUBLIC_KEY -> false
                    else -> true
                }
            }
            return if (external) GENERAL_NOSTR else DESENT_INBOX
        }
    }
}

enum class Nip46Decision { ALLOW, PROMPT, DENY }

/** Keys for the per-pairing "always allow" grants ("remember my choice"). */
object Nip46Grant {
    /** Grant key for a method call — `sign_event` is refined by event kind. */
    fun key(method: String, eventKind: Long? = null): String =
        if (eventKind != null && method == Nip46Method.SIGN_EVENT) "sign_event:$eventKind" else method
}

object Nip46Policy {

    /**
     * @param method one of [Nip46Method].
     * @param eventKind the unsigned event's kind, if [method] is [Nip46Method.SIGN_EVENT].
     * @param grants per-pairing "always allow" grants (see [Nip46Grant.key]).
     *        A matching grant upgrades a PROMPT to ALLOW ("remember my choice");
     *        it can never override a DENY (e.g. identity-only stays signing-free).
     */
    fun decide(
        profile: Nip46PermissionProfile,
        method: String,
        eventKind: Long? = null,
        grants: Map<String, Boolean> = emptyMap()
    ): Nip46Decision {
        val base = when (method) {
            Nip46Method.GET_PUBLIC_KEY -> Nip46Decision.ALLOW

            // Informational / lifecycle methods: no key material is exposed.
            Nip46Method.PING, Nip46Method.GET_RELAYS, Nip46Method.SWITCH_RELAYS -> Nip46Decision.ALLOW
            Nip46Method.LOGOUT -> Nip46Decision.ALLOW

            Nip46Method.SIGN_EVENT -> when {
                profile == Nip46PermissionProfile.IDENTITY_ONLY -> Nip46Decision.DENY
                eventKind != null && isAutoApproveKind(profile, eventKind) -> Nip46Decision.ALLOW
                else -> Nip46Decision.PROMPT
            }

            Nip46Method.NIP04_ENCRYPT, Nip46Method.NIP04_DECRYPT,
            Nip46Method.NIP44_ENCRYPT, Nip46Method.NIP44_DECRYPT -> when (profile) {
                Nip46PermissionProfile.IDENTITY_ONLY -> Nip46Decision.DENY
                else -> Nip46Decision.PROMPT
            }

            // Unwrap a NIP-59 gift wrap addressed to the user (returns the rumor).
            // Auto-allow for the inbox profiles (the web inbox decrypts emails with
            // one round trip instead of many nip44_decrypt calls); prompt for
            // general-nostr; deny for identity-only.
            Nip46Method.NIP59_UNWRAP -> when (profile) {
                Nip46PermissionProfile.DESENT_INBOX, Nip46PermissionProfile.DESENT_MANAGE -> Nip46Decision.ALLOW
                Nip46PermissionProfile.GENERAL_NOSTR -> Nip46Decision.PROMPT
                Nip46PermissionProfile.IDENTITY_ONLY -> Nip46Decision.DENY
            }

            Nip46Method.DELEGATE -> when (profile) {
                Nip46PermissionProfile.GENERAL_NOSTR -> Nip46Decision.PROMPT
                else -> Nip46Decision.DENY
            }

            // connect is sent BY the signer (or handled as a pairing lifecycle
            // event before policy runs); disconnect/logout likewise.
            Nip46Method.CONNECT, Nip46Method.DISCONNECT -> Nip46Decision.ALLOW

            else -> Nip46Decision.DENY
        }
        return if (base == Nip46Decision.PROMPT && grants[Nip46Grant.key(method, eventKind)] == true) {
            Nip46Decision.ALLOW
        } else {
            base
        }
    }

    fun isAutoApproveKind(profile: Nip46PermissionProfile, kind: Long): Boolean = when (profile) {
        Nip46PermissionProfile.DESENT_INBOX ->
            kind in setOf(0L, 5L, 22242L, 27235L)
        Nip46PermissionProfile.DESENT_MANAGE ->
            kind in setOf(0L, 5L, 22242L, 27235L)
        Nip46PermissionProfile.GENERAL_NOSTR ->
            kind in setOf(0L, 1L, 3L, 5L, 6L, 7L, 9735L) ||
                kind in 10_000L..19_999L ||   // regular replaceable
                kind in 30_000L..39_999L      // parameterized replaceable
        Nip46PermissionProfile.IDENTITY_ONLY -> false
    }
}

// ---------------------------------------------------------------------------
// Domain: pairing record + pending prompt
// ---------------------------------------------------------------------------

/**
 * One approved NIP-46 consumer. Persisted by [Nip46PairingStore].
 * The handshake secret is stored only as a sha256 hash (audit, never reused).
 */
@Serializable
data class Nip46Pairing(
    val sessionPubkey: String,      // consumer client pubkey (hex); primary key
    val userPubkey: String,         // the signer (user) pubkey (hex)
    val label: String,
    val profile: Nip46PermissionProfile,
    val pairedAt: Long,             // unix seconds
    val lastUsedAt: Long,
    val expiresAt: Long?,           // unix seconds, or null = until revoked
    val revoked: Boolean = false,
    val signCount: Int = 0,
    val denyCount: Int = 0,
    val lastKindSigned: Long? = null,
    val pairingSecretHash: String,  // sha256-hex of the handshake secret
    /** Relays the RAW transport uses for this pairing (empty for gift-wrapped pairings). */
    val relays: List<String> = emptyList(),
    /** Which wire transport this conversation rides. Legacy records default to GIFT_WRAP. */
    val transport: Nip46Transport = Nip46Transport.GIFT_WRAP,
    /** Per-pairing "always allow" grants from the sign-prompt checkbox. Key: [Nip46Grant.key]. */
    val grants: Map<String, Boolean> = emptyMap()
)

/** A sign request awaiting a user decision (surfaced to the UI / notification). */
data class Nip46SignPrompt(
    val requestId: String,
    val sessionPubkey: String,
    val label: String,
    val userPubkey: String,
    val method: String,
    val unsignedEventJson: String?,   // raw params[0] for sign_event previews
    val eventKind: Long?
)

/**
 * How long a prompt-required request may sit unanswered before the bunker
 * auto-denies it. Shared by the phone dialog's countdown and the wear push
 * (which stamps the matching expiresAt) so both expire at the same instant.
 */
const val PROMPT_TIMEOUT_SECONDS = 120L

// ---------------------------------------------------------------------------
// Helpers for reading params[0] as an unsigned event
// ---------------------------------------------------------------------------

/**
 * Normalize a `sign_event` params[0] to a JsonObject. Standard clients (and the
 * spec example) pass a **JSON string** containing the event; the DeSent web
 * inbox passes a nested object. Both are accepted.
 */
fun parseUnsignedEventParam(element: JsonElement?): JsonObject? = when (element) {
    is JsonObject -> element
    is JsonPrimitive -> element.contentOrNull?.let { raw ->
        runCatching { nip46Json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }
    else -> null
}

/** @return the `kind` field of a sign_event params[0] object, or null. */
fun JsonElement.readKind(): Long? = try {
    parseUnsignedEventParam(this)?.get("kind")?.jsonPrimitive?.contentOrNull?.toLong()
} catch (e: Exception) { null }

/** @return a params[0] object pretty-printed for the sign-prompt preview. */
fun JsonElement.toPreview(): String = try {
    val obj = parseUnsignedEventParam(this) ?: return toString()
    nip46Json.encodeToString(JsonObject.serializer(), obj)
} catch (e: Exception) { toString() }
