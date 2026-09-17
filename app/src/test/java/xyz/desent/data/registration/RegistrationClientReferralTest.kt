package xyz.desent.data.registration

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
import xyz.desent.data.registration.model.ReferralsResponse
import xyz.desent.data.registration.model.RegisterRequest
import xyz.desent.data.registration.model.RegistrationError

/**
 * Referral-endpoint coverage for [RegistrationClient] against
 * refs/FromServer/ANDROID_REFERRALS.md §2 and REGISTRATION_API_REFERENCE.md.
 */
class RegistrationClientReferralTest {

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var auth: NostrHttpAuth
    private lateinit var client: RegistrationClient

    private val requestSlot = slot<Request>()

    @Before
    fun setUp() {
        okHttpClient = mockk(relaxed = true)
        auth = mockk()
        coEvery { auth.buildAuthHeader(any(), any(), any()) } returns
            Result.success("Nostr test-event")
        client = RegistrationClient(okHttpClient, auth)
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

    // ---- happy paths ------------------------------------------------------

    @Test
    fun getRegisterMode_parsesMode() = runBlocking {
        enqueue(mockResponse(200, """{"mode":"referral"}"""))
        val result = client.getRegisterMode()
        assertTrue(result.isSuccess)
        assertEquals("referral", result.getOrNull()!!.mode)
    }

    @Test
    fun checkReferralCode_uppercasesInput_andSendsNoAuth() = runBlocking {
        enqueue(mockResponse(200, """{"valid":true}"""))

        val result = client.checkReferralCode("ds-armxh2-mh7yfc")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!.valid)
        val url = requestSlot.captured.url.toString()
        assertTrue("URL should carry the uppercased code: $url",
            url.endsWith("/api/register/referral?code=DS-ARMXH2-MH7YFC"))
        // Public endpoint — must not burn a NIP-98 signature per check.
        assertNull(requestSlot.captured.header("Authorization"))
    }

    @Test
    fun getReferrals_parsesCodesAndCap_andSignsRequest() = runBlocking {
        val body = """
            {
              "codes": [
                {"code":"DS-ARMXH2-MH7YFC","used":false,"used_by":null,
                 "created_at":"2026-08-03T20:28:31.075+00:00","used_at":null},
                {"code":"DS-K2M9X4-P8QW3R","used":true,
                 "used_by":"879f1560458ae059b84c0d09fc3170239bd6d0ed18d63283f38050ae4f1b8a63",
                 "created_at":"2026-08-03T20:28:31.075+00:00",
                 "used_at":"2026-08-05T10:00:00.000+00:00"}
              ],
              "cap": 3
            }
        """.trimIndent()
        enqueue(mockResponse(200, body))

        val result = client.getReferrals()

        assertTrue(result.isSuccess)
        val parsed: ReferralsResponse = result.getOrNull()!!
        assertEquals(2, parsed.codes.size)
        assertEquals(3, parsed.cap)
        assertEquals("DS-ARMXH2-MH7YFC", parsed.codes[0].code)
        assertTrue(!parsed.codes[0].used)
        assertTrue(parsed.codes[1].used)
        assertEquals(
            "879f1560458ae059b84c0d09fc3170239bd6d0ed18d63283f38050ae4f1b8a63",
            parsed.codes[1].usedBy
        )
        assertEquals("Nostr test-event", requestSlot.captured.header("Authorization"))
    }

    @Test
    fun register_serializesReferralCodeAndSignsPayload() = runBlocking {
        var signedPayload: ByteArray? = null
        coEvery { auth.buildAuthHeader(any(), any(), any()) } answers {
            signedPayload = thirdArg()
            Result.success("Nostr test-event")
        }
        enqueue(
            mockResponse(
                201,
                """{"pubkey":"aa","nip05_username":"alice","nip05_domain":"desent.xyz"}"""
            )
        )

        val request = RegisterRequest(
            local = "alice",
            displayName = "Alice",
            referralCode = "DS-ARMXH2-MH7YFC"
        )
        val result = client.register(request)

        assertTrue(result.isSuccess)
        val sent = okio.Buffer().also { requestSlot.captured.body!!.writeTo(it) }.readUtf8()
        assertTrue("referral_code missing from body: $sent",
            sent.contains("\"referral_code\":\"DS-ARMXH2-MH7YFC\""))
        assertTrue(sent.contains("\"local\":\"alice\""))
        // A POST with a body must sign the payload tag over the exact bytes.
        assertTrue("payload bytes not signed", signedPayload != null)
    }

    // ---- error matrix (§2.6) ----------------------------------------------

    @Test
    fun referralErrors_mapToTypedFailures() = runBlocking {
        val cases = mapOf(
            "registration_disabled" to RegistrationError.RegistrationDisabled,
            "referral_required" to RegistrationError.ReferralRequired,
            "invalid_referral_code" to RegistrationError.InvalidReferralCode,
            "account_disabled" to RegistrationError.AccountDisabled,
            "pubkey_not_registered" to RegistrationError.PubkeyNotRegistered
        )
        for ((wire, expected) in cases) {
            enqueue(mockResponse(403, errorBody(wire)))
            val error = client.getReferrals().exceptionOrNull()
            assertTrue("expected $expected for $wire, got $error",
                error == expected)
        }
    }

    @Test
    fun conflictCodes_distinguishAlreadyRegisteredFromTaken() = runBlocking {
        enqueue(mockResponse(409, errorBody("already_registered")))
        assertTrue(
            client.register(RegisterRequest(local = "alice", displayName = "Alice"))
                .exceptionOrNull() is RegistrationError.AlreadyRegistered
        )

        enqueue(mockResponse(409, errorBody("taken")))
        assertTrue(
            client.register(RegisterRequest(local = "alice", displayName = "Alice"))
                .exceptionOrNull() is RegistrationError.Taken
        )
    }

    @Test
    fun rateLimited_parsesRetryAfterSeconds() = runBlocking {
        enqueue(
            mockResponse(429, errorBody("rate_limited", ",\"retry_after_seconds\":60"))
        )

        val error = client.getReferrals().exceptionOrNull()

        assertTrue(error is RegistrationError.RateLimited)
        assertEquals(60L, (error as RegistrationError.RateLimited).retryAfterSeconds)
    }

    @Test
    fun domainErrors_carryDomain() = runBlocking {
        enqueue(mockResponse(403, errorBody("registration_disabled_domain", ",\"domain\":\"yadha.net\"")))

        val error = client.register(RegisterRequest(local = "alice", displayName = "Alice"))
            .exceptionOrNull()

        assertTrue(error is RegistrationError.RegistrationDisabledDomain)
        assertEquals("yadha.net", (error as RegistrationError.RegistrationDisabledDomain).domain)
    }
}
