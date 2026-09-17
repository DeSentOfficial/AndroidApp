package xyz.desent.presentation.ui.agents.viewmodel

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
import xyz.desent.data.agents.model.AgentsError
import xyz.desent.data.vanity.model.VanityError
import xyz.desent.domain.model.Agent
import xyz.desent.domain.model.AgentMode
import xyz.desent.domain.model.AgentsSnapshot
import xyz.desent.domain.model.AliasTierInfo
import xyz.desent.domain.model.Invoice
import xyz.desent.domain.model.InvoiceState
import xyz.desent.domain.model.PaymentTargetType
import xyz.desent.domain.model.PaymentsConfig
import xyz.desent.domain.model.VanityRequest
import xyz.desent.domain.model.VanityRequestStatus
import xyz.desent.domain.usecase.AgentsUseCase
import xyz.desent.domain.usecase.AliasUseCase
import xyz.desent.domain.usecase.PaymentsUseCase

/**
 * Short-address lightning checkout in [AgentsViewModel] — the agents 402
 * deep-links the alias vanity flow (ANDROID_AI_AGENTS.md §3, ANDROID_PAYMENTS
 * .md §5): pay the invoice, then the create replays automatically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentsViewModelCheckoutTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var agentsUseCase: AgentsUseCase
    private lateinit var aliasUseCase: AliasUseCase
    private lateinit var paymentsUseCase: PaymentsUseCase

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        agentsUseCase = mockk()
        aliasUseCase = mockk()
        paymentsUseCase = mockk()

        // Real key generation runs scrypt on Dispatchers.Default — a real
        // dispatcher hop the virtual-time scheduler can't await. Stub it.
        io.mockk.mockkObject(xyz.desent.data.agents.AgentKeyFactory)
        io.mockk.coEvery { xyz.desent.data.agents.AgentKeyFactory.generate() } returns
            Result.success(
                xyz.desent.data.agents.AgentKeyFactory.Generated(
                    agentPubkeyHex = "b".repeat(64),
                    connectionCode = xyz.desent.domain.model.AgentConnectionCode(
                        ncryptsec = "ncryptsec1stub", passphrase = "stub-passphrase"
                    )
                )
            )

        coEvery { aliasUseCase.getTierInfo() } returns Result.success(
            AliasTierInfo(
                tier = "free", cap = 3, used = 0, emailDomain = "desent.xyz", agents = true
            )
        )
        coEvery { agentsUseCase.listAgents() } returns Result.success(snapshot())
        coEvery { paymentsUseCase.getConfig() } returns Result.success(
            PaymentsConfig(strikeEnabled = true)
        )
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
        Dispatchers.resetMain()
    }

    private fun makeVm() = AgentsViewModel(agentsUseCase, aliasUseCase, paymentsUseCase)

    private fun kotlinx.coroutines.test.TestScope.settle() {
        testScheduler.advanceUntilIdle()
    }

    private fun snapshot(agents: List<Agent> = emptyList()) = AgentsSnapshot(
        agents = agents, used = agents.size, cap = 3, tier = "free",
        enabled = true, emailDomain = "desent.xyz"
    )

    private fun agent() = Agent(
        id = 1, agentPubkey = "a".repeat(64), addressLocal = "abc",
        addressDomain = "desent.xyz", address = "abc@desent.xyz",
        label = "Helper", mode = AgentMode.DIGEST, triggerKeywords = emptyList(),
        policyNote = null, status = "active", createdAt = null, lastUsedAt = null
    )

    private fun pendingRequest() = VanityRequest(
        id = 7, localPart = "abc", domain = "desent.xyz", kind = "alias",
        quotedSatoshi = 50_000L, status = VanityRequestStatus.PENDING,
        requestedAt = null, decidedAt = null, note = null
    )

    private fun invoice(state: InvoiceState = InvoiceState.UNPAID, settledAt: String? = null) =
        Invoice(
            id = 42, targetType = PaymentTargetType.VANITY_REQUEST, targetId = 7,
            amountSatoshi = 50_000L, amountUsd = "50.00", lnInvoice = "lnbc500u1p3q",
            state = state, expiresAt = "2026-09-16T18:04:00+00:00",
            createdAt = null, paidAt = null, settledAt = settledAt
        )

    private val vanity402 = AgentsError.VanityPrice(
        length = 3, priceSats = 50_000L, ladder = mapOf("3" to 50_000L), freeLength = 8
    )

    @Test
    fun `createAgent 402 with strike on shows the payment prompt`() = runTest(mainDispatcher) {
        coEvery { agentsUseCase.createAgent(any(), "abc", AgentMode.DIGEST, "Helper") } returns
            Result.failure(vanity402)

        val vm = makeVm()
        settle()

        vm.createAgent("Helper", "abc", AgentMode.DIGEST)

        val prompt = vm.uiState.value.vanityPrompt
        assertNotNull(prompt)
        assertEquals(50_000L, prompt!!.priceSats)
        assertEquals("Helper", prompt.label)
        assertEquals(AgentMode.DIGEST, prompt.mode)
        assertTrue(vm.uiState.value.paymentsStrikeEnabled)
        assertNull(vm.uiState.value.checkout)
    }

    @Test
    fun `payVanityAddress creates the request and opens checkout`() = runTest(mainDispatcher) {
        coEvery { agentsUseCase.createAgent(any(), "abc", AgentMode.DIGEST, "Helper") } returns
            Result.failure(vanity402)
        coEvery { aliasUseCase.createVanityRequest("abc", null, "alias") } returns
            Result.success(pendingRequest())
        coEvery { paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7) } returns
            Result.success(invoice())

        val vm = makeVm()
        settle()
        vm.createAgent("Helper", "abc", AgentMode.DIGEST)
        vm.payVanityAddress()
        settle()

        val checkout = vm.uiState.value.checkout
        assertNotNull(checkout)
        assertEquals(42L, checkout!!.invoice?.id)
        assertEquals("abc", checkout.claimLocalPart)
        assertNull(vm.uiState.value.vanityPrompt) // dialog replaced by the sheet
        coVerify(exactly = 1) { aliasUseCase.createVanityRequest("abc", null, "alias") }
    }

    @Test
    fun `paid invoice replays the agent create`() = runTest(mainDispatcher) {
        coEvery { agentsUseCase.createAgent(any(), "abc", AgentMode.DIGEST, "Helper") } returns
            Result.failure(vanity402) andThen Result.success(agent())
        coEvery { aliasUseCase.createVanityRequest("abc", null, "alias") } returns
            Result.success(pendingRequest())
        coEvery { paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7) } returns
            Result.success(invoice())
        coEvery { paymentsUseCase.getInvoice(42) } returns Result.success(
            invoice(state = InvoiceState.PAID, settledAt = "2026-09-16T17:40:01+00:00")
        )

        val vm = makeVm()
        settle()
        vm.createAgent("Helper", "abc", AgentMode.DIGEST)
        vm.payVanityAddress()
        settle()
        vm.resumePolling()

        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()

        assertNull(vm.uiState.value.checkout) // auto-dismissed on settle
        assertNotNull(vm.uiState.value.handoff) // the create succeeded → handoff
        coVerify(exactly = 2) {
            agentsUseCase.createAgent(any(), "abc", AgentMode.DIGEST, "Helper")
        }
    }

    @Test
    fun `request_exists reuses own pending row and mints its invoice`() = runTest(mainDispatcher) {
        coEvery { agentsUseCase.createAgent(any(), "abc", AgentMode.DIGEST, "Helper") } returns
            Result.failure(vanity402)
        coEvery { aliasUseCase.createVanityRequest("abc", null, "alias") } returns
            Result.failure(VanityError.RequestExists)
        coEvery { aliasUseCase.listVanityRequests() } returns
            Result.success(listOf(pendingRequest()))
        coEvery { paymentsUseCase.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7) } returns
            Result.success(invoice())

        val vm = makeVm()
        settle()
        vm.createAgent("Helper", "abc", AgentMode.DIGEST)
        vm.payVanityAddress()
        settle()

        val checkout = vm.uiState.value.checkout
        assertNotNull(checkout)
        assertEquals(42L, checkout!!.invoice?.id)
    }

    @Test
    fun `strike off keeps the info-only prompt`() = runTest(mainDispatcher) {
        coEvery { paymentsUseCase.getConfig() } returns Result.success(PaymentsConfig())
        coEvery { agentsUseCase.createAgent(any(), "abc", AgentMode.DIGEST, "Helper") } returns
            Result.failure(vanity402)

        val vm = makeVm()
        settle()
        vm.createAgent("Helper", "abc", AgentMode.DIGEST)

        assertNotNull(vm.uiState.value.vanityPrompt)
        assertTrue(!vm.uiState.value.paymentsStrikeEnabled)
        coVerify(exactly = 0) { aliasUseCase.createVanityRequest(any(), any(), any()) }
    }
}
