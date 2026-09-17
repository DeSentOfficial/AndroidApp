package xyz.desent.domain.model

data class Alias(
    val id: Long,
    val email: String,
    val localPart: String,
    val label: String?,
    val isActive: Boolean,
    val createdAt: String
)

data class AliasTierInfo(
    val tier: String,
    /** Effective alias cap (free_cap + slots_owned); null = unlimited (lifetime). */
    val cap: Int?,
    val used: Int,
    val emailDomain: String,
    val freeCap: Int? = null,
    val slotsOwned: Int? = null,
    val storageUsed: Long? = null,
    val storageCap: Long? = null,
    /**
     * Renewal date of a yearly plan (ISO timestamp); null = lifetime/free.
     * A past date = lapsed yearly — tier is already "free" server-side.
     */
    val paidUntil: String? = null,
    /** DM web-of-trust relay default radius (DM_WEB_OF_TRUST.md §3.4). */
    val wotDefaultMaxHops: Int? = null,
    /** Resolved radius for the caller; null = filter off / free tier. */
    val wotEffectiveMaxHops: Int? = null,
    /**
     * Key-rotation capability flag (master switch AND entitled — paid tier
     * or the one-off add-on, ANDROID_KEY_ROTATION.md). Null = server didn't
     * report it; treat as unavailable (locked entry point).
     */
    val keyRotation: Boolean? = null,
    /** Caller owns the one-off add-on — picks the locked-state message. */
    val keyRotationPurchased: Boolean? = null,
    /** Add-on on sale; false/missing = never render a buy button. */
    val keyRotationPurchaseEnabled: Boolean? = null,
    val keyRotationPriceSats: Long? = null,
    /** "sats" or "usd" — how the operator priced the add-on. */
    val keyRotationPriceMode: String? = null,
    /** Exact USD sticker (display only — never convert sats↔USD client-side). */
    val keyRotationPriceUsd: String? = null,
    /**
     * AI-agents master switch (ANDROID_AI_AGENTS.md §1). Null = server
     * didn't report it; treat as off (fail-closed).
     */
    val agents: Boolean? = null,
    /**
     * Relay mirroring usable NOW — master switch AND entitled
     * (ANDROID_DM_FANOUT.md §3). Null = server didn't report it; treat as
     * unavailable (fail-closed, same as [keyRotation]).
     */
    val dmFanout: Boolean? = null,
    /** Caller owns the one-off add-on — picks the locked-state message. */
    val dmFanoutPurchased: Boolean? = null,
    /** Add-on on sale; false/missing = never render a buy button. */
    val fanoutPurchaseEnabled: Boolean? = null,
    val fanoutPriceSats: Long? = null,
    /** "sats" or "usd" — how the operator priced the add-on. */
    val fanoutPriceMode: String? = null,
    /** Exact USD sticker (display only — never convert sats↔USD client-side). */
    val fanoutPriceUsd: String? = null
) {
    /** Anything other than "free" is paid — "paid" (yearly) or "lifetime". */
    val isPaid: Boolean get() = tier.isNotBlank() && !tier.equals("free", ignoreCase = true)

    val isLifetime: Boolean get() = tier.equals("lifetime", ignoreCase = true)

    /** True when the alias-count cap is reached; lifetime (null cap) never is. */
    val isAtCap: Boolean get() = cap != null && used >= cap

    /** 0f..1f when both storage fields are present, else null. */
    val storageFraction: Float?
        get() = if (storageCap != null && storageCap > 0 && storageUsed != null) {
            (storageUsed.toFloat() / storageCap).coerceIn(0f, 1f)
        } else null
}
