package xyz.desent.presentation.ui.login.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.data.registration.model.RegistrationError
import xyz.desent.domain.model.CustodialAccountCreationRequest
import xyz.desent.domain.model.CustodialLoginResult
import xyz.desent.domain.model.RegistrationMode
import xyz.desent.domain.repository.CustodialAccountRepository
import xyz.desent.domain.usecase.AuthUseCase
import xyz.desent.domain.usecase.RefreshPrimaryAddressUseCase
import xyz.desent.domain.usecase.RegistrationUseCase

/**
 * Custodial (username & password) flows in [LoginViewModel] against
 * refs/FromServer/ANDROID_CUSTODIAL_ACCOUNTS.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelCustodialTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var authUseCase: AuthUseCase
    private lateinit var registrationUseCase: RegistrationUseCase
    private lateinit var custodialAccountRepository: CustodialAccountRepository
    private lateinit var refreshPrimaryAddressUseCase: RefreshPrimaryAddressUseCase

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        authUseCase = mockk()
        registrationUseCase = mockk()
        custodialAccountRepository = mockk()
        refreshPrimaryAddressUseCase = mockk(relaxed = true)

        coEvery { registrationUseCase.getRegistrationMode() } returns
            Result.success(RegistrationMode.OPEN)
        coEvery { registrationUseCase.checkAvailable(any()) } returns
            Result.success(
                xyz.desent.domain.model.AvailabilityInfo(
                    available = true, local = null, priceSats = 0, vanity = false
                )
            )
        coEvery { registrationUseCase.validateReferralCode(any()) } returns
            Result.success(true)
        coEvery { custodialAccountRepository.suggestUsernames(any()) } returns
            Result.success(listOf("amberfalcon", "bravelotus"))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun makeVm() = LoginViewModel(
        authUseCase,
        registrationUseCase,
        custodialAccountRepository,
        mockk(relaxed = true),
        refreshPrimaryAddressUseCase,
        mockk(relaxed = true),
        mockk(relaxed = true)
    )

    private fun kotlinx.coroutines.test.TestScope.settle() {
        testScheduler.advanceUntilIdle()
    }

    // ---- suggestions -------------------------------------------------------

    @Test
    fun switchingToPasswordCreateMode_loadsSuggestionsOnce() = runTest(mainDispatcher) {
        val vm = makeVm()

        vm.onCreateAccountModeChange(CreateAccountMode.USERNAME_PASSWORD)
        settle()

        assertEquals(listOf("amberfalcon", "bravelotus"), vm.uiState.value.suggestedUsernames)
        // Re-entering the mode must not re-fetch (30 req/hour/IP limit).
        vm.onCreateAccountModeChange(CreateAccountMode.KEY_ONLY)
        vm.onCreateAccountModeChange(CreateAccountMode.USERNAME_PASSWORD)
        settle()
        coVerify(exactly = 1) { custodialAccountRepository.suggestUsernames(any()) }
    }

    @Test
    fun defaultCreateMode_isUsernamePassword() {
        val vm = makeVm()
        assertEquals(CreateAccountMode.USERNAME_PASSWORD, vm.uiState.value.createAccountMode)
    }

    // ---- custodial login ---------------------------------------------------

    @Test
    fun custodialLogin_success_setsLoginSuccess_andClearsPassword() = runTest(mainDispatcher) {
        coEvery { authUseCase.custodialLogin("bravefalcon", "pw-secret", any(), any()) } returns
            Result.success(CustodialLoginResult(npub = "npub1abc"))
        val vm = makeVm()

        vm.onCustodialUsernameChange("bravefalcon")
        vm.onCustodialPasswordChange("pw-secret")
        vm.onCustodialLogin()
        settle()

        assertTrue(vm.uiState.value.isLoginSuccess)
        assertFalse(vm.uiState.value.legacyAccountHint)
        assertEquals("", vm.custodialPassword)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun custodialLogin_legacyEnvelope_showsHintUntilDismissed() = runTest(mainDispatcher) {
        coEvery { authUseCase.custodialLogin(any(), any(), any(), any()) } returns
            Result.success(CustodialLoginResult(npub = "npub1abc", legacyEnvelope = true))
        val vm = makeVm()

        vm.onCustodialUsernameChange("bravefalcon")
        vm.onCustodialPasswordChange("pw-secret")
        vm.onCustodialLogin()
        settle()

        // v1 account: hint first, not an immediate jump into the app.
        assertTrue(vm.uiState.value.legacyAccountHint)
        assertFalse(vm.uiState.value.isLoginSuccess)
        vm.onLegacyUpgradeHintDismissed()
        assertFalse(vm.uiState.value.legacyAccountHint)
        assertTrue(vm.uiState.value.isLoginSuccess)
    }

    @Test
    fun custodialLogin_wrongPassword_mapsToCredentialsMessage() = runTest(mainDispatcher) {
        coEvery { authUseCase.custodialLogin(any(), any(), any(), any()) } returns
            Result.failure(RegistrationError.InvalidCredentials)
        val vm = makeVm()

        vm.onCustodialUsernameChange("bravefalcon")
        vm.onCustodialPasswordChange("wrong")
        vm.onCustodialLogin()
        settle()

        assertEquals("Incorrect username or password", vm.uiState.value.error)
    }

    @Test
    fun custodialLogin_locked_setsAccountLockedUntil() = runTest(mainDispatcher) {
        coEvery { authUseCase.custodialLogin(any(), any(), any(), any()) } returns
            Result.failure(RegistrationError.AccountLocked(retryAfterSeconds = 900))
        val vm = makeVm()

        vm.onCustodialUsernameChange("bravefalcon")
        vm.onCustodialPasswordChange("wrong")
        vm.onCustodialLogin()
        settle()

        val until = vm.uiState.value.accountLockedUntil
        assertNotNull(until)
        assertTrue("lock window should be ~15 min in the future", until!! > System.currentTimeMillis() + 800_000)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun custodialLogin_disabled_mapsMessage() = runTest(mainDispatcher) {
        coEvery { authUseCase.custodialLogin(any(), any(), any(), any()) } returns
            Result.failure(RegistrationError.CustodialDisabled)
        val vm = makeVm()

        vm.onCustodialUsernameChange("bravefalcon")
        vm.onCustodialPasswordChange("pw")
        vm.onCustodialLogin()
        settle()

        assertEquals(
            "Password sign-in is currently disabled on this server",
            vm.uiState.value.error
        )
    }

    @Test
    fun custodialLogin_blankFields_showsError_withoutCallingRepository() = runTest(mainDispatcher) {
        val vm = makeVm()

        vm.onCustodialLogin()

        assertEquals("Please enter your username and password", vm.uiState.value.error)
        coVerify(exactly = 0) { authUseCase.custodialLogin(any(), any(), any(), any()) }
    }

    // ---- custodial signup form validation ----------------------------------

    @Test
    fun custodialForm_shortUsername_isValidForVanityPurchase() = runTest(mainDispatcher) {
        val vm = makeVm()
        fillCustodialForm(vm, username = "short")

        // 1-7 char names are one-time purchases since 2026-09-16
        // (CUSTODIAL_ACCOUNTS.md §4.1) — no client-side minimum; the shared
        // regex + availability pipeline gate them.
        assertTrue(vm.isCreateCustodialFormValid)
    }

    @Test
    fun custodialForm_invalidWhenPasswordsDoNotMatch() = runTest(mainDispatcher) {
        val vm = makeVm()
        fillCustodialForm(vm, password = "hunter22!", confirm = "hunter23!")

        assertFalse(vm.isCreateCustodialFormValid)
    }

    @Test
    fun custodialForm_validWhenComplete() = runTest(mainDispatcher) {
        val vm = makeVm()
        fillCustodialForm(vm)

        assertTrue(vm.isCreateCustodialFormValid)
    }

    @Test
    fun createCustodialAccount_shortUsername_isSentThrough() = runTest(mainDispatcher) {
        coEvery { authUseCase.createCustodialAccount(any(), any(), any()) } returns
            Result.success(
                xyz.desent.domain.model.AccountCreationResult(
                    nsec = "nsec1x", npub = "npub1x", user = mockk(relaxed = true)
                )
            )
        val vm = makeVm()
        fillCustodialForm(vm, username = "short")

        vm.onCreateCustodialAccount(mockk(relaxed = true))

        // No client-side min-length: the server's vanity gate decides.
        coVerify(exactly = 1) { authUseCase.createCustodialAccount(any(), any(), isNull()) }
    }

    @Test
    fun createCustodialAccount_success_showsBackupDialog_andClearsPasswords() =
        runTest(mainDispatcher) {
            val account = xyz.desent.domain.model.AccountCreationResult(
                nsec = "nsec1x", npub = "npub1x",
                user = mockk(relaxed = true)
            )
            coEvery { authUseCase.createCustodialAccount(any(), any()) } returns
                Result.success(account)
            val vm = makeVm()
            fillCustodialForm(vm)

            vm.onCreateCustodialAccount(mockk(relaxed = true))

            assertNotNull(vm.uiState.value.createdAccount)
            assertEquals(account.npub, vm.uiState.value.createdAccount!!.npub)
            assertEquals("", vm.signupPassword)
            assertEquals("", vm.signupPasswordConfirm)
        }

    @Test
    fun createCustodialAccount_success_refreshesPrimaryAddress() = runTest(mainDispatcher) {
        coEvery { authUseCase.createCustodialAccount(any(), any()) } returns
            Result.success(
                xyz.desent.domain.model.AccountCreationResult(
                    nsec = "nsec1x", npub = "npub1x", user = mockk(relaxed = true)
                )
            )
        val vm = makeVm()
        fillCustodialForm(vm)

        vm.onCreateCustodialAccount(mockk(relaxed = true))

        // Same as the login flows: the switcher must not show "No address"
        // until the next cold-launch refreshAll.
        coVerify(exactly = 1) { refreshPrimaryAddressUseCase.refreshOne("npub1x") }
    }

    @Test
    fun createCustodialAccount_failure_doesNotRefreshPrimaryAddress() = runTest(mainDispatcher) {
        coEvery { authUseCase.createCustodialAccount(any(), any()) } returns
            Result.failure(RegistrationError.TooShort(8))
        val vm = makeVm()
        fillCustodialForm(vm)

        vm.onCreateCustodialAccount(mockk(relaxed = true))

        coVerify(exactly = 0) { refreshPrimaryAddressUseCase.refreshOne(any()) }
    }

    @Test
    fun createCustodialAccount_tooShortFromServer_mapsMessage() = runTest(mainDispatcher) {
        coEvery { authUseCase.createCustodialAccount(any(), any()) } returns
            Result.failure(RegistrationError.TooShort(8))
        val vm = makeVm()
        fillCustodialForm(vm)

        vm.onCreateCustodialAccount(mockk(relaxed = true))

        assertEquals("Username must be at least 8 characters", vm.uiState.value.error)
    }

    @Test
    fun createCustodialAccount_rateLimited_setsRetryAt() = runTest(mainDispatcher) {
        coEvery { authUseCase.createCustodialAccount(any(), any()) } returns
            Result.failure(RegistrationError.RateLimited(retryAfterSeconds = 60))
        val vm = makeVm()
        fillCustodialForm(vm)

        vm.onCreateCustodialAccount(mockk(relaxed = true))

        assertNotNull(vm.uiState.value.rateLimitRetryAt)
    }

    @Test
    fun createCustodialAccount_sendsNormalizedRequest() = runTest(mainDispatcher) {
        val slot = kotlinx.coroutines.CompletableDeferred<CustodialAccountCreationRequest>()
        coEvery { authUseCase.createCustodialAccount(any(), any()) } answers {
            slot.complete(firstArg())
            Result.success(
                xyz.desent.domain.model.AccountCreationResult(
                    nsec = "nsec1x", npub = "npub1x", user = mockk(relaxed = true)
                )
            )
        }
        val vm = makeVm()
        fillCustodialForm(vm, displayName = "Alice", referralCode = "ds-armxh2-mh7yfc")

        vm.onCreateCustodialAccount(mockk(relaxed = true))

        val sent = slot.getCompleted()
        assertEquals("bravefalcon", sent.username)
        assertEquals("hunter22!", sent.password)
        assertEquals("Alice", sent.displayName)
        assertEquals("DS-ARMXH2-MH7YFC", sent.referralCode)
    }

    // ---- helpers -----------------------------------------------------------

    private fun fillCustodialForm(
        vm: LoginViewModel,
        username: String = "bravefalcon",
        password: String = "hunter22!",
        confirm: String = password,
        displayName: String = "Alice",
        referralCode: String? = null
    ) {
        vm.onDisplayNameChange(displayName)
        vm.onLocalChange(username) // shared availability pipeline (stubbed Available)
        vm.onSignupPasswordChange(password)
        vm.onSignupPasswordConfirmChange(confirm)
        if (referralCode != null) vm.onReferralCodeChange(referralCode)
    }
}
