package xyz.desent.presentation.ui.login.viewmodel

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.desent.data.RelayConfig
import xyz.desent.data.payments.model.PaymentsError
import xyz.desent.data.registration.model.RegistrationError
import xyz.desent.data.vanity.model.VanityError
import xyz.desent.domain.model.AccountCreationRequest
import xyz.desent.domain.model.CustodialAccountCreationRequest
import xyz.desent.domain.model.Invoice
import xyz.desent.domain.model.InvoiceState
import xyz.desent.domain.model.PaymentTargetType
import xyz.desent.domain.model.RegistrationMode
import xyz.desent.domain.model.VanityRequest
import xyz.desent.domain.model.VanityRequestStatus
import xyz.desent.domain.repository.CustodialAccountRepository
import xyz.desent.domain.repository.PreparedSignupKey
import xyz.desent.domain.usecase.AliasUseCase
import xyz.desent.domain.usecase.AuthUseCase
import xyz.desent.domain.usecase.PaymentsUseCase
import xyz.desent.domain.usecase.RefreshPrimaryAddressUseCase
import xyz.desent.domain.usecase.RegistrationUseCase
import xyz.desent.data.repository.NostrRepository
import xyz.desent.crypto.Nip49
import xyz.desent.presentation.ui.components.CheckoutUiState
import xyz.desent.presentation.ui.components.formatSats
import xyz.desent.util.ImageCompressor

/** Method selector for logging into an existing account (nsec vs username/password). */
enum class ExistingLoginMode { KEY, PASSWORD }

/** Method selector inside the "Create New Account" section. */
enum class CreateAccountMode { KEY_ONLY, USERNAME_PASSWORD }

enum class UsernameAvailability { Idle, Checking, Available, Taken, Invalid }

/** Live validation state for the invite-code field (GET /api/register/referral). */
enum class ReferralValidation { Idle, Checking, Valid, Invalid }

data class LoginUiState(
    val isLoading: Boolean = false,
    val isLoginSuccess: Boolean = false,
    val error: String? = null,
    val createdAccount: xyz.desent.domain.model.AccountCreationResult? = null,
    val hasAcknowledgedBackup: Boolean = false,
    /** Server registration mode; null while loading / after a failed fetch (fail-open). */
    val registrationMode: RegistrationMode? = null,
    /** Epoch millis before which the submit action is rate-limited (429). */
    val rateLimitRetryAt: Long? = null,
    /** Whether the optional invite field is expanded in open mode. */
    val inviteFieldVisible: Boolean = false,
    /** Active method inside the existing-login section (nsec vs username/password). */
    val existingLoginMode: ExistingLoginMode = ExistingLoginMode.KEY,
    /** Active method inside the create-account section. */
    val createAccountMode: CreateAccountMode = CreateAccountMode.USERNAME_PASSWORD,
    /** Available name ideas from GET /api/custodial/suggest (loaded lazily). */
    val suggestedUsernames: List<String> = emptyList(),
    /** Epoch millis until which a custodial login is locked (423, 5 fails → 15 min). */
    val accountLockedUntil: Long? = null,
    /**
     * A v1 (pre-NIP-49) custodial login succeeded — show the one-time
     * "upgrade via web" hint before continuing into the app.
     */
    val legacyAccountHint: Boolean = false,
    /**
     * Non-null when the pasted key is a LINKED identity (migration 042): it
     * signs a 22242 proof, and the account itself opens with the ACCOUNT
     * password — show the password prompt instead of importing the key.
     */
    val linkedKeyProbe: xyz.desent.domain.repository.LinkedNostrProbe.Linked? = null,
    /**
     * The pasted key is a NIP-49 `ncryptsec1…` (password-encrypted) string —
     * show the password prompt; on submit it is decrypted locally and
     * imported through the ordinary key path.
     */
    val ncryptsecPrompt: Boolean = false,
    /**
     * Strike gate for the pre-account vanity checkout (ANDROID_PAYMENTS.md
     * §1) — fails closed, so `false` keeps the legacy explain-only path for
     * priced names.
     */
    val paymentsStrikeEnabled: Boolean = false,
    /** Lightning checkout in progress for a priced signup name (§5). */
    val checkout: CheckoutUiState? = null
)

