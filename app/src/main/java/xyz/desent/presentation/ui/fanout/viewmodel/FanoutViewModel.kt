package xyz.desent.presentation.ui.fanout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.payments.model.PaymentsError
import xyz.desent.data.repository.FanoutRepositoryImpl
import xyz.desent.domain.model.AliasTierInfo
import xyz.desent.domain.model.BackupFetchResult
import xyz.desent.domain.model.FanoutHealth
import xyz.desent.domain.model.FanoutRelayCheck
import xyz.desent.domain.model.FanoutRelayEntry
import xyz.desent.domain.model.FanoutRelayInfo
import xyz.desent.domain.model.FeatureProduct
import xyz.desent.domain.model.Invoice
import xyz.desent.domain.model.InvoiceState
import xyz.desent.domain.model.FanoutReconcile
import xyz.desent.domain.model.PaymentTargetType
import xyz.desent.domain.model.PremiumRequiredException
import xyz.desent.domain.model.RelayListMarker
import xyz.desent.domain.model.SecurityConfig
import xyz.desent.domain.repository.SecurityConfigRepository
import xyz.desent.domain.usecase.AliasUseCase
import xyz.desent.domain.usecase.FanoutUseCase
import xyz.desent.domain.usecase.PaymentsUseCase
import xyz.desent.presentation.ui.components.CheckoutUiState

/** Render state derived from tier-info (ANDROID_DM_FANOUT.md §3). */
enum class FanoutAvailability {
    /** tier-info not loaded yet — fail closed, show nothing interactive. */
    LOADING,

    /** dm_fanout false but the user already holds the entitlement — the
     *  operator hasn't switched the feature on; NEVER show an upsell. */
    NEUTRAL_LOCKED,

    /** dm_fanout false, no entitlement — upsell with the buy path. */
    UPSELL,

    /** dm_fanout true — full feature surface. */
    ENABLED
}

/** Directory picker sheet state. */
data class DirectoryPickerState(
    val isLoading: Boolean = true,
    val query: String = "",
    val relays: List<FanoutRelayInfo> = emptyList(),
    val failed: Boolean = false
)

data class FanoutUiState(
    val isLoading: Boolean = true,
    val tierInfo: AliasTierInfo? = null,
    val dmFanoutEnabled: Boolean = false,
    val relayEntries: List<FanoutRelayEntry> = emptyList(),
    /** Unpublished local edits to the relay list. */
    val relayListDirty: Boolean = false,
    val isPublishingRelays: Boolean = false,
    /**
     * Per-relay profile + probe data (icon, name, ping, uptime,
     * `supported_nips`) from the directory detail lookup or the relay's own
     * NIP-11 doc. Drives the per-NIP chips under each relay row.
     */
    val relayInfo: Map<String, FanoutRelayInfo> = emptyMap(),
    /** Server relay-check verdicts per relay URL (§6.1, advisory only). */
    val relayChecks: Map<String, FanoutRelayCheck> = emptyMap(),
    val health: FanoutHealth? = null,
    val healthFailed: Boolean = false,
    val directory: DirectoryPickerState? = null,
    val backupReport: List<BackupFetchResult> = emptyList(),
    val isFetchingBackup: Boolean = false,
    /** §6.3 mirror repair: last reconcile report (null = none loaded). */
    val reconcileReport: FanoutReconcile? = null,
    val isReconciling: Boolean = false,
    val isImporting: Boolean = false,
    val checkout: CheckoutUiState? = null,
    val isBuying: Boolean = false,
    /** Set when a dm_fanout save hit the relay's premium gate. */
    val premiumPrompt: Boolean = false,
    val toggleError: String? = null,
    val toast: String? = null
) {
    val availability: FanoutAvailability
        get() {
            val tier = tierInfo ?: return FanoutAvailability.LOADING
            if (tier.dmFanout == true) return FanoutAvailability.ENABLED
            val entitled = tier.dmFanoutPurchased == true || tier.isPaid
            return if (entitled) FanoutAvailability.NEUTRAL_LOCKED else FanoutAvailability.UPSELL
        }
}

/**
 * Relay Mirroring settings (ANDROID_DM_FANOUT.md): availability gating +
 * Strike add-on checkout, the 30079 `dm_fanout` opt-in, the NIP-65 relay
 * list editor with the directory.yadha.net picker and advisory NIP-support
 * highlights, delivery health pills, and the manual backup fetch.
 */
