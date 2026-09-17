package xyz.desent.presentation.ui.agents.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.desent.data.agents.AgentKeyFactory
import xyz.desent.data.agents.model.AgentsError
import xyz.desent.data.payments.model.PaymentsError
import xyz.desent.data.vanity.model.VanityError
import xyz.desent.domain.model.Agent
import xyz.desent.domain.model.AgentConnectionCode
import xyz.desent.domain.model.AgentMode
import xyz.desent.domain.model.AgentUpdate
import xyz.desent.domain.model.AgentsSnapshot
import xyz.desent.domain.model.AliasTierInfo
import xyz.desent.domain.model.Invoice
import xyz.desent.domain.model.InvoiceState
import xyz.desent.domain.model.PaymentTargetType
import xyz.desent.domain.model.VanityRequestStatus
import xyz.desent.domain.usecase.AgentsUseCase
import xyz.desent.domain.usecase.AliasUseCase
import xyz.desent.domain.usecase.PaymentsUseCase
import xyz.desent.presentation.ui.components.CheckoutUiState

/** 402 `vanity_price` payload for the "short address" dialog. */
data class AgentVanityPrompt(
    val localPart: String,
    val priceSats: Long,
    val ladder: Map<String, Long>,
    val freeLength: Int,
    /** The create-form values to replay once the invoice settles. */
    val label: String,
    val mode: AgentMode
)

data class AgentsUiState(
    val agents: List<Agent> = emptyList(),
    val snapshot: AgentsSnapshot? = null,
    /** Master-switch gate from /api/aliases/tier-info; null = not loaded yet. */
    val tierInfo: AliasTierInfo? = null,
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val error: String? = null,
    val toast: String? = null,
    /** Non-null shows the one-time connection-code handoff screen (§3 step 4). */
    val handoff: AgentHandoff? = null,
    /** Non-null shows the short-address pricing dialog (402 vanity_price). */
    val vanityPrompt: AgentVanityPrompt? = null,
    /** Non-null shows the billing upsell (402 agent_cap_reached). */
    val capPrompt: Boolean = false,
    /** Strike gate for the short-address checkout; fails closed (§1). */
    val paymentsStrikeEnabled: Boolean = false,
    /** Lightning checkout in progress for a short agent address (§5). */
    val checkout: CheckoutUiState? = null
) {
    /** Fail-closed section gate: missing tier field = feature off (§1). */
    val sectionEnabled: Boolean get() = tierInfo?.agents == true

    val capLabel: String
        get() {
            val snap = snapshot ?: return ""
            return if (snap.cap == null) "${snap.used} of unlimited" else "${snap.used} of ${snap.cap}"
        }
}

/** One-time handoff payload: shown once, then gone forever (§3 step 4). */
data class AgentHandoff(
    val agent: Agent,
    val connectionCode: AgentConnectionCode
)

/** Create-form values replayed after a vanity invoice settles (§5). */
private data class PendingAgentCreate(
    val label: String,
    val localPart: String,
    val mode: AgentMode
)

