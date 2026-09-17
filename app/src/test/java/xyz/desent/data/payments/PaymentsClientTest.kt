package xyz.desent.data.payments

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
import xyz.desent.data.payments.model.PaymentsError
import xyz.desent.domain.model.FeatureProduct
import xyz.desent.domain.model.PaymentTargetType

/**
 * [PaymentsClient] coverage against
 * refs/FROM_email.desent.xyz/ANDROID_PAYMENTS.md §1, §3, §6.
 */
class PaymentsClientTest {

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var auth: NostrHttpAuth
    private lateinit var client: PaymentsClient

    private val requestSlot = slot<Request>()

    @Before
    fun setUp() {
        okHttpClient = mockk(relaxed = true)
        auth = mockk()
        coEvery { auth.buildAuthHeader(any(), any(), any()) } returns
            Result.success("Nostr test-event")
        client = PaymentsClient(okHttpClient, auth)
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

    private val invoiceJson =
        """{"id":42,"target_type":"vanity_request","target_id":7,
            "amount_satoshi":64618,"amount_usd":"50.00",
            "ln_invoice":"lnbc646180n1p3q","state":"unpaid",
            "expires_at":"2026-08-22T18:04:00+00:00",
            "created_at":"2026-08-22T17:34:00+00:00",
            "paid_at":null,"settled_at":null}"""

    @Test
    fun `config parses gate and tier pricing`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"strike_enabled":true,"tier_price_sats":12943,
                    "tier_price_mode":"usd","tier_price_usd":"10.00",
                    "lifetime_enabled":true,"lifetime_price_sats":32358,
                    "lifetime_price_mode":"usd","lifetime_price_usd":"25.00",
                    "btc_usd":"77401.8500"}"""
            )
        )

        val result = client.getConfig()

        assertTrue(result.isSuccess)
        val cfg = result.getOrNull()!!
        assertTrue(cfg.strikeEnabled)
        assertEquals(12_943L, cfg.tierPriceSats)
        assertEquals("usd", cfg.tierPriceMode)
        assertEquals("10.00", cfg.tierPriceUsd)
        assertTrue(cfg.lifetimeEnabled)
        assertEquals(32_358L, cfg.lifetimePriceSats)
        assertEquals("25.00", cfg.lifetimePriceUsd)
        assertEquals("77401.8500", cfg.btcUsd)
        // Public endpoint — no auth burned.
        assertNull(requestSlot.captured.header("Authorization"))
    }

    @Test
    fun `mintInvoice posts target and signs payload`() = runBlocking {
        enqueue(mockResponse(200, invoiceJson))

        val result = client.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7)

        assertTrue(result.isSuccess)
        val invoice = result.getOrNull()!!
        assertEquals(42L, invoice.id)
        assertEquals("vanity_request", invoice.targetType)
        assertEquals(7L, invoice.targetId)
        assertEquals(64_618L, invoice.amountSatoshi)
        assertEquals("50.00", invoice.amountUsd)
        assertEquals("lnbc646180n1p3q", invoice.lnInvoice)
        assertEquals("unpaid", invoice.state)
        assertEquals("2026-08-22T18:04:00+00:00", invoice.expiresAt)
        val url = requestSlot.captured.url.toString()
        assertTrue("invoice URL: $url", url.endsWith("/api/payments/invoice"))
        val sent = okio.Buffer().also { requestSlot.captured.body!!.writeTo(it) }.readUtf8()
        assertTrue("target_type missing from body: $sent", sent.contains("\"target_type\":\"vanity_request\""))
        assertTrue("target_id missing from body: $sent", sent.contains("\"target_id\":7"))
        assertEquals("Nostr test-event", requestSlot.captured.header("Authorization"))
    }

    @Test
    fun `getInvoice parses state transitions`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"id":42,"target_type":"tier_purchase","target_id":1,
                    "amount_satoshi":12943,"amount_usd":"10.00",
                    "ln_invoice":"lnbc…","state":"paid",
                    "expires_at":null,"created_at":null,
                    "paid_at":"2026-08-22T17:40:00+00:00",
                    "settled_at":"2026-08-22T17:40:01+00:00"}"""
            )
        )

        val result = client.getInvoice(42)

        assertTrue(result.isSuccess)
        val invoice = result.getOrNull()!!
        assertEquals("paid", invoice.state)
        assertEquals("2026-08-22T17:40:01+00:00", invoice.settledAt)
        val url = requestSlot.captured.url.toString()
        assertTrue("poll URL: $url", url.endsWith("/api/payments/42"))
        assertEquals("Nostr test-event", requestSlot.captured.header("Authorization"))
    }

    @Test
    fun `listInvoices parses wrapped and bare array shapes`() = runBlocking {
        enqueue(mockResponse(200, """{"invoices":[$invoiceJson]}"""))

        val wrapped = client.listInvoices()
        assertTrue(wrapped.isSuccess)
        assertEquals(1, wrapped.getOrNull()!!.size)

        enqueue(mockResponse(200, "[$invoiceJson]"))

        val bare = client.listInvoices()
        assertTrue(bare.isSuccess)
        assertEquals(1, bare.getOrNull()!!.size)
    }

    @Test
    fun `purchaseTier posts without body and parses response`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"id":43,"target_type":"tier_purchase","target_id":9,
                    "amount_satoshi":12943,"ln_invoice":"lnbc…","state":"unpaid"}"""
            )
        )

        val result = client.purchaseTier()

        assertTrue(result.isSuccess)
        assertEquals("tier_purchase", result.getOrNull()!!.targetType)
        val url = requestSlot.captured.url.toString()
        assertTrue("tier URL: $url", url.endsWith("/api/payments/tier"))
    }

    @Test
    fun `purchaseTier sends the requested plan in the body`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"id":44,"target_type":"tier_purchase","target_id":10,
                    "amount_satoshi":32358,"ln_invoice":"lnbc…","state":"unpaid"}"""
            )
        )

        val result = client.purchaseTier(plan = "lifetime")

        assertTrue(result.isSuccess)
        val sent = okio.Buffer().also { requestSlot.captured.body!!.writeTo(it) }.readUtf8()
        assertTrue("plan missing from body: $sent", sent.contains("\"plan\":\"lifetime\""))
    }

    @Test
    fun `getHistory parses the unified purchase timeline`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"items":[
                      {"kind":"vanity_request","target_id":42,"status":"claimed",
                       "amount_satoshi":64618,"amount_usd":"50.00",
                       "requested_at":"2026-08-20T12:00:00Z",
                       "decided_at":"2026-08-20T12:05:00Z","decided_by":"strike","note":null,
                       "detail":{"local_part":"bob","domain":"desent.xyz","kind":"alias"},
                       "invoice":{"id":7,"target_type":"vanity_request","target_id":42,
                                  "amount_satoshi":64618,"state":"paid",
                                  "settled_at":"2026-08-20T12:05:00Z"}},
                      {"kind":"slot_purchase","target_id":8,"status":"approved",
                       "amount_satoshi":646,"amount_usd":null,
                       "requested_at":"2026-08-19T09:00:00Z",
                       "decided_at":null,"decided_by":null,"note":null,
                       "detail":{"quantity":3},"invoice":null}
                   ]}"""
            )
        )

        val result = client.getHistory()

        assertTrue(result.isSuccess)
        val items = result.getOrNull()!!.items
        assertEquals(2, items.size)
        assertEquals("vanity_request", items[0].kind)
        assertEquals("claimed", items[0].status)
        assertEquals(64_618L, items[0].amountSatoshi)
        assertEquals("strike", items[0].decidedBy)
        assertEquals("bob", items[0].detail?.let { (it as kotlinx.serialization.json.JsonObject)["local_part"]?.let { p -> (p as kotlinx.serialization.json.JsonPrimitive).content } })
        assertEquals("paid", items[0].invoice?.state)
        assertEquals("settled_at present", "2026-08-20T12:05:00Z", items[0].invoice?.settledAt)
        // Read-only nested invoice: ln_invoice absent is fine (nullable default).
        assertNull(items[1].invoice)
        val url = requestSlot.captured.url.toString()
        assertTrue("history URL: $url", url.endsWith("/api/payments/history"))
        assertEquals("Nostr test-event", requestSlot.captured.header("Authorization"))
    }

    @Test
    fun `403 lifetime_disabled and 409 plan_switch_blocked map to typed errors`() = runBlocking {
        enqueue(mockResponse(403, errorBody("lifetime_disabled")))
        assertTrue(
            client.purchaseTier("lifetime").exceptionOrNull() is PaymentsError.LifetimeDisabled
        )

        enqueue(mockResponse(409, errorBody("plan_switch_blocked")))
        assertTrue(
            client.purchaseTier("lifetime").exceptionOrNull() is PaymentsError.PlanSwitchBlocked
        )
    }

    @Test
    fun `404 maps to TargetNotFound`() = runBlocking {
        enqueue(mockResponse(404, errorBody("target_not_found")))

        val error = client.mintInvoice(PaymentTargetType.VANITY_REQUEST, 99).exceptionOrNull()

        assertTrue(error is PaymentsError.TargetNotFound)
    }

    @Test
    fun `409 not_pending carries target status`() = runBlocking {
        enqueue(mockResponse(409, errorBody("not_pending", ",\"status\":\"approved\"")))

        val error = client.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7).exceptionOrNull()

        assertTrue(error is PaymentsError.NotPending)
        error as PaymentsError.NotPending
        assertEquals("approved", error.status)
    }

    @Test
    fun `409 already_paid maps to AlreadyPaid`() = runBlocking {
        enqueue(mockResponse(409, errorBody("already_paid")))

        val error = client.purchaseTier().exceptionOrNull()

        assertTrue(error is PaymentsError.AlreadyPaid)
    }

    @Test
    fun `422 maps invalid_target_type and zero_price`() = runBlocking {
        enqueue(mockResponse(422, errorBody("invalid_target_type")))
        assertTrue(
            client.mintInvoice(PaymentTargetType.TIER_PURCHASE, 1).exceptionOrNull()
                is PaymentsError.InvalidTargetType
        )

        enqueue(mockResponse(422, errorBody("zero_price")))
        assertTrue(
            client.mintInvoice(PaymentTargetType.SLOT_PURCHASE, 1).exceptionOrNull()
                is PaymentsError.ZeroPrice
        )
    }

    @Test
    fun `429 502 503 map to rate limit, strike unavailable and payments disabled`() = runBlocking {
        enqueue(mockResponse(429, errorBody("rate_limited")))
        assertTrue(
            client.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7).exceptionOrNull()
                is PaymentsError.RateLimited
        )

        enqueue(mockResponse(502, errorBody("strike_unavailable")))
        assertTrue(
            client.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7).exceptionOrNull()
                is PaymentsError.StrikeUnavailable
        )

        enqueue(mockResponse(503, errorBody("payments_disabled")))
        assertTrue(
            client.mintInvoice(PaymentTargetType.VANITY_REQUEST, 7).exceptionOrNull()
                is PaymentsError.PaymentsDisabled
        )
    }

    // ---------------- Feature add-ons (ANDROID_PAYMENTS.md §3.1b) ----------------

    @Test
    fun `purchaseFeature posts the product to the one-call endpoint`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"id":51,"target_type":"feature_purchase","target_id":3,
                    "amount_satoshi":6462,"amount_usd":"5.00","ln_invoice":"lnbc…",
                    "state":"unpaid","expires_at":"2026-09-01T00:00:00+00:00"}"""
            )
        )

        val result = client.purchaseFeature(FeatureProduct.DM_FANOUT)

        assertTrue(result.isSuccess)
        val invoice = result.getOrNull()!!
        assertEquals("feature_purchase", invoice.targetType)
        assertEquals(6_462L, invoice.amountSatoshi)
        val url = requestSlot.captured.url.toString()
        assertTrue("feature URL: $url", url.endsWith("/api/payments/feature"))
        val sent = okio.Buffer().also { requestSlot.captured.body!!.writeTo(it) }.readUtf8()
        assertTrue("product missing from body: $sent", sent.contains("\"product\":\"dm_fanout\""))
        assertEquals("Nostr test-event", requestSlot.captured.header("Authorization"))
    }

    @Test
    fun `purchaseFeature maps the parameterized error codes`() = runBlocking {
        enqueue(mockResponse(403, errorBody("fanout_purchases_disabled")))
        client.purchaseFeature(FeatureProduct.DM_FANOUT).exceptionOrNull().let { error ->
            assertTrue(error is PaymentsError.FeaturePurchasesDisabled)
            error as PaymentsError.FeaturePurchasesDisabled
            assertEquals("fanout", error.feature)
        }

        enqueue(mockResponse(403, errorBody("key_rotation_purchases_disabled")))
        client.purchaseFeature(FeatureProduct.KEY_ROTATION).exceptionOrNull().let { error ->
            assertTrue(error is PaymentsError.FeaturePurchasesDisabled)
            error as PaymentsError.FeaturePurchasesDisabled
            assertEquals("key_rotation", error.feature)
        }

        enqueue(mockResponse(503, errorBody("fanout_disabled")))
        client.purchaseFeature(FeatureProduct.DM_FANOUT).exceptionOrNull().let { error ->
            assertTrue(error is PaymentsError.FeatureDisabled)
            assertEquals("fanout", (error as PaymentsError.FeatureDisabled).feature)
        }

        enqueue(mockResponse(503, errorBody("key_rotation_disabled")))
        client.purchaseFeature(FeatureProduct.KEY_ROTATION).exceptionOrNull().let { error ->
            assertTrue(error is PaymentsError.FeatureDisabled)
            assertEquals("key_rotation", (error as PaymentsError.FeatureDisabled).feature)
        }

        enqueue(mockResponse(409, errorBody("already_entitled")))
        client.purchaseFeature(FeatureProduct.KEY_ROTATION).exceptionOrNull().let { error ->
            assertTrue(error is PaymentsError.AlreadyEntitled)
            // parseError lacks product context — purchaseFeature enriches it.
            assertEquals("key_rotation", (error as PaymentsError.AlreadyEntitled).product)
        }

        enqueue(mockResponse(422, errorBody("invalid_product")))
        assertTrue(
            client.purchaseFeature(FeatureProduct.DM_FANOUT).exceptionOrNull()
                is PaymentsError.InvalidProduct
        )
    }

    @Test
    fun `config parses the tier-sales kill switch`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"strike_enabled":true,"tier_purchases_enabled":false,
                    "tier_price_sats":12943}"""
            )
        )
        client.getConfig().getOrNull()!!.let { cfg ->
            assertEquals(false, cfg.tierPurchasesEnabled)
        }

        // Missing field = treat as enabled (migration 043 default).
        enqueue(mockResponse(200, """{"strike_enabled":true}"""))
        client.getConfig().getOrNull()!!.let { cfg ->
            assertNull(cfg.tierPurchasesEnabled)
        }
    }
}
