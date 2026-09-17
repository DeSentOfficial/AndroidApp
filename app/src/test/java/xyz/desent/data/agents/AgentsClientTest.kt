package xyz.desent.data.agents

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.agents.model.AgentModeDto
import xyz.desent.data.agents.model.AgentsError

/**
 * Coverage for [AgentsClient] against refs/ANDROID_AI_AGENTS.md §2-§4:
 * list parsing, the npub-only create body, and the typed error mapping
 * (403/409/422/402 vanity_price + agent_cap_reached).
 */
class AgentsClientTest {

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var auth: NostrHttpAuth
    private lateinit var client: AgentsClient

    private val requestSlot = slot<Request>()

    @Before
    fun setUp() {
        okHttpClient = mockk(relaxed = true)
        auth = mockk()
        coEvery { auth.buildAuthHeader(any(), any(), any()) } returns
            Result.success("Nostr test-event")
        client = AgentsClient(okHttpClient, auth)
    }

    private fun mockResponse(code: Int, body: String): Response {
        val rb = mockk<ResponseBody>(relaxed = true)
        every { rb.string() } returns body
        val resp = mockk<Response>(relaxed = true)
        every { resp.isSuccessful } returns (code in 200..299)
        every { resp.code } returns code
        every { resp.body } returns rb
        return resp
    }

    private fun enqueue(resp: Response) {
        val call = mockk<Call>()
        every { call.execute() } returns resp
        every { okHttpClient.newCall(capture(requestSlot)) } returns call
    }

    private fun errorBody(error: String, extra: String = ""): String =
        """{"detail":{"error":"$error"$extra}}"""

    @Test
    fun `list parses agents and cap snapshot`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"agents":[{"id":3,"agent_pubkey":"aa","address_local":"claw",
                    "address_domain":"desent.xyz","address":"claw@desent.xyz",
                    "label":"OpenClaw","mode":"respond",
                    "trigger_keywords":["schedule"],"policy_note":"Reply",
                    "status":"active","created_at":"2026-01-01"}],
                    "used":1,"cap":5,"tier":"paid","enabled":true,
                    "email_domain":"desent.xyz"}"""
            )
        )

        val result = client.listAgents()

        assertTrue(result.isSuccess)
        val resp = result.getOrNull()!!
        assertEquals(1, resp.agents.size)
        assertEquals("claw@desent.xyz", resp.agents[0].address)
        assertEquals("respond", resp.agents[0].mode)
        assertEquals(5, resp.cap)
        assertTrue(resp.enabled)
    }

    @Test
    fun `create sends only the pubkey never a secret`() = runBlocking {
        enqueue(
            mockResponse(
                201,
                """{"id":3,"agent_pubkey":"aa","address_local":"claw",
                    "address":"claw@desent.xyz","mode":"digest"}"""
            )
        )

        val result = client.createAgent("aa", "claw", AgentModeDto.DIGEST, "OpenClaw")

        assertTrue(result.isSuccess)
        val body = okio.Buffer().also { requestSlot.captured.body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("\"agent_pubkey\":\"aa\""))
        assertTrue(body.contains("\"address_local\":\"claw\""))
        assertTrue(body.contains("\"mode\":\"digest\""))
        // Security invariant (§3): no secret material in the POST body.
        assertFalse(body.contains("ncryptsec", ignoreCase = true))
        assertFalse(body.contains("passphrase"))
        assertFalse(body.contains("priv"))
    }

    @Test
    fun `update patches mode keywords policy persona and profile`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"id":3,"agent_pubkey":"aa","address_local":"claw","mode":"respond"}"""
            )
        )

        val result = client.updateAgent(
            3, AgentModeDto.RESPOND, listOf("invoice"), "Note",
            systemPrompt = "You are Pearl.",
            displayName = "Pearl",
            pictureUrl = "https://example.com/p.png",
            about = "Warm concise assistant"
        )

        assertTrue(result.isSuccess)
        val req = requestSlot.captured
        assertEquals("https://desent.xyz/api/agents/3", req.url.toString())
        val body = okio.Buffer().also { req.body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("\"mode\":\"respond\""))
        assertTrue(body.contains("invoice"))
        assertTrue(body.contains("\"system_prompt\":\"You are Pearl.\""))
        assertTrue(body.contains("\"display_name\":\"Pearl\""))
        assertTrue(body.contains("\"picture_url\":\"https://example.com/p.png\""))
        assertTrue(body.contains("\"about\":\"Warm concise assistant\""))
    }

    @Test
    fun `delete hits the id path`() = runBlocking {
        enqueue(mockResponse(200, """{"status":"deleted","id":7}"""))

        val result = client.deleteAgent(7)

        assertTrue(result.isSuccess)
        assertEquals(
            "https://desent.xyz/api/agents/7",
            requestSlot.captured.url.toString()
        )
    }

    @Test
    fun `403 agents_disabled maps to Disabled`() = runBlocking {
        enqueue(mockResponse(403, errorBody("agents_disabled")))
        assertTrue(client.listAgents().exceptionOrNull() is AgentsError.Disabled)
    }

    @Test
    fun `409 maps taken and already registered`() = runBlocking {
        enqueue(mockResponse(409, errorBody("taken")))
        assertTrue(client.listAgents().exceptionOrNull() is AgentsError.Taken)

        enqueue(mockResponse(409, errorBody("agent_already_registered")))
        assertTrue(client.listAgents().exceptionOrNull() is AgentsError.AlreadyRegistered)
    }

    @Test
    fun `422 maps validation errors`() = runBlocking {
        enqueue(mockResponse(422, errorBody("invalid_local_part")))
        assertTrue(client.createAgent("aa", "bad part", AgentModeDto.DIGEST, null)
            .exceptionOrNull() is AgentsError.InvalidLocalPart)

        enqueue(mockResponse(422, errorBody("invalid_pubkey")))
        assertTrue(client.createAgent("zz", "claw", AgentModeDto.DIGEST, null)
            .exceptionOrNull() is AgentsError.InvalidPubkey)

        enqueue(mockResponse(422, errorBody("invalid_mode")))
        assertTrue(client.createAgent("aa", "claw", AgentModeDto.DIGEST, null)
            .exceptionOrNull() is AgentsError.InvalidMode)
    }

    @Test
    fun `402 vanity_price carries ladder`() = runBlocking {
        enqueue(
            mockResponse(
                402,
                errorBody("vanity_price", ""","length":3,"price_sats":50000,
                    "ladder":{"3":50000,"4":25000},"free_length":8""")
            )
        )

        val err = client.createAgent("aa", "abc", AgentModeDto.DIGEST, null)
            .exceptionOrNull() as AgentsError.VanityPrice
        assertEquals(3, err.length)
        assertEquals(50_000L, err.priceSats)
        assertEquals(8, err.freeLength)
    }

    @Test
    fun `402 agent_cap_reached carries cap snapshot`() = runBlocking {
        enqueue(
            mockResponse(402, errorBody("agent_cap_reached", ",\"cap\":2,\"used\":2,\"tier\":\"free\""))
        )

        val err = client.createAgent("aa", "claw", AgentModeDto.DIGEST, null)
            .exceptionOrNull() as AgentsError.CapReached
        assertEquals(2, err.cap)
        assertEquals("free", err.tier)
    }
}