class AgentsViewModel(
    private val agentsUseCase: AgentsUseCase,
    private val aliasUseCase: AliasUseCase,
    private val paymentsUseCase: PaymentsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentsUiState())
    val uiState: StateFlow<AgentsUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    /** The create attempt a live checkout is paying for. */
    private var pendingCreate: PendingAgentCreate? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // Master switch (fail-closed): a missing "agents" field on an old
            // server keeps the section locked.
            aliasUseCase.getTierInfo().onSuccess { tier ->
                _uiState.value = _uiState.value.copy(tierInfo = tier)
            }

            // Strike gate for the short-address checkout; fails closed.
            paymentsUseCase.getConfig().onSuccess { config ->
                _uiState.value = _uiState.value.copy(paymentsStrikeEnabled = config.strikeEnabled)
            }

            val result = agentsUseCase.listAgents()
            if (result.isSuccess) {
                val snapshot = result.getOrNull()!!
                _uiState.value = _uiState.value.copy(
                    agents = snapshot.agents,
                    snapshot = snapshot,
                    isLoading = false
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = result.exceptionOrNull()?.friendlyMessage()
                )
            }
        }
    }

    fun refresh() = load()

    /**
     * Onboarding step 2 (§3): generate the keypair + connection code
     * ON-DEVICE and BEFORE the network call, then POST only the npub. On
     * success the one-time handoff screen shows; on failure the secret is
     * discarded (the wizard restarts with a fresh key).
     */
    fun createAgent(label: String, addressLocal: String, mode: AgentMode) {
        val localPart = addressLocal.trim().lowercase()
        validateLocalPart(localPart)?.let { violation ->
            _uiState.value = _uiState.value.copy(toast = violation)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCreating = true)

            val generated = AgentKeyFactory.generate().getOrNull()
            if (generated == null) {
                _uiState.value = _uiState.value.copy(
                    isCreating = false,
                    toast = "Failed to generate the agent key"
                )
                return@launch
            }

            val result = agentsUseCase.createAgent(
                agentPubkey = generated.agentPubkeyHex,
                addressLocal = localPart,
                mode = mode,
                label = label.trim().takeIf { it.isNotEmpty() }
            )

            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isCreating = false,
                    handoff = AgentHandoff(result.getOrNull()!!, generated.connectionCode)
                )
                load()
            } else {
                _uiState.value = _uiState.value.copy(isCreating = false)
                when (val err = result.exceptionOrNull()) {
                    is AgentsError.VanityPrice -> _uiState.value = _uiState.value.copy(
                        vanityPrompt = AgentVanityPrompt(
                            localPart = localPart,
                            priceSats = err.priceSats,
                            ladder = err.ladder,
                            freeLength = err.freeLength,
                            label = label.trim(),
                            mode = mode
                        )
                    )
                    is AgentsError.CapReached -> _uiState.value = _uiState.value.copy(
                        capPrompt = true
                    )
                    else -> _uiState.value = _uiState.value.copy(toast = err?.friendlyMessage())
                }
            }
        }
    }

    fun updateAgent(
        id: Long,
        mode: AgentMode,
        triggerKeywords: List<String>,
        policyNote: String,
        systemPrompt: String,
        displayName: String,
        pictureUrl: String,
        about: String
    ) {
        val keywords = normalizeKeywords(triggerKeywords)
        if (keywords == null) {
            _uiState.value = _uiState.value.copy(toast = "Max $MAX_KEYWORDS keywords of $MAX_KEYWORD_LENGTH chars each")
            return
        }
        val picture = pictureUrl.trim()
        if (picture.isNotEmpty() && !picture.startsWith("http://") && !picture.startsWith("https://")) {
            _uiState.value = _uiState.value.copy(toast = "Picture URL must be http(s)")
            return
        }
        val name = displayName.trim()
        if (name.length > MAX_DISPLAY_NAME_LENGTH) {
            _uiState.value = _uiState.value.copy(toast = "Display name must be ≤ $MAX_DISPLAY_NAME_LENGTH chars")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val result = agentsUseCase.updateAgent(
                id,
                AgentUpdate(
                    mode = mode,
                    triggerKeywords = keywords,
                    policyNote = policyNote.trim().take(MAX_POLICY_LENGTH).takeIf { it.isNotEmpty() },
                    // Newlines preserved server-side (§7) — send verbatim,
                    // clamped, never client-mangled.
                    systemPrompt = systemPrompt.trim().take(MAX_SYSTEM_PROMPT_LENGTH).takeIf { it.isNotEmpty() },
                    displayName = name.takeIf { it.isNotEmpty() },
                    pictureUrl = picture.takeIf { it.isNotEmpty() },
                    about = about.trim().take(MAX_ABOUT_LENGTH).takeIf { it.isNotEmpty() }
                )
            )
            _uiState.value = _uiState.value.copy(isSaving = false)

            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(toast = "Agent updated")
                load()
            } else {
                _uiState.value = _uiState.value.copy(toast = result.exceptionOrNull()?.friendlyMessage())
            }
        }
    }

    /** Hard revoke: the address frees immediately and the agent loses read + send. */
    fun deleteAgent(id: Long) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true)
            val result = agentsUseCase.deleteAgent(id)
            _uiState.value = _uiState.value.copy(isDeleting = false)

            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(toast = "Agent revoked")
                load()
            } else {
                _uiState.value = _uiState.value.copy(toast = result.exceptionOrNull()?.friendlyMessage())
            }
        }
    }

    /** The handoff screen was dismissed — the code is unrecoverable now. */
    fun dismissHandoff() {
        _uiState.value = _uiState.value.copy(handoff = null)
    }

    fun dismissVanityPrompt() {
        _uiState.value = _uiState.value.copy(vanityPrompt = null)
    }

    fun dismissCapPrompt() {
        _uiState.value = _uiState.value.copy(capPrompt = false)
    }

    // ------------------------------------------------------------------
    // Short-address lightning checkout — refs/FROM_email.desent.xyz/
    // ANDROID_PAYMENTS.md §5 (the agents 402 deep-links the alias flow)
    // ------------------------------------------------------------------

    /**
     * "Continue to payment" from the short-address dialog: create-or-reuse
     * the vanity request (the OWNER signs — the agent key is minted later,
     * per attempt), mint the invoice, and on settle replay the create.
     */
    fun payVanityAddress() {
        val prompt = _uiState.value.vanityPrompt ?: return
        pendingCreate = PendingAgentCreate(prompt.label, prompt.localPart, prompt.mode)
        _uiState.value = _uiState.value.copy(vanityPrompt = null, isCreating = true)

        viewModelScope.launch {
            val created = aliasUseCase.createVanityRequest(
                localPart = prompt.localPart,
                domain = null,
                kind = VANITY_KIND_ALIAS
            )
            val request: xyz.desent.domain.model.VanityRequest? = when {
                created.isSuccess -> created.getOrThrow()
                created.exceptionOrNull() is VanityError.RequestExists ->
                    reuseOwnVanityRequest(prompt.localPart)
                else -> {
                    _uiState.value = _uiState.value.copy(
                        isCreating = false,
                        toast = created.exceptionOrNull()?.friendlyVanityMessage() ?: "Request failed"
                    )
                    null
                }
            }
            if (request == null) return@launch

            _uiState.value = _uiState.value.copy(isCreating = false)
            when (request.status) {
                VanityRequestStatus.PENDING -> startCheckout(request.id, prompt.localPart)
                VanityRequestStatus.APPROVED -> retryPendingCreate()
                VanityRequestStatus.DENIED -> _uiState.value = _uiState.value.copy(
                    toast = "Request for ${request.email} was denied"
                )
                VanityRequestStatus.CLAIMED -> _uiState.value = _uiState.value.copy(
                    toast = "${request.email} is already claimed. Pick another address"
                )
            }
        }
    }

    /** 409 `request_exists`: reuse our own row for this name, if any (§5). */
    private suspend fun reuseOwnVanityRequest(localPart: String): xyz.desent.domain.model.VanityRequest? {
        val existing = aliasUseCase.listVanityRequests().getOrNull()
            ?.firstOrNull { it.localPart == localPart }
        if (existing == null) {
            _uiState.value = _uiState.value.copy(
                isCreating = false,
                toast = "This address is already being requested"
            )
        }
        return existing
    }

    /** Mint (or return the still-live) invoice and open the checkout sheet. */
    private fun startCheckout(targetId: Long, claimLocalPart: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                checkout = CheckoutUiState(
                    target = PaymentTargetType.VANITY_REQUEST,
                    targetId = targetId,
                    isMinting = true,
                    claimLocalPart = claimLocalPart
                )
            )
            paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, targetId).fold(
                onSuccess = { invoice ->
                    _uiState.value = _uiState.value.copy(
                        checkout = CheckoutUiState(
                            target = PaymentTargetType.VANITY_REQUEST,
                            targetId = targetId,
                            invoice = invoice,
                            claimLocalPart = claimLocalPart
                        )
                    )
                },
                onFailure = { e -> onMintFailed(targetId, claimLocalPart, e) }
            )
        }
    }

    /** "New invoice" on expiry — re-mint for the same target. */
    fun reMintInvoice() {
        val checkout = _uiState.value.checkout ?: return
        startCheckout(checkout.targetId, checkout.claimLocalPart ?: return)
    }

    private fun onMintFailed(targetId: Long, claimLocalPart: String, e: Throwable) {
        when (e) {
            is PaymentsError.NotPending -> when (e.status?.lowercase()) {
                "approved" -> {
                    _uiState.value = _uiState.value.copy(checkout = null)
                    retryPendingCreate()
                }
                "denied" -> _uiState.value = _uiState.value.copy(
                    checkout = null,
                    toast = "Request was denied"
                )
                else -> _uiState.value = _uiState.value.copy(
                    checkout = null,
                    toast = "Purchase is no longer pending"
                )
            }
            is PaymentsError.PaymentsDisabled -> {
                _uiState.value = _uiState.value.copy(
                    checkout = null,
                    paymentsStrikeEnabled = false,
                    toast = "Payments are currently disabled"
                )
            }
            is PaymentsError.TargetNotFound -> _uiState.value = _uiState.value.copy(
                checkout = null,
                toast = "Purchase not found"
            )
            else -> {
                // RateLimited / StrikeUnavailable / network — manual retry in the sheet.
                _uiState.value = _uiState.value.copy(
                    checkout = CheckoutUiState(
                        target = PaymentTargetType.VANITY_REQUEST,
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
                    handleCheckoutTerminal(invoice)
                    return@launch
                }
            }
        }
    }

    private fun handleCheckoutTerminal(invoice: Invoice) {
        when (invoice.state) {
            InvoiceState.PAID -> if (invoice.isProcessing) {
                // Money moved, credit pending admin resolution — terminal, no retry.
                _uiState.value = _uiState.value.copy(
                    checkout = null,
                    toast = "Payment received. Processing…"
                )
            } else {
                _uiState.value = _uiState.value.copy(checkout = null)
                // Approval was credited server-side — replay the create (§5).
                retryPendingCreate()
            }
            // Expired / cancelled: the sheet stays open offering "New invoice".
            else -> Unit
        }
    }

    /** Replay the create attempt the checkout paid for. */
    private fun retryPendingCreate() {
        val pending = pendingCreate ?: return
        createAgent(pending.label, pending.localPart, pending.mode)
    }

    private fun Throwable.friendlyVanityMessage(): String = when (this) {
        is VanityError.Taken -> "That address is already taken"
        is VanityError.NotPriced -> "Addresses of 8+ characters are free, no purchase needed"
        is VanityError.Reserved -> "That address is reserved"
        is VanityError.InvalidLocalPart -> "Invalid address format"
        is VanityError.RateLimited -> "Too many requests. Try again later."
        is VanityError.Unauthorized -> "Authentication failed. Please sign in again."
        else -> message ?: "Request failed"
    }

    private fun Throwable.friendlyPaymentsMessage(): String = when (this) {
        is PaymentsError.RateLimited -> "Too many invoices requested. Try again later."
        is PaymentsError.StrikeUnavailable -> "Payment provider unavailable. Try again."
        else -> message ?: "Couldn't create the invoice"
    }

    override fun onCleared() {
        pausePolling()
        super.onCleared()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    /** ≤20 keywords × 64 chars, trimmed + deduped (§4); null = invalid. */
    private fun normalizeKeywords(raw: List<String>): List<String>? {
        val normalized = raw.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (normalized.size > MAX_KEYWORDS) return null
        if (normalized.any { it.length > MAX_KEYWORD_LENGTH }) return null
        return normalized
    }

    private fun validateLocalPart(localPart: String): String? {
        if (localPart.isEmpty()) return "Address cannot be empty"
        if (localPart.length > MAX_LOCAL_LENGTH) return "Address must be ≤ $MAX_LOCAL_LENGTH chars"
        if (!localPart.matches(LOCAL_PART_REGEX)) return "Only letters, digits, '.', '_', '-' allowed"
        if (localPart in RESERVED_LOCAL_PARTS) return "This address is reserved"
        return null
    }

    private fun Throwable.friendlyMessage(): String = when (this) {
        is AgentsError.Disabled -> "AI agents are disabled on this server"
        is AgentsError.Taken -> "That address is already taken"
        is AgentsError.AlreadyRegistered -> "This agent key is already registered"
        is AgentsError.InvalidLocalPart -> "Invalid address format"
        is AgentsError.InvalidPubkey -> "Invalid agent public key"
        is AgentsError.InvalidMode -> "Invalid agent mode"
        is AgentsError.InvalidDomain -> "Invalid domain"
        is AgentsError.Unauthorized -> "Authentication failed. Please sign in again."
        is AgentsError.NotFound -> "Agent not found"
        is AgentsError.RateLimited -> "Too many requests. Try again later."
        is AgentsError.VanityPrice -> "Short address, so pricing applies"
        is AgentsError.CapReached -> "Agent cap reached for your tier"
        else -> message ?: "Request failed"
    }

    companion object {
        // The agents 402 deep-links the alias vanity flow — same request kind.
        private const val VANITY_KIND_ALIAS = "alias"

        // ANDROID_PAYMENTS.md §4: poll every ~3 s while the checkout is visible.
        private const val POLL_INTERVAL_MS = 3_000L

        private const val MAX_LOCAL_LENGTH = 50
        private const val MAX_KEYWORDS = 20
        private const val MAX_KEYWORD_LENGTH = 64
        private const val MAX_POLICY_LENGTH = 500
        private const val MAX_SYSTEM_PROMPT_LENGTH = 4000
        private const val MAX_DISPLAY_NAME_LENGTH = 100
        private const val MAX_ABOUT_LENGTH = 1000
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
