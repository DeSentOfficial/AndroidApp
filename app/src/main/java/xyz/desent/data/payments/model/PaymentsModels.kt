package xyz.desent.data.payments.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/payments/config` — public checkout gate (ANDROID_PAYMENTS.md §1).
 * Cached ~5 min server-side; display configuration, never live checkout data.
 */
@Serializable
data class PaymentsConfigResponse(
    @SerialName("strike_enabled") val strikeEnabled: Boolean = false,
    /** Tier-sales kill switch (migration 043); missing = treat as enabled. */
    @SerialName("tier_purchases_enabled") val tierPurchasesEnabled: Boolean? = null,
    @SerialName("tier_price_sats") val tierPriceSats: Long? = null,
    @SerialName("tier_price_mode") val tierPriceMode: String? = null,
    /** Operator's exact dollar sticker, e.g. "10.00"; null in sats mode. */
    @SerialName("tier_price_usd") val tierPriceUsd: String? = null,
    /** Whether the lifetime plan is on sale (migration 033); off → hide every lifetime surface. */
    @SerialName("lifetime_enabled") val lifetimeEnabled: Boolean = false,
    @SerialName("lifetime_price_sats") val lifetimePriceSats: Long? = null,
    @SerialName("lifetime_price_mode") val lifetimePriceMode: String? = null,
    @SerialName("lifetime_price_usd") val lifetimePriceUsd: String? = null,
    /** Live BTC/USD rate the server used; null → omit dollar readouts. */
    @SerialName("btc_usd") val btcUsd: String? = null
)

@Serializable
data class MintInvoiceRequest(
    @SerialName("target_type") val targetType: String,
    @SerialName("target_id") val targetId: Long
)

/** `POST /api/payments/tier` body (migration 033); absent field = "yearly". */
@Serializable
data class PurchaseTierRequest(
    val plan: String = "yearly"
)

/**
 * `POST /api/payments/feature` body (§3.1b, migration 057) — the one-call
 * checkout for ANY registered add-on product (`dm_fanout`, `key_rotation`).
 */
@Serializable
data class PurchaseFeatureRequest(
    val product: String
)

/**
 * A lightning checkout invoice — the response shape of every
 * payments endpoint that mints or reports one.
 */
@Serializable
data class InvoiceDto(
    val id: Long = 0,
    @SerialName("target_type") val targetType: String = "",
    @SerialName("target_id") val targetId: Long = 0,
    @SerialName("amount_satoshi") val amountSatoshi: Long = 0,
    /** Derived server-side from the pinned invoice sats; never convert locally. */
    @SerialName("amount_usd") val amountUsd: String? = null,
    @SerialName("ln_invoice") val lnInvoice: String = "",
    val state: String = "unpaid",
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("paid_at") val paidAt: String? = null,
    @SerialName("settled_at") val settledAt: String? = null
)

@Serializable
data class InvoiceListResponse(
    val invoices: List<InvoiceDto> = emptyList()
)

/**
 * `GET /api/payments/history` — the unified purchase timeline
 * (ANDROID_PAYMENTS.md §3.5): one item per purchase across all three flows
 * with the latest invoice attached (read-only — no `ln_invoice`), plus
 * admin-approved purchases without invoices and orphan invoices.
 */
@Serializable
data class PaymentHistoryResponse(
    val items: List<PaymentHistoryItemDto> = emptyList()
)

@Serializable
data class PaymentHistoryItemDto(
    /** "vanity_request" | "slot_purchase" | "tier_purchase" (orphan: raw/empty). */
    val kind: String? = null,
    @SerialName("target_id") val targetId: Long = 0,
    /** Purchase status; for orphans the invoice state. */
    val status: String? = null,
    @SerialName("amount_satoshi") val amountSatoshi: Long = 0,
    @SerialName("amount_usd") val amountUsd: String? = null,
    @SerialName("requested_at") val requestedAt: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
    /** "strike" | admin npub | null. */
    @SerialName("decided_by") val decidedBy: String? = null,
    val note: String? = null,
    /** Kind-specific: vanity {local_part,domain,kind}, slots {quantity}, tier {plan}; null on orphans. */
    val detail: kotlinx.serialization.json.JsonElement? = null,
    /** Latest invoice for the purchase; null when none (admin approvals). */
    val invoice: InvoiceDto? = null
)

// RFC 7807-ish error envelope: { "detail": { "error": "...", "status": "..." } }
@Serializable
data class PaymentsErrorDetail(
    val error: String? = null,
    /** On 409 not_pending: which status the target row is already in. */
    val status: String? = null
)

@Serializable
data class PaymentsErrorResponse(
    val detail: PaymentsErrorDetail? = null
)

/** Typed failures raised by [xyz.desent.data.payments.PaymentsClient]. */
sealed class PaymentsError(message: String) : Exception(message) {    /** 404 — no such row, or it belongs to another key (indistinguishable by design). */
    object TargetNotFound : PaymentsError("Purchase not found")
    /** 409 — the target is already decided; [status] says which (approved/denied/…). */
    class NotPending(val status: String?) : PaymentsError("Purchase is no longer pending")
    /** 422 — tier purchases use the dedicated `POST /api/payments/tier` endpoint. */
    object InvalidTargetType : PaymentsError("Invalid target type")
    /** 422 — the target row has no sats price. */
    object ZeroPrice : PaymentsError("Nothing to pay")
    /** 429 — 20 invoice mints per pubkey per hour. */
    object RateLimited : PaymentsError("Too many invoices requested — try again later")
    /** 502 — transient Strike outage; retry. */
    object StrikeUnavailable : PaymentsError("Payment provider unavailable — try again")
    /** 503 — payments disabled; fall back to the approval flow. */
    object PaymentsDisabled : PaymentsError("Payments are currently disabled")
    /** 409 — account already has the paid tier. */
    object AlreadyPaid : PaymentsError("You already have the paid tier")
    /** 403 — operator turned lifetime sales off; hide the lifetime option. */
    object LifetimeDisabled : PaymentsError("Lifetime plan is not on sale")
    /** 409 — a live invoice exists for the other plan; pay it or let it expire. */
    object PlanSwitchBlocked : PaymentsError("An invoice for the other plan is still open")
    /** 403 `<product>_purchases_disabled` — add-on sales off; never render a buy button. */
    class FeaturePurchasesDisabled(val feature: String) :
        PaymentsError("${feature.featureDisplayName()} add-on purchases are currently disabled")
    /** 503 `<feature>_disabled` — feature master switch off; hide the buy path. */
    class FeatureDisabled(val feature: String) :
        PaymentsError("${feature.featureDisplayName()} isn't enabled on this relay yet")
    /** 409 — entitlement already held (plan or the add-on); just re-fetch tier-info. */
    class AlreadyEntitled(val product: String? = null) :
        PaymentsError("You already have ${product.displayName() ?: "this feature"}")
    /** 422 — the product isn't in the server's add-on registry. */
    object InvalidProduct : PaymentsError("Invalid product")
    object Unauthorized : PaymentsError("Authentication failed — try re-login")
    class Server(message: String, val code: Int) : PaymentsError(message)
    class Unknown(message: String) : PaymentsError(message)
}

/**
 * Human name for an add-on product wire string ("dm_fanout" → "relay
 * mirroring"). Accepts both the product wire code and the bare feature code
 * the server puts in `<feature>_disabled` errors ("fanout").
 */
private fun String?.displayName(): String? = when (this) {
    "dm_fanout", "fanout" -> "relay mirroring"
    "key_rotation" -> "key rotation"
    null -> null
    else -> replace('_', ' ')
}

/** Non-null display name for a feature code; unknown codes fall back to the raw string. */
private fun String.featureDisplayName(): String = displayName() ?: this