class FanoutViewModel(
    private val fanoutUseCase: FanoutUseCase,
    private val aliasUseCase: AliasUseCase,
    private val paymentsUseCase: PaymentsUseCase,
    private val securityConfigRepository: SecurityConfigRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(FanoutUiState())
    val uiState: StateFlow<FanoutUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        // Light core only — tier-info plus the dm_fanout config stream — so
        // the Mail spoke can host the toggle card without pulling the
        // relay-list/health machinery. The advanced screen calls [load].
        refreshTierInfo()
        viewModelScope.launch {
            val npub = preferencesManager.npubKey.firstOrNull() ?: return@launch
            securityConfigRepository.observe(npub).collect { config: SecurityConfig? ->
                _uiState.value = _uiState.value.copy(dmFanoutEnabled = config?.dmFanout ?: false)
            }
        }
    }

    /**
     * Full advanced-screen load: tier-info, the relay list (with per-relay
     * info) and delivery health. The Mail spoke's toggle card never calls
     * this.
     */
    fun load() {
        viewModelScope.launch {
            aliasUseCase.getTierInfo().onSuccess { tier ->
                _uiState.value = _uiState.value.copy(tierInfo = tier)
            }
            fanoutUseCase.fetchRelayList().onSuccess { entries ->
                _uiState.value = _uiState.value.copy(relayEntries = entries, relayListDirty = false)
                entries.forEach { loadRelayInfo(it.url) }
            }
            refreshHealth()
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    /** Re-fetch tier-info only (post-purchase: `dm_fanout` should flip true). */
    fun refreshTierInfo() {
        viewModelScope.launch {
            aliasUseCase.getTierInfo().onSuccess { tier ->
                _uiState.value = _uiState.value.copy(tierInfo = tier)
            }
        }
    }

    // ---------------- Opt-in toggle (kind 30079 partial save) ----------------

    /**
     * `{"dm_fanout": bool}` — verdict-awaited publish; a premium rejection
     * reverts the switch and shows the upsell instead of retrying.
     */
    fun setDmFanout(enabled: Boolean) {
        if (_uiState.value.availability != FanoutAvailability.ENABLED && enabled) {
            _uiState.value = _uiState.value.copy(premiumPrompt = true)
            return
        }
        viewModelScope.launch {
            val npub = preferencesManager.npubKey.firstOrNull() ?: return@launch
            _uiState.value = _uiState.value.copy(dmFanoutEnabled = enabled, toggleError = null)
            securityConfigRepository.setDmFanout(npub, enabled).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        toast = if (enabled) {
                            "Relay mirroring on. Saving to desent.xyz…"
                        } else {
                            "Relay mirroring off"
                        }
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        dmFanoutEnabled = !enabled,
                        premiumPrompt = e is PremiumRequiredException,
                        toggleError = if (e !is PremiumRequiredException) {
                            "Couldn't update. ${e.message ?: "Check your connection"}"
                        } else {
                            null
                        }
                    )
                }
            )
        }
    }

    fun dismissPremiumPrompt() {
        _uiState.value = _uiState.value.copy(premiumPrompt = false)
    }

    // ---------------- Strike add-on checkout ----------------

    /** POST /api/payments/feature {"product":"dm_fanout"} — one call creates-or-reuses + mints. */
    fun purchaseFanout() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBuying = true)
            val result = paymentsUseCase.purchaseFeature(FeatureProduct.DM_FANOUT)
            _uiState.value = _uiState.value.copy(isBuying = false)
            result.fold(
                onSuccess = { invoice ->
                    _uiState.value = _uiState.value.copy(
                        checkout = CheckoutUiState(
                            target = PaymentTargetType.FEATURE_PURCHASE,
                            targetId = invoice.targetId,
                            invoice = invoice,
                            featureProduct = FeatureProduct.DM_FANOUT
                        )
                    )
                },
                onFailure = { e -> onPurchaseFailed(e) }
            )
        }
    }

    /** "New invoice" on expiry — the endpoint reuses/re-mints server-side. */
    fun reMintInvoice() {
        purchaseFanout()
    }

    private fun onPurchaseFailed(e: Throwable) {
        _uiState.value = _uiState.value.copy(toast = e.friendlyMessage())
        when (e) {
            is PaymentsError.AlreadyEntitled -> refreshTierInfo()
            is PaymentsError.FeatureDisabled,
            is PaymentsError.FeaturePurchasesDisabled -> refreshTierInfo()
            else -> Unit // RateLimited / StrikeUnavailable / network — manual retry.
        }
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
        _uiState.value = _uiState.value.copy(checkout = null)
        _uiState.value = _uiState.value.copy(
            toast = if (invoice.isProcessing) {
                "Payment received. Processing…"
            } else {
                "Payment received. The mirroring add-on is now active."
            }
        )
        // `state == paid` IS the entitlement signal: re-fetch tier-info so
        // `dm_fanout` flips and the feature surface unlocks.
        refreshTierInfo()
    }

    // ---------------- NIP-65 relay list editor ----------------

    /** Add a manually typed URL — canonicalized by the nestled WSS check. */
    fun addRelay(input: String) {
        val url = FanoutRepositoryImpl.canonicalRelayUrl(input) ?: run {
            _uiState.value = _uiState.value.copy(toast = "Enter a valid relay address (wss://…)")
            return
        }
        if (_uiState.value.relayEntries.any { it.url.equals(url, ignoreCase = true) }) {
            _uiState.value = _uiState.value.copy(toast = "That relay is already in your list")
            return
        }
        _uiState.value = _uiState.value.copy(
            relayEntries = _uiState.value.relayEntries + FanoutRelayEntry(url = url),
            relayListDirty = true
        )
        loadRelayInfo(url)
    }

    fun addFromDirectory(relay: FanoutRelayInfo) {
        val url = FanoutRepositoryImpl.canonicalRelayUrl(relay.url) ?: return
        if (_uiState.value.relayEntries.any { it.url.equals(url, ignoreCase = true) }) {
            _uiState.value = _uiState.value.copy(
                toast = "That relay is already in your list",
                directory = null
            )
            return
        }
        _uiState.value = _uiState.value.copy(
            relayEntries = _uiState.value.relayEntries + FanoutRelayEntry(url = url),
            relayListDirty = true,
            directory = null
        )
        // The directory row already carries icon/ping/uptime/nips.
        _uiState.value = _uiState.value.copy(
            relayInfo = _uiState.value.relayInfo + (url to relay)
        )
    }

    fun removeRelay(index: Int) {
        val entries = _uiState.value.relayEntries.toMutableList()
        if (index !in entries.indices) return
        entries.removeAt(index)
        _uiState.value = _uiState.value.copy(relayEntries = entries, relayListDirty = true)
    }

    fun setMarker(index: Int, marker: RelayListMarker) {
        val entries = _uiState.value.relayEntries.toMutableList()
        if (index !in entries.indices) return
        entries[index] = entries[index].copy(marker = marker)
        _uiState.value = _uiState.value.copy(relayEntries = entries, relayListDirty = true)
    }

    fun publishRelays() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPublishingRelays = true)
            fanoutUseCase.publishRelayList(_uiState.value.relayEntries).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isPublishingRelays = false,
                        relayListDirty = false,
                        toast = "Relay list published to desent.xyz"
                    )
                    fanoutUseCase.fetchRelayList().onSuccess { entries ->
                        _uiState.value = _uiState.value.copy(relayEntries = entries)
                    }
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isPublishingRelays = false,
                        toast = "Publish failed. ${e.message ?: "Try again."}"
                    )
                }
            )
        }
    }

    /**
     * Profile + probe data for one relay: directory detail first (icon, ping,
     * uptime, harvested NIP-11), the relay's own NIP-11 doc as fallback.
     */
    private fun loadRelayInfo(url: String) {
        if (_uiState.value.relayInfo.containsKey(url)) return
        viewModelScope.launch {
            val info = fanoutUseCase.fetchRelayInfo(url).getOrNull()
            if (info != null) {
                _uiState.value = _uiState.value.copy(
                    relayInfo = _uiState.value.relayInfo + (url to info)
                )
            }
        }
        checkRelay(url)
    }

    /**
     * §6.1 server relay-check — directory uptime/RTT + NIP-11 + a
     * capabilities verdict. Advisory only; never blocks a save.
     */
    private fun checkRelay(url: String) {
        if (_uiState.value.relayChecks.containsKey(url)) return
        viewModelScope.launch {
            fanoutUseCase.relayCheck(url).onSuccess { check ->
                _uiState.value = _uiState.value.copy(
                    relayChecks = _uiState.value.relayChecks + (url to check)
                )
            }
        }
    }

    // ---------------- Directory picker (directory.yadha.net) ----------------

    fun openDirectory() {
        _uiState.value = _uiState.value.copy(
            directory = DirectoryPickerState(isLoading = true)
        )
        searchDirectory(null)
    }

    fun searchDirectory(query: String?) {
        _uiState.value = _uiState.value.copy(
            directory = (_uiState.value.directory ?: DirectoryPickerState())
                .copy(isLoading = true, query = query ?: "", failed = false)
        )
        viewModelScope.launch {
            fanoutUseCase.searchDirectory(query).fold(
                onSuccess = { relays ->
                    _uiState.value = _uiState.value.copy(
                        directory = DirectoryPickerState(
                            isLoading = false,
                            query = query ?: "",
                            relays = relays
                        )
                    )
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(
                        directory = (_uiState.value.directory ?: DirectoryPickerState())
                            .copy(isLoading = false, failed = true)
                    )
                }
            )
        }
    }

    fun closeDirectory() {
        _uiState.value = _uiState.value.copy(directory = null)
    }

    // ---------------- Health + backup fetch ----------------

    fun refreshHealth() {
        viewModelScope.launch {
            fanoutUseCase.getHealth().fold(
                onSuccess = { health ->
                    _uiState.value = _uiState.value.copy(health = health, healthFailed = false)
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(healthFailed = true)
                }
            )
        }
    }

    /** Manual "Fetch now" — the same path the automatic sync runs. */
    fun fetchBackupNow() {
        val entries = _uiState.value.relayEntries
        if (entries.isEmpty()) {
            _uiState.value = _uiState.value.copy(toast = "Add backup relays to your list first")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isFetchingBackup = true)
            fanoutUseCase.fetchFromBackupRelays(entries).fold(
                onSuccess = { report ->
                    _uiState.value = _uiState.value.copy(
                        isFetchingBackup = false,
                        backupReport = report,
                        toast = "${report.count { it.status == xyz.desent.domain.model.BackupFetchStatus.DONE }}/${report.size} relays fetched"
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isFetchingBackup = false,
                        toast = "Fetch failed. ${e.message ?: "Try again."}"
                    )
                }
            )
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null, toggleError = null)
    }

    // ---------------- Mirror repair (§6.3) ----------------

    /**
     * Explicit user action only — the server rate-limits reconcile to
     * ~5/hour; never call this on a timer.
     */
    fun reconcileMirrors() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isReconciling = true)
            fanoutUseCase.reconcile().fold(
                onSuccess = { report ->
                    _uiState.value = _uiState.value.copy(
                        isReconciling = false,
                        reconcileReport = report
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isReconciling = false,
                        toast = "Reconcile failed. ${e.message ?: "Try again later."}"
                    )
                }
            )
        }
    }

    /** Import the missing wraps the last reconcile found (≤ 100 per call). */
    fun importMissing() {
        val ids = _uiState.value.reconcileReport?.importableIds ?: emptyList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true)
            fanoutUseCase.importWraps(ids).fold(
                onSuccess = { result ->
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        reconcileReport = null,
                        toast = if (result.imported > 0) {
                            "Imported ${result.imported} new messages. Check your inbox."
                        } else {
                            "Nothing new to import"
                        }
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        toast = "Import failed. ${e.message ?: "Try again."}"
                    )
                }
            )
        }
    }

    fun dismissReconcileReport() {
        _uiState.value = _uiState.value.copy(reconcileReport = null)
    }

    private fun Throwable.friendlyMessage(): String = when (this) {
        is PaymentsError.FeaturePurchasesDisabled ->
            if (feature == "fanout") "Relay mirroring purchases are currently disabled"
            else message ?: "Add-on purchases are currently disabled"
        is PaymentsError.FeatureDisabled ->
            if (feature == "fanout") "Relay mirroring isn't enabled on this relay yet"
            else message ?: "That feature isn't enabled on this relay yet"
        is PaymentsError.AlreadyEntitled ->
            if (product == FeatureProduct.DM_FANOUT.wire) "You already have relay mirroring"
            else message ?: "You already have this feature"
        is PaymentsError.InvalidProduct -> "Invalid product"
        is PaymentsError.RateLimited -> message ?: "Too many invoices requested. Try again later."
        is PaymentsError.StrikeUnavailable -> message ?: "Payment provider unavailable. Try again."
        is PaymentsError.PaymentsDisabled -> message ?: "Payments are currently disabled"
        is PaymentsError.Unauthorized -> message ?: "Authentication failed. Please sign in again."
        else -> message ?: "Something went wrong"
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3_000L
    }
}
