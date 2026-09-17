package xyz.desent.data

import xyz.desent.crypto.Bech32Utils

/**
 * Centralized configuration for the DeSent email-bridge relay.
 *
 * The relay holds a single permanent keypair (`RELAY_PUBKEY`) used for both
 * inbound (gift-wrapped FROM the relay) and outbound (gift-wrapped TO the
 * relay) email. The relay decrypts anything addressed to its npub with its own
 * private key — there are no per-thread ephemeral keypairs.
 *
 * See refs/REPLY_AND_FORWARD.md.
 */
object RelayConfig {
    /** Hex (secp256k1 x-only) public key of the email-bridge relay. */
    const val RELAY_PUBKEY_HEX: String =
        "aabb7b42d2a08f384bbc03ee7c1864881ccc1381c81dbbd1bd22877ec86b39e3"

    /** npub (bech32) form of [RELAY_PUBKEY_HEX], computed once. */
    val RELAY_PUBKEY_NPUB: String by lazy { Bech32Utils.hexToNpub(RELAY_PUBKEY_HEX) }

    /** WebSocket endpoint of the DeSent service relay (also hosts the email/blob/alias/message APIs).
     *  Production apex host since the 2026-08 flip from `email.desent.xyz` (hard cutover — the
     *  legacy subdomain no longer resolves; Room MIGRATION_35_36 rewrites stored relay rows).
     *  This is the ONLY relay the app publishes to or syncs app data through. */
    const val EMAIL_RELAY_URL: String = "wss://desent.xyz"

    /**
     * Public content-addressed endpoint serving panel-uploaded NIP-58 badge
     * art (`https://desent.xyz/api/badges/assets/<sha256>`). Immutable
     * (sha256-keyed, `Cache-Control: immutable`) — safe to cache forever.
     * See refs/FROM_email.desent.xyz/BADGES_PROTOCOL.md §Assets endpoint.
     */
    const val BADGE_ASSETS_BASE_URL: String = "https://desent.xyz/api/badges/assets/"

    /**
     * NIP-98-authenticated server-side favicon cache
     * (`https://desent.xyz/api/favicon/<domain>` — kind 27235 GET, same
     * dialect as the alias API). One origin probe per domain per TTL serves
     * every client; positives and negatives are cached server-side for 24 h
     * (admin-tunable). `404` = probed, no usable icon. Clients fall back to
     * direct `https://<domain>/favicon.ico` probes for the session on
     * `401`/`403`/`5xx`/timeout.
     * See refs/FROM_email.desent.xyz/FAVICON_CACHE.md.
     */
    const val FAVICON_CACHE_BASE_URL: String = "https://desent.xyz/api/favicon/"

    /** Exact-match form of the decommissioned pre-flip email-relay hosts
     *  (optional trailing slash), plus the retired chat relay. */
    private val LEGACY_EMAIL_HOST = Regex("^wss://(email|mail|chat)\\.desent\\.xyz/?$")

    /**
     * Rewrites relay URLs still pointing at decommissioned DeSent hosts
     * (`email.desent.xyz` / `mail.desent.xyz` / `chat.desent.xyz`) to the
     * production apex [EMAIL_RELAY_URL]. Everything else passes through
     * untouched.
     */
    fun normalizeLegacyRelayUrl(url: String): String =
        if (LEGACY_EMAIL_HOST.matches(url)) EMAIL_RELAY_URL else url

    /**
     * Third-party relays consulted — read-only, and ONLY for kind-0 profile
     * fetches: the initial own-profile bootstrap at login
     * (see [xyz.desent.data.repository.NostrRepository.fetchOwnProfileFromRelays])
     * and contact-profile enrichment
     * (see [xyz.desent.data.contacts.ContactProfileResolverImpl]). The
     * connections are temporary and torn down after the fetch. No app data
     * is ever published or synced through these.
     *
     * Order matters — lookups fall through sequentially: `desent.xyz` is the
     * primary (also the only relay the app publishes/syncs through),
     * `relay.yadha.net` the secondary, then public coverage relays.
     */
    val PUBLIC_PROFILE_RELAYS: List<String> = listOf(
        "wss://desent.xyz",
        "wss://relay.yadha.net",
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net"
    )

    /**
     * Public read-only Nostr relay directory (JSON over HTTPS, no auth —
     * refs/FROM_directory.desent.xyz/API.md). Powers the relay-mirroring
     * relay picker: suggested online clearnet relays for the user's NIP-65
     * list. Pulled from the yadha.net host.
     */
    const val RELAY_DIRECTORY_BASE_URL: String = "https://directory.yadha.net"

    /**
     * x-only pubkey (hex) of the DeSent key that publishes the spam blocklist
     * (NIP-51 kind 30000, `d = "desent-spamlist-v1"`). The app only ever
     * verifies signatures against this key — the matching nsec stays
     * server-side. See `refs/SPAM_LIST_REFERENCE.md` §"Trust model".
     */
    const val SPAM_LIST_PUBKEY_HEX: String =
        "fe910afb2f1330b95d024857f1abd951baaedbd26450e93bd09c241ca33379a6"

    /** `d` tag identifying the spam blocklist parameterized replaceable event. */
    const val SPAM_LIST_D_TAG: String = "desent-spamlist-v1"

    /** Kind used for the spam blocklist (NIP-33 parameterized replaceable). */
    const val SPAM_LIST_KIND: Int = 30000
}
