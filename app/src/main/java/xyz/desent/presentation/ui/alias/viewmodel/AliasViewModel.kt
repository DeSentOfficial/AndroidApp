package xyz.desent.presentation.ui.alias.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.desent.data.alias.model.AliasError
import xyz.desent.data.payments.model.PaymentsError
import xyz.desent.data.vanity.model.VanityError
import xyz.desent.domain.model.Alias
import xyz.desent.domain.model.AliasTierInfo
import xyz.desent.domain.model.Invoice
import xyz.desent.domain.model.InvoiceState
import xyz.desent.domain.model.PaymentTargetType
import xyz.desent.domain.model.PaymentsConfig
import xyz.desent.domain.model.VanityConfig
import xyz.desent.domain.model.VanityRequest
import xyz.desent.domain.model.VanityRequestStatus
import xyz.desent.domain.usecase.AliasUseCase
import xyz.desent.domain.usecase.PaymentsUseCase
import xyz.desent.presentation.ui.components.CheckoutUiState
import xyz.desent.presentation.ui.components.SlotPurchasePrompt

/** 402 `vanity_price` payload driving the "Request this address" sheet. */
data class VanityPricePrompt(
    val localPart: String,
    val priceSats: Long,
    val ladder: Map<String, Long>,
    val freeLength: Int,
    val priceUsd: String? = null
)

data class AliasUiState(
    val aliases: List<Alias> = emptyList(),
    val tierInfo: AliasTierInfo? = null,
    val emailDomain: String = "desent.xyz",
    /** Cached pricing ladder for the live price hint; null hides the hint. */
    val vanityConfig: VanityConfig? = null,
    val slotPriceSats: Long? = null,
    val slotPriceUsd: String? = null,
    /** Strike checkout gate (ANDROID_PAYMENTS.md §1); false → legacy approval flows. */
    val paymentsConfig: PaymentsConfig = PaymentsConfig(),
    val vanityRequests: List<VanityRequest> = emptyList(),
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
    val isDeleting: Boolean = false,
    val isRequestingVanity: Boolean = false,
    val isBuyingSlots: Boolean = false,
    val isLoadingSlots: Boolean = false,
    val isBuyingTier: Boolean = false,
    val error: String? = null,
    val toast: String? = null,
    /** Non-null shows the vanity request sheet (402 vanity_price path). */
    val vanityPricePrompt: VanityPricePrompt? = null,
    /** Non-null shows the slot purchase sheet (402 slot_price path or proactive). */
    val slotPricePrompt: SlotPurchasePrompt? = null,
    /** Non-null shows the lightning checkout sheet (Strike payments). */
    val checkout: CheckoutUiState? = null,
    /** Non-null opens the create dialog pre-filled (approved-request claim). */
    val prefillLocalPart: String? = null
) {
    /**
     * Tier upgrade CTA gate (§6): payments on, tier-sales kill switch not
     * `false` (migration 043; missing = enabled), and a free account.
     */
    val showTierCta: Boolean
        get() = paymentsConfig.strikeEnabled &&
            paymentsConfig.tierPurchasesEnabled != false &&
            tierInfo?.tier?.equals("free", true) == true
}

