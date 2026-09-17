package xyz.desent.domain.usecase

import xyz.desent.domain.model.FeatureProduct
import xyz.desent.domain.model.Invoice
import xyz.desent.domain.model.PaymentTargetType
import xyz.desent.domain.model.PaymentsConfig
import xyz.desent.domain.model.PurchaseHistoryItem
import xyz.desent.domain.model.PurchasePlan
import xyz.desent.domain.repository.PaymentsRepository

class PaymentsUseCase(
    private val paymentsRepository: PaymentsRepository
) {
    suspend fun getConfig(): Result<PaymentsConfig> =
        paymentsRepository.getConfig()

    suspend fun mintInvoice(
        targetType: PaymentTargetType,
        targetId: Long,
        identity: nostr.id.Identity? = null
    ): Result<Invoice> =
        paymentsRepository.mintInvoice(targetType, targetId, identity)

    suspend fun getInvoice(id: Long, identity: nostr.id.Identity? = null): Result<Invoice> =
        paymentsRepository.getInvoice(id, identity)

    suspend fun listInvoices(): Result<List<Invoice>> =
        paymentsRepository.listInvoices()

    suspend fun purchaseTier(plan: PurchasePlan = PurchasePlan.YEARLY): Result<Invoice> =
        paymentsRepository.purchaseTier(plan)

    /** One-call feature add-on checkout (ANDROID_PAYMENTS.md §3.1b). */
    suspend fun purchaseFeature(product: FeatureProduct): Result<Invoice> =
        paymentsRepository.purchaseFeature(product)

    suspend fun getHistory(): Result<List<PurchaseHistoryItem>> =
        paymentsRepository.getHistory()
}
