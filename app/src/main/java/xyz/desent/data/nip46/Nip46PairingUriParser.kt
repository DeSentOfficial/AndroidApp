package xyz.desent.data.nip46

import xyz.desent.crypto.Bech32Utils
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLDecoder

/**
 * Parsed `nostrconnect://` pairing URI (NIP-46 "client-initiated" connection
 * token; NIP46_BUNKER_CLIENT.md §3.1 + the standard spec as spoken by Amethyst,
 * iris, nsec.app…).
 *
 * DeSent-internal QRs (all relays on desent.xyz) keep the metadata-private
 * gift-wrap transport; anything else is standard [Nip46Transport.RAW] NIP-46 on
 * the client's chosen relays.
 */
data class Nip46PairingUri(
    val sessionPubkey: String,   // 64-hex x-only pubkey of the client keypair
    val relayUrls: List<String>, // normalized wss://…/ , deduped, QR order
    val relayUrl: String,        // first entry (back-compat with single-relay callers)
    val secret: String,          // single-use handshake token (hex or short random string)
    val label: String?,          // from `name` / `label` / `metadata.name`
    val permissions: String?,    // DeSent profile name (desent-inbox, …)
    val perms: List<Nip46Perm>,  // standard requested-permissions list (`perms=`)
    val expiry: Long?,           // unix seconds
    val transport: Nip46Transport
)

object Nip46PairingUriParser {

    /**
     * Relays that keep the DeSent gift-wrapped (metadata-private) transport.
     * The legacy `email`/`mail` subdomain entries stay so pre-flip QRs keep
     * parsing as GIFT_WRAP (they normalize to the apex relay at connect time).
     */
    val DESSENT_RELAYS = setOf(
        "wss://desent.xyz/",
        "wss://email.desent.xyz/",
        "wss://mail.desent.xyz/"
    )

    private val HEX64 = Regex("[0-9a-fA-F]{64}")
    private const val MAX_RELAYS = 8
    private const val SECRET_MIN = 8
    private const val SECRET_MAX = 512

    fun parse(input: String): Result<Nip46PairingUri> = runCatching {
        val raw = input.trim()
        if (!raw.startsWith("nostrconnect://")) {
            throw IllegalArgumentException("Not a nostrconnect:// URI")
        }

        val rest = raw.removePrefix("nostrconnect://")
        val (hostRaw, queryRaw) = rest.split("?", limit = 2).let {
            if (it.size == 2) it[0] to it[1] else it[0] to ""
        }

        val sessionPubkey = normalizePubkey(hostRaw)
            ?: throw IllegalArgumentException("Client pubkey must be 64 hex chars (or npub)")

        val params = parseQuery(queryRaw)

        val relays = params.filter { it.first == "relay" }
            .map { normalizeRelay(it.second) }
            .distinct()
        if (relays.isEmpty()) {
            throw IllegalArgumentException("Missing relay parameter")
        }
        if (relays.size > MAX_RELAYS) {
            throw IllegalArgumentException("Too many relays (max $MAX_RELAYS)")
        }

        val secret = params.firstOrNull { it.first == "secret" }?.second
            ?: throw IllegalArgumentException("Missing secret parameter")
        if (secret.length !in SECRET_MIN..SECRET_MAX) {
            throw IllegalArgumentException("Secret must be $SECRET_MIN–$SECRET_MAX characters")
        }

        val expiry = params.firstOrNull { it.first == "expiry" }?.second?.toLongOrNull()
        if (expiry != null && expiry * 1000L < System.currentTimeMillis()) {
            throw IllegalArgumentException("This pairing QR has expired")
        }

        val transport = if (relays.all { it in DESSENT_RELAYS }) Nip46Transport.GIFT_WRAP else Nip46Transport.RAW

        if (transport == Nip46Transport.RAW) {
            // The app never publishes to third-party relays, so a pairing that
            // wants to talk over foreign relays cannot be served. Point the
            // consumer at a bunker:// code from DeSent instead (it embeds the
            // DeSent relay and works with any NIP-46 client, incl. Amethyst).
            throw IllegalArgumentException(
                "This app only pairs over desent.xyz. Ask the app for a bunker:// code or use DeSent's 'Show my bunker code' instead."
            )
        }

        val permissions = params.firstOrNull { it.first == "permissions" }?.second?.take(120)
        val perms = parsePerms(params.firstOrNull { it.first == "perms" }?.second)
        val label = resolveLabel(params)

        Nip46PairingUri(
            sessionPubkey = sessionPubkey,
            relayUrls = relays,
            relayUrl = relays.first(),
            secret = secret,
            label = label,
            permissions = permissions,
            perms = perms,
            expiry = expiry,
            transport = transport
        )
    }

    /** Accept a 64-hex x-only pubkey or an npub (some clients bech32-encode the host). */
    private fun normalizePubkey(host: String): String? {
        val trimmed = host.trim()
        if (HEX64.matches(trimmed)) return trimmed.lowercase()
        if (trimmed.startsWith("npub1")) {
            return runCatching { Bech32Utils.npubToHex(trimmed).lowercase() }.getOrNull()
        }
        return null
    }

    /** Label precedence: standard `name`, DeSent `label`, then `metadata` JSON `name`. */
    private fun resolveLabel(params: List<Pair<String, String>>): String? {
        params.firstOrNull { it.first == "name" && it.second.isNotBlank() }?.second?.let { return it.take(120) }
        params.firstOrNull { it.first == "label" && it.second.isNotBlank() }?.second?.let { return it.take(120) }
        params.firstOrNull { it.first == "metadata" }?.second?.let { metadata ->
            val name = runCatching {
                nip46Json.parseToJsonElement(metadata).jsonObject["name"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (!name.isNullOrBlank()) return name.take(120)
        }
        return null
    }

    /** Minimal `a=1&b=2&a=3` parser (order-preserving, repeated keys kept). */
    private fun parseQuery(query: String): List<Pair<String, String>> =
        query.split('&').mapNotNull { entry ->
            if (entry.isBlank()) return@mapNotNull null
            val idx = entry.indexOf('=')
            val key = if (idx >= 0) entry.substring(0, idx) else entry
            val value = if (idx >= 0) entry.substring(idx + 1) else ""
            decode(key).trim() to decode(value).trim()
        }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    fun normalizeRelay(relay: String): String {
        val r = relay.trim().removeSuffix("/")
        return when {
            r.startsWith("wss://") -> "$r/"
            r.startsWith("ws://") -> "wss://" + r.removePrefix("ws://") + "/"
            r.startsWith("http://") -> "wss://" + r.removePrefix("http://") + "/"
            r.startsWith("https://") -> "wss://" + r.removePrefix("https://") + "/"
            else -> "wss://$r/"
        }
    }
}
