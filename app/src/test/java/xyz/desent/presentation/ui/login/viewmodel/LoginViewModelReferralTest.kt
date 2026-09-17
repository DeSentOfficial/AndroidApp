package xyz.desent.presentation.ui.login.viewmodel

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.data.registration.model.RegistrationError
import xyz.desent.domain.model.AccountCreationRequest
import xyz.desent.domain.model.RegistrationMode
import xyz.desent.domain.repository.CustodialAccountRepository
import xyz.desent.domain.usecase.AuthUseCase
import xyz.desent.domain.usecase.RefreshPrimaryAddressUseCase
import xyz.desent.domain.usecase.RegistrationUseCase

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelReferralTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var authUseCase: AuthUseCase
    private lateinit var registrationUseCase: RegistrationUseCase
    private lateinit var refreshPrimaryAddressUseCase: RefreshPrimaryAddressUseCase

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        authUseCase = mockk()
        registrationUseCase = mockk()
        refreshPrimaryAddressUseCase = mockk(relaxed = true)

        // Eager init fetch + debounced availability checks.
        coEvery { registrationUseCase.getRegistrationMode() } returns
            Result.success(RegistrationMode.OPEN)
        coEvery { registrationUseCase.checkAvailable(any()) } returns
            Result.success(xyz.desent.domain.model.AvailabilityInfo(available = true, local = null, priceSats = 0, vanity = false))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun makeVm(): LoginViewModel {
        val custodialRepo = mockk<CustodialAccountRepository>()
        // Explicit stub: relaxed Result defaults cast badly for generic lists.
        coEvery { custodialRepo.suggestUsernames(any()) } returns Result.success(emptyList())
        return LoginViewModel(
            authUseCase,
            registrationUseCase,
            custodialRepo,
            mockk(relaxed = true),
            refreshPrimaryAddressUseCase,
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
    }

    /** Fast-forward virtual time so debounced validation jobs complete. */
    private fun kotlinx.coroutines.test.TestScope.settle() {
        testScheduler.advanceUntilIdle()
    }

    private fun fillValidAddressForm(vm: LoginViewModel) {
        vm.onDisplayNameChange("Alice")
        vm.onLocalChange("alice") // availability stubbed → Available after debounce
    }

    @Test
    fun init_fetchesRegistrationMode() {
        val vm = makeVm()
        assertEquals(RegistrationMode.OPEN, vm.uiState.value.registrationMode)
    }

    @Test
    fun referralCode_normalizesUppercase_andValidatesAfterDebounce() = runTest(mainDispatcher) {
        coEvery { registrationUseCase.validateReferralCode("DS-ARMXH2-MH7YFC") } returns
            Result.success(true)
        val vm = makeVm()

        vm.onReferralCodeChange("ds-armxh2-mh7yfc")
        settle()

        assertEquals("DS-ARMXH2-MH7YFC", vm.referralCode)
        assertEquals(ReferralValidation.Valid, vm.referralValidation)
    }

    @Test
    fun referralCode_invalidServerResponse_marksInvalid() = runTest(mainDispatcher) {
        coEvery { registrationUseCase.validateReferralCode("DS-ARMXH2-MH7YFC") } returns
            Result.success(false)
        val vm = makeVm()

        vm.onReferralCodeChange("DS-ARMXH2-MH7YFC")
        settle()

        assertEquals(ReferralValidation.Invalid, vm.referralValidation)
    }

    @Test
    fun referralCode_partialInput_staysIdleWithoutServerCall() = runTest(mainDispatcher) {
        val vm = makeVm()

        vm.onReferralCodeChange("DS-ARM")

        assertEquals(ReferralValidation.Idle, vm.referralValidation)
        io.mockk.coVerify(exactly = 0) { registrationUseCase.validateReferralCode(any()) }
    }

    @Test
    fun referralMode_requiresValidCodeBeforeSubmit() = runTest(mainDispatcher) {
        coEvery { registrationUseCase.getRegistrationMode() } returns
            Result.success(RegistrationMode.REFERRAL)
        coEvery { registrationUseCase.validateReferralCode(any()) } returns
            Result.success(true)
        val vm = makeVm()
        fillValidAddressForm(vm)
        settle()

        // Form complete but no invite code → blocked.
        assertFalse(vm.isCreateFormValid)

        vm.onReferralCodeChange("DS-ARMXH2-MH7YFC")
        settle()
        assertTrue(vm.isCreateFormValid)
    }

    @Test
    fun openMode_neverBlocksOnInviteCode() = runTest(mainDispatcher) {
        val vm = makeVm() // mode OPEN from init stub
        fillValidAddressForm(vm)
        vm.onReferralCodeChange("DS-NOTREAL")

        assertTrue(vm.isCreateFormValid)
    }

    @Test
    fun referralMode_makesInviteFieldVisible() {
        coEvery { registrationUseCase.getRegistrationMode() } returns
            Result.success(RegistrationMode.REFERRAL)
        val vm = makeVm()
        assertTrue(vm.uiState.value.inviteFieldVisible)
    }

    @Test
    fun prefill_makesInviteFieldVisibleAndValidates() = runTest(mainDispatcher) {
        coEvery { registrationUseCase.validateReferralCode("DS-ARMXH2-MH7YFC") } returns
            Result.success(true)
        val vm = makeVm()

        vm.onReferralCodePrefilled("ds-armxh2-mh7yfc")
        settle()

        assertTrue(vm.uiState.value.inviteFieldVisible)
        assertEquals("DS-ARMXH2-MH7YFC", vm.referralCode)
        assertEquals(ReferralValidation.Valid, vm.referralValidation)
    }

    @Test
    fun createAccount_sendsReferralCode() = runTest(mainDispatcher) {
        coEvery { registrationUseCase.validateReferralCode(any()) } returns Result.success(true)
        val slot = io.mockk.slot<AccountCreationRequest>()
        coEvery { authUseCase.createAccount(capture(slot), any<Context>()) } returns
            Result.failure(RegistrationError.Taken)
        val vm = makeVm()
        fillValidAddressForm(vm)
        vm.onReferralCodeChange("DS-ARMXH2-MH7YFC")
        settle()

        vm.onCreateAccount(mockk(relaxed = true))

        assertEquals("DS-ARMXH2-MH7YFC", slot.captured.referralCode)
    }

    @Test
    fun createAccount_success_refreshesPrimaryAddress() = runTest(mainDispatcher) {
        coEvery { authUseCase.createAccount(any(), any<Context>()) } returns
            Result.success(
                xyz.desent.domain.model.AccountCreationResult(
                    nsec = "nsec1x", npub = "npub1x", user = mockk(relaxed = true)
                )
            )
        val vm = makeVm()
        fillValidAddressForm(vm)
        settle()

        vm.onCreateAccount(mockk(relaxed = true))

        // Same as the login flows: the switcher must not show "No address"
        // until the next cold-launch refreshAll.
        coVerify(exactly = 1) { refreshPrimaryAddressUseCase.refreshOne("npub1x") }
    }

    @Test
    fun createAccount_failure_doesNotRefreshPrimaryAddress() = runTest(mainDispatcher) {
        coEvery { authUseCase.createAccount(any(), any<Context>()) } returns
            Result.failure(RegistrationError.Taken)
        val vm = makeVm()
        fillValidAddressForm(vm)
        settle()

        vm.onCreateAccount(mockk(relaxed = true))

        coVerify(exactly = 0) { refreshPrimaryAddressUseCase.refreshOne(any()) }
    }

    @Test
    fun createFailure_invalidReferralCode_clearsGate() = runTest(mainDispatcher) {
        coEvery { registrationUseCase.validateReferralCode(any()) } returns Result.success(true)
        coEvery { authUseCase.createAccount(any(), any<Context>()) } returns
            Result.failure(RegistrationError.InvalidReferralCode)
        val vm = makeVm()
        fillValidAddressForm(vm)
        vm.onReferralCodeChange("DS-ARMXH2-MH7YFC")
        settle()
        vm.onCreateAccount(mockk(relaxed = true))

        // Code cleared, gate returned, mode forced to referral.
        assertEquals("", vm.referralCode)
        assertEquals(ReferralValidation.Invalid, vm.referralValidation)
        assertEquals(RegistrationMode.REFERRAL, vm.uiState.value.registrationMode)
        assertTrue(vm.uiState.value.inviteFieldVisible)
        assertTrue(vm.uiState.value.error?.contains("invite code", ignoreCase = true) == true)
    }

    @Test
    fun createFailure_rateLimited_setsRetryTimestamp() = runTest(mainDispatcher) {
        coEvery { authUseCase.createAccount(any(), any<Context>()) } returns
            Result.failure(RegistrationError.RateLimited(90))
        val vm = makeVm()
        fillValidAddressForm(vm)

        settle()
        val before = System.currentTimeMillis()
        vm.onCreateAccount(mockk(relaxed = true))

        val retryAt = vm.uiState.value.rateLimitRetryAt
        assertTrue("retryAt should be ~90s out: $retryAt", retryAt != null)
        assertTrue(retryAt!! >= before + 89_000 && retryAt <= before + 91_000)
        assertFalse(vm.uiState.value.isLoading)
    }
}
