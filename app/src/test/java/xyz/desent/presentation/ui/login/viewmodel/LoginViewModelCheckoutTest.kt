package xyz.desent.presentation.ui.login.viewmodel

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.data.registration.model.RegistrationError
import xyz.desent.data.repository.NostrRepository
import xyz.desent.domain.model.AccountCreationResult
import xyz.desent.domain.model.AvailabilityInfo
import xyz.desent.domain.model.Invoice
import xyz.desent.domain.model.InvoiceState
import xyz.desent.domain.model.PaymentTargetType
import xyz.desent.domain.model.PaymentsConfig
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

/**
 * Pre-account vanity checkout in [LoginViewModel] against
 * refs/FROM_email.desent.xyz/ANDROID_PAYMENTS.md §5 +
 * CUSTODIAL_ACCOUNTS.md §4.1: priced signup names pay a lightning invoice
 * first, then the register retry reuses the SAME key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelCheckoutTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var authUseCase: AuthUseCase
    private lateinit var registrationUseCase: RegistrationUseCase
    private lateinit var custodialAccountRepository: CustodialAccountRepository
    private lateinit var nostrRepository: NostrRepository
    private lateinit var refreshPrimaryAddressUseCase: RefreshPrimaryAddressUseCase
    private lateinit var paymentsUseCase: PaymentsUseCase
    private lateinit var aliasUseCase: AliasUseCase

    /** Real (pure-JVM) signer — the checkout calls are matched on it. */
    private val identity = nostr.id.Identity.create(PRIV_HEX)
    private val preparedKey =
        PreparedSignupKey(nsec = PREPARED_NSEC, npub = PREPARED_NPUB, identity = identity)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        authUseCase = mockk()
        registrationUseCase = mockk()
        custodialAccountRepository = mockk()
        nostrRepository = mockk(relaxed = true)
        refreshPrimaryAddressUseCase = mockk(relaxed = true)
        paymentsUseCase = mockk()
        aliasUseCase = mockk()

        coEvery { registrationUseCase.getRegistrationMode() } returns
            Result.success(RegistrationMode.OPEN)
        coEvery { registrationUseCase.checkAvailable(any()) } returns
            Result.success(AvailabilityInfo(available = true, local = null, priceSats = 0, vanity = false))
        coEvery { registrationUseCase.validateReferralCode(any()) } returns Result.success(true)
        coEvery { custodialAccountRepository.suggestUsernames(any()) } returns
            Result.success(emptyList())
        coEvery { paymentsUseCase.getConfig() } returns Result.success(
            PaymentsConfig(strikeEnabled = true)
        )
        coEvery { authUseCase.prepareSignupKey() } returns Result.success(preparedKey)
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
        Dispatchers.resetMain()
    }

    private fun makeVm() = LoginViewModel(
        authUseCase,
        registrationUseCase,
        custodialAccountRepository,
        nostrRepository,
        refreshPrimaryAddressUseCase,
        paymentsUseCase,
        aliasUseCase
    )

    private fun kotlinx.coroutines.test.TestScope.settle() {
        testScheduler.advanceUntilIdle()
    }

    /** Fill the key-only create form with a PRICED name (3 chars = vanity). */
    private fun kotlinx.coroutines.test.TestScope.fillPricedKeyOnlyForm(
        vm: LoginViewModel,
        local: String = "abc"
    ) {
        vm.onCreateAccountModeChange(CreateAccountMode.KEY_ONLY)
        vm.onCreateScreenShown()
        settle() // payments config + registration mode
        vm.onDisplayNameChange("Alice")
        vm.onLocalChange(local)
        settle() // 450 ms availability debounce
    }

    private fun pricedAvailability(local: String) =
        AvailabilityInfo(available = true, local = local, priceSats = 50_000L, vanity = true)

    private fun pendingRequest(id: Long = 7, localPart: String = "abc") = VanityRequest(
        id = id, localPart = localPart, domain = "desent.xyz", kind = "primary",
        quotedSatoshi = 50_000L, status = VanityRequestStatus.PENDING,
        requestedAt = null, decidedAt = null, note = null
    )

    private fun invoice(
        id: Long = 42,
        state: InvoiceState = InvoiceState.UNPAID,
        settledAt: String? = null
    ) = Invoice(
        id = id,
        targetType = PaymentTargetType.VANITY_REQUEST,
        targetId = 7,
        amountSatoshi = 50_000L,
        amountUsd = "50.00",
        lnInvoice = "lnbc500u1p3q",
        state = state,
        expiresAt = "2026-09-16T18:04:00+00:00",
        createdAt = null,
        paidAt = null,
        settledAt = settledAt
    )

    // ---- gate ---------------------------------------------------------------

    @Test
    fun `payments config fails closed to the legacy path`() = runTest(mainDispatcher) {
        coEvery { paymentsUseCase.getConfig() } returns Result.failure(
            xyz.desent.data.payments.model.PaymentsError.Unknown("offline")
        )
        val vm = makeVm()

        vm.onCreateScreenShown()
        settle()

        assertTrue(!vm.uiState.value.paymentsStrikeEnabled)
    }

    // ---- proactive checkout --------------------------------------------------

    @Test
    fun `priced name with strike on opens checkout instead of registering`() =
        runTest(mainDispatcher) {
            coEvery { registrationUseCase.checkAvailable("abc") } returns
                Result.success(pricedAvailability("abc"))
            coEvery { aliasUseCase.createVanityRequest("abc", null, "primary", identity) } returns
                Result.success(pendingRequest())
            coEvery {
                paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7, identity)
            } returns Result.success(invoice())

            val vm = makeVm()
            fillPricedKeyOnlyForm(vm)
            vm.onCreateAccount(mockk(relaxed = true))
            settle()

            val checkout = vm.uiState.value.checkout
            assertNotNull(checkout)
            assertEquals(42L, checkout!!.invoice?.id)
            assertEquals(PaymentTargetType.VANITY_REQUEST, checkout.target)
            coVerify(exactly = 0) { authUseCase.createAccount(any(), any(), any()) }
            coVerify(exactly = 1) { authUseCase.prepareSignupKey() }
        }

    @Test
    fun `paid invoice retries the key-only register with the prepared key`() =
        runTest(mainDispatcher) {
            coEvery { registrationUseCase.checkAvailable("abc") } returns
                Result.success(pricedAvailability("abc"))
            coEvery { aliasUseCase.createVanityRequest("abc", null, "primary", identity) } returns
                Result.success(pendingRequest())
            coEvery {
                paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7, identity)
            } returns Result.success(invoice())
            coEvery { paymentsUseCase.getInvoice(42, identity) } returns Result.success(
                invoice(state = InvoiceState.PAID, settledAt = "2026-09-16T17:40:01+00:00")
            )
            coEvery { authUseCase.createAccount(any(), any(), PREPARED_NSEC) } returns
                Result.success(
                    AccountCreationResult(nsec = PREPARED_NSEC, npub = PREPARED_NPUB, user = mockk(relaxed = true))
                )

            val vm = makeVm()
            fillPricedKeyOnlyForm(vm)
            vm.onCreateAccount(mockk(relaxed = true))
            settle()
            vm.resumePolling()

            testScheduler.advanceTimeBy(3_000)
            testScheduler.runCurrent()

            assertNull(vm.uiState.value.checkout) // auto-dismissed on settle
            assertNotNull(vm.uiState.value.createdAccount)
            // The retry MUST reuse the key that paid — its pubkey owns the approval.
            coVerify(exactly = 1) { authUseCase.createAccount(any(), any(), PREPARED_NSEC) }
        }

    @Test
    fun `paid invoice retries the custodial register with the prepared key`() =
        runTest(mainDispatcher) {
            coEvery { registrationUseCase.checkAvailable("abc") } returns
                Result.success(pricedAvailability("abc"))
            coEvery { aliasUseCase.createVanityRequest("abc", null, "primary", identity) } returns
                Result.success(pendingRequest())
            coEvery {
                paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7, identity)
            } returns Result.success(invoice())
            coEvery { paymentsUseCase.getInvoice(42, identity) } returns Result.success(
                invoice(state = InvoiceState.PAID, settledAt = "2026-09-16T17:40:01+00:00")
            )
            coEvery { authUseCase.createCustodialAccount(any(), any(), PREPARED_NSEC) } returns
                Result.success(
                    AccountCreationResult(nsec = PREPARED_NSEC, npub = PREPARED_NPUB, user = mockk(relaxed = true))
                )

            val vm = makeVm()
            vm.onCreateScreenShown()
            settle()
            vm.onDisplayNameChange("Alice")
            vm.onLocalChange("abc")
            settle()
            vm.onSignupPasswordChange("hunter22!")
            vm.onSignupPasswordConfirmChange("hunter22!")
            vm.onCreateCustodialAccount(mockk(relaxed = true))
            settle()
            vm.resumePolling()

            testScheduler.advanceTimeBy(3_000)
            testScheduler.runCurrent()

            assertNull(vm.uiState.value.checkout)
            coVerify(exactly = 1) {
                authUseCase.createCustodialAccount(any(), any(), PREPARED_NSEC)
            }
        }

    // ---- 409 reuse ------------------------------------------------------------

    @Test
    fun `request_exists reuses own pending row and reuses the prepared key`() =
        runTest(mainDispatcher) {
            coEvery { registrationUseCase.checkAvailable("abc") } returns
                Result.success(pricedAvailability("abc"))
            coEvery { aliasUseCase.createVanityRequest("abc", null, "primary", identity) } returns
                Result.failure(xyz.desent.data.vanity.model.VanityError.RequestExists) andThen
                Result.failure(xyz.desent.data.vanity.model.VanityError.RequestExists)
            coEvery { aliasUseCase.listVanityRequests(identity) } returns
                Result.success(listOf(pendingRequest()))
            coEvery {
                paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7, identity)
            } returns Result.success(invoice())

            val vm = makeVm()
            fillPricedKeyOnlyForm(vm)
            vm.onCreateAccount(mockk(relaxed = true))
            settle()

            assertNotNull(vm.uiState.value.checkout)

            // Dismiss unpaid, then submit again: the SAME key must be reused
            // (the request row is keyed to its pubkey — §4.1).
            vm.dismissCheckout()
            vm.onCreateAccount(mockk(relaxed = true))
            settle()

            coVerify(exactly = 1) { authUseCase.prepareSignupKey() }
            coVerify(exactly = 2) {
                aliasUseCase.createVanityRequest("abc", null, "primary", identity)
            }
        }

    // ---- legacy fallback + reactive 402 ---------------------------------------

    @Test
    fun `strike off keeps the explain-only legacy message`() = runTest(mainDispatcher) {
        coEvery { paymentsUseCase.getConfig() } returns Result.success(PaymentsConfig())
        coEvery { registrationUseCase.checkAvailable("abc") } returns
            Result.success(pricedAvailability("abc"))
        coEvery { authUseCase.createAccount(any(), any(), isNull()) } returns Result.failure(
            RegistrationError.VanityPrice(
                length = 3, priceSats = 50_000L,
                ladder = mapOf("3" to 50_000L), freeLength = 8
            )
        )

        val vm = makeVm()
        fillPricedKeyOnlyForm(vm)
        vm.onCreateAccount(mockk(relaxed = true))
        settle()

        assertNull(vm.uiState.value.checkout)
        assertTrue(vm.uiState.value.error?.contains("In-app payments are currently unavailable") == true)
        coVerify(exactly = 0) { paymentsUseCase.mintInvoice(any(), any(), any()) }
    }

    @Test
    fun `reactive 402 with strike on routes into the checkout`() = runTest(mainDispatcher) {
        // Availability said free (stale cache / ladder drift); the register
        // call itself 402s — the server quote is authoritative (§5).
        coEvery { aliasUseCase.createVanityRequest("alicebrave", null, "primary", identity) } returns
            Result.success(pendingRequest(localPart = "alicebrave"))
        coEvery {
            paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7, identity)
        } returns Result.success(invoice())
        coEvery { authUseCase.createAccount(any(), any(), isNull()) } returns Result.failure(
            RegistrationError.VanityPrice(
                length = 3, priceSats = 50_000L,
                ladder = mapOf("3" to 50_000L), freeLength = 8
            )
        )

        val vm = makeVm()
        fillPricedKeyOnlyForm(vm, local = "alicebrave") // stubbed free above
        settle()
        vm.onCreateAccount(mockk(relaxed = true))
        settle()

        val checkout = vm.uiState.value.checkout
        assertNotNull(checkout)
        assertEquals(42L, checkout!!.invoice?.id)
        coVerify(exactly = 1) { authUseCase.createAccount(any(), any(), isNull()) }
    }

    companion object {
        /** Valid secp256k1 scalar ("aa" × 32) — pure-JVM Identity works in tests. */
        private val PRIV_HEX = "aa".repeat(32)
        private const val PREPARED_NSEC = "nsec1preparedkey"
        private const val PREPARED_NPUB = "npub1preparedkey"
    }
}
