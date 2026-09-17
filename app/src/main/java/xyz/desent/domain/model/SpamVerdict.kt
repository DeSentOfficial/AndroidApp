package xyz.desent.domain.model

/**
 * The result of running the client-side spam filter on one email.
 *
 * `reasons` are short machine-readable codes (see the scoring table in
 * `refs/SPAM_FILTER_REFERENCE.md` §"Layer 1") so they can be joined into the
 * `emails.spamReasons` column and re-split for display.
 */
data class SpamVerdict(
    val score: Double,
    val isSpam: Boolean,
    val reasons: List<String>,
    val layer: String
) {
    companion object {
        /** Verdict for messages exempt from filtering (e.g. SYSTEM confirmations). */
        val NOT_SPAM = SpamVerdict(
            score = 0.0,
            isSpam = false,
            reasons = emptyList(),
            layer = "exempt"
        )

        /** Strong "this is ham" verdict, used by allowlist overrides. */
        fun allowlisted(domain: String) = SpamVerdict(
            score = 0.0,
            isSpam = false,
            reasons = listOf("allowlist_override:$domain"),
            layer = "allowlist"
        )
    }
}

/**
 * Parsed NIP-51 spam blocklist manifest (see `refs/SPAM_LIST_REFERENCE.md`).
 *
 * Lists are lowercased on parse. `spamPatterns` are kept as raw regex source
 * strings; compilation is deferred to the classifier (patterns that fail to
 * compile are dropped with a log, never crash).
 */
data class SpamListManifest(
    val version: Long,
    val updatedAt: Long,
    val ttlHours: Int,
    val blockedDomains: List<String>,
    val allowedDomains: List<String>,
    val blockedSenders: List<String>,
    val spamPatterns: List<String>,
    val trustedBridgeDomains: List<String>
) {
    companion object {
        val EMPTY = SpamListManifest(
            version = 0,
            updatedAt = 0,
            ttlHours = 24,
            blockedDomains = emptyList(),
            allowedDomains = emptyList(),
            blockedSenders = emptyList(),
            spamPatterns = emptyList(),
            trustedBridgeDomains = emptyList()
        )
    }
}

/**
 * User-configurable filter weights + thresholds (persisted in DataStore; see
 * `refs/SPAM_FILTER_REFERENCE.md` §"Settings"). Defaults produce the scoring
 * described in the reference doc.
 */
data class SpamFilterConfig(
    val enabled: Boolean = true,
    val threshold: Double = 5.0,
    val heuristicWeight: Double = 0.6,
    val bayesianWeight: Double = 0.4,
    val layerHeuristicsEnabled: Boolean = true,
    val layerBlocklistEnabled: Boolean = true,
    val layerBayesianEnabled: Boolean = true,
    // Remote-image policy (see RemoteImagePolicy.kt). Images are blocked for
    // everyone by default; entries below are the user's own trust decisions.
    val blockRemoteImages: Boolean = true,
    val imageAllowedSenders: List<String> = emptyList(),
    val imageAllowedDomains: List<String> = emptyList(),
    val imagesAllowedForContacts: Boolean = true
)
