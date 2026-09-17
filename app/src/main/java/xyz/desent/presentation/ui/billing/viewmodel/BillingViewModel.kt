package xyz.desent.presentation.ui.billing.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.desent.data.payments.model.PaymentsError
import xyz.desent.domain.model.AliasTierInfo
import xyz.desent.domain.model.FeatureProduct
import xyz.desent.domain.model.Invoice
import xyz.desent.domain.model.InvoiceState
import xyz.desent.domain.model.PaymentTargetType
import xyz.desent.domain.model.PaymentsConfig
import xyz.desent.domain.model.PurchaseHistoryItem
import xyz.desent.domain.model.PurchasePlan
import xyz.desent.domain.usecase.AliasUseCase
import xyz.desent.domain.usecase.PaymentsUseCase
import xyz.desent.presentation.ui.components.CheckoutUiState

data class BillingUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val paymentsConfig: PaymentsConfig = PaymentsConfig(),
    val tierInfo: AliasTierInfo? = null,
    val history: List<PurchaseHistoryItem> = emptyList(),
    val historyFailed: Boolean = false,
    /** Active tier checkout, if any (plan purchases only — the billing page
     *  never re-pays old invoices, ANDROID_PAYMENTS.md §3.5). */
    val checkout: CheckoutUiState? = null,
    /** Plan the current checkout is buying; drives "New invoice" re-mints. */
    val checkoutPlan: PurchasePlan = PurchasePlan.YEARLY,
    /** Add-on the current checkout is buying; set takes precedence over [checkoutPlan]. */
    val checkoutFeature: FeatureProduct? = null,
    val isBuyingTier: Boolean = false,
    /** Which add-on row is minting an invoice (row-level spinner). */
    val isBuyingFeature: FeatureProduct? = null,
    val toast: String? = null
) {
    /**
     * Tier-sales gate (§6): CTA only when Strike is on AND the migration-043
     * kill switch isn't `false` (missing field = enabled).
     */
    val tierSalesOpen: Boolean
        get() = paymentsConfig.strikeEnabled && paymentsConfig.tierPurchasesEnabled != false
}

/**
 * Pricing & Billing screen (ANDROID_PAYMENTS.md §1/§3.5/§6): current plan
 * with renewal date, yearly/lifetime plan cards paid through the shared
 * [xyz.desent.presentation.ui.components.CheckoutSheet], and the unified
 * purchase timeline from `GET /api/payments/history`. Prices always come
 * from server responses — never hardcoded, never converted locally.
 */