class AliasViewModel(
    private val aliasUseCase: AliasUseCase,
    private val paymentsUseCase: PaymentsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(AliasUiState())
    val uiState: StateFlow<AliasUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        loadAll()
    }

    fun loadAll() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            aliasUseCase.getConfig().onSuccess { cfg ->
                _uiState.value = _uiState.value.copy(
                    emailDomain = cfg.emailDomain,
                    vanityConfig = cfg.vanity,
                    slotPriceSats = cfg.slotPriceSats,
                    slotPriceUsd = cfg.slotPriceUsd
                )
            }

            // Fails closed in the repository: a failed fetch keeps the legacy flows.
            paymentsUseCase.getConfig().onSuccess { payments ->
                _uiState.value = _uiState.value.copy(paymentsConfig = payments)
            }

            val result = aliasUseCase.listAliases()
            if (result.isSuccess) {
                val (aliases, tier) = result.getOrNull()!!
                _uiState.value = _uiState.value.copy(
                    aliases = aliases,
                    tierInfo = tier,
                    isLoading = false
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = result.exceptionOrNull()?.friendlyMessage()
                )
            }

            loadVanityRequests()
        }
    }

    fun refresh() = loadAll()

    fun createAlias(localPart: String, label: String?) {
        val trimmed = localPart.trim()
        when (val violation = validateLocalPart(trimmed)) {
            null -> { /* proceed */ }
            else -> {
                _uiState.value = _uiState.value.copy(toast = violation)
                return
            }
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCreating = true)
            val result = aliasUseCase.createAlias(trimmed, label?.trim()?.takeIf { it.isNotEmpty() })
            _uiState.value = _uiState.value.copy(isCreating = false)

            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(toast = "Alias created: ${result.getOrNull()!!.email}")
                loadAll()
            } else {
                when (val err = result.exceptionOrNull()) {
                    is AliasError.VanityPrice -> {
                        // The server quote is authoritative; also refresh the
                        // cached ladder opportunistically (§2).
                        _uiState.value = _uiState.value.copy(
                            vanityConfig = VanityConfig(
                                freeLength = err.freeLength,
                                ladder = err.ladder.entries
                                    .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }
                                    .toMap(),
                                ladderUsd = _uiState.value.vanityConfig?.ladderUsd ?: emptyMap()
                            ),
                            vanityPricePrompt = VanityPricePrompt(
                                localPart = trimmed,
                                priceSats = err.priceSats,
                                ladder = err.ladder,
                                freeLength = err.freeLength,
                                priceUsd = _uiState.value.vanityConfig?.usdStickerOf(trimmed)
                            )
                        )
                    }
                    is AliasError.SlotPrice -> {
                        _uiState.value = _uiState.value.copy(
                            slotPricePrompt = err.toPrompt()
                        )
                    }
                    else -> {
                        _uiState.value = _uiState.value.copy(
                            toast = err?.friendlyMessage()
                        )
                    }
                }
            }
        }
    }

    fun deleteAlias(id: Long) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true)
            val result = aliasUseCase.deleteAlias(id)
            _uiState.value = _uiState.value.copy(isDeleting = false)

            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(toast = "Alias deleted")
                loadAll()
            } else {
                _uiState.value = _uiState.value.copy(
                    toast = result.exceptionOrNull()?.friendlyMessage()
                )
            }
        }
    }

    /**
     * POST /api/vanity/requests — then either mint a lightning invoice
     * (Strike enabled) or fall back to operator approval (§5).
     */
    fun requestVanityAddress(localPart: String, domain: String?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRequestingVanity = true)
            val result = aliasUseCase.createVanityRequest(localPart, domain, kind = "alias")
            _uiState.value = _uiState.value.copy(isRequestingVanity = false)

            result.fold(
                onSuccess = { request ->
                    _uiState.value = _uiState.value.copy(vanityPricePrompt = null)
                    loadVanityRequests()
                    if (_uiState.value.paymentsConfig.strikeEnabled) {
                        startCheckout(
                            target = PaymentTargetType.VANITY_REQUEST,
                            targetId = request.id,
                            claimLocalPart = localPart
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            toast = "Requested ${request.email} — pending operator approval"
                        )
                    }
                },
                onFailure = { e ->
                    if (e is VanityError.RequestExists && _uiState.value.paymentsConfig.strikeEnabled) {
                        reuseOwnVanityRequest(localPart, domain)
                    } else {
                        _uiState.value = _uiState.value.copy(toast = e.friendlyVanityMessage())
                    }
                }
            )
        }
    }

    /**
     * 409 `request_exists` with payments on: re-fetch own requests and reuse
     * the pending row (mint an invoice for it), or prompt the claim retry if
     * it already reads approved (§5).
     */
    private suspend fun reuseOwnVanityRequest(localPart: String, domain: String?) {
        val existing = aliasUseCase.listVanityRequests().getOrNull()
            ?.firstOrNull { it.localPart == localPart && (domain == null || it.domain == domain) }

        when (existing?.status) {
            null -> _uiState.value = _uiState.value.copy(
                toast = "This name is already being requested"
            )
            VanityRequestStatus.PENDING -> {
                _uiState.value = _uiState.value.copy(vanityPricePrompt = null)
                startCheckout(
                    target = PaymentTargetType.VANITY_REQUEST,
                    targetId = existing.id,
                    claimLocalPart = localPart
                )
            }
            VanityRequestStatus.APPROVED -> {
                _uiState.value = _uiState.value.copy(
                    vanityPricePrompt = null,
                    prefillLocalPart = localPart,
                    toast = "$localPart@${existing.domain} approved — finish claiming it"
                )
            }
            else -> _uiState.value = _uiState.value.copy(
                toast = "Request for this name was ${existing.status.name.lowercase()} — " +
                    "you can request it again"
            )
        }
    }

    /**
     * POST /api/aliases/slots — then either mint a lightning invoice (Strike
     * enabled; the cap rises on settle) or fall back to operator approval.
     */
    fun buySlots(quantity: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBuyingSlots = true)
            val result = aliasUseCase.purchaseSlots(quantity)
            _uiState.value = _uiState.value.copy(isBuyingSlots = false)

            result.fold(
                onSuccess = { purchase ->
                    _uiState.value = _uiState.value.copy(slotPricePrompt = null)
                    if (_uiState.value.paymentsConfig.strikeEnabled) {
                        startCheckout(PaymentTargetType.SLOT_PURCHASE, purchase.id)
                    } else {
                        _uiState.value = _uiState.value.copy(
                            toast = "Slot pack requested (${purchase.quantity}) — pending operator approval"
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(toast = e.friendlyMessage())
                }
            )
        }
    }

    /** Proactive entry point (at-cap banner / settings): fetch live slot math, open the sheet. */
    fun openSlotPurchase() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingSlots = true)
            val status = aliasUseCase.getSlotStatus().getOrNull()
            val tier = _uiState.value.tierInfo
            _uiState.value = _uiState.value.copy(
                isLoadingSlots = false,
                slotPricePrompt = SlotPurchasePrompt(
                    tier = status?.tier ?: tier?.tier,
                    freeCap = status?.freeCap ?: tier?.freeCap,
                    slotsOwned = status?.slotsOwned ?: tier?.slotsOwned,
                    cap = status?.cap ?: tier?.cap,
                    used = tier?.used,
                    priceSats = status?.priceSats ?: _uiState.value.slotPriceSats ?: 0L,
                    priceUsd = _uiState.value.slotPriceUsd
                )
            )
        }
    }

    /** POST /api/payments/tier — open the caller's tier purchase + mint its invoice (§6). */
    fun purchaseTier() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBuyingTier = true)
            val result = paymentsUseCase.purchaseTier()
            _uiState.value = _uiState.value.copy(isBuyingTier = false)

            result.fold(
                onSuccess = { invoice ->
                    _uiState.value = _uiState.value.copy(
                        checkout = CheckoutUiState(
                            target = PaymentTargetType.TIER_PURCHASE,
                            targetId = invoice.targetId,
                            invoice = invoice
                        )
                    )
                },
                onFailure = { e ->
                    if (e is PaymentsError.AlreadyPaid) {
                        _uiState.value = _uiState.value.copy(
                            toast = "You already have the paid tier"
                        )
                        loadAll()
                    } else {
                        _uiState.value = _uiState.value.copy(toast = e.friendlyPaymentsMessage())
                    }
                }
            )
        }
    }

    /**
     * Mint (or return the still-live) invoice for a pending purchase and open
     * the checkout sheet. Polling starts when the sheet enters composition
     * ([resumePolling]) and stops when it leaves ([pausePolling]).
     */
    fun startCheckout(
        target: PaymentTargetType,
        targetId: Long,
        claimLocalPart: String? = null
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                checkout = CheckoutUiState(
                    target = target,
                    targetId = targetId,
                    isMinting = true,
                    claimLocalPart = claimLocalPart
                )
            )
            paymentsUseCase.mintInvoice(target, targetId).fold(
                onSuccess = { invoice ->
                    _uiState.value = _uiState.value.copy(
                        checkout = CheckoutUiState(
                            target = target,
                            targetId = targetId,
                            invoice = invoice,
                            claimLocalPart = claimLocalPart
                        )
                    )
                },
                onFailure = { e -> onMintFailed(target, targetId, claimLocalPart, e) }
            )
        }
    }

    /** "New invoice" on expiry — re-mint for the same target (server-side fresh quote). */
    fun reMintInvoice() {
        val checkout = _uiState.value.checkout ?: return
        startCheckout(checkout.target, checkout.targetId, checkout.claimLocalPart)
    }

    private fun onMintFailed(
        target: PaymentTargetType,
        targetId: Long,
        claimLocalPart: String?,
        e: Throwable
    ) {
        when (e) {
            is PaymentsError.NotPending -> {
                // Already decided — detail.status says which (§3.1).
                when (e.status?.lowercase()) {
                    "approved" -> {
                        _uiState.value = _uiState.value.copy(checkout = null)
                        if (claimLocalPart != null) {
                            _uiState.value = _uiState.value.copy(prefillLocalPart = claimLocalPart)
                        } else {
                            loadAll()
                        }
                    }
                    "denied" -> {
                        _uiState.value = _uiState.value.copy(
                            checkout = null,
                            toast = "Request was denied"
                        )
                        loadVanityRequests()
                    }
                    else -> {
                        _uiState.value = _uiState.value.copy(
                            checkout = null,
                            toast = "Purchase is no longer pending"
                        )
                        loadVanityRequests()
                    }
                }
            }
            is PaymentsError.AlreadyPaid -> {
                _uiState.value = _uiState.value.copy(
                    checkout = null,
                    toast = "You already have the paid tier"
                )
                loadAll()
            }
            is PaymentsError.PaymentsDisabled -> {
                // Re-check the gate; fall back to the approval flow.
                _uiState.value = _uiState.value.copy(
                    checkout = null,
                    toast = "Payments are currently disabled"
                )
                refreshPaymentsConfig()
            }
            is PaymentsError.TargetNotFound -> {
                _uiState.value = _uiState.value.copy(
                    checkout = null,
                    toast = "Purchase not found"
                )
            }
            else -> {
                // RateLimited / StrikeUnavailable / network — manual retry in the sheet.
                _uiState.value = _uiState.value.copy(
                    checkout = CheckoutUiState(
                        target = target,
                        targetId = targetId,
                        isMinting = false,
                        mintError = e.friendlyPaymentsMessage(),
                        claimLocalPart = claimLocalPart
                    )
                )
            }
        }
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

    fun dismissCheckout() {
        pausePolling()
        _uiState.value = _uiState.value.copy(checkout = null)
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
                    handleCheckoutTerminal(invoice, checkout.claimLocalPart)
                    return@launch
                }
            }
        }
    }

    private fun handleCheckoutTerminal(invoice: Invoice, claimLocalPart: String?) {
        when (invoice.state) {
            InvoiceState.PAID -> if (invoice.isProcessing) {
                // Money moved, credit pending admin resolution — terminal, no retry.
                _uiState.value = _uiState.value.copy(toast = "Payment received — processing")
            } else {
                when (invoice.targetType) {
                    PaymentTargetType.VANITY_REQUEST -> {
                        _uiState.value = _uiState.value.copy(checkout = null)
                        // Approval was credited server-side — auto-retry the claim (§5).
                        if (claimLocalPart != null) createAlias(claimLocalPart, null) else loadAll()
                    }
                    PaymentTargetType.SLOT_PURCHASE -> {
                        _uiState.value = _uiState.value.copy(
                            checkout = null,
                            toast = "Payment received — alias slots credited"
                        )
                        loadAll()
                    }
                    else -> {
                        _uiState.value = _uiState.value.copy(
                            checkout = null,
                            toast = "Payment received — paid tier active"
                        )
                        loadAll()
                    }
                }
            }
            // Expired / cancelled: the sheet stays open offering "New invoice".
            else -> Unit
        }
    }

    private fun refreshPaymentsConfig() {
        viewModelScope.launch {
            paymentsUseCase.getConfig().onSuccess { payments ->
                _uiState.value = _uiState.value.copy(paymentsConfig = payments)
            }
        }
    }

    /** Approved request → pre-fill the create form to finish the claim. */
    fun startClaimFromRequest(request: VanityRequest) {
        _uiState.value = _uiState.value.copy(prefillLocalPart = request.localPart)
    }

    fun dismissVanityPrompt() {
        _uiState.value = _uiState.value.copy(vanityPricePrompt = null)
    }

    fun dismissSlotPrompt() {
        _uiState.value = _uiState.value.copy(slotPricePrompt = null)
    }

    fun onPrefillConsumed() {
        _uiState.value = _uiState.value.copy(prefillLocalPart = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    private fun loadVanityRequests() {
        viewModelScope.launch {
            val result = aliasUseCase.listVanityRequests()
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(vanityRequests = result.getOrNull()!!)
            }
            // Silent on failure — status badges are auxiliary.
        }
    }

    private fun AliasError.SlotPrice.toPrompt() = SlotPurchasePrompt(
        tier = tier,
        freeCap = freeCap,
        slotsOwned = slotsOwned,
        cap = cap,
        used = used,
        priceSats = priceSats,
        priceUsd = _uiState.value.slotPriceUsd
    )

    private fun validateLocalPart(localPart: String): String? {
        if (localPart.isEmpty()) return "Local part cannot be empty"
        if (localPart.length > MAX_LOCAL_LENGTH) return "Local part must be ≤ $MAX_LOCAL_LENGTH chars"
        if (!localPart.matches(LOCAL_PART_REGEX)) return "Only letters, digits, '.', '_', '-' allowed"
        if (localPart.lowercase() in RESERVED_LOCAL_PARTS) return "This local-part is reserved"
        return null
    }

    private fun Throwable.friendlyMessage(): String = when (this) {
        is AliasError.Taken -> "That alias is already taken"
        is AliasError.Reserved -> "That local-part is reserved"
        is AliasError.InvalidLocalPart -> "Invalid local-part format"
        is AliasError.MatchesUsername -> "That is already your username"
        is AliasError.Unauthorized -> "Authentication failed — try re-login"
        is AliasError.Forbidden -> "Account not registered on this relay"
        is AliasError.NotFound -> "Alias not found"
        is AliasError.RateLimited -> "Too many requests — try again later"
        is AliasError.VanityPrice -> "Short address — request approval first"
        is AliasError.SlotPrice -> "Alias cap reached — buy more slots"
        is AliasError.QuotaExceeded -> "Storage full — delete files, messages, or notes to free space"
        else -> message ?: "Request failed"
    }

    private fun Throwable.friendlyVanityMessage(): String = when (this) {
        is VanityError.RequestExists -> "This name is already being requested"
        is VanityError.Taken -> "That address is already taken"
        is VanityError.NotPriced -> "Names 8+ characters are free — create it directly"
        is VanityError.Reserved -> "That local-part is reserved"
        is VanityError.InvalidLocalPart -> "Invalid local-part format"
        is VanityError.InvalidKind -> "Invalid request kind"
        is VanityError.RegistrationDisabledDomain -> "That domain is closed for new claims"
        is VanityError.RateLimited -> "Too many requests — try again later"
        is VanityError.Unauthorized -> "Authentication failed — try re-login"
        else -> message ?: "Request failed"
    }

    private fun Throwable.friendlyPaymentsMessage(): String = when (this) {
        is PaymentsError.TargetNotFound -> "Purchase not found"
        is PaymentsError.NotPending -> "Purchase is no longer pending"
        is PaymentsError.InvalidTargetType -> "Invalid target type"
        is PaymentsError.ZeroPrice -> "Nothing to pay"
        is PaymentsError.RateLimited -> "Too many invoices requested — try again later"
        is PaymentsError.StrikeUnavailable -> "Payment provider unavailable — try again"
        is PaymentsError.PaymentsDisabled -> "Payments are currently disabled"
        is PaymentsError.AlreadyPaid -> "You already have the paid tier"
        is PaymentsError.Unauthorized -> "Authentication failed — try re-login"
        else -> message ?: "Request failed"
    }

    override fun onCleared() {
        pausePolling()
        super.onCleared()
    }

    companion object {
        /** ANDROID_PAYMENTS.md §4: poll every ~3 s while the checkout is visible. */
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_LOCAL_LENGTH = 50
        private val LOCAL_PART_REGEX = Regex("[A-Za-z0-9._\\-]+")
        private val RESERVED_LOCAL_PARTS = setOf(
            "admin", "administrator", "postmaster", "abuse", "root", "info",
            "noreply", "no-reply", "mail", "webmaster", "hostmaster", "security",
            "support", "help", "contact", "sales", "billing", "feedback",
            "mailer-daemon", "mailerdaemon", "null", "uucp", "news", "usenet",
            "system", "operator", "postoffice", "dm"
        )
    }
}
