package xyz.desent.domain.model

/**
 * Live vanity pricing config from `GET /api/aliases/config`. The ladder is
 * operator-tunable at runtime — never hardcode prices or the free threshold.
 */
data class VanityConfig(
    val freeLength: Int = 8,
    val ladder: Map<Int, Long> = emptyMap(),
    /** Per-length USD stickers (server-provided; never converted locally). */
    val ladderUsd: Map<Int, String> = emptyMap()
) {
    /**
     * One-time price in sats for [local], or null when the name is free
     * (length >= [freeLength], a 0 ladder entry, or a missing entry).
     */
    fun priceOf(local: String): Long? =
        local.length
            .takeIf { it < freeLength }
            ?.let { ladder[it] ?: 0L }
            ?.takeIf { it > 0 }

    /** Exact USD sticker for [local], or null (sats mode / no sticker). */
    fun usdStickerOf(local: String): String? =
        local.length
            .takeIf { it < freeLength }
            ?.let { ladderUsd[it] }
}

enum class VanityRequestStatus {
    PENDING, APPROVED, DENIED, CLAIMED;

    companion object {
        fun fromWire(value: String?): VanityRequestStatus = when (value?.lowercase()) {
            "approved" -> APPROVED
            "denied" -> DENIED
            "claimed" -> CLAIMED
            else -> PENDING
        }
    }
}

/** A vanity (short address) approval request; `claimed` is terminal. */
data class VanityRequest(
    val id: Long,
    val localPart: String,
    val domain: String,
    val kind: String,
    val quotedSatoshi: Long,
    val status: VanityRequestStatus,
    val requestedAt: String?,
    val decidedAt: String?,
    val note: String?
) {
    val email: String get() = "$localPart@$domain"
}

/** One alias slot-pack purchase; approval credits slots immediately. */
data class AliasSlotPurchase(
    val id: Long,
    val quantity: Int,
    val quotedSatoshi: Long,
    val status: VanityRequestStatus,
    val requestedAt: String?,
    val decidedAt: String?,
    val note: String?
)

/** Live slot math from `GET /api/aliases/slots`; `cap` is already effective. */
data class AliasSlotsStatus(
    val tier: String,
    val freeCap: Int,
    val slotsOwned: Int,
    /** Null = unlimited (lifetime). */
    val cap: Int?,
    val priceSats: Long,
    val purchases: List<AliasSlotPurchase>
)

/** Alias-family service config (public endpoint). */
data class AliasServiceConfig(
    val emailDomain: String,
    val vanity: VanityConfig,
    val slotPriceSats: Long?,
    val slotPriceUsd: String? = null
)

/** `GET /api/register/available` result: availability plus vanity pricing. */
data class AvailabilityInfo(
    val available: Boolean,
    val local: String?,
    val priceSats: Long,
    val vanity: Boolean
) {
    val priced: Boolean get() = available && vanity && priceSats > 0
}
