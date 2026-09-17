package xyz.desent.data.alias

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.alias.model.AliasError

/**
 * Vanity/slot coverage for [AliasClient] against
 * refs/FromServer/ANDROID_VANITY_PRICING.md §1, §2, §6.
 */
class AliasClientVanityTest {

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var auth: NostrHttpAuth
    private lateinit var client: AliasClient

    private val requestSlot = slot<Request>()

    @Before
    fun setUp() {
        okHttpClient = mockk(relaxed = true)
        auth = mockk()
        coEvery { auth.buildAuthHeader(any(), any(), any()) } returns
            Result.success("Nostr test-event")
        client = AliasClient(okHttpClient, auth)
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
    fun `config parses ladder, free length and slot price`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"email_domain":"desent.xyz","email_domains":["desent.xyz"],
                    "primary":"desent.xyz","vanity_free_length":8,
                    "vanity_ladder":{"1":250000,"2":100000,"3":50000,"4":25000,
                    "5":10000,"6":5000,"7":2000},"slot_price_sats":5000}"""
            )
        )

        val result = client.getConfig()

        assertTrue(result.isSuccess)
        val cfg = result.getOrNull()!!
        assertEquals("desent.xyz", cfg.emailDomain)
        assertEquals(8, cfg.vanityFreeLength)
        assertEquals(7, cfg.vanityLadder.size)
        assertEquals(250_000L, cfg.vanityLadder["1"])
        assertEquals(50_000L, cfg.vanityLadder["3"])
        assertEquals(5_000L, cfg.slotPriceSats)
        // Public endpoint — no auth burned.
        assertNull(requestSlot.captured.header("Authorization"))
    }

    @Test
    fun `tier-info parses freeCap and slotsOwned`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"tier":"free","cap":8,"used":3,"free_cap":3,"slots_owned":5,
                    "email_domain":"desent.xyz"}"""
            )
        )

        val result = client.getTierInfo()

        assertTrue(result.isSuccess)
        val tier = result.getOrNull()!!
        assertEquals(8, tier.cap)
        assertEquals(3, tier.freeCap)
        assertEquals(5, tier.slotsOwned)
    }

    @Test
    fun `402 vanity_price carries server quote and ladder`() = runBlocking {
        enqueue(
            mockResponse(
                402,
                """{"detail":{"error":"vanity_price","length":3,"price_sats":50000,
                    "ladder":{"1":250000,"3":50000},"free_length":8}}"""
            )
        )

        val error = client.createAlias("abc", null).exceptionOrNull()

        assertTrue(error is AliasError.VanityPrice)
        error as AliasError.VanityPrice
        assertEquals(3, error.length)
        assertEquals(50_000L, error.priceSats)
        assertEquals(mapOf("1" to 250_000L, "3" to 50_000L), error.ladder)
        assertEquals(8, error.freeLength)
    }

    @Test
    fun `402 slot_price carries cap math`() = runBlocking {
        enqueue(
            mockResponse(
                402,
                """{"detail":{"error":"slot_price","tier":"free","free_cap":3,
                    "slots_owned":0,"cap":3,"used":3,"price_sats":5000}}"""
            )
        )

        val error = client.createAlias("newalias", null).exceptionOrNull()

        assertTrue(error is AliasError.SlotPrice)
        error as AliasError.SlotPrice
        assertEquals("free", error.tier)
        assertEquals(3, error.freeCap)
        assertEquals(0, error.slotsOwned)
        assertEquals(3, error.cap)
        assertEquals(3, error.used)
        assertEquals(5_000L, error.priceSats)
    }

    @Test
    fun `402 unknown error code maps to Server`() = runBlocking {
        enqueue(mockResponse(402, errorBody("something_else", ",\"price_sats\":1")))

        val error = client.createAlias("abc", null).exceptionOrNull()

        assertTrue(error is AliasError.Server)
        assertEquals(402, (error as AliasError.Server).code)
    }

    @Test
    fun `purchaseSlots posts quantity and signs payload`() = runBlocking {
        enqueue(
            mockResponse(
                201,
                """{"id":9,"quantity":2,"quoted_satoshi":10000,"status":"pending"}"""
            )
        )

        val result = client.purchaseSlots(2)

        assertTrue(result.isSuccess)
        val purchase = result.getOrNull()!!
        assertEquals(2, purchase.quantity)
        assertEquals(10_000L, purchase.quotedSatoshi)
        assertEquals("pending", purchase.status)
        val url = requestSlot.captured.url.toString()
        assertTrue("slots URL: $url", url.endsWith("/api/aliases/slots"))
        val sent = okio.Buffer().also { requestSlot.captured.body!!.writeTo(it) }.readUtf8()
        assertTrue("quantity missing from body: $sent", sent.contains("\"quantity\":2"))
        assertEquals("Nostr test-event", requestSlot.captured.header("Authorization"))
    }

    @Test
    fun `getSlots parses status and history`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"tier":"free","free_cap":3,"slots_owned":5,"cap":8,
                    "price_sats":5000,
                    "purchases":[{"id":1,"quantity":5,"quoted_satoshi":25000,
                    "status":"approved","note":null}]}"""
            )
        )

        val result = client.getSlots()

        assertTrue(result.isSuccess)
        val status = result.getOrNull()!!
        assertEquals(8, status.cap)
        assertEquals(3, status.freeCap)
        assertEquals(5, status.slotsOwned)
        assertEquals(5_000L, status.priceSats)
        assertEquals(1, status.purchases.size)
        assertEquals("approved", status.purchases[0].status)
    }

    @Test
    fun `tier-info parses the relay mirroring availability fields`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"tier":"free","cap":8,"used":3,"email_domain":"desent.xyz",
                    "dm_fanout":false,"dm_fanout_purchased":true,
                    "fanout_purchase_enabled":true,"fanout_price_sats":6462,
                    "fanout_price_mode":"usd","fanout_price_usd":"5.00"}"""
            )
        )

        val result = client.getTierInfo()

        assertTrue(result.isSuccess)
        val tier = result.getOrNull()!!
        assertEquals(false, tier.dmFanout)
        assertEquals(true, tier.dmFanoutPurchased)
        assertEquals(true, tier.fanoutPurchaseEnabled)
        assertEquals(6_462L, tier.fanoutPriceSats)
        assertEquals("usd", tier.fanoutPriceMode)
        assertEquals("5.00", tier.fanoutPriceUsd)
    }

    @Test
    fun `tier-info tolerates absent mirroring fields`() = runBlocking {
        enqueue(
            mockResponse(200, """{"tier":"free","cap":8,"used":0,"email_domain":"desent.xyz"}""")
        )

        val result = client.getTierInfo()

        assertTrue(result.isSuccess)
        val tier = result.getOrNull()!!
        assertNull(tier.dmFanout)
        assertNull(tier.dmFanoutPurchased)
        assertNull(tier.fanoutPurchaseEnabled)
        assertNull(tier.fanoutPriceSats)
    }
}
