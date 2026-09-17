package xyz.desent.presentation.ui.settings.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.database.dao.AccountDao
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.payments.model.PaymentsError
import xyz.desent.domain.model.AliasTierInfo
import xyz.desent.domain.model.FeatureProduct
import xyz.desent.domain.model.Invoice
import xyz.desent.domain.model.InvoiceState
import xyz.desent.domain.model.PaymentTargetType
import xyz.desent.domain.usecase.AliasUseCase
import xyz.desent.domain.usecase.KeyRotationUseCase
import xyz.desent.domain.usecase.PaymentsUseCase

/**
 * [RollKeyViewModel] coverage against
 * refs/FROM_email.desent.xyz/ANDROID_KEY_ROTATION.md: fail-closed
 * availability (paid tier OR the one-off add-on), the §3.1b Strike
 * checkout for the key-rotation add-on, and the paid → tier refetch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RollKeyViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var keyRotationUseCase: KeyRotationUseCase
    private lateinit var secureKeyManager: SecureKeyManager
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var accountDao: AccountDao
    private lateinit var aliasUseCase: AliasUseCase
    private lateinit var paymentsUseCase: PaymentsUseCase

    private val npub = "npub1test"

    private fun tier(
        tier: String = "paid",
        keyRotation: Boolean? = true,
        purchased: Boolean? = null
    ) = AliasTierInfo(
        tier = tier,
        cap = null,
        used = 0,
        emailDomain = "desent.xyz",
        keyRotation = keyRotation,
        keyRotationPurchased = purchased
    )

    private fun invoice(state: InvoiceState = InvoiceState.UNPAID) = Invoice(
        id = 61,
        targetType = PaymentTargetType.FEATURE_PURCHASE,
        targetId = 5,
        amountSatoshi = 6_462L,
        amountUsd = "5.00",
        lnInvoice = "lnbc…",
        state = state,
        expiresAt = "2026-09-20T00:00:00+00:00",
        createdAt = null,
        paidAt = null,
        settledAt = null
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        keyRotationUseCase = mockk(relaxed = true)
        secureKeyManager = mockk()
        preferencesManager = mockk()
        accountDao = mockk()
        aliasUseCase = mockk()
        paymentsUseCase = mockk()
        every { preferencesManager.npubKey } returns flowOf(npub)
        coEvery { preferencesManager.getActiveNpub() } returns npub
        coEvery { accountDao.getCustodialUsername(npub) } returns "tester"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = RollKeyViewModel(
        keyRotationUseCase = keyRotationUseCase,
        secureKeyManager = secureKeyManager,
        preferencesManager = preferencesManager,
        accountDao = accountDao,
        aliasUseCase = aliasUseCase,
        paymentsUseCase = paymentsUseCase
    )

    // ---------------- Availability (fail-closed) ----------------

    @Test
    fun `availability is ENABLED only when tier-info says key_rotation`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns Result.success(tier(keyRotation = true))

        val vm = viewModel()

        assertEquals(RollKeyAvailability.ENABLED, vm.uiState.value.availability)
    }

    @Test
    fun `availability fails closed on missing tier-info`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns Result.failure(Exception("offline"))

        val vm = viewModel()

        assertEquals(RollKeyAvailability.LOADING, vm.uiState.value.availability)
    }

    @Test
    fun `capability flag false on a paid account is neutral, never an upsell`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns
            Result.success(tier(tier = "lifetime", keyRotation = false))

        val vm = viewModel()

        assertEquals(RollKeyAvailability.NEUTRAL_LOCKED, vm.uiState.value.availability)
    }

    @Test
    fun `add-on owner sees the neutral state`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns
            Result.success(tier(tier = "free", keyRotation = false, purchased = true))

        val vm = viewModel()

        assertEquals(RollKeyAvailability.NEUTRAL_LOCKED, vm.uiState.value.availability)
    }

    @Test
    fun `free unentitled user sees the upsell`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns
            Result.success(tier(tier = "free", keyRotation = false))

        val vm = viewModel()

        assertEquals(RollKeyAvailability.UPSELL, vm.uiState.value.availability)
    }

    @Test
    fun `null capability flag on a free account fails closed to the upsell`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns
            Result.success(tier(tier = "free", keyRotation = null))

        val vm = viewModel()

        assertEquals(RollKeyAvailability.UPSELL, vm.uiState.value.availability)
    }

    // ---------------- Add-on checkout (§3.1b) ----------------

    @Test
    fun `purchaseKeyRotation opens the checkout sheet for the right product`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns
            Result.success(tier(tier = "free", keyRotation = false))
        coEvery { paymentsUseCase.purchaseFeature(FeatureProduct.KEY_ROTATION) } returns
            Result.success(invoice())

        val vm = viewModel()
        vm.purchaseKeyRotation()

        val checkout = vm.uiState.value.checkout
        assertNotNull(checkout)
        assertEquals(PaymentTargetType.FEATURE_PURCHASE, checkout!!.target)
        assertEquals(FeatureProduct.KEY_ROTATION, checkout.featureProduct)
        assertEquals(61L, checkout.invoice!!.id)
    }

    @Test
    fun `already entitled refetches tier-info instead of opening checkout`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns
            Result.success(tier(tier = "free", keyRotation = false))
        coEvery { paymentsUseCase.purchaseFeature(FeatureProduct.KEY_ROTATION) } returns
            Result.failure(PaymentsError.AlreadyEntitled("key_rotation"))

        val vm = viewModel()
        vm.purchaseKeyRotation()

        assertNull(vm.uiState.value.checkout)
        coVerify(exactly = 2) { aliasUseCase.getTierInfo() } // initial + refetch
    }

    @Test
    fun `paid invoice dismisses checkout and refetches tier-info`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.getTierInfo() } returns
            Result.success(tier(tier = "free", keyRotation = false))
        coEvery { paymentsUseCase.purchaseFeature(FeatureProduct.KEY_ROTATION) } returns
            Result.success(invoice())
        coEvery { paymentsUseCase.getInvoice(61L) } returns Result.success(
            invoice(state = InvoiceState.PAID).copy(settledAt = "2026-09-20T00:01:00+00:00")
        )

        val vm = viewModel()
        vm.purchaseKeyRotation()
        vm.resumePolling()

        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
        vm.pausePolling()

        assertNull(vm.uiState.value.checkout)
        // `state == paid` IS the entitlement signal → availability re-derived.
        coVerify(atLeast = 2) { aliasUseCase.getTierInfo() }
    }
}
