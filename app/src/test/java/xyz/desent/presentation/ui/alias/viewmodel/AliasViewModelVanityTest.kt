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
import xyz.desent.data.vanity.model.VanityError
import xyz.desent.domain.model.Alias
import xyz.desent.domain.model.AliasServiceConfig
import xyz.desent.domain.model.AliasTierInfo
import xyz.desent.domain.model.VanityConfig
import xyz.desent.domain.model.VanityRequest
import xyz.desent.domain.model.VanityRequestStatus
import xyz.desent.domain.model.PaymentsConfig
import xyz.desent.domain.usecase.AliasUseCase
import xyz.desent.domain.usecase.PaymentsUseCase

/**
 * [AliasViewModel] vanity/slot behavior against
 * refs/FromServer/ANDROID_VANITY_PRICING.md §4-6 (legacy operator-approval
 * flows — Strike disabled).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AliasViewModelVanityTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var aliasUseCase: AliasUseCase
    private lateinit var paymentsUseCase: PaymentsUseCase

    private val tierAtCap = AliasTierInfo(
        tier = "free", cap = 3, used = 3, emailDomain = "desent.xyz"
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
                    ladder = mapOf(1 to 250_000L, 3 to 50_000L, 7 to 2_000L)
                ),
                slotPriceSats = 5_000L
            )
        )
        // Strike disabled: every flow below takes the legacy approval path.
        coEvery { paymentsUseCase.getConfig() } returns Result.success(PaymentsConfig())
        coEvery { aliasUseCase.listAliases() } returns Result.success(
            emptyList<Alias>() to tierAtCap
        )
        coEvery { aliasUseCase.listVanityRequests() } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
        Dispatchers.resetMain()
    }

    private fun makeVm() = AliasViewModel(aliasUseCase, paymentsUseCase)

    @Test
    fun `init loads config with ladder for the price hint`() = runTest(mainDispatcher) {
        val vm = makeVm()

        val cfg = vm.uiState.value.vanityConfig
        assertNotNull(cfg)
        assertEquals(50_000L, cfg!!.priceOf("abc"))
        assertNull(cfg.priceOf("alice123"))
        assertEquals(5_000L, vm.uiState.value.slotPriceSats)
    }

    @Test
    fun `createAlias is never blocked by the client-side cap`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.createAlias(any(), any()) } returns Result.success(
            Alias(1, "new@desent.xyz", "new", null, true, "2026-08-17")
        )

        val vm = makeVm() // tier: 3 of 3 used — at cap
        vm.createAlias("newalias", null)

        coVerify(exactly = 1) { aliasUseCase.createAlias("newalias", null) }
        // loadAll() re-runs after success.
        coVerify(atLeast = 2) { aliasUseCase.listAliases() }
    }

    @Test
    fun `402 vanity_price opens prompt and refreshes cached ladder`() = runTest(mainDispatcher) {
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
        assertEquals("abc", prompt!!.localPart)
        assertEquals(50_000L, prompt.priceSats)
        // Opportunistic ladder refresh from the 402 body (§2).
        assertEquals(50_000L, vm.uiState.value.vanityConfig?.priceOf("abc"))
        assertNull(vm.uiState.value.toast)
    }

    @Test
    fun `402 slot_price opens purchase prompt instead of toast`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.createAlias(any(), any()) } returns Result.failure(
            AliasError.SlotPrice(
                tier = "free", freeCap = 3, slotsOwned = 0, cap = 3, used = 3, priceSats = 5_000L
            )
        )

        val vm = makeVm()
        vm.createAlias("newalias", null)

        val prompt = vm.uiState.value.slotPricePrompt
        assertNotNull(prompt)
        assertEquals(5_000L, prompt!!.priceSats)
        assertEquals(3, prompt.cap)
        assertNull(vm.uiState.value.toast)
    }

    @Test
    fun `requestVanityAddress posts request and reloads badges`() = runTest(mainDispatcher) {
        coEvery {
            aliasUseCase.createVanityRequest("abc", "desent.xyz", "alias")
        } returns Result.success(
            VanityRequest(
                id = 7, localPart = "abc", domain = "desent.xyz", kind = "alias",
                quotedSatoshi = 50_000L, status = VanityRequestStatus.PENDING,
                requestedAt = null, decidedAt = null, note = null
            )
        )

        val vm = makeVm()
        vm.requestVanityAddress("abc", "desent.xyz")

        coVerify(atLeast = 2) { aliasUseCase.listVanityRequests() }
        assertTrue(vm.uiState.value.toast?.contains("pending operator approval") == true)
        assertNull(vm.uiState.value.vanityPricePrompt)
    }

    @Test
    fun `requestVanityAddress surfaces request_exists as toast`() = runTest(mainDispatcher) {
        coEvery {
            aliasUseCase.createVanityRequest("abc", any(), any())
        } returns Result.failure(VanityError.RequestExists)

        val vm = makeVm()
        vm.requestVanityAddress("abc", "desent.xyz")

        assertEquals("This name is already being requested", vm.uiState.value.toast)
    }

    @Test
    fun `approved request pre-fills the create dialog`() = runTest(mainDispatcher) {
        val vm = makeVm()
        val approved = VanityRequest(
            id = 7, localPart = "abc", domain = "desent.xyz", kind = "alias",
            quotedSatoshi = 50_000L, status = VanityRequestStatus.APPROVED,
            requestedAt = null, decidedAt = null, note = null
        )

        vm.startClaimFromRequest(approved)

        assertEquals("abc", vm.uiState.value.prefillLocalPart)
        vm.onPrefillConsumed()
        assertNull(vm.uiState.value.prefillLocalPart)
    }

    @Test
    fun `buySlots requests pack and clears prompt`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.purchaseSlots(2) } returns Result.success(
            xyz.desent.domain.model.AliasSlotPurchase(
                id = 9, quantity = 2, quotedSatoshi = 10_000L,
                status = VanityRequestStatus.PENDING,
                requestedAt = null, decidedAt = null, note = null
            )
        )

        val vm = makeVm()
        vm.buySlots(2)

        assertTrue(vm.uiState.value.toast?.contains("Slot pack requested") == true)
        assertNull(vm.uiState.value.slotPricePrompt)
        assertFalse(vm.uiState.value.isBuyingSlots)
    }

    @Test
    fun `openSlotPurchase fetches live slot math for the sheet`() = runTest(mainDispatcher) {
        coEvery { aliasUseCase.getSlotStatus() } returns Result.success(
            xyz.desent.domain.model.AliasSlotsStatus(
                tier = "free", freeCap = 3, slotsOwned = 5, cap = 8, priceSats = 5_000L,
                purchases = emptyList()
            )
        )

        val vm = makeVm()
        vm.openSlotPurchase()

        val prompt = vm.uiState.value.slotPricePrompt
        assertNotNull(prompt)
        assertEquals(8, prompt!!.cap)
        assertEquals(5, prompt.slotsOwned)
        assertEquals(5_000L, prompt.priceSats)
    }
}
