package xyz.desent.presentation.ui.billing.viewmodel

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

/**
 * [BillingViewModel] coverage against refs/FROM_email.desent.xyz/
 * ANDROID_PAYMENTS.md §1 (gate), §3.5 (history timeline), §6 (plans).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BillingViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var paymentsUseCase: PaymentsUseCase
    private lateinit var aliasUseCase: AliasUseCase

    private val config = PaymentsConfig(
        strikeEnabled = true,
        tierPriceSats = 12_943L,
        tierPriceUsd = "10.00",
        lifetimeEnabled = true,
        lifetimePriceSats = 32_358L,
        lifetimePriceUsd = "25.00"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        paymentsUseCase = mockk()
        aliasUseCase = mockk()

        coEvery { paymentsUseCase.getConfig() } returns Result.success(config)
        coEvery { aliasUseCase.getTierInfo() } returns Result.success(
            AliasTierInfo(
                tier = "free",
                cap = 2,
                used = 1,
                emailDomain = "desent.xyz",
                storageUsed = 100L,
                storageCap = 1000L,
                paidUntil = null
            )
        )
        coEvery { paymentsUseCase.getHistory() } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
        Dispatchers.resetMain()
    }

    private fun makeVm() = BillingViewModel(paymentsUseCase, aliasUseCase)

    private fun invoice(
        id: Long = 90,
        state: InvoiceState = InvoiceState.UNPAID,
        settledAt: String? = null
    ) = Invoice(
        id = id,
        targetType = PaymentTargetType.TIER_PURCHASE,
        targetId = 11,
        amountSatoshi = 12_943L,
        amountUsd = "10.00",
        lnInvoice = "lnbc…",
        state = state,
        expiresAt = "2026-09-01T00:00:00+00:00",
        createdAt = null,
        paidAt = null,
        settledAt = settledAt
    )

    @Test
    fun `init loads gate, tier info and history`() = runTest(mainDispatcher) {
        val vm = makeVm()

        assertTrue(vm.uiState.value.paymentsConfig.strikeEnabled)
        assertTrue(vm.uiState.value.paymentsConfig.lifetimeEnabled)
        assertEquals("free", vm.uiState.value.tierInfo?.tier)
        assertFalse(vm.uiState.value.historyFailed)
    }

    @Test
    fun `history failure flags the timeline without killing the screen`() = runTest(mainDispatcher) {
        coEvery { paymentsUseCase.getHistory() } returns Result.failure(Exception("boom"))

        val vm = makeVm()

        assertTrue(vm.uiState.value.historyFailed)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `purchaseTier opens checkout with the plan invoice`() = runTest(mainDispatcher) {
        coEvery { paymentsUseCase.purchaseTier(PurchasePlan.LIFETIME) } returns Result.success(invoice(id = 91))

        val vm = makeVm()
        vm.purchaseTier(PurchasePlan.LIFETIME)

        val checkout = vm.uiState.value.checkout
        assertNotNull(checkout)
        assertEquals(PaymentTargetType.TIER_PURCHASE, checkout!!.target)
        assertEquals(91L, checkout.invoice?.id)
        assertEquals(PurchasePlan.LIFETIME, vm.uiState.value.checkoutPlan)
    }

    @Test
    fun `already_paid refreshes instead of opening a checkout`() = runTest(mainDispatcher) {
        coEvery { paymentsUseCase.purchaseTier(any()) } returns Result.failure(PaymentsError.AlreadyPaid)

        val vm = makeVm()
        vm.purchaseTier(PurchasePlan.LIFETIME)

        assertNull(vm.uiState.value.checkout)
        assertNotNull(vm.uiState.value.toast)
        coVerify(atLeast = 2) { aliasUseCase.getTierInfo() } // initial + refresh
    }

    @Test
    fun `plan_switch_blocked surfaces the operator guidance`() = runTest(mainDispatcher) {
        coEvery { paymentsUseCase.purchaseTier(any()) } returns Result.failure(PaymentsError.PlanSwitchBlocked)

        val vm = makeVm()
        vm.purchaseTier(PurchasePlan.LIFETIME)

        assertTrue(vm.uiState.value.toast!!.contains("other plan"))
    }

    @Test
    fun `reMint re-purchases the current checkout plan`() = runTest(mainDispatcher) {
        coEvery { paymentsUseCase.purchaseTier(PurchasePlan.YEARLY) } returns Result.success(invoice(id = 92))

        val vm = makeVm()
        vm.purchaseTier(PurchasePlan.YEARLY)
        vm.reMintInvoice()

        coVerify(exactly = 2) { paymentsUseCase.purchaseTier(PurchasePlan.YEARLY) }
    }

    @Test
    fun `paid invoice closes checkout and reloads tier and history`() = runTest(mainDispatcher) {
        coEvery { paymentsUseCase.purchaseTier(any()) } returns Result.success(invoice(id = 93))
        coEvery { paymentsUseCase.getInvoice(93L) } returns Result.success(
            invoice(id = 93, state = InvoiceState.PAID, settledAt = "2026-08-25T10:00:00+00:00")
        )
        coEvery { paymentsUseCase.getHistory() } returns Result.success(
            listOf(
                PurchaseHistoryItem(
                    kind = PaymentTargetType.TIER_PURCHASE,
                    targetId = 11,
                    status = "approved",
                    amountSatoshi = 12_943L,
                    amountUsd = "10.00",
                    requestedAt = "2026-08-25T09:00:00Z",
                    decidedAt = "2026-08-25T10:00:00Z",
                    decidedBy = "strike",
                    note = null,
                    detailJson = null,
                    invoice = invoice(id = 93, state = InvoiceState.PAID, settledAt = "2026-08-25T10:00:00+00:00")
                )
            )
        )

        val vm = makeVm()
        vm.purchaseTier(PurchasePlan.YEARLY)
        vm.resumePolling()

        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()

        assertNull(vm.uiState.value.checkout)
        assertTrue(vm.uiState.value.toast!!.contains("plan active"))
        assertEquals(1, vm.uiState.value.history.size)
        vm.pausePolling()
    }

    // ---------------- §6 CTA gate + §3.1b add-ons ----------------

    @Test
    fun `tier sales gate hides CTAs only when the kill switch is explicitly false`() = runTest(mainDispatcher) {
        // Missing field (= null) and true both leave the gate open.
        val vm = makeVm()
        assertTrue(vm.uiState.value.tierSalesOpen)

        coEvery { paymentsUseCase.getConfig() } returns Result.success(config.copy(tierPurchasesEnabled = true))
        assertTrue(makeVm().uiState.value.tierSalesOpen)

        coEvery { paymentsUseCase.getConfig() } returns Result.success(config.copy(tierPurchasesEnabled = false))
        assertFalse(makeVm().uiState.value.tierSalesOpen)

        coEvery { paymentsUseCase.getConfig() } returns Result.success(config.copy(strikeEnabled = false))
        assertFalse(makeVm().uiState.value.tierSalesOpen)
    }

    @Test
    fun `purchaseFeature opens the add-on checkout and re-mints the same product`() = runTest(mainDispatcher) {
        val featureInvoice = invoice(id = 94).copy(
            targetType = PaymentTargetType.FEATURE_PURCHASE,
            targetId = 5
        )
        coEvery { paymentsUseCase.purchaseFeature(FeatureProduct.KEY_ROTATION) } returns
            Result.success(featureInvoice)

        val vm = makeVm()
        vm.purchaseFeature(FeatureProduct.KEY_ROTATION)

        val checkout = vm.uiState.value.checkout
        assertNotNull(checkout)
        assertEquals(PaymentTargetType.FEATURE_PURCHASE, checkout!!.target)
        assertEquals(FeatureProduct.KEY_ROTATION, checkout.featureProduct)

        vm.reMintInvoice()

        coVerify(exactly = 2) { paymentsUseCase.purchaseFeature(FeatureProduct.KEY_ROTATION) }
        coVerify(exactly = 0) { paymentsUseCase.purchaseTier(any()) }
    }

    @Test
    fun `already_entitled on an add-on refreshes instead of opening a checkout`() = runTest(mainDispatcher) {
        coEvery { paymentsUseCase.purchaseFeature(FeatureProduct.DM_FANOUT) } returns
            Result.failure(PaymentsError.AlreadyEntitled("dm_fanout"))

        val vm = makeVm()
        vm.purchaseFeature(FeatureProduct.DM_FANOUT)

        assertNull(vm.uiState.value.checkout)
        coVerify(atLeast = 2) { aliasUseCase.getTierInfo() } // initial + refresh
    }
}
