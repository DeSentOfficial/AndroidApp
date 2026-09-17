package xyz.desent.domain.repository

import xyz.desent.domain.model.FeatureProduct
import xyz.desent.domain.model.Invoice
import xyz.desent.domain.model.PaymentTargetType
import xyz.desent.domain.model.PaymentsConfig
import xyz.desent.domain.model.PurchaseHistoryItem
import xyz.desent.domain.model.PurchasePlan

interface PaymentsRepository {
    suspend fun getConfig(): Result<PaymentsConfig>

    /**
     * @param identity explicit NIP-98 signer for the signup vanity checkout,
     * where the claiming key is memory-only (not the active account). Null —
     * the default — signs with the active account.
     */
    suspend fun mintInvoice(
        targetType: PaymentTargetType,
        targetId: Long,
        identity: nostr.id.Identity? = null
    ): Result<Invoice>
    suspend fun getInvoice(id: Long, identity: nostr.id.Identity? = null): Result<Invoice>
    suspend fun listInvoices(): Result<List<Invoice>>
    suspend fun purchaseTier(plan: PurchasePlan = PurchasePlan.YEARLY): Result<Invoice>
    /** One-call feature add-on checkout (ANDROID_PAYMENTS.md §3.1b). */
    suspend fun purchaseFeature(product: FeatureProduct): Result<Invoice>
    suspend fun getHistory(): Result<List<PurchaseHistoryItem>>
}