class BillingViewModel(
    private val paymentsUseCase: PaymentsUseCase,
    private val aliasUseCase: AliasUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(BillingUiState())
    val uiState: StateFlow<BillingUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        loadAll()
    }

    fun loadAll() {
        viewModelScope.launch {
            paymentsUseCase.getConfig().onSuccess { config ->
                _uiState.value = _uiState.value.copy(paymentsConfig = config)
            }
            aliasUseCase.getTierInfo().onSuccess { tier ->
                _uiState.value = _uiState.value.copy(tierInfo = tier)
            }
            loadHistory()
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        loadAll()
    }

    private suspend fun loadHistory() {
        paymentsUseCase.getHistory().fold(
            onSuccess = { items ->
                _uiState.value = _uiState.value.copy(history = items, historyFailed = false)
            },
            onFailure = {
                _uiState.value = _uiState.value.copy(historyFailed = true)
            }
        )
        _uiState.value = _uiState.value.copy(isRefreshing = false)
    }

    /** POST /api/payments/tier {plan} — open the plan's checkout (§6). */
    fun purchaseTier(plan: PurchasePlan) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBuyingTier = true)
            val result = paymentsUseCase.purchaseTier(plan)
            _uiState.value = _uiState.value.copy(isBuyingTier = false)
            result.fold(
                onSuccess = { invoice ->
                    _uiState.value = _uiState.value.copy(
                        checkoutPlan = plan,
                        checkoutFeature = null,
                        checkout = CheckoutUiState(
                            target = PaymentTargetType.TIER_PURCHASE,
                            targetId = invoice.targetId,
                            invoice = invoice
                        )
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(toast = e.friendlyPaymentsMessage())
                    // already_paid / plan_switch_blocked: refresh so the UI
                    // reflects which plan is actually live.
                    if (e is PaymentsError.AlreadyPaid || e is PaymentsError.PlanSwitchBlocked) loadAll()
                }
            )
        }
    }

    /** POST /api/payments/feature {product} — open the add-on's checkout (§3.1b). */
    fun purchaseFeature(product: FeatureProduct) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBuyingFeature = product)
            val result = paymentsUseCase.purchaseFeature(product)
            _uiState.value = _uiState.value.copy(isBuyingFeature = null)
            result.fold(
                onSuccess = { invoice ->
                    _uiState.value = _uiState.value.copy(
                        checkoutFeature = product,
                        checkout = CheckoutUiState(
                            target = PaymentTargetType.FEATURE_PURCHASE,
                            targetId = invoice.targetId,
                            invoice = invoice,
                            featureProduct = product
                        )
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(toast = e.friendlyPaymentsMessage())
                    // already_entitled / sales switches: refresh so owned
                    // state reflects the server.
                    if (e is PaymentsError.AlreadyEntitled ||
                        e is PaymentsError.FeaturePurchasesDisabled ||
                        e is PaymentsError.FeatureDisabled
                    ) loadAll()
                }
            )
        }
    }

    /** "New invoice" on expiry — re-open the live purchase (fresh server quote). */
    fun reMintInvoice() {
        val feature = _uiState.value.checkoutFeature
        if (feature != null) purchaseFeature(feature) else purchaseTier(_uiState.value.checkoutPlan)
    }

    fun dismissCheckout() {
        pausePolling()
        _uiState.value = _uiState.value.copy(checkout = null)
    }

    /** Poll `GET /api/payments/{id}` ~3 s while the checkout sheet is visible. */
    fun resumePolling() {
        val invoice = _uiState.value.checkout?.invoice ?: return
        if (invoice.isTerminal) return
        if (pollJob?.isActive == true) return
        startPollLoop(invoice.id)
    }

    fun pausePolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun startPollLoop(invoiceId: Long) {
        pausePolling()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val invoice = paymentsUseCase.getInvoice(invoiceId).getOrNull() ?: continue
                val checkout = _uiState.value.checkout
                if (checkout?.invoice?.id != invoice.id) continue // re-mint raced; stale poll
                _uiState.value = _uiState.value.copy(checkout = checkout.copy(invoice = invoice))
                if (invoice.isTerminal) {
                    pollJob = null
                    handleTerminal(invoice)
                    return@launch
                }
            }
        }
    }

    private fun handleTerminal(invoice: Invoice) {
        if (invoice.state != InvoiceState.PAID) return // expired/cancelled: sheet offers re-mint
        val feature = _uiState.value.checkoutFeature
        _uiState.value = _uiState.value.copy(checkout = null)
        _uiState.value = _uiState.value.copy(
            toast = if (invoice.isProcessing) {
                // Money moved, credit pending admin resolution — terminal, no retry.
                "Payment received — processing"
            } else if (feature != null) {
                "Payment received — add-on active"
            } else {
                "Payment received — plan active"
            }
        )
        // Settlement credited server-side: re-fetch caps + the timeline.
        loadAll()
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    private fun Throwable.friendlyPaymentsMessage(): String = when (this) {
        is PaymentsError.AlreadyPaid -> "You already have the lifetime plan"
        is PaymentsError.LifetimeDisabled -> "Lifetime plan is not on sale right now"
        is PaymentsError.PlanSwitchBlocked -> "An invoice for the other plan is still open — pay it or let it expire first"
        is PaymentsError.FeaturePurchasesDisabled -> message ?: "Add-on purchases are currently disabled"
        is PaymentsError.FeatureDisabled -> message ?: "That feature isn't enabled on this relay yet"
        is PaymentsError.AlreadyEntitled -> message ?: "You already have this feature"
        is PaymentsError.InvalidProduct -> message ?: "Invalid product"
        is PaymentsError.RateLimited -> message ?: "Too many invoices requested — try again later"
        is PaymentsError.StrikeUnavailable -> message ?: "Payment provider unavailable — try again"
        is PaymentsError.PaymentsDisabled -> message ?: "Payments are currently disabled"
        is PaymentsError.Unauthorized -> message ?: "Authentication failed — try re-login"
        else -> message ?: "Something went wrong"
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3_000L
    }
}
