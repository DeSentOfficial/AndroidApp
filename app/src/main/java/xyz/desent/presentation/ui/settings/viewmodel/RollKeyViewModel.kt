package xyz.desent.presentation.ui.settings.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.local.database.dao.AccountDao
import xyz.desent.data.payments.model.PaymentsError
import xyz.desent.data.registration.model.KeyRotationError
import xyz.desent.domain.model.AliasTierInfo
import xyz.desent.domain.model.FeatureProduct
import xyz.desent.domain.model.Invoice
import xyz.desent.domain.model.InvoiceState
import xyz.desent.domain.model.PaymentTargetType
import xyz.desent.domain.usecase.AliasUseCase
import xyz.desent.domain.usecase.KeyRotationUseCase
import xyz.desent.domain.usecase.PaymentsUseCase
import xyz.desent.presentation.ui.components.CheckoutUiState

/** Wizard surface state (ANDROID_KEY_ROTATION.md §1). */
enum class RollKeyWizardStep { INTRO, BACKUP, PROGRESS, DONE, ERROR }

/** Minimum length for the new custodial password. */
const val ROLL_KEY_MIN_PASSWORD = 8

/** Render state derived from tier-info (ANDROID_KEY_ROTATION.md intro). */
enum class RollKeyAvailability {
    /** tier-info not loaded yet — fail closed, show nothing interactive. */
    LOADING,

    /** key_rotation false but the user already holds the entitlement — the
     *  operator hasn't switched the feature on; NEVER show an upsell. */
    NEUTRAL_LOCKED,

    /** key_rotation false, no entitlement — upsell with the buy path. */
    UPSELL,

    /** key_rotation true — full wizard. */
    ENABLED
}

data class RollKeyUiState(
    val tierInfo: AliasTierInfo? = null,
    /** Null = the active account has no password → the conversion flow. */
    val custodialUsername: String? = null,
    val step: RollKeyWizardStep = RollKeyWizardStep.INTRO,

    // The pre-generated key (shown on BACKUP, used by the rotate call).
    val newNsec: String? = null,
    val newNpub: String? = null,
    val keySaved: Boolean = false,

    // Form fields.
    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val migrateMail: Boolean = true,
    val keepOldIdentity: Boolean = true,

    // Add-on checkout (ANDROID_KEY_ROTATION.md intro / §3.1b).
    val checkout: CheckoutUiState? = null,
    val isBuying: Boolean = false,

    // Progress + outcome.
    val activeStep: KeyRotationUseCase.Step? = null,
    val result: KeyRotationUseCase.Result? = null,
    /** True when a failure happened AFTER the server committed the re-key. */
    val committed: Boolean = false,
    val error: String? = null,
    val busy: Boolean = false
) {
    val isCustodial: Boolean get() = custodialUsername != null
    val canRotate: Boolean get() = newPassword.length >= ROLL_KEY_MIN_PASSWORD &&
        newPassword == confirmPassword &&
        (!isCustodial || currentPassword.isNotEmpty())

    /** Fail-closed: no tier-info (or key_rotation null) → locked entry. */
    val availability: RollKeyAvailability
        get() {
            val tier = tierInfo ?: return RollKeyAvailability.LOADING
            if (tier.keyRotation == true) return RollKeyAvailability.ENABLED
            val entitled = tier.keyRotationPurchased == true || tier.isPaid
            return if (entitled) RollKeyAvailability.NEUTRAL_LOCKED else RollKeyAvailability.UPSELL
        }
}

