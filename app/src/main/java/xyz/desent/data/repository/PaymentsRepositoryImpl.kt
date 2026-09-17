package xyz.desent.data.repository

import android.util.Log
import xyz.desent.data.payments.PaymentsClient
import xyz.desent.data.payments.model.InvoiceDto
import xyz.desent.domain.model.FeatureProduct
import xyz.desent.domain.model.Invoice
import xyz.desent.domain.model.InvoiceState
import xyz.desent.domain.model.PaymentTargetType
import xyz.desent.domain.model.PaymentsConfig
import xyz.desent.domain.model.PurchaseHistoryItem
import xyz.desent.domain.model.PurchasePlan
import xyz.desent.domain.repository.PaymentsRepository

/**
 * Repository for the Strike checkout API. The config gate fails closed: a
 * failed fetch degrades to [PaymentsConfig.strikeEnabled] == false so the
 * legacy operator-approval flows stay reachable (ANDROID_PAYMENTS.md §7).
 */
class PaymentsRepositoryImpl(
    private val paymentsClient: PaymentsClient
) : PaymentsRepository {

    override suspend fun getConfig(): Result<PaymentsConfig> {
        return paymentsClient.getConfig().map { it.toDomain() }
            .recoverCatching { e ->
                Log.w(TAG, "getConfig failed, failing closed to legacy flows: ${e.message}")
                PaymentsConfig()
            }
    }

    override suspend fun mintInvoice(
        targetType: PaymentTargetType,
        targetId: Long,
        identity: nostr.id.Identity?
    ): Result<Invoice> {
        return paymentsClient.mintInvoice(targetType, targetId, identity).map { it.toDomain() }
    }

    override suspend fun getInvoice(id: Long, identity: nostr.id.Identity?): Result<Invoice> {
        return paymentsClient.getInvoice(id, identity).map { it.toDomain() }
    }

    override suspend fun listInvoices(): Result<List<Invoice>> {
        return paymentsClient.listInvoices().map { list -> list.map { it.toDomain() } }
    }

    override suspend fun purchaseTier(plan: PurchasePlan): Result<Invoice> {
        return paymentsClient.purchaseTier(plan.wire).map { it.toDomain() }
    }

    override suspend fun purchaseFeature(product: FeatureProduct): Result<Invoice> {
        return paymentsClient.purchaseFeature(product).map { it.toDomain() }
    }

    override suspend fun getHistory(): Result<List<PurchaseHistoryItem>> {
        return paymentsClient.getHistory().map { resp ->
            resp.items.map { it.toDomain() }
        }
    }

    private fun xyz.desent.data.payments.model.PaymentsConfigResponse.toDomain() = PaymentsConfig(
        strikeEnabled = strikeEnabled,
        tierPurchasesEnabled = tierPurchasesEnabled,
        tierPriceSats = tierPriceSats,
        tierPriceMode = tierPriceMode,
        tierPriceUsd = tierPriceUsd,
        lifetimeEnabled = lifetimeEnabled,
        lifetimePriceSats = lifetimePriceSats,
        lifetimePriceMode = lifetimePriceMode,
        lifetimePriceUsd = lifetimePriceUsd,
        btcUsd = btcUsd
    )

    private fun xyz.desent.data.payments.model.PaymentHistoryItemDto.toDomain() =
        PurchaseHistoryItem(
            kind = PaymentTargetType.fromWire(kind),
            targetId = targetId,
            status = status,
            amountSatoshi = amountSatoshi,
            amountUsd = amountUsd,
            requestedAt = requestedAt,
            decidedAt = decidedAt,
            decidedBy = decidedBy,
            note = note,
            detailJson = detail?.toString(),
            featureProduct = FeatureProduct.fromWire(detail?.productWire()),
            invoice = invoice?.toDomain()
        )

    /** `detail.product` for feature purchases; null for every other kind. */
    private fun kotlinx.serialization.json.JsonElement.productWire(): String? =
        (this as? kotlinx.serialization.json.JsonObject)
            ?.get("product")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.takeIf { it.isString }
            ?.content

    private fun InvoiceDto.toDomain() = Invoice(
        id = id,
        targetType = PaymentTargetType.fromWire(targetType),
        targetId = targetId,
        amountSatoshi = amountSatoshi,
        amountUsd = amountUsd,
        lnInvoice = lnInvoice,
        state = InvoiceState.fromWire(state),
        expiresAt = expiresAt,
        createdAt = createdAt,
        paidAt = paidAt,
        settledAt = settledAt
    )

    companion object {
        private const val TAG = "PaymentsRepository"
    }
}
