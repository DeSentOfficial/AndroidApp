package xyz.desent.domain.model

/**
 * `GET /api/payments/config` — the checkout feature gate
 * (refs/FROM_email.desent.xyz/ANDROID_PAYMENTS.md §1). `false` (or a failed
 * fetch — the repository fails closed) keeps the legacy operator-approval
 * flows. All fields are display configuration; payable amounts always come
 * from server responses (`quoted_satoshi`, invoice `amount_satoshi`).
 */
data class PaymentsConfig(
    val strikeEnabled: Boolean = false,
    /**
     * Tier-sales kill switch (migration 043). Null = server didn't report
     * it — treat as enabled; `false` → hide every tier buy CTA (§6:
     * CTA only when `strike_enabled && tier_purchases_enabled !== false &&
     * tier == "free"`).
     */
    val tierPurchasesEnabled: Boolean? = null,
    val tierPriceSats: Long? = null,
    /** "sats" or "usd" — how the operator priced the tier group. */
    val tierPriceMode: String? = null,
    /** Exact USD sticker, e.g. "10.00"; null in sats mode / rate unavailable. */
    val tierPriceUsd: String? = null,
    /** Whether the lifetime plan is on sale; `false` → hide every lifetime surface. */
    val lifetimeEnabled: Boolean = false,
    val lifetimePriceSats: Long? = null,
    val lifetimePriceMode: String? = null,
    val lifetimePriceUsd: String? = null,
    /** Live BTC/USD rate the server used; null → omit dollar readouts. */
    val btcUsd: String? = null
)

/** Which paid plan a tier purchase buys (ANDROID_PAYMENTS.md §6). */
enum class PurchasePlan(val wire: String) {
    YEARLY("yearly"),
    LIFETIME("lifetime")
}

/**
 * One-off feature add-on products sold through `POST /api/payments/feature`
 * (ANDROID_PAYMENTS.md §3.1b). The registry lives server-side
 * (`backend/email_relay/feature_addons.py`); these are the products the
 * Android buy paths offer.
 */
enum class FeatureProduct(val wire: String) {
    /** DM-relay fan-out mirror (ANDROID_DM_FANOUT.md §3.1). */
    DM_FANOUT("dm_fanout"),

    /** One-off key rotation (ANDROID_KEY_ROTATION.md, migration 057). */
    KEY_ROTATION("key_rotation");

    companion object {
        fun fromWire(value: String?): FeatureProduct? =
            entries.find { it.wire == value }
    }
}

/** What a lightning invoice is paying for. */
enum class PaymentTargetType(val wire: String) {
    VANITY_REQUEST("vanity_request"),
    SLOT_PURCHASE("slot_purchase"),
    TIER_PURCHASE("tier_purchase"),
    /** One-off feature add-on (ANDROID_PAYMENTS.md §3.1b); see [featureProduct]. */
    FEATURE_PURCHASE("feature_purchase");

    companion object {
        fun fromWire(value: String?): PaymentTargetType? =
            entries.find { it.wire == value }
    }
}

/** `payment_invoices.state` (ANDROID_PAYMENTS.md §3.4). */
enum class InvoiceState {
    UNPAID, PENDING, PAID, EXPIRED, CANCELLED;

    companion object {
        fun fromWire(value: String?): InvoiceState = when (value?.lowercase()) {
            "pending" -> PENDING
            "paid" -> PAID
            "expired" -> EXPIRED
            "cancelled" -> CANCELLED
            else -> UNPAID
        }
    }
}

/** A lightning checkout invoice; `state == PAID` is the "payment complete" signal. */
data class Invoice(
    val id: Long,
    val targetType: PaymentTargetType?,
    val targetId: Long,
    val amountSatoshi: Long,
    /** Derived server-side from the pinned invoice sats; never convert locally. */
    val amountUsd: String?,
    val lnInvoice: String,
    val state: InvoiceState,
    val expiresAt: String?,
    val createdAt: String?,
    val paidAt: String?,
    val settledAt: String?
) {
    /** Paid but unresolved (amount mismatch / denied mid-flight) — admin resolves it. */
    val isProcessing: Boolean get() = state == InvoiceState.PAID && settledAt == null

    /** `paid`, `expired` and `cancelled` end polling; `unpaid`/`pending` stay live. */
    val isTerminal: Boolean get() = state == InvoiceState.PAID ||
        state == InvoiceState.EXPIRED ||
        state == InvoiceState.CANCELLED
}

/** Pill semantics for the billing history timeline (ANDROID_PAYMENTS.md §3.5). */
enum class HistoryPill { CREDITED, PROCESSING, AWAITING, DENIED, MUTED }

/**
 * One purchase in the unified billing timeline (vanity request, alias slot
 * pack, tier purchase, or one-off feature add-on) with its latest —
 * read-only — invoice attached.
 */
data class PurchaseHistoryItem(
    val kind: PaymentTargetType?,
    val targetId: Long,
    val status: String?,
    val amountSatoshi: Long,
    val amountUsd: String?,
    val requestedAt: String?,
    val decidedAt: String?,
    val decidedBy: String?,
    val note: String?,
    /** Raw kind-specific detail JSON (display only). */
    val detailJson: String?,
    /** `detail.product` for `feature_purchase` items; null otherwise. */
    val featureProduct: FeatureProduct? = null,
    val invoice: Invoice?
) {
    /**
     * Mirrors the admin ledger pills: approved/claimed = credited; invoice
     * `paid` without `settled_at` = paid · processing; `unpaid` = awaiting
     * (or expired past `expires_at`); denied = denied; everything else muted.
     */
    val pill: HistoryPill
        get() {
            val s = status?.lowercase()
            return when {
                s == "approved" || s == "claimed" -> HistoryPill.CREDITED
                invoice?.isProcessing == true -> HistoryPill.PROCESSING
                invoice?.state == InvoiceState.UNPAID || invoice?.state == InvoiceState.PENDING ->
                    HistoryPill.AWAITING
                s == "denied" -> HistoryPill.DENIED
                else -> HistoryPill.MUTED
            }
        }

    /** Human label for the purchase kind; orphans render as "Invoice". */
    val kindLabel: String
        get() = when (kind) {
            PaymentTargetType.VANITY_REQUEST -> "Vanity address"
            PaymentTargetType.SLOT_PURCHASE -> "Alias slots"
            PaymentTargetType.TIER_PURCHASE -> "Paid plan"
            PaymentTargetType.FEATURE_PURCHASE -> when (featureProduct) {
                FeatureProduct.DM_FANOUT -> "Relay mirroring add-on"
                FeatureProduct.KEY_ROTATION -> "Key rotation add-on"
                // Registry product the app doesn't know (yet).
                null -> "Feature add-on"
            }
            null -> "Invoice"
        }
}
