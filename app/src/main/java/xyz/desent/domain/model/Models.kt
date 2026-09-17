package xyz.desent.domain.model

import android.net.Uri
import kotlinx.serialization.Serializable

data class User(
    val npub: String,
    val name: String?,
    val displayName: String?,
    val about: String?,
    val picture: String?,
    val banner: String? = null,
    val website: String? = null,
    val lud06: String? = null,
    val lud16: String? = null,
    val nip05: String?,
    val nip05Verified: Boolean = false,
    val createdAt: Long,
    val lastUpdated: Long = System.currentTimeMillis(),
    val relayListJson: String? = null
)

data class AccountCreationRequest(
    val local: String,
    val displayName: String,
    val about: String?,
    val pictureUri: Uri?,
    /** Invite code (DS-XXXXXX-XXXXXX); required in referral mode, optional in open. */
    val referralCode: String? = null
)

data class AccountCreationResult(
    val nsec: String,
    val npub: String,
    val user: User
)

data class Follow(
    val followerNpub: String,
    val followingNpub: String,
    val isFavorite: Boolean = false,
    val createdAt: Long,
    val petname: String? = null,
    val isLocalOnly: Boolean = false
)

/**
 * Unwrapped NIP-17 GiftWrap content
 *
 * [senderNpub] is the seal signer's npub (the real sender), never the
 * gift-wrap's one-time outer author. [createdAt] is the rumor's creation
 * time (unix seconds); 0 when absent on the wire.
 */
data class UnwrappedContent(
    val content: String,
    val senderNpub: String,
    val kind: Int,
    val tags: List<List<String>>,
    val createdAt: Long = 0
)

data class Relay(
    val url: String,
    val isActive: Boolean = true,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val failureCount: Int = 0,
    val lastConnectedAt: Long? = null,
    val isWrite: Boolean = false,
    val isPersistent: Boolean = true,
    val nip11Metadata: Nip11Metadata? = null,
    /** When the cached NIP-11 metadata was fetched; null when never fetched. */
    val nip11CachedAt: Long? = null
)

enum class ConnectionStatus {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    ERROR
}

/** A relay listed in the DeSent relay directory (directory.desent.xyz). */
data class RelayDirectoryEntry(
    val url: String,
    val host: String? = null,
    val status: RelayDirectoryStatus = RelayDirectoryStatus.UNKNOWN,
    val name: String? = null,
    val icon: String? = null,
    val isPremium: Boolean = false
)

/** Directory probe status for a relay; UNKNOWN doubles as "not probed". */
enum class RelayDirectoryStatus {
    ONLINE,
    OFFLINE,
    UNKNOWN,
    UNPROBEABLE
}

@Serializable
data class Nip11Metadata(
    val name: String? = null,
    val description: String? = null,
    val pubkey: String? = null,
    val contact: String? = null,
    val supportedNips: List<Int>? = null,
    val version: String? = null,
    val icon: String? = null,
    val software: String? = null,
    val relayCountries: List<String>? = null,
    val languageTags: List<String>? = null,
    val postingPolicy: String? = null,
    val limitations: Limitations? = null,
    val fees: Fees? = null,
    val payments: String? = null
)

@Serializable
data class Limitations(
    val maxMessageLength: Long? = null,
    val maxSubscriptionIdLength: Long? = null,
    val maxFilterTags: Int? = null,
    val maxLimit: Long? = null,
    val maxSubtotal: Long? = null,
    val minPrefix: Int? = null,
    val maxEventTags: Int? = null,
    val maxContentLength: Long? = null,
    val minPowDifficulty: Int? = null,
    val authRequired: Boolean? = null,
    val paymentRequired: Boolean? = null
)

@Serializable
data class Fees(
    val admission: List<Fee>? = null,
    val subscription: List<Fee>? = null,
    val publication: List<Fee>? = null
)

@Serializable
data class Fee(
    val amount: Long,
    val unit: String,
    val period: String? = null
)
