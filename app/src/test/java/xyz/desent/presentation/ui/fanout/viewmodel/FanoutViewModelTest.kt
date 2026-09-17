package xyz.desent.presentation.ui.fanout.viewmodel

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.payments.model.PaymentsError
import xyz.desent.domain.model.AliasTierInfo
import xyz.desent.domain.model.FanoutImportResult
import xyz.desent.domain.model.FanoutNipGap
import xyz.desent.domain.model.FanoutReconcile
import xyz.desent.domain.model.FanoutReconcileMirror
import xyz.desent.domain.model.FanoutRelayCheck
import xyz.desent.domain.model.FanoutRelayCheckPill
import xyz.desent.domain.model.FanoutRelayEntry
import xyz.desent.domain.model.FeatureProduct
import xyz.desent.domain.model.Invoice
import xyz.desent.domain.model.InvoiceState
import xyz.desent.domain.model.PaymentTargetType
import xyz.desent.domain.model.PremiumRequiredException
import xyz.desent.domain.model.SecurityConfig
import xyz.desent.domain.repository.SecurityConfigRepository
import xyz.desent.domain.usecase.AliasUseCase
import xyz.desent.domain.usecase.FanoutUseCase
import xyz.desent.domain.usecase.PaymentsUseCase

/**
 * [FanoutViewModel] coverage against
 * refs/FROM_email.desent.xyz/ANDROID_DM_FANOUT.md: fail-closed availability,
 * the premium-gated opt-in, the one-call Strike checkout, and the relay
 * editor's canonicalization.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FanoutViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var fanoutUseCase: FanoutUseCase
    private lateinit var aliasUseCase: AliasUseCase
    private lateinit var paymentsUseCase: PaymentsUseCase
    private lateinit var securityConfigRepository: SecurityConfigRepository
    private lateinit var preferencesManager: PreferencesManager

    private val npub = "npub1test"

    private fun tier(
        tier: String = "paid",
        dmFanout: Boolean? = true,
        purchased: Boolean? = null
    ) = AliasTierInfo(
        tier = tier,
        cap = null,
        used = 0,
        emailDomain = "desent.xyz",
        dmFanout = dmFanout,
        dmFanoutPurchased = purchased
    )

    private fun invoice(state: InvoiceState = InvoiceState.UNPAID) = Invoice(
        id = 51,
        targetType = PaymentTargetType.FEATURE_PURCHASE,
        targetId = 3,
        amountSatoshi = 6_462L,
        amountUsd = "5.00",
        lnInvoice = "lnbc…",
        state = state,
        expiresAt = "2026-09-01T00:00:00+00:00",
        createdAt = null,
        paidAt = null,
        settledAt = null
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        fanoutUseCase = mockk(relaxed = true)
        aliasUseCase = mockk()
        paymentsUseCase = mockk()
        securityConfigRepository = mockk(relaxed = true)
        preferencesManager = mockk()
        every { preferencesManager.npubKey } returns flowOf(npub)
        every { securityConfigRepository.observe(npub) } returns flowOf(SecurityConfig())
        coEvery { fanoutUseCase.fetchRelayList() } returns Result.success(emptyList())
        coEvery { fanoutUseCase.getHealth() } returns Result.failure(Exception("offline"))
        coEvery { fanoutUseCase.fetchRelayInfo(any()) } returns Result.success(null)
        coEvery { fanoutUseCase.relayCheck(any()) } returns Result.failure(Exception("offline"))
        coEvery { fanoutUseCase.reconcile() } returns Result.failure(Exception("offline"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = FanoutViewModel(
        fanoutUseCase = fanoutUseCase,
        aliasUseCase = aliasUseCase,
        paymentsUseCase = paymentsUseCase,
        securityConfigRepository = securityConfigRepository,
        preferencesManager = preferencesManager
    )

    // ---------------- Availability (§3, fail-closed) ----------------

    @Test
    fun `availability is ENABLED only when tier-info says dm_fanout`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns Result.success(tier(dmFanout = true))

        val vm = viewModel()

        assertEquals(FanoutAvailability.ENABLED, vm.uiState.value.availability)
    }

    @Test
    fun `availability fails closed on missing tier-info`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns Result.failure(Exception("offline"))

        val vm = viewModel()

        assertEquals(FanoutAvailability.LOADING, vm.uiState.value.availability)
    }

    @Test
    fun `free unentitled user sees the upsell`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns Result.success(tier(tier = "free", dmFanout = false))

        val vm = viewModel()

        assertEquals(FanoutAvailability.UPSELL, vm.uiState.value.availability)
    }

    @Test
    fun `add-on owner sees the neutral state, never an upsell`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns
            Result.success(tier(tier = "free", dmFanout = false, purchased = true))

        val vm = viewModel()

        assertEquals(FanoutAvailability.NEUTRAL_LOCKED, vm.uiState.value.availability)
    }

    @Test
    fun `paid-tier user sees the neutral state`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns Result.success(tier(tier = "lifetime", dmFanout = false))

        val vm = viewModel()

        assertEquals(FanoutAvailability.NEUTRAL_LOCKED, vm.uiState.value.availability)
    }

    // ---------------- Opt-in toggle (§2) ----------------

    @Test
    fun `setDmFanout publishes the partial save`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns Result.success(tier())
        coEvery { securityConfigRepository.setDmFanout(npub, true) } returns Result.success(Unit)

        val vm = viewModel()
        vm.setDmFanout(true)

        assertTrue(vm.uiState.value.dmFanoutEnabled)
        coVerify { securityConfigRepository.setDmFanout(npub, true) }
    }

    @Test
    fun `premium rejection reverts the switch and shows the prompt`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns Result.success(tier())
        coEvery { securityConfigRepository.setDmFanout(npub, true) } returns Result.failure(
            PremiumRequiredException("premium required: fan-out needs a paid plan")
        )

        val vm = viewModel()
        vm.setDmFanout(true)

        assertFalse(vm.uiState.value.dmFanoutEnabled)
        assertTrue(vm.uiState.value.premiumPrompt)
        // No auto-retry of a premium-rejected publish.
        coVerify(exactly = 1) { securityConfigRepository.setDmFanout(any(), any()) }
    }

    @Test
    fun `toggle failure reverts with a plain error`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns Result.success(tier())
        coEvery { securityConfigRepository.setDmFanout(npub, false) } returns Result.failure(
            Exception("no ack")
        )

        val vm = viewModel()
        vm.setDmFanout(false)

        assertTrue(vm.uiState.value.dmFanoutEnabled) // reverted back
        assertFalse(vm.uiState.value.premiumPrompt)
        assertNotNull(vm.uiState.value.toggleError)
    }

    // ---------------- Strike checkout (§3.1) ----------------

    @Test
    fun `purchaseFanout opens the checkout sheet`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns Result.success(tier(tier = "free", dmFanout = false))
        coEvery { paymentsUseCase.purchaseFeature(FeatureProduct.DM_FANOUT) } returns Result.success(invoice())

        val vm = viewModel()
        vm.purchaseFanout()

        val checkout = vm.uiState.value.checkout
        assertNotNull(checkout)
        assertEquals(PaymentTargetType.FEATURE_PURCHASE, checkout!!.target)
        assertEquals(FeatureProduct.DM_FANOUT, checkout.featureProduct)
        assertEquals(51L, checkout.invoice!!.id)
    }

    @Test
    fun `already entitled refetches tier-info instead of opening checkout`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns Result.success(tier(tier = "free", dmFanout = false))
        coEvery { paymentsUseCase.purchaseFeature(FeatureProduct.DM_FANOUT) } returns
            Result.failure(PaymentsError.AlreadyEntitled("dm_fanout"))
        coEvery { fanoutUseCase.getHealth() } returns Result.failure(Exception("offline"))

        val vm = viewModel()
        vm.purchaseFanout()

        assertNull(vm.uiState.value.checkout)
        coVerify(exactly = 2) { aliasUseCase.getTierInfo() } // initial + refetch
    }

    @Test
    fun `paid invoice dismisses checkout and refetches tier-info`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.getTierInfo() } returns Result.success(tier(tier = "free", dmFanout = false))
        coEvery { paymentsUseCase.purchaseFeature(FeatureProduct.DM_FANOUT) } returns Result.success(invoice())
        coEvery { paymentsUseCase.getInvoice(51L) } returns Result.success(
            invoice(state = InvoiceState.PAID).copy(settledAt = "2026-09-01T00:01:00+00:00")
        )

        val vm = viewModel()
        vm.purchaseFanout()
        vm.resumePolling()

        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
        vm.pausePolling()

        assertNull(vm.uiState.value.checkout)
        assertNotNull(vm.uiState.value.toast)
        // `state == paid` IS the entitlement signal → availability re-derived.
        coVerify(atLeast = 2) { aliasUseCase.getTierInfo() }
    }

    // ---------------- Relay editor ----------------

    @Test
    fun `addRelay canonicalizes input through the nestled WSS check`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns Result.success(tier())

        val vm = viewModel()
        vm.addRelay("relay.example.com")

        assertEquals(
            listOf(FanoutRelayEntry("wss://relay.example.com")),
            vm.uiState.value.relayEntries
        )
        assertTrue(vm.uiState.value.relayListDirty)
    }

    @Test
    fun `addRelay rejects duplicates and garbage`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns Result.success(tier())

        val vm = viewModel()
        vm.addRelay("wss://relay.example.com")
        vm.addRelay("wss://relay.example.com/") // dup after canonicalize
        vm.addRelay("   ")                       // no usable host

        assertEquals(1, vm.uiState.value.relayEntries.size)
        assertNotNull(vm.uiState.value.toast)
    }

    @Test
    fun `relay list loads with relay info fired per entry`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns Result.success(tier())
        coEvery { fanoutUseCase.fetchRelayList() } returns Result.success(
            listOf(FanoutRelayEntry("wss://relay.example.com"))
        )

        val vm = viewModel()
        // Advanced load is explicit now — init only pulls the toggle core.
        vm.load()

        assertEquals(
            listOf(FanoutRelayEntry("wss://relay.example.com")),
            vm.uiState.value.relayEntries
        )
        assertFalse(vm.uiState.value.relayListDirty)
        coVerify { fanoutUseCase.fetchRelayInfo("wss://relay.example.com") }
    }

    @Test
    fun `relay info lands in the row state for the chips and ping`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns Result.success(tier())
        coEvery { fanoutUseCase.fetchRelayInfo("wss://relay.example.com") } returns
            Result.success(
                xyz.desent.domain.model.FanoutRelayInfo(
                    url = "wss://relay.example.com",
                    name = "Example Relay",
                    iconUrl = "https://relay.example.com/icon.png",
                    rttOpenMs = 424,
                    uptime7d = 0.99,
                    supportedNips = setOf(1, 9, 17, 59)
                )
            )

        val vm = viewModel()
        vm.addRelay("relay.example.com")

        val info = vm.uiState.value.relayInfo["wss://relay.example.com"]
        assertNotNull(info)
        assertEquals(424L, info!!.rttOpenMs)
        assertEquals(
            xyz.desent.domain.model.FanoutNipSupportStatus.SUPPORTED,
            info.nipSupport
        )
    }

    // ---------------- Mirror surface (§6) ----------------

    @Test
    fun `relay check verdict lands in the relay row state`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns Result.success(tier())
        coEvery { fanoutUseCase.relayCheck("wss://relay.example.com") } returns Result.success(
            FanoutRelayCheck(
                url = "wss://relay.example.com",
                usable = true,
                missingNips = listOf(FanoutNipGap(40, "deletes wouldn't propagate"))
            )
        )

        val vm = viewModel()
        vm.addRelay("relay.example.com")

        val check = vm.uiState.value.relayChecks["wss://relay.example.com"]
        assertNotNull(check)
        assertEquals(FanoutRelayCheckPill.AMBER, check!!.pill) // usable but a NIP is missing
        assertTrue(check.advisoryText!!.contains("NIP-40"))
    }

    @Test
    fun `reconcile shows per-mirror gaps and import consumes them`() = runTest {
        coEvery { aliasUseCase.getTierInfo() } returns Result.success(tier())
        coEvery { fanoutUseCase.reconcile() } returns Result.success(
            FanoutReconcile(
                mirrors = listOf(
                    FanoutReconcileMirror(
                        url = "wss://mirror.example.com",
                        seen = 12,
                        missingLocallyIds = listOf("aa", "bb"),
                        locallyDeletedCount = 1
                    )
                )
            )
        )
        coEvery { fanoutUseCase.importWraps(listOf("aa", "bb")) } returns Result.success(
            FanoutImportResult(imported = 2, skipped = 0)
        )

        val vm = viewModel()
        vm.reconcileMirrors()

        val report = vm.uiState.value.reconcileReport
        assertNotNull(report)
        assertEquals(listOf("aa", "bb"), report!!.importableIds)

        vm.importMissing()

        assertNull(vm.uiState.value.reconcileReport)
        assertEquals("Imported 2 new messages. Check your inbox.", vm.uiState.value.toast)
        coVerify(exactly = 1) { fanoutUseCase.importWraps(listOf("aa", "bb")) }
    }
}
