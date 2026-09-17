package xyz.desent.data.alias.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AliasDto(
    val id: Long,
    @SerialName("alias_email") val aliasEmail: String,
    @SerialName("alias_local") val aliasLocal: String,
    val label: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class AliasListResponse(
    val aliases: List<AliasDto> = emptyList(),
    val used: Int = 0,
    /** Null = unlimited (lifetime tier). */
    val cap: Int? = null,
    val tier: String = "free",
    @SerialName("paid_until") val paidUntil: String? = null
)

@Serializable
data class TierInfoResponse(
    val tier: String = "free",
    /** Effective cap (free_cap + slots_owned); null = unlimited (lifetime). */
    val cap: Int? = null,
    val used: Int = 0,
    @SerialName("free_cap") val freeCap: Int? = null,
    @SerialName("slots_owned") val slotsOwned: Int? = null,
    @SerialName("email_domain") val emailDomain: String = "",
    @SerialName("storage_used") val storageUsed: Long? = null,
    @SerialName("storage_cap") val storageCap: Long? = null,
    /** Renewal date of a yearly plan; null = lifetime/free (migration 033). */
    @SerialName("paid_until") val paidUntil: String? = null,
    /** DM web-of-trust relay default radius (DM_WEB_OF_TRUST.md §3.4). */
    @SerialName("wot_default_max_hops") val wotDefaultMaxHops: Int? = null,
    /** Resolved radius for the caller; null = filter off / free tier. */
    @SerialName("wot_effective_max_hops") val wotEffectiveMaxHops: Int? = null,
    /**
     * Key rotation: master switch AND entitled — active paid tier OR the
     * one-off add-on (ANDROID_KEY_ROTATION.md, migration 057).
     */
    @SerialName("key_rotation") val keyRotation: Boolean? = null,
    /** Caller owns the one-off key-rotation add-on (vs plan inclusion). */
    @SerialName("key_rotation_purchased") val keyRotationPurchased: Boolean? = null,
    /** Add-on on sale (master + Strike + sales switch); false = never render a buy button. */
    @SerialName("key_rotation_purchase_enabled") val keyRotationPurchaseEnabled: Boolean? = null,
    @SerialName("key_rotation_price_sats") val keyRotationPriceSats: Long? = null,
    /** "sats" or "usd" — how the operator priced the add-on. */
    @SerialName("key_rotation_price_mode") val keyRotationPriceMode: String? = null,
    /** Exact USD sticker, e.g. "5.00"; null in sats mode. Display only — never convert. */
    @SerialName("key_rotation_price_usd") val keyRotationPriceUsd: String? = null,
    /** AI agents master switch (ANDROID_AI_AGENTS.md §1). Missing = off (fail-closed). */
    @SerialName("agents") val agents: Boolean? = null,
    /** DM-relay fan-out usable NOW (master switch AND entitled; ANDROID_DM_FANOUT.md §3). */
    @SerialName("dm_fanout") val dmFanout: Boolean? = null,
    /** Caller owns the one-off mirroring add-on (picks the locked-state message). */
    @SerialName("dm_fanout_purchased") val dmFanoutPurchased: Boolean? = null,
    /** Add-on on sale (master + Strike + sales switch); false = never render a buy button. */
    @SerialName("fanout_purchase_enabled") val fanoutPurchaseEnabled: Boolean? = null,
    @SerialName("fanout_price_sats") val fanoutPriceSats: Long? = null,
    /** "sats" or "usd" — how the operator priced the add-on. */
    @SerialName("fanout_price_mode") val fanoutPriceMode: String? = null,
    /** Exact USD sticker, e.g. "5.00"; null in sats mode. Display only — never convert. */
    @SerialName("fanout_price_usd") val fanoutPriceUsd: String? = null
)

@Serializable
data class ConfigResponse(
    @SerialName("email_domain") val emailDomain: String = "desent.xyz",
    @SerialName("vanity_free_length") val vanityFreeLength: Int = 8,
    @SerialName("vanity_ladder") val vanityLadder: Map<String, Long> = emptyMap(),
    @SerialName("slot_price_sats") val slotPriceSats: Long? = null,
    /** "sats" or "usd" — how the operator priced the slot group. */
    @SerialName("slot_price_mode") val slotPriceMode: String? = null,
    /** Exact USD sticker per slot, e.g. "0.50"; null in sats mode. */
    @SerialName("slot_price_usd") val slotPriceUsd: String? = null,
    /** Per-length USD stickers; partial (missing keys keep their sats prices). */
    @SerialName("vanity_ladder_usd") val vanityLadderUsd: Map<String, String?> = emptyMap(),
    // One-off feature add-on prices also ride this public config (ANDROID_PAYMENTS.md §2).
    // Buy paths gate on tier-info instead (authenticated, per-caller); these are display-only.
    @SerialName("fanout_purchase_enabled") val fanoutPurchaseEnabled: Boolean? = null,
    @SerialName("fanout_price_sats") val fanoutPriceSats: Long? = null,
    @SerialName("fanout_price_mode") val fanoutPriceMode: String? = null,
    @SerialName("fanout_price_usd") val fanoutPriceUsd: String? = null,
    @SerialName("key_rotation_purchase_enabled") val keyRotationPurchaseEnabled: Boolean? = null,
    @SerialName("key_rotation_price_sats") val keyRotationPriceSats: Long? = null,
    @SerialName("key_rotation_price_mode") val keyRotationPriceMode: String? = null,
    @SerialName("key_rotation_price_usd") val keyRotationPriceUsd: String? = null
)

@Serializable
data class CreateAliasRequest(
    @SerialName("alias_local") val aliasLocal: String,
    val label: String? = null
)

@Serializable
data class DeleteAliasResponse(
    val status: String = "deleted",
    val id: Long = 0,
    @SerialName("alias_email") val aliasEmail: String = ""
)

@Serializable
data class SlotPurchaseRequest(
    val quantity: Int
)

@Serializable
data class SlotPurchaseDto(
    val id: Long = 0,
    val quantity: Int = 1,
    @SerialName("quoted_satoshi") val quotedSatoshi: Long = 0,
    val status: String = "pending",
    @SerialName("requested_at") val requestedAt: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
    val note: String? = null
)

@Serializable
data class SlotsStatusResponse(
    val tier: String = "free",
    @SerialName("free_cap") val freeCap: Int = 0,
    @SerialName("slots_owned") val slotsOwned: Int = 0,
    /** Null = unlimited (lifetime tier never 402s the slot gate). */
    val cap: Int? = null,
    @SerialName("price_sats") val priceSats: Long = 0,
    val purchases: List<SlotPurchaseDto> = emptyList()
)

// RFC 7807-ish: { "detail": { "error": "...", tier?, cap?, used?, storage_used?, storage_cap?, incoming? } }
// (FastAPI sometimes returns detail as a plain string, so keep fields nullable.)
@Serializable
data class AliasErrorDetail(
    val error: String? = null,
    val tier: String? = null,
    val cap: Int? = null,
    val used: Int? = null,
    val length: Int? = null,
    @SerialName("price_sats") val priceSats: Long? = null,
    val ladder: Map<String, Long>? = null,
    @SerialName("free_length") val freeLength: Int? = null,
    @SerialName("free_cap") val freeCap: Int? = null,
    @SerialName("slots_owned") val slotsOwned: Int? = null,
    @SerialName("storage_used") val storageUsed: Long? = null,
    @SerialName("storage_cap") val storageCap: Long? = null,
    val incoming: Long? = null,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Long? = null
)

@Serializable
data class AliasErrorResponse(
    val detail: AliasErrorDetail? = null
)

/** Typed failures raised by [xyz.desent.data.alias.AliasClient]. */
sealed class AliasError(message: String) : Exception(message) {
    object Taken : AliasError("This alias is already taken")
    object Reserved : AliasError("This local-part is reserved")
    object InvalidLocalPart : AliasError("Invalid local-part format")
    object MatchesUsername : AliasError("This is already your username")
    object Unauthorized : AliasError("Authentication failed")
    object Forbidden : AliasError("Account not registered on this relay")
    object NotFound : AliasError("Alias not found")
    object RateLimited : AliasError("Too many requests — try again later")
    /**
     * 402 `vanity_price`: the short local-part carries a one-time price.
     * Route to the "request this address" flow, not a generic error.
     */
    class VanityPrice(
        val length: Int,
        val priceSats: Long,
        val ladder: Map<String, Long>,
        val freeLength: Int
    ) : AliasError("Short address pricing applies")
    /**
     * 402 `slot_price`: the alias count cap is reached; slot packs raise it.
     * Never disable alias creation at the cap — route to the slot purchase flow.
     */
    class SlotPrice(
        val tier: String?,
        val freeCap: Int?,
        val slotsOwned: Int?,
        val cap: Int?,
        val used: Int?,
        val priceSats: Long
    ) : AliasError("Alias slot purchase required")
    /** Raised on 413 from upload paths; carries the server-reported quota snapshot. */
    class QuotaExceeded(
        val used: Long?,
        val cap: Long?,
        val incoming: Long?,
        val tier: String?
    ) : AliasError("Storage quota exceeded")
    class Server(message: String, val code: Int) : AliasError(message)
    class Unknown(message: String) : AliasError(message)
}