class RollKeyViewModel(
    private val keyRotationUseCase: KeyRotationUseCase,
    private val secureKeyManager: SecureKeyManager,
    private val preferencesManager: PreferencesManager,
    private val accountDao: AccountDao,
    private val aliasUseCase: AliasUseCase,
    private val paymentsUseCase: PaymentsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(RollKeyUiState())
    val uiState: StateFlow<RollKeyUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            val tier = aliasUseCase.getTierInfo().getOrNull()
            if (tier != null) {
                _uiState.value = _uiState.value.copy(tierInfo = tier)
            }
            val npub = preferencesManager.getActiveNpub() ?: return@launch
            _uiState.value = _uiState.value.copy(
                custodialUsername = accountDao.getCustodialUsername(npub)
            )
        }
    }

    fun update(
        currentPassword: String? = null,
        newPassword: String? = null,
        confirmPassword: String? = null,
        migrateMail: Boolean? = null,
        keepOldIdentity: Boolean? = null,
        keySaved: Boolean? = null
    ) {
        _uiState.value = _uiState.value.copy(
            currentPassword = currentPassword ?: _uiState.value.currentPassword,
            newPassword = newPassword ?: _uiState.value.newPassword,
            confirmPassword = confirmPassword ?: _uiState.value.confirmPassword,
            migrateMail = migrateMail ?: _uiState.value.migrateMail,
            keepOldIdentity = keepOldIdentity ?: _uiState.value.keepOldIdentity,
            keySaved = keySaved ?: _uiState.value.keySaved
        )
    }

    /** Generate the fresh keypair and move to the mandatory backup screen. */
    fun generateNewKey() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, error = null)
            secureKeyManager.generateKeyPair().fold(
                onSuccess = { (nsec, npub) ->
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        newNsec = nsec,
                        newNpub = npub,
                        keySaved = false,
                        step = RollKeyWizardStep.BACKUP
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        error = "Could not generate a key: ${e.message}"
                    )
                }
            )
        }
    }

    /** The destructive step — snapshot → rotate → migrate → cleanup. */
    fun rotate() {
        val state = _uiState.value
        val nsec = state.newNsec ?: return
        if (!state.canRotate) return
        _uiState.value = state.copy(step = RollKeyWizardStep.PROGRESS, error = null, committed = false)
        viewModelScope.launch {
            keyRotationUseCase.rotate(
                KeyRotationUseCase.Params(
                    newNsec = nsec,
                    newPassword = state.newPassword,
                    currentPassword = state.currentPassword.takeIf { state.isCustodial },
                    migrateMail = state.migrateMail,
                    keepOldIdentity = !state.isCustodial && state.keepOldIdentity
                ),
                onProgress = { step ->
                    _uiState.value = _uiState.value.copy(activeStep = step)
                }
            ).fold(
                onSuccess = { result ->
                    _uiState.value = _uiState.value.copy(
                        step = RollKeyWizardStep.DONE,
                        result = result,
                        // The password has served its purpose — drop it from state.
                        currentPassword = "",
                        newPassword = "",
                        confirmPassword = ""
                    )
                },
                onFailure = { e ->
                    val rotationError = e as? KeyRotationUseCase.RotationException
                    val message = rotationError?.let { describe(it) } ?: (e.message ?: "Rotation failed")
                    _uiState.value = _uiState.value.copy(
                        step = RollKeyWizardStep.ERROR,
                        error = message,
                        committed = rotationError?.committed == true,
                        result = rotationError?.partial,
                        currentPassword = "",
                        newPassword = "",
                        confirmPassword = ""
                    )
                }
            )
        }
    }

    /** Retry a pending `/cleanup` after a post-commit failure or warning. */
    fun retryCleanup() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true)
            keyRotationUseCase.finishCleanup().fold(
                onSuccess = {
                    val result = _uiState.value.result
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        step = RollKeyWizardStep.DONE,
                        error = null,
                        result = result?.copy(cleanupPending = false)
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        error = "Cleanup still pending: ${e.message}"
                    )
                }
            )
        }
    }

    fun backToIntro() {
        _uiState.value = _uiState.value.copy(step = RollKeyWizardStep.INTRO)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // ---------------- Key-rotation add-on checkout (§3.1b) ----------------

    /** POST /api/payments/feature {"product":"key_rotation"} — one call creates-or-reuses + mints. */
    fun purchaseKeyRotation() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBuying = true)
            val result = paymentsUseCase.purchaseFeature(FeatureProduct.KEY_ROTATION)
            _uiState.value = _uiState.value.copy(isBuying = false)
            result.fold(
                onSuccess = { invoice ->
                    _uiState.value = _uiState.value.copy(
                        checkout = CheckoutUiState(
                            target = PaymentTargetType.FEATURE_PURCHASE,
                            targetId = invoice.targetId,
                            invoice = invoice,
                            featureProduct = FeatureProduct.KEY_ROTATION
                        )
                    )
                },
                onFailure = { e -> onPurchaseFailed(e) }
            )
        }
    }

    /** "New invoice" on expiry — the endpoint reuses/re-mints server-side. */
    fun reMintInvoice() {
        purchaseKeyRotation()
    }

    private fun onPurchaseFailed(e: Throwable) {
        _uiState.value = _uiState.value.copy(error = e.friendlyMessage())
        when (e) {
            is PaymentsError.AlreadyEntitled -> refreshTierInfo()
            is PaymentsError.FeatureDisabled,
            is PaymentsError.FeaturePurchasesDisabled -> refreshTierInfo()
            else -> Unit // RateLimited / StrikeUnavailable / network — manual retry.
        }
    }

    /** Re-fetch tier-info only (post-purchase: `key_rotation` should flip true). */
    fun refreshTierInfo() {
        viewModelScope.launch {
            aliasUseCase.getTierInfo().onSuccess { tier ->
                _uiState.value = _uiState.value.copy(tierInfo = tier)
            }
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
            error = if (invoice.isProcessing) {
                "Payment received. Processing…"
            } else {
                null
            }
        )
        // `state == paid` IS the entitlement signal: re-fetch tier-info so
        // `key_rotation` flips and the wizard unlocks.
        refreshTierInfo()
    }

    private fun Throwable.friendlyMessage(): String = when (this) {
        is PaymentsError.FeaturePurchasesDisabled ->
            if (feature == "key_rotation") "Key rotation purchases are currently disabled"
            else message ?: "Add-on purchases are currently disabled"
        is PaymentsError.FeatureDisabled ->
            if (feature == "key_rotation") "Key rotation isn't enabled on this relay yet"
            else message ?: "That feature isn't enabled on this relay yet"
        is PaymentsError.AlreadyEntitled ->
            if (product == FeatureProduct.KEY_ROTATION.wire) "You already have key rotation"
            else message ?: "You already have this feature"
        is PaymentsError.InvalidProduct -> "Invalid product"
        is PaymentsError.RateLimited -> message ?: "Too many invoices requested. Try again later."
        is PaymentsError.StrikeUnavailable -> message ?: "Payment provider unavailable. Try again."
        is PaymentsError.PaymentsDisabled -> message ?: "Payments are currently disabled"
        is PaymentsError.Unauthorized -> message ?: "Authentication failed. Please sign in again."
        else -> message ?: "Something went wrong"
    }

    private fun describe(e: KeyRotationUseCase.RotationException): String {
        val cause = e.cause
        val base = when (cause) {
            is KeyRotationError.PremiumRequired ->
                "Key rotation requires an active paid plan."
            is KeyRotationError.RotationCooldown ->
                "Keys were rolled in the last 24 hours. Try again later."
            is KeyRotationError.PubkeyTaken ->
                "The generated key is already registered. Go back and generate a new one."
            is KeyRotationError.InvalidCredentials ->
                "Current password is incorrect."
            is KeyRotationError.InvalidNonce ->
                "The challenge expired. Start again."
            is KeyRotationError.InvalidNewKeyProof ->
                "The new-key proof was rejected. Go back and generate a new key."
            is KeyRotationError.KeyRotationDisabled ->
                "Key rotation is disabled on this server."
            is KeyRotationError.KeepIdentityNotAllowed ->
                "Keeping the old key as a login is only possible on key-only accounts."
            is KeyRotationError.KeepIdentityNeedsConversion ->
                "Keeping the old key requires the password conversion fields."
            is KeyRotationError.NostrLinkDisabled ->
                "Linked-key sign-in is disabled on this server. Uncheck \"Keep signing in with my current key\"."
            is KeyRotationError.IdentityAlreadyLinked ->
                "The current key is already linked to another account. Uncheck \"Keep signing in with my current key\"."
            is KeyRotationError.CustodialDisabled ->
                "Username & password accounts are disabled on this server."
            is KeyRotationError.AccountLocked ->
                "Too many failed attempts. The account is temporarily locked."
            is KeyRotationError.RateLimited ->
                "Too many attempts. Try again later."
            else -> cause?.message ?: "Rotation failed"
        }
        return if (e.committed) {
            "$base. The account has already moved to the new key. " +
                "Sign-in and address work; the remaining step can be retried."
        } else {
            base
        }
    }

    companion object {
        private const val TAG = "RollKeyViewModel"
        private const val POLL_INTERVAL_MS = 3_000L
    }
}
