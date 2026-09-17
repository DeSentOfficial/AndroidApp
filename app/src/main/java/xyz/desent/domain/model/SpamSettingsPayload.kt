package xyz.desent.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Encrypted payloads synced via NIP-78 kind 30078 so a user's spam-filter
 * policy follows them across devices. Content is NIP-44 self-encrypted and
 * published only to the email-bridge relay (`wss://desent.xyz`), which
 * force-scopes reads to the authenticated pubkey.
 *
 * Two namespaces:
 *  - `desent:spam-settings`           — scalar config, LWW by `created_at`.
 *  - `desent:spam-tokens:manifest`    — token-snapshot manifest.
 *  - `desent:spam-tokens:<i>`         — token-snapshot shard `i`.
 *
 * The token snapshot merges across devices via element-wise `max` of the
 * spam/ham counts (a convergent state-CRDT): training is never lost and never
 * double-counted. See refs/SPAM_FILTER_REFERENCE.md.
 */

// ---------------------------------------------------------------------------
// Config namespace (`desent:spam-settings`)
// ---------------------------------------------------------------------------

/** Wire shape inside the `desent:spam-settings` 30078 ciphertext. */
@Serializable
data class SpamSettingsPayload(
    val enabled: Boolean,
    val threshold: Double,
    @SerialName("heuristic_weight") val heuristicWeight: Double,
    @SerialName("bayesian_weight") val bayesianWeight: Double,
    @SerialName("layer_heuristics") val layerHeuristicsEnabled: Boolean,
    @SerialName("layer_blocklist") val layerBlocklistEnabled: Boolean,
    @SerialName("layer_bayesian") val layerBayesianEnabled: Boolean,
    // Remote-image policy. Defaulted so payloads written by older builds
    // (which lack these fields) still decode — missing = "images not managed".
    @SerialName("block_remote_images") val blockRemoteImages: Boolean = true,
    @SerialName("image_allowed_senders") val imageAllowedSenders: List<String> = emptyList(),
    @SerialName("image_allowed_domains") val imageAllowedDomains: List<String> = emptyList(),
    @SerialName("images_allowed_for_contacts") val imagesAllowedForContacts: Boolean = true,
    // Personal block/allow rules from the user's own mark-as-spam / not-spam
    // feedback. Defaulted so payloads written by older builds still decode.
    @SerialName("blocked_domains") val blockedDomains: List<String> = emptyList(),
    @SerialName("allowed_domains") val allowedDomains: List<String> = emptyList(),
    @SerialName("blocked_senders") val blockedSenders: List<String> = emptyList(),
    @SerialName("allowed_senders") val allowedSenders: List<String> = emptyList(),
    /** Epoch seconds — the event `created_at` echoed for last-write-wins. */
    @SerialName("updated_at") val updatedAt: Long
)

// ---------------------------------------------------------------------------
// Token snapshot namespace (`desent:spam-tokens:*`)
// ---------------------------------------------------------------------------

/**
 * One Bayesian token row on the wire. Hashes are SHA-256 hex (lowercase), so
 * the payload carries no email plaintext — safe to store on a relay even before
 * the NIP-44 self-encryption layer is applied.
 */
@Serializable
data class SpamTokenEntry(
    val h: String,   // SHA-256 hex token hash
    val s: Int,      // spam count
    val m: Int       // ham (non-spam) count
)

/** One shard of a token snapshot (`d = "desent:spam-tokens:<index>"`). */
@Serializable
data class SpamTokenShard(
    val index: Int,
    val entries: List<SpamTokenEntry>
)

/**
 * Manifest for a sharded token snapshot (`d = "desent:spam-tokens:manifest"`).
 * Readers use [shardCount] to know how many `desent:spam-tokens:<i>` shards to
 * reassemble, and [updatedAt] as the snapshot watermark.
 */
@Serializable
data class SpamTokenManifest(
    @SerialName("shard_count") val shardCount: Int,
    @SerialName("total_tokens") val totalTokens: Int,
    @SerialName("updated_at") val updatedAt: Long
)