class LoginViewModel(
    private val authUseCase: AuthUseCase,
    private val registrationUseCase: RegistrationUseCase,
    private val custodialAccountRepository: CustodialAccountRepository,
    private val nostrRepository: NostrRepository,
    private val refreshPrimaryAddressUseCase: RefreshPrimaryAddressUseCase,
    private val paymentsUseCase: PaymentsUseCase,
    private val aliasUseCase: AliasUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _nsecInput = mutableStateOf("")
    val nsecInput: String by _nsecInput

    private val _rememberMe = mutableStateOf(false)
    val rememberMe: Boolean by _rememberMe

    private val _enableBiometrics = mutableStateOf(false)
    val enableBiometrics: Boolean by _enableBiometrics

    private val _displayName = mutableStateOf("")
    val displayName: String by _displayName

    private val _about = mutableStateOf("")
    val about: String by _about

    private val _local = mutableStateOf("")
    val local: String by _local

    private val _availability = mutableStateOf(UsernameAvailability.Idle)
    val availability: UsernameAvailability by _availability
    private var availabilityJob: Job? = null

    /** One-time price (sats) for the current local part; 0 = free name. */
    private val _availabilityPriceSats = mutableStateOf(0L)
    val availabilityPriceSats: Long by _availabilityPriceSats

    private val _referralCode = mutableStateOf("")
    val referralCode: String by _referralCode

    private val _referralValidation = mutableStateOf(ReferralValidation.Idle)
    val referralValidation: ReferralValidation by _referralValidation
    private var referralJob: Job? = null
    private var modeJob: Job? = null

    private val _selectedProfilePictureUri = mutableStateOf<Uri?>(null)
    val selectedProfilePictureUri: Uri? by _selectedProfilePictureUri

    // ---- Custodial (username & password) form state ----

    /** Username for logging into an existing custodial account. */
    private val _custodialUsername = mutableStateOf("")
    val custodialUsername: String by _custodialUsername

    private val _custodialPassword = mutableStateOf("")
    val custodialPassword: String by _custodialPassword

    /** Password for the custodial signup path (username reuses [local]). */
    private val _signupPassword = mutableStateOf("")
    val signupPassword: String by _signupPassword

    private val _signupPasswordConfirm = mutableStateOf("")
    val signupPasswordConfirm: String by _signupPasswordConfirm

    private var suggestionsJob: Job? = null

    private val _displayNameError = mutableStateOf<String?>(null)
    val displayNameError: String? by _displayNameError

    val isCreateFormValid: Boolean
        get() = _displayName.value.isNotBlank() &&
            _local.value.isNotBlank() &&
            LOCAL_PART_REGEX.matches(_local.value) &&
            _availability.value != UsernameAvailability.Taken &&
            _availability.value != UsernameAvailability.Invalid &&
            // Referral mode: a server-validated invite code is required.
            (_uiState.value.registrationMode != RegistrationMode.REFERRAL ||
                _referralValidation.value == ReferralValidation.Valid)

    val isCustodialLoginFormValid: Boolean
        get() = _custodialUsername.value.isNotBlank() &&
            _custodialPassword.value.isNotBlank()

    val isCreateCustodialFormValid: Boolean
        get() = _displayName.value.isNotBlank() &&
            // Short usernames (1-7 chars) are one-time vanity purchases since
            // 2026-09-16 (CUSTODIAL_ACCOUNTS.md §4.1) — the shared regex +
            // availability pipeline gate them, no fixed minimum.
            LOCAL_PART_REGEX.matches(_local.value) &&
            _availability.value != UsernameAvailability.Taken &&
            _availability.value != UsernameAvailability.Invalid &&
            _signupPassword.value.length >= CUSTODIAL_PASSWORD_MIN &&
            _signupPassword.value == _signupPasswordConfirm.value &&
            (_uiState.value.registrationMode != RegistrationMode.REFERRAL ||
                _referralValidation.value == ReferralValidation.Valid)

    init {
        refreshRegistrationMode()
    }

    /** Fetch the global registration mode (public, cheap). Fail-open on error. */
    fun refreshRegistrationMode() {
        if (modeJob?.isActive == true) return
        modeJob = viewModelScope.launch {
            registrationUseCase.getRegistrationMode()
                .onSuccess { mode ->
                    _uiState.value = _uiState.value.copy(registrationMode = mode)
                    if (mode == RegistrationMode.REFERRAL) {
                        _uiState.value = _uiState.value.copy(inviteFieldVisible = true)
                    }
                }
        }
    }

    fun onInviteFieldToggle() {
        _uiState.value = _uiState.value.copy(
            inviteFieldVisible = !_uiState.value.inviteFieldVisible
        )
    }

    /**
     * Invite-code input. The server compares verbatim after trimming, so force
     * UPPERCASE and strip characters outside the DS-XXXXXX-XXXXXX alphabet.
     * Validation is debounced (30 checks/hour/IP server-side) and only fires
     * once the input matches the full code format.
     */
    fun onReferralCodeChange(value: String) {
        val sanitized = value.trim().uppercase().filter { it in REFERRAL_ALPHABET }
        _referralCode.value = sanitized
        clearError()

        referralJob?.cancel()
        if (REFERRAL_CODE_REGEX.matches(sanitized)) {
            _referralValidation.value = ReferralValidation.Checking
            referralJob = viewModelScope.launch {
                delay(REFERRAL_DEBOUNCE_MS)
                val valid = registrationUseCase.validateReferralCode(sanitized).getOrDefault(false)
                // Ignore stale results if the user kept typing.
                if (_referralCode.value == sanitized) {
                    _referralValidation.value =
                        if (valid) ReferralValidation.Valid else ReferralValidation.Invalid
                }
            }
        } else {
            _referralValidation.value = ReferralValidation.Idle
        }
    }

    /**
     * `?ref=` deep link intake: pre-fill the gate and auto-trigger validation.
     * The code is kept in memory only (never persisted), so a re-shared link
     * can't silently reuse a stale value.
     */
    fun onReferralCodePrefilled(code: String) {
        _uiState.value = _uiState.value.copy(inviteFieldVisible = true)
        onReferralCodeChange(code)
    }
    
    fun onNsecChange(newNsec: String) {
        _nsecInput.value = newNsec
        clearError()
    }
    
    fun onRememberMeChange(enabled: Boolean) {
        _rememberMe.value = enabled
        if (!enabled) {
            _enableBiometrics.value = false
        }
    }
    
    fun onBiometricsChange(enabled: Boolean) {
        _enableBiometrics.value = enabled
    }
    
    /**
     * The create-account flow was entered (chooser or form): re-poll the
     * registration mode (refs/FromServer/ANDROID_REFERRALS.md §2.1), warm the
     * username suggestions for the custodial form, and fetch the payments
     * gate so priced names route to lightning checkout (fails closed).
     */
    fun onCreateScreenShown() {
        refreshRegistrationMode()
        refreshPaymentsConfig()
        if (_uiState.value.createAccountMode == CreateAccountMode.USERNAME_PASSWORD) {
            ensureSuggestionsLoaded()
        }
    }
    
    fun onDisplayNameChange(value: String) {
        _displayName.value = value
        _displayNameError.value = if (value.isBlank()) "Display name is required" else null
        clearError()
    }
    
    fun onAboutChange(value: String) {
        _about.value = value
    }

    fun onLocalChange(value: String) {
        // Lowercase + strip invalid chars as the user types.
        val sanitized = value.lowercase().trim().filter { it.isLetterOrDigit() || it in "._-" }
        _local.value = sanitized
        clearError()

        availabilityJob?.cancel()
        if (sanitized.isBlank() || !LOCAL_PART_REGEX.matches(sanitized)) {
            _availability.value = if (sanitized.isBlank()) UsernameAvailability.Idle
            else UsernameAvailability.Invalid
            return
        }
        _availability.value = UsernameAvailability.Checking
        _availabilityPriceSats.value = 0L
        availabilityJob = viewModelScope.launch {
            delay(450)  // debounce
            val result = registrationUseCase.checkAvailable(sanitized)
            val info = result.getOrNull()
            // Ignore stale results if the user kept typing.
            if (_local.value == sanitized) {
                if (info != null) {
                    _availability.value =
                        if (info.available) UsernameAvailability.Available else UsernameAvailability.Taken
                    _availabilityPriceSats.value = info.priceSats
                } else {
                    // Preserve the legacy failure mapping (fetch failed → Taken).
                    _availability.value = UsernameAvailability.Taken
                }
            }
        }
    }
    
    fun onProfilePictureSelected(uri: Uri, context: Context) {
        _selectedProfilePictureUri.value = uri
    }
    
    fun onClearProfilePicture() {
        _selectedProfilePictureUri.value = null
    }
    
    fun onLogin() {
        if (_nsecInput.value.isBlank()) {
            setError("Please enter your nsec key")
            return
        }

        // NIP-49 encrypted key: switch to the password phase instead of
        // importing. Checked before the linked-identity probe below — a
        // probe needs a plaintext signing key, so it can only fail on an
        // ncryptsec. isValidNcryptsec parses without the KDF, so a bad
        // paste is rejected here rather than after the password is typed.
        val input = _nsecInput.value.trim()
        if (input.lowercase().startsWith(Nip49.HRP + "1")) {
            if (!Nip49.isValidNcryptsec(input)) {
                setError("This doesn't look like a valid ncryptsec key")
                return
            }
            _ncryptsecPassword.value = ""
            _uiState.value = _uiState.value.copy(ncryptsecPrompt = true)
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true)

        viewModelScope.launch {
            try {
                // Linked-identity probe (NOSTR_CUSTODIAL.md §3): if this key
                // was kept as a login identity by a key rotation, it opens a
                // CUSTODIAL account — the password fetches the real key. A
                // probe that cannot reach the server falls through to the
                // plain import so offline key logins keep working.
                val probe = authUseCase.probeLinkedNostrAccount(_nsecInput.value).getOrNull()
                if (probe is xyz.desent.domain.repository.LinkedNostrProbe.Linked) {
                    _linkedPassword.value = ""
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        linkedKeyProbe = probe
                    )
                    return@launch
                }

                val result = authUseCase.login(
                    _nsecInput.value,
                    _rememberMe.value,
                    _enableBiometrics.value
                )
                
                result.fold(
                    onSuccess = { npub ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isLoginSuccess = true
                        )
                        // Pull the user's NIP-01 kind-0 profile from the public
                        // profile relays (newest wins). Fire-and-forget: the relay
                        // subscription persists on the WebSocket clients, so the
                        // profile trickles into Room and the UI refreshes even if
                        // this ViewModel is cleared on navigation.
                        viewModelScope.launch {
                            nostrRepository.fetchOwnProfileFromRelays(
                                npub,
                                RelayConfig.PUBLIC_PROFILE_RELAYS
                            )
                        }
                        // Cache the registered primary address right away —
                        // a fresh key-import has no kind-0 nip05 to derive it
                        // from, so the switcher would show "No address".
                        viewModelScope.launch {
                            refreshPrimaryAddressUseCase.refreshOne(npub)
                        }
                    },
                    onFailure = { exception ->
                        setError("Login failed: ${exception.message}")
                    }
                )
            } catch (e: Exception) {
                setError("Login error: ${e.message}")
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }
    
    fun onCreateAccount(context: Context) {
        if (!isCreateFormValid) {
            _displayNameError.value = "Display name is required"
            setError("Please fill in required fields")
            return
        }

        // Priced short name with Strike on: pay the vanity invoice first,
        // then the register retry consumes the approval with the SAME key
        // (ANDROID_PAYMENTS.md §5, CUSTODIAL_ACCOUNTS.md §4.1).
        if (_availabilityPriceSats.value > 0 && _uiState.value.paymentsStrikeEnabled) {
            startSignupVanityCheckout(context)
            return
        }

        performCreateAccount(context, existingNsec = null)
    }

    private fun performCreateAccount(context: Context, existingNsec: String?) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            try {
                val request = AccountCreationRequest(
                    local = _local.value.trim(),
                    displayName = _displayName.value.trim(),
                    about = _about.value.ifBlank { null }?.trim(),
                    pictureUri = _selectedProfilePictureUri.value,
                    // Sent in both modes: required in referral mode, optional
                    // attribution in open mode (invalid codes are ignored there).
                    referralCode = _referralCode.value.takeIf { it.isNotBlank() }
                )

                val result = authUseCase.createAccount(request, context, existingNsec)

                result.fold(
                    onSuccess = { accountResult ->
                        clearPreparedSignupKey()
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            createdAccount = accountResult,
                            hasAcknowledgedBackup = false
                        )
                        // Cache the just-registered primary address now, as
                        // the login flows do — otherwise the switcher shows
                        // "No address" until the next cold-launch refreshAll.
                        viewModelScope.launch {
                            refreshPrimaryAddressUseCase.refreshOne(accountResult.npub)
                        }
                    },
                    onFailure = { exception ->
                        handleCreateFailure(exception, context)
                    }
                )
            } catch (e: Exception) {
                setError("Account creation error: ${e.message}")
            }
        }
    }

    /** Map typed registration failures to gate-specific UI state (§2.6). */
    private fun handleCreateFailure(exception: Throwable, context: Context) {
        when (exception) {
            is RegistrationError.VanityPrice -> {
                // Ladder drift / stale availability cache: the server quote is
                // authoritative. With Strike on, route straight into the
                // pay-then-register flow (ANDROID_PAYMENTS.md §5); otherwise
                // explain the legacy operator path (ANDROID_VANITY_PRICING §4).
                _availabilityPriceSats.value = exception.priceSats
                _uiState.value = _uiState.value.copy(isLoading = false)
                if (_uiState.value.paymentsStrikeEnabled) {
                    startSignupVanityCheckout(context)
                } else {
                    setError(
                        "Short addresses cost ${formatSats(exception.priceSats)} sats one-time. " +
                            "In-app payments are currently unavailable on this server — " +
                            "create your account with a free name (8+ characters) first, " +
                            "then request ${_local.value}@desent.xyz from the Aliases screen."
                    )
                }
            }
            is RegistrationError.TooShort -> {
                _uiState.value = _uiState.value.copy(isLoading = false)
                setError("Username must be at least ${exception.minLength} characters")
            }
            is RegistrationError.CustodialDisabled -> {
                _uiState.value = _uiState.value.copy(isLoading = false)
                setError("Username & password accounts are currently disabled on this server")
            }
            is RegistrationError.BlobTooLarge -> {
                _uiState.value = _uiState.value.copy(isLoading = false)
                setError("Account creation failed: encrypted key blob rejected. Please retry.")
            }
            is RegistrationError.InvalidVerifier -> {
                _uiState.value = _uiState.value.copy(isLoading = false)
                setError("Account creation failed: invalid verifier. Please retry.")
            }
            is RegistrationError.InvalidReferralCode -> {
                // Someone consumed the code between validation and submit:
                // clear it and return the user to the gate.
                referralJob?.cancel()
                _referralCode.value = ""
                _referralValidation.value = ReferralValidation.Invalid
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    registrationMode = RegistrationMode.REFERRAL,
                    inviteFieldVisible = true,
                    error = "That invite code is invalid or already used, please enter another"
                )
            }
            is RegistrationError.ReferralRequired -> {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    registrationMode = RegistrationMode.REFERRAL,
                    inviteFieldVisible = true,
                    error = "An invite code is required to register"
                )
            }
            is RegistrationError.RegistrationDisabled -> {
                refreshRegistrationMode()
                setError("Registration is currently closed, check back later")
            }
            is RegistrationError.RateLimited -> {
                val retryAt = System.currentTimeMillis() +
                    ((exception.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS) * 1000)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    rateLimitRetryAt = retryAt
                )
            }
            is RegistrationError.AccountDisabled ->
                setError("This account has been suspended")
            else ->
                setError("Account creation failed: ${exception.message}")
        }
    }
    
    fun onAcknowledgeBackup() {
        _uiState.value = _uiState.value.copy(
            hasAcknowledgedBackup = !_uiState.value.hasAcknowledgedBackup
        )
    }
    
    fun onBackupConfirmedAndContinue() {
        _uiState.value = _uiState.value.copy(isLoginSuccess = true)
    }
    
    fun onQrCodeScanned(nsec: String) {
        _nsecInput.value = nsec
        // A scanned key only makes sense in key mode — switch so the filled
        // field is visible.
        _uiState.value = _uiState.value.copy(existingLoginMode = ExistingLoginMode.KEY)
    }

    // ------------------------------------------------------------------
    // Linked-key (old identity) login — NOSTR_CUSTODIAL.md §3
    // ------------------------------------------------------------------

    /** Password field for the linked-identity probe's second phase. */
    private val _linkedPassword = mutableStateOf("")
    val linkedPassword: String by _linkedPassword

    val isLinkedLoginFormValid: Boolean
        get() = _linkedPassword.value.isNotBlank()

    fun onLinkedPasswordChange(value: String) {
        _linkedPassword.value = value
        clearError()
    }

    fun cancelLinkedLogin() {
        _linkedPassword.value = ""
        _uiState.value = _uiState.value.copy(linkedKeyProbe = null, error = null)
    }

    /**
     * Finish the linked-key login: the password + probe salt derive the
     * verifier, the handoff fetches the ACCOUNT key's blob, and the decrypted
     * account key (not the pasted old key) provisions the session.
     */
    fun onLinkedLogin() {
        val probe = _uiState.value.linkedKeyProbe ?: return
        if (_linkedPassword.value.isBlank()) {
            setError("Please enter the account password")
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true)

        viewModelScope.launch {
            try {
                val result = authUseCase.completeLinkedNostrLogin(
                    probe,
                    _linkedPassword.value,
                    _rememberMe.value,
                    _enableBiometrics.value
                )

                result.fold(
                    onSuccess = { loginResult ->
                        _linkedPassword.value = ""
                        _nsecInput.value = ""
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            linkedKeyProbe = null,
                            isLoginSuccess = true
                        )
                        viewModelScope.launch {
                            refreshPrimaryAddressUseCase.refreshOne(loginResult.npub)
                        }
                    },
                    onFailure = { exception ->
                        handleCustodialLoginFailure(exception)
                    }
                )
            } catch (e: Exception) {
                setError("Login error: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // ncryptsec (NIP-49) key login — decrypt, then ordinary import
    // ------------------------------------------------------------------

    /** Password field for the ncryptsec login's second phase. */
    private val _ncryptsecPassword = mutableStateOf("")
    val ncryptsecPassword: String by _ncryptsecPassword

    val isNcryptsecLoginFormValid: Boolean
        get() = _ncryptsecPassword.value.isNotBlank()

    fun onNcryptsecPasswordChange(value: String) {
        _ncryptsecPassword.value = value
        clearError()
    }

    fun cancelNcryptsecLogin() {
        _ncryptsecPassword.value = ""
        _uiState.value = _uiState.value.copy(ncryptsecPrompt = false, error = null)
    }

    /**
     * Finish the ncryptsec login: the pasted key is decrypted locally with
     * the password (scrypt, ~1–2 s) and provisioned through the ordinary
     * key-import path. The password is cleared the moment it is no longer
     * needed and is never persisted.
     */
    fun onNcryptsecLogin() {
        if (!_uiState.value.ncryptsecPrompt) return
        if (_ncryptsecPassword.value.isBlank()) {
            setError("Please enter the password this key was encrypted with")
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true)

        viewModelScope.launch {
            try {
                val result = authUseCase.loginWithNcryptsec(
                    _nsecInput.value.trim(),
                    _ncryptsecPassword.value,
                    _rememberMe.value,
                    _enableBiometrics.value
                )

                result.fold(
                    onSuccess = { npub ->
                        _ncryptsecPassword.value = ""
                        _nsecInput.value = ""
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            ncryptsecPrompt = false,
                            isLoginSuccess = true
                        )
                        viewModelScope.launch {
                            nostrRepository.fetchOwnProfileFromRelays(
                                npub,
                                RelayConfig.PUBLIC_PROFILE_RELAYS
                            )
                        }
                        viewModelScope.launch {
                            refreshPrimaryAddressUseCase.refreshOne(npub)
                        }
                    },
                    onFailure = { exception ->
                        when (exception) {
                            is Nip49.WrongPasswordException ->
                                setError(
                                    "Incorrect password — this is the password the key " +
                                        "was encrypted with"
                                )
                            is Nip49.MalformedNcryptsecException -> {
                                _ncryptsecPassword.value = ""
                                _uiState.value = _uiState.value.copy(ncryptsecPrompt = false)
                                setError("This ncryptsec key is malformed")
                            }
                            else -> setError("Login failed: ${exception.message}")
                        }
                    }
                )
            } catch (e: Exception) {
                setError("Login error: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // Custodial (username & password) flows — refs/FromServer/
    // ANDROID_CUSTODIAL_ACCOUNTS.md
    // ------------------------------------------------------------------

    fun onExistingLoginModeChange(mode: ExistingLoginMode) {
        _uiState.value = _uiState.value.copy(existingLoginMode = mode, error = null)
    }

    fun onCreateAccountModeChange(mode: CreateAccountMode) {
        _uiState.value = _uiState.value.copy(createAccountMode = mode, error = null)
        if (mode == CreateAccountMode.USERNAME_PASSWORD) {
            ensureSuggestionsLoaded()
        }
    }

    /** GET /api/custodial/suggest — loaded once per section entry (30/h/IP). */
    private fun ensureSuggestionsLoaded() {
        if (suggestionsJob?.isActive == true) return
        if (_uiState.value.suggestedUsernames.isNotEmpty()) return
        suggestionsJob = viewModelScope.launch {
            val names = custodialAccountRepository.suggestUsernames().getOrNull().orEmpty()
            if (names.isNotEmpty() && _uiState.value.suggestedUsernames.isEmpty()) {
                _uiState.value = _uiState.value.copy(suggestedUsernames = names)
            }
        }
    }

    fun onCustodialUsernameChange(value: String) {
        _custodialUsername.value = value.trim()
        clearError()
    }

    fun onCustodialPasswordChange(value: String) {
        _custodialPassword.value = value
        clearError()
    }

    fun onSignupPasswordChange(value: String) {
        _signupPassword.value = value
        clearError()
    }

    fun onSignupPasswordConfirmChange(value: String) {
        _signupPasswordConfirm.value = value
        clearError()
    }

    /** Pick one of the suggested names into the username field. */
    fun onPickSuggestedUsername(name: String) {
        onLocalChange(name)
    }

    /**
     * Username & password login — a one-time-per-device provisioning step.
     * The 64 MiB Argon2id derivation plus two network hops take ~1–3 s; the
     * UI shows a progress state via [LoginUiState.isLoading].
     */
    fun onCustodialLogin() {
        if (!isCustodialLoginFormValid) {
            setError("Please enter your username and password")
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true)

        viewModelScope.launch {
            try {
                val result = authUseCase.custodialLogin(
                    _custodialUsername.value,
                    _custodialPassword.value,
                    _rememberMe.value,
                    _enableBiometrics.value
                )

                result.fold(
                    onSuccess = { loginResult ->
                        _uiState.value = _uiState.value.copy(isLoading = false)
                        // Same fire-and-forget profile fetch as the key login.
                        viewModelScope.launch {
                            nostrRepository.fetchOwnProfileFromRelays(
                                loginResult.npub,
                                RelayConfig.PUBLIC_PROFILE_RELAYS
                            )
                        }
                        // Cache the registered primary address immediately
                        // (same rationale as the key login).
                        viewModelScope.launch {
                            refreshPrimaryAddressUseCase.refreshOne(loginResult.npub)
                        }
                        // The password was needed only for the one-time blob
                        // fetch — clear it from the field immediately.
                        _custodialPassword.value = ""
                        if (loginResult.legacyEnvelope) {
                            // v1 account decrypted fine, but the server still
                            // holds the old envelope — suggest the one-time
                            // web upgrade before continuing.
                            _uiState.value = _uiState.value.copy(legacyAccountHint = true)
                        } else {
                            _uiState.value = _uiState.value.copy(isLoginSuccess = true)
                        }
                    },
                    onFailure = { exception ->
                        handleCustodialLoginFailure(exception)
                    }
                )
            } catch (e: Exception) {
                setError("Login error: ${e.message}")
            }
        }
    }

    /** Map typed custodial-login failures to gate-specific UI state. */
    private fun handleCustodialLoginFailure(exception: Throwable) {
        when (exception) {
            is RegistrationError.InvalidCredentials ->
                setError("Incorrect username or password")
            is xyz.desent.data.registration.model.NostrLinkError.InvalidCredentials ->
                setError("Incorrect password")
            is xyz.desent.data.registration.model.NostrLinkError.InvalidProof,
            is xyz.desent.data.registration.model.NostrLinkError.InvalidNonce -> {
                // The 5-min handoff/nonce expired — restart from the key.
                _linkedPassword.value = ""
                _uiState.value = _uiState.value.copy(linkedKeyProbe = null)
                setError("The sign-in challenge expired, please try again")
            }
            is xyz.desent.data.registration.model.NostrLinkError.NostrLinkDisabled ->
                setError("Linked-key sign-in is disabled on this server")
            is xyz.desent.data.registration.model.NostrLinkError.CustodialDisabled ->
                setError("Password sign-in is currently disabled on this server")
            is RegistrationError.AccountLocked -> {
                val until = System.currentTimeMillis() +
                    ((exception.retryAfterSeconds ?: DEFAULT_LOCKOUT_SECONDS) * 1000)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    accountLockedUntil = until
                )
            }
            is RegistrationError.CustodialDisabled ->
                setError("Password sign-in is currently disabled on this server")
            is RegistrationError.RateLimited -> {
                val retryAt = System.currentTimeMillis() +
                    ((exception.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS) * 1000)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    rateLimitRetryAt = retryAt
                )
            }
            is RegistrationError.AccountDisabled ->
                setError("This account has been suspended")
            else ->
                setError("Login failed: ${exception.message}")
        }
    }

    /** The user acknowledged the v1 "upgrade via web" hint — continue in. */
    fun onLegacyUpgradeHintDismissed() {
        _uiState.value = _uiState.value.copy(
            legacyAccountHint = false,
            isLoginSuccess = true
        )
    }

    /**
     * Username & password signup. The username reuses the shared address
     * field ([local] + availability pipeline); the password is held in
     * memory only until the encrypted blob is built on-device.
     */
    fun onCreateCustodialAccount(context: Context) {
        if (!isCreateCustodialFormValid) {
            if (_signupPassword.value != _signupPasswordConfirm.value) {
                setError("Passwords do not match")
            } else {
                setError("Please fill in required fields")
            }
            return
        }

        // Short usernames are one-time purchases on the custodial path too
        // (CUSTODIAL_ACCOUNTS.md §4.1) — pay first, then register with the
        // SAME freshly generated key (it signs both the vanity request and
        // the final register call).
        if (_availabilityPriceSats.value > 0 && _uiState.value.paymentsStrikeEnabled) {
            startSignupVanityCheckout(context)
            return
        }

        performCreateCustodialAccount(context, existingNsec = null)
    }

    private fun performCreateCustodialAccount(context: Context, existingNsec: String?) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            try {
                val request = CustodialAccountCreationRequest(
                    username = _local.value.trim(),
                    password = _signupPassword.value,
                    displayName = _displayName.value.trim(),
                    referralCode = _referralCode.value.takeIf { it.isNotBlank() }
                )

                val result = authUseCase.createCustodialAccount(request, context, existingNsec)

                result.fold(
                    onSuccess = { accountResult ->
                        clearPreparedSignupKey()
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            createdAccount = accountResult,
                            hasAcknowledgedBackup = false
                        )
                        // The signup password is one-shot; don't leave it in
                        // the field behind the backup dialog.
                        _signupPassword.value = ""
                        _signupPasswordConfirm.value = ""
                        viewModelScope.launch {
                            refreshPrimaryAddressUseCase.refreshOne(accountResult.npub)
                        }
                    },
                    onFailure = { exception ->
                        handleCreateFailure(exception, context)
                    }
                )
            } catch (e: Exception) {
                setError("Account creation error: ${e.message}")
            }
        }
    }
    
    // ------------------------------------------------------------------
    // Pre-account vanity checkout — refs/FROM_email.desent.xyz/
    // ANDROID_PAYMENTS.md §5 + CUSTODIAL_ACCOUNTS.md §4.1
    // ------------------------------------------------------------------

    /**
     * Memory-only signup key — reused across checkout retries because the
     * vanity request row is keyed to its pubkey (a dismissed checkout MUST
     * NOT regenerate the key). Dropped once the account exists.
     */
    private var preparedSignupKey: PreparedSignupKey? = null

    /** ApplicationContext for the post-payment register retry (resolver only). */
    private var paidRetryContext: Context? = null

    private var pollJob: Job? = null
    private var paymentsConfigJob: Job? = null

    /** GET /api/payments/config — the Strike gate; fails closed to legacy. */
    private fun refreshPaymentsConfig() {
        if (paymentsConfigJob?.isActive == true) return
        paymentsConfigJob = viewModelScope.launch {
            paymentsUseCase.getConfig().onSuccess { config ->
                _uiState.value = _uiState.value.copy(paymentsStrikeEnabled = config.strikeEnabled)
            }
        }
    }

    /**
     * Create-or-reuse the vanity request for the priced signup name (kind
     * `primary`, NIP-98-signed by the memory-only key), then mint the
     * lightning invoice. On settlement the register call retries with the
     * SAME key — the approval is consumed in its claim transaction.
     */
    private fun startSignupVanityCheckout(context: Context) {
        paidRetryContext = context.applicationContext
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            val key = preparedSignupKey
                ?: authUseCase.prepareSignupKey().getOrNull()
                    ?.also { preparedSignupKey = it }
            if (key == null) {
                setError("Couldn't generate a signup key — check your connection and try again")
                return@launch
            }

            val local = _local.value.trim()
            val created = aliasUseCase.createVanityRequest(
                localPart = local,
                domain = null,
                kind = VANITY_KIND_PRIMARY,
                identity = key.identity
            )
            val request = when {
                created.isSuccess -> created.getOrThrow()
                created.exceptionOrNull() is VanityError.RequestExists ->
                    reuseOwnSignupVanityRequest(local, key)
                else -> {
                    setError(
                        created.exceptionOrNull()?.friendlyVanityMessage() ?: "Request failed"
                    )
                    null
                }
            }
            if (request == null) {
                _uiState.value = _uiState.value.copy(isLoading = false)
                return@launch
            }

            _uiState.value = _uiState.value.copy(isLoading = false)
            when (request.status) {
                VanityRequestStatus.PENDING -> startCheckout(request.id)
                VanityRequestStatus.APPROVED ->
                    // An earlier payment settled or an operator approved —
                    // the register retry consumes the approval now.
                    retryRegisterAfterPayment()
                VanityRequestStatus.DENIED -> setError(
                    "Request for ${request.email} was denied" +
                        (request.note?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                )
                VanityRequestStatus.CLAIMED ->
                    setError("${request.email} was already claimed — pick another name")
            }
        }
    }

    /** 409 `request_exists`: reuse our own row for this name, if any (§5). */
    private suspend fun reuseOwnSignupVanityRequest(
        local: String,
        key: PreparedSignupKey
    ): VanityRequest? {
        // Owner-scoped to the signup key — anything found IS ours.
        val existing = aliasUseCase.listVanityRequests(key.identity).getOrNull()
            ?.firstOrNull { it.localPart == local }
        if (existing == null) {
            setError("This name is already being requested")
        }
        return existing
    }

    /** Mint (or return the still-live) invoice and open the checkout sheet. */
    private fun startCheckout(targetId: Long) {
        val key = preparedSignupKey ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                checkout = CheckoutUiState(
                    target = PaymentTargetType.VANITY_REQUEST,
                    targetId = targetId,
                    isMinting = true
                )
            )
            paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, targetId, key.identity)
                .fold(
                    onSuccess = { invoice ->
                        _uiState.value = _uiState.value.copy(
                            checkout = CheckoutUiState(
                                target = PaymentTargetType.VANITY_REQUEST,
                                targetId = targetId,
                                invoice = invoice
                            )
                        )
                    },
                    onFailure = { e -> onMintFailed(targetId, e) }
                )
        }
    }

    /** "New invoice" on expiry — re-mint for the same target. */
    fun reMintInvoice() {
        val checkout = _uiState.value.checkout ?: return
        startCheckout(checkout.targetId)
    }

    private fun onMintFailed(targetId: Long, e: Throwable) {
        when (e) {
            is PaymentsError.NotPending -> when (e.status?.lowercase()) {
                "approved" -> {
                    // Settled by an earlier payment — finish the signup now.
                    _uiState.value = _uiState.value.copy(checkout = null)
                    retryRegisterAfterPayment()
                }
                "denied" -> {
                    _uiState.value = _uiState.value.copy(checkout = null)
                    setError("Your request was denied — pick another name")
                }
                else -> {
                    _uiState.value = _uiState.value.copy(checkout = null)
                    setError("Purchase is no longer pending")
                }
            }
            is PaymentsError.PaymentsDisabled -> {
                // Strike was switched off mid-flight — back to the legacy path.
                _uiState.value = _uiState.value.copy(checkout = null, paymentsStrikeEnabled = false)
                setError(
                    "In-app payments are currently unavailable on this server. Create " +
                        "your account with a free name (8+ characters) first, then request " +
                        "${_local.value}@desent.xyz from the Aliases screen."
                )
            }
            is PaymentsError.TargetNotFound -> {
                _uiState.value = _uiState.value.copy(checkout = null)
                setError("Purchase not found")
            }
            else -> {
                // RateLimited / StrikeUnavailable / network — manual retry in the sheet.
                _uiState.value = _uiState.value.copy(
                    checkout = CheckoutUiState(
                        target = PaymentTargetType.VANITY_REQUEST,
                        targetId = targetId,
                        isMinting = false,
                        mintError = e.friendlyPaymentsMessage()
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
            val identity = preparedSignupKey?.identity
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val invoice = paymentsUseCase.getInvoice(invoiceId, identity).getOrNull()
                    ?: continue
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
                _uiState.value = _uiState.value.copy(checkout = null)
                setError("Payment received — processing. Press Create again in a moment.")
            } else {
                _uiState.value = _uiState.value.copy(checkout = null)
                // Approval was credited server-side — register now with the
                // SAME key that paid (§5).
                retryRegisterAfterPayment()
            }
            // Expired / cancelled: the sheet stays open offering "New invoice".
            else -> Unit
        }
    }

    /** Post-payment register retry — reuses the key the request is bound to. */
    private fun retryRegisterAfterPayment() {
        val key = preparedSignupKey ?: return
        val context = paidRetryContext ?: return
        if (_uiState.value.createAccountMode == CreateAccountMode.USERNAME_PASSWORD) {
            performCreateCustodialAccount(context, key.nsec)
        } else {
            performCreateAccount(context, key.nsec)
        }
    }

    /** The signup key is one-shot: drop it once its account exists. */
    private fun clearPreparedSignupKey() {
        preparedSignupKey = null
        paidRetryContext = null
    }

    private fun Throwable.friendlyVanityMessage(): String = when (this) {
        is VanityError.Taken -> "That address is already taken"
        is VanityError.NotPriced -> "Names 8+ characters are free — no purchase needed"
        is VanityError.Reserved -> "That name is reserved"
        is VanityError.InvalidLocalPart -> "Invalid name format"
        is VanityError.RateLimited -> "Too many requests — try again later"
        is VanityError.Unauthorized -> "Authentication failed — try again"
        else -> message ?: "Request failed"
    }

    private fun Throwable.friendlyPaymentsMessage(): String = when (this) {
        is PaymentsError.RateLimited -> "Too many invoices requested — try again later"
        is PaymentsError.StrikeUnavailable -> "Payment provider unavailable — try again"
        else -> message ?: "Couldn't create the invoice"
    }

    override fun onCleared() {
        pausePolling()
        clearPreparedSignupKey()
        super.onCleared()
    }

    private fun setError(error: String) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            error = error
        )
    }
    
    private fun clearError() {
        if (_uiState.value.error != null) {
            _uiState.value = _uiState.value.copy(error = null)
        }
    }

    companion object {
        // DeSent local-part rules: 3-32 chars, [a-z0-9._-], starting/ending alnum.
        private val LOCAL_PART_REGEX = Regex("^[a-z0-9](?:[a-z0-9._-]{1,30}[a-z0-9])?$")

        // Vanity request kind for a signup primary address (§3.1).
        private const val VANITY_KIND_PRIMARY = "primary"

        // ANDROID_PAYMENTS.md §4: poll every ~3 s while the checkout is visible.
        private const val POLL_INTERVAL_MS = 3_000L

        // Invite codes: DS-XXXXXX-XXXXXX over [A-Z0-9-] (refs ANDROID_REFERRALS.md §1).
        val REFERRAL_CODE_REGEX = Regex("^DS-[A-Z0-9]{6}-[A-Z0-9]{6}$")
        private const val REFERRAL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-"

        // Debounce for live invite validation; the endpoint allows 30 req/hour/IP.
        private const val REFERRAL_DEBOUNCE_MS = 350L

        // Server default when a 429 body omits retry_after_seconds.
        private const val DEFAULT_RETRY_AFTER_SECONDS = 60L

        // Custodial usernames: 1-7 chars are one-time purchases (vanity
        // gate since 2026-09-16); 8+ are free. Kept for the TooShort error
        // mapping (legacy servers).
        const val CUSTODIAL_USERNAME_MIN = 8

        // Client-side floor for new custodial passwords.
        const val CUSTODIAL_PASSWORD_MIN = 8

        // Server lockout default when a 423 body omits retry_after_seconds.
        private const val DEFAULT_LOCKOUT_SECONDS = 900L
    }
}
