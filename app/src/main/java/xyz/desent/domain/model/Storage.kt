package xyz.desent.domain.model

/**
 * Per-category byte breakdown of a user's unified storage quota.
 *
 * Wire shape comes from `GET https://desent.xyz/api/storage`; see
 * refs/STORAGE_TAB_ANDROID.md. The [categories] always sum to [used].
 * [refreshedAt] is client-set (epoch millis) when the response is mapped.
 */
data class StorageBreakdown(
    val used: Long,
    val cap: Long,
    val tier: String,
    val source: String,
    val categories: List<StorageCategory>,
    val refreshedAt: Long
) {
    /** 0f..1f when a positive cap is reported, else null (render without bars). */
    val fraction: Float?
        get() = if (cap > 0) (used.toFloat() / cap).coerceIn(0f, 1f) else null

    /**
     * Anything other than "free" is a paid tier — "paid" (yearly, active)
     * and "lifetime" both count (refs/FROM_email.desent.xyz/ALIAS_API_REFERENCE.md
     * § Tier rules).
     */
    val isPaid: Boolean get() = tier.isNotBlank() && !tier.equals("free", ignoreCase = true)

    /**
     * True when `SUM(categories.bytes) != used` — a parsing anomaly on our
     * side per spec §8. While set, category percentages must be computed
     * against [used] (not [cap]) so the rows still visually stack to the
     * hero total.
     */
    val categorySumMismatch: Boolean
        get() = categories.sumOf { it.bytes } != used
}

/**
 * One row of the breakdown. [key] is the stable machine identifier (one of
 * [CATEGORY_ORDER]); [label] is the human-readable title from the server.
 */
data class StorageCategory(
    val key: String,
    val label: String,
    val bytes: Long
)

/**
 * Fixed display order for the known storage categories. The server's
 * response array may arrive in any order — sort client-side before
 * rendering. Keys not in this list are appended at the end, preserving
 * their relative server order, so unknown future categories remain
 * visible. `settings` covers user-settings events (kind 30079) added by
 * the 2026-08-25 doc update.
 */
val STORAGE_CATEGORY_ORDER: List<String> = listOf(
    "blobs",
    "notes",
    "calendar",
    "contacts",
    "mail",
    "nip46",
    "authored",
    "settings"
)
