package xyz.desent.presentation.ui.alias.viewmodel

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.data.alias.model.AliasError
import xyz.desent.data.payments.model.PaymentsError
import xyz.desent.data.vanity.model.VanityError
import xyz.desent.domain.model.Alias
import xyz.desent.domain.model.AliasServiceConfig
import xyz.desent.domain.model.AliasSlotPurchase
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

/**
 * [AliasViewModel] Strike checkout behavior against
 * refs/FROM_email.desent.xyz/ANDROID_PAYMENTS.md §3-6.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AliasViewModelCheckoutTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var aliasUseCase: AliasUseCase
    private lateinit var paymentsUseCase: PaymentsUseCase

    private val strikeConfig = PaymentsConfig(
        strikeEnabled = true,
        tierPriceSats = 12_943L,
        tierPriceMode = "usd",
        tierPriceUsd = "10.00",
        btcUsd = "77401.8500"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        aliasUseCase = mockk()
        paymentsUseCase = mockk()

        coEvery { aliasUseCase.getConfig() } returns Result.success(
            AliasServiceConfig(
                emailDomain = "desent.xyz",
                vanity = VanityConfig(
                    freeLength = 8,
                    ladder = mapOf(3 to 50_000L),
                    ladderUsd = mapOf(3 to "50.00")
                ),
                slotPriceSats = 5_000L,
                slotPriceUsd = "0.50"
            )
        )
        coEvery { aliasUseCase.listAliases() } returns Result.success(
            emptyList<Alias>() to AliasTierInfo(tier = "free", cap = 3, used = 3, emailDomain = "desent.xyz")
        )
        coEvery { aliasUseCase.listVanityRequests() } returns Result.success(emptyList())
        coEvery { paymentsUseCase.getConfig() } returns Result.success(strikeConfig)
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
        Dispatchers.resetMain()
    }

    private fun makeVm() = AliasViewModel(aliasUseCase, paymentsUseCase)

    private fun invoice(
        id: Long = 42,
        target: PaymentTargetType = PaymentTargetType.VANITY_REQUEST,
        targetId: Long = 7,
        state: InvoiceState = InvoiceState.UNPAID,
        settledAt: String? = null
    ) = Invoice(
        id = id,
        targetType = target,
        targetId = targetId,
        amountSatoshi = 64_618L,
        amountUsd = "50.00",
        lnInvoice = "lnbc646180n1p3q",
        state = state,
        expiresAt = "2026-08-22T18:04:00+00:00",
        createdAt = null,
        paidAt = null,
        settledAt = settledAt
    )

    private fun pendingRequest(id: Long = 7, localPart: String = "abc") = VanityRequest(
        id = id, localPart = localPart, domain = "desent.xyz", kind = "alias",
        quotedSatoshi = 50_000L, status = VanityRequestStatus.PENDING,
        requestedAt = null, decidedAt = null, note = null
    )

    @Test
    fun `init loads payments config and gates the tier CTA on free tier`() = runTest(mainDispatcher) {
        val vm = makeVm()

        assertTrue(vm.uiState.value.paymentsConfig.strikeEnabled)
        assertTrue(vm.uiState.value.showTierCta) // strike on + tier "free"

        coEvery { aliasUseCase.listAliases() } returns Result.success(
            emptyList<Alias>() to AliasTierInfo(tier = "paid", cap = 25, used = 3, emailDomain = "desent.xyz")
        )
        vm.refresh()
        assertFalse(vm.uiState.value.showTierCta) // paid tier → no CTA
    }

    @Test
    fun `config fetch failure fails closed to legacy flows`() = runTest(mainDispatcher) {
        coEvery { paymentsUseCase.getConfig() } returns Result.failure(
            PaymentsError.Unknown("offline")
        )

        val vm = makeVm()

        assertFalse(vm.uiState.value.paymentsConfig.strikeEnabled)
        assertFalse(vm.uiState.value.showTierCta)
    }

    @Test
    fun `vanity request with strike on mints invoice and opens checkout`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.createVanityRequest("abc", "desent.xyz", "alias") } returns
            Result.success(pendingRequest())
        coEvery { paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7) } returns
            Result.success(invoice())

        val vm = makeVm()
        vm.requestVanityAddress("abc", "desent.xyz")

        val checkout = vm.uiState.value.checkout
        assertNotNull(checkout)
        assertEquals(42L, checkout!!.invoice?.id)
        assertEquals("abc", checkout.claimLocalPart)
        assertEquals(PaymentTargetType.VANITY_REQUEST, checkout.target)
        assertNull(vm.uiState.value.toast) // no legacy "operator approval" toast
        assertNull(vm.uiState.value.vanityPricePrompt)
    }

    @Test
    fun `request_exists reuses own pending row and mints its invoice`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.createVanityRequest("abc", any(), any()) } returns
            Result.failure(VanityError.RequestExists)
        coEvery { aliasUseCase.listVanityRequests() } returns Result.success(listOf(pendingRequest()))
        coEvery { paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7) } returns
            Result.success(invoice())

        val vm = makeVm()
        vm.requestVanityAddress("abc", "desent.xyz")

        val checkout = vm.uiState.value.checkout
        assertNotNull(checkout)
        assertEquals(42L, checkout!!.invoice?.id)
        assertEquals("abc", checkout.claimLocalPart)
        coVerify(exactly = 1) { paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7) }
    }

    @Test
    fun `request_exists with approved row prompts the claim retry instead of checkout`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.createVanityRequest("abc", any(), any()) } returns
            Result.failure(VanityError.RequestExists)
        coEvery { aliasUseCase.listVanityRequests() } returns Result.success(
            listOf(pendingRequest().copy(status = VanityRequestStatus.APPROVED))
        )

        val vm = makeVm()
        vm.requestVanityAddress("abc", "desent.xyz")

        assertNull(vm.uiState.value.checkout)
        assertEquals("abc", vm.uiState.value.prefillLocalPart)
        assertTrue(vm.uiState.value.toast?.contains("approved — finish claiming it") == true)
    }

    @Test
    fun `request_exists held by someone else surfaces the indistinguishable toast`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.createVanityRequest("abc", any(), any()) } returns
            Result.failure(VanityError.RequestExists)
        // Own list has nothing for this name.
        coEvery { aliasUseCase.listVanityRequests() } returns Result.success(emptyList())

        val vm = makeVm()
        vm.requestVanityAddress("abc", "desent.xyz")

        assertNull(vm.uiState.value.checkout)
        assertEquals("This name is already being requested", vm.uiState.value.toast)
    }

    @Test
    fun `buySlots with strike on opens checkout for the purchase`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.purchaseSlots(2) } returns Result.success(
            AliasSlotPurchase(
                id = 9, quantity = 2, quotedSatoshi = 10_000L,
                status = VanityRequestStatus.PENDING,
                requestedAt = null, decidedAt = null, note = null
            )
        )
        coEvery { paymentsUseCase.mintInvoice(PaymentTargetType.SLOT_PURCHASE, 9) } returns
            Result.success(invoice(id = 55, target = PaymentTargetType.SLOT_PURCHASE, targetId = 9))

        val vm = makeVm()
        vm.buySlots(2)

        val checkout = vm.uiState.value.checkout
        assertNotNull(checkout)
        assertEquals(55L, checkout!!.invoice?.id)
        assertEquals(PaymentTargetType.SLOT_PURCHASE, checkout.invoice?.targetType)
        assertNull(vm.uiState.value.slotPricePrompt)
        assertNull(vm.uiState.value.toast)
    }

    @Test
    fun `purchaseTier opens checkout without a prior request row`() = runTest(mainDispatcher) {
        coEvery { paymentsUseCase.purchaseTier() } returns Result.success(
            invoice(id = 77, target = PaymentTargetType.TIER_PURCHASE, targetId = 9)
        )

        val vm = makeVm()
        vm.purchaseTier()

        val checkout = vm.uiState.value.checkout
        assertNotNull(checkout)
        assertEquals(77L, checkout!!.invoice?.id)
        assertEquals(PaymentTargetType.TIER_PURCHASE, checkout.invoice?.targetType)
        assertFalse(vm.uiState.value.isBuyingTier)
    }

    @Test
    fun `purchaseTier already_paid toasts and refreshes instead of checkout`() = runTest(mainDispatcher) {
        coEvery { paymentsUseCase.purchaseTier() } returns Result.failure(PaymentsError.AlreadyPaid)

        val vm = makeVm()
        vm.purchaseTier()

        assertNull(vm.uiState.value.checkout)
        assertEquals("You already have the paid tier", vm.uiState.value.toast)
        coVerify(atLeast = 2) { aliasUseCase.listAliases() } // tier-info refreshed
    }

    @Test
    fun `mint failure on transient error keeps checkout open with retry`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.createVanityRequest("abc", "desent.xyz", "alias") } returns
            Result.success(pendingRequest())
        coEvery { paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7) } returns
            Result.failure(PaymentsError.StrikeUnavailable) andThen
            Result.success(invoice())

        val vm = makeVm()
        vm.requestVanityAddress("abc", "desent.xyz")

        val failed = vm.uiState.value.checkout
        assertNotNull(failed)
        assertNull(failed!!.invoice)
        assertNotNull(failed.mintError)

        vm.reMintInvoice() // "Try again"

        val retried = vm.uiState.value.checkout
        assertNotNull(retried)
        assertEquals(42L, retried!!.invoice?.id)
        assertNull(retried.mintError)
    }

    @Test
    fun `paid settled vanity invoice auto-retries the claim`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.createVanityRequest("abc", "desent.xyz", "alias") } returns
            Result.success(pendingRequest())
        coEvery { paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7) } returns
            Result.success(invoice())
        coEvery { paymentsUseCase.getInvoice(42) } returns Result.success(
            invoice(state = InvoiceState.PAID, settledAt = "2026-08-22T17:40:01+00:00")
        )
        coEvery { aliasUseCase.createAlias("abc", null) } returns Result.success(
            Alias(1, "abc@desent.xyz", "abc", null, true, "2026-08-22")
        )

        val vm = makeVm()
        vm.requestVanityAddress("abc", "desent.xyz")
        vm.resumePolling()

        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()

        assertNull(vm.uiState.value.checkout) // auto-dismissed on settle
        coVerify(exactly = 1) { aliasUseCase.createAlias("abc", null) }
        assertTrue(vm.uiState.value.toast?.startsWith("Alias created") == true)
    }

    @Test
    fun `paid settled slot purchase credits cap and refreshes`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.purchaseSlots(1) } returns Result.success(
            AliasSlotPurchase(
                id = 9, quantity = 1, quotedSatoshi = 5_000L,
                status = VanityRequestStatus.PENDING,
                requestedAt = null, decidedAt = null, note = null
            )
        )
        coEvery { paymentsUseCase.mintInvoice(PaymentTargetType.SLOT_PURCHASE, 9) } returns
            Result.success(invoice(id = 55, target = PaymentTargetType.SLOT_PURCHASE, targetId = 9))
        coEvery { paymentsUseCase.getInvoice(55) } returns Result.success(
            invoice(
                id = 55, target = PaymentTargetType.SLOT_PURCHASE, targetId = 9,
                state = InvoiceState.PAID, settledAt = "2026-08-22T17:40:01+00:00"
            )
        )

        val vm = makeVm()
        vm.buySlots(1)
        vm.resumePolling()

        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()

        assertNull(vm.uiState.value.checkout)
        assertEquals("Payment received — alias slots credited", vm.uiState.value.toast)
        coVerify(atLeast = 2) { aliasUseCase.listAliases() } // cap re-fetched
    }

    @Test
    fun `paid without settlement shows processing and never retries the claim`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.createVanityRequest("abc", "desent.xyz", "alias") } returns
            Result.success(pendingRequest())
        coEvery { paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7) } returns
            Result.success(invoice())
        coEvery { paymentsUseCase.getInvoice(42) } returns Result.success(
            invoice(state = InvoiceState.PAID, settledAt = null) // unresolved
        )

        val vm = makeVm()
        vm.requestVanityAddress("abc", "desent.xyz")
        vm.resumePolling()

        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()

        val checkout = vm.uiState.value.checkout
        assertNotNull(checkout) // stays open in processing state
        assertTrue(checkout!!.invoice?.isProcessing == true)
        assertEquals("Payment received — processing", vm.uiState.value.toast)
        coVerify(exactly = 0) { aliasUseCase.createAlias(any(), any()) }

        vm.pausePolling() // terminal for the user — polling stops
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
        coVerify(exactly = 1) { paymentsUseCase.getInvoice(42) }
    }

    @Test
    fun `expired invoice stays open and re-mint fetches a fresh one`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.createVanityRequest("abc", "desent.xyz", "alias") } returns
            Result.success(pendingRequest())
        coEvery { paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7) } returns
            Result.success(invoice()) andThen
            Result.success(invoice(id = 43))
        coEvery { paymentsUseCase.getInvoice(42) } returns Result.success(
            invoice(state = InvoiceState.EXPIRED)
        )

        val vm = makeVm()
        vm.requestVanityAddress("abc", "desent.xyz")
        vm.resumePolling()

        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()

        val expired = vm.uiState.value.checkout
        assertNotNull(expired) // sheet stays, offering "New invoice"
        assertEquals(InvoiceState.EXPIRED, expired!!.invoice?.state)

        vm.reMintInvoice()

        val fresh = vm.uiState.value.checkout
        assertNotNull(fresh)
        assertEquals(43L, fresh!!.invoice?.id)
        vm.pausePolling()
    }

    @Test
    fun `transient poll failures keep polling until settlement`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.createVanityRequest("abc", "desent.xyz", "alias") } returns
            Result.success(pendingRequest())
        coEvery { paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7) } returns
            Result.success(invoice())
        coEvery { paymentsUseCase.getInvoice(42) } returns Result.failure(
            PaymentsError.Unknown("network blip")
        ) andThen Result.success(
            invoice(state = InvoiceState.PAID, settledAt = "2026-08-22T17:40:01+00:00")
        )
        coEvery { aliasUseCase.createAlias("abc", null) } returns Result.success(
            Alias(1, "abc@desent.xyz", "abc", null, true, "2026-08-22")
        )

        val vm = makeVm()
        vm.requestVanityAddress("abc", "desent.xyz")
        vm.resumePolling()

        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
        assertNotNull(vm.uiState.value.checkout) // failure ignored — still waiting

        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
        assertNull(vm.uiState.value.checkout) // second tick settles
        coVerify(exactly = 1) { aliasUseCase.createAlias("abc", null) }
    }

    @Test
    fun `dismissCheckout clears state and stops polling`() = runTest(mainDispatcher) {
        coEvery { paymentsUseCase.mintInvoice(PaymentTargetType.SLOT_PURCHASE, 9) } returns
            Result.success(invoice(id = 55, target = PaymentTargetType.SLOT_PURCHASE, targetId = 9))
        coEvery { paymentsUseCase.getInvoice(55) } returns Result.success(
            invoice(id = 55, target = PaymentTargetType.SLOT_PURCHASE, targetId = 9)
        )

        val vm = makeVm()
        vm.startCheckout(PaymentTargetType.SLOT_PURCHASE, 9)
        vm.resumePolling()
        vm.dismissCheckout()

        assertNull(vm.uiState.value.checkout)

        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
        coVerify(exactly = 0) { paymentsUseCase.getInvoice(55) }
    }

    @Test
    fun `mint not_pending approved pre-fills the claim`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.createVanityRequest("abc", "desent.xyz", "alias") } returns
            Result.success(pendingRequest())
        coEvery { paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7) } returns
            Result.failure(PaymentsError.NotPending("approved"))

        val vm = makeVm()
        vm.requestVanityAddress("abc", "desent.xyz")

        assertNull(vm.uiState.value.checkout)
        assertEquals("abc", vm.uiState.value.prefillLocalPart)
    }

    @Test
    fun `strike disabled keeps the legacy operator-approval toasts`() = runTest(mainDispatcher) {
        coEvery { paymentsUseCase.getConfig() } returns Result.success(PaymentsConfig())
        coEvery { aliasUseCase.createVanityRequest("abc", "desent.xyz", "alias") } returns
            Result.success(pendingRequest())

        val vm = makeVm()
        vm.requestVanityAddress("abc", "desent.xyz")

        assertNull(vm.uiState.value.checkout)
        assertTrue(vm.uiState.value.toast?.contains("pending operator approval") == true)
        coVerify(exactly = 0) { paymentsUseCase.mintInvoice(any(), any()) }
    }

    @Test
    fun `402 sheet prompts carry the config USD sticker`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.createAlias("abc", null) } returns Result.failure(
            AliasError.VanityPrice(
                length = 3, priceSats = 50_000L,
                ladder = mapOf("3" to 50_000L), freeLength = 8
            )
        )

        val vm = makeVm()
        vm.createAlias("abc", null)

        val prompt = vm.uiState.value.vanityPricePrompt
        assertNotNull(prompt)
        // Sticker from the cached ladder USD — never a client-side conversion.
        assertEquals("50.00", prompt!!.priceUsd)
    }
}
