package xyz.desent.data.vanity

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
import xyz.desent.data.vanity.model.VanityError

/**
 * [VanityClient] against refs/FromServer/ANDROID_VANITY_PRICING.md §3.
 */
class VanityClientTest {

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var auth: NostrHttpAuth
    private lateinit var client: VanityClient

    private val requestSlot = slot<Request>()

    @Before
    fun setUp() {
        okHttpClient = mockk(relaxed = true)
        auth = mockk()
        coEvery { auth.buildAuthHeader(any(), any(), any()) } returns
            Result.success("Nostr test-event")
        client = VanityClient(okHttpClient, auth)
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

    private fun errorBody(error: String): String =
        """{"detail":{"error":"$error"}}"""

    @Test
    fun `createRequest posts localPart domain kind and signs payload`() = runBlocking {
        enqueue(
            mockResponse(
                201,
                """{"id":7,"local_part":"abc","domain":"desent.xyz","kind":"alias",
                    "quoted_satoshi":50000,"status":"pending",
                    "requested_at":"2026-08-16T12:00:00+00:00",
                    "decided_at":null,"note":null}"""
            )
        )

        val result = client.createRequest("abc", "desent.xyz", kind = "alias")

        assertTrue(result.isSuccess)
        val request = result.getOrNull()!!
        assertEquals(7, request.id)
        assertEquals("abc", request.localPart)
        assertEquals(50_000L, request.quotedSatoshi)
        assertEquals("pending", request.status)
        assertEquals("Nostr test-event", requestSlot.captured.header("Authorization"))
        val sent = okio.Buffer().also { requestSlot.captured.body!!.writeTo(it) }.readUtf8()
        assertTrue("local_part missing: $sent", sent.contains("\"local_part\":\"abc\""))
        assertTrue("domain missing: $sent", sent.contains("\"domain\":\"desent.xyz\""))
        assertTrue("kind missing: $sent", sent.contains("\"kind\":\"alias\""))
    }

    @Test
    fun `listRequests parses wrapped shape`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"requests":[{"id":7,"local_part":"abc","domain":"desent.xyz",
                    "kind":"alias","quoted_satoshi":50000,"status":"approved"}]}"""
            )
        )

        val result = client.listRequests()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()!!.size)
        assertEquals("approved", result.getOrNull()!![0].status)
    }

    @Test
    fun `listRequests parses bare array shape`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """[{"id":8,"local_part":"xyz","domain":"desent.xyz","kind":"alias",
                    "quoted_satoshi":2000,"status":"denied","note":"too short for price"}]"""
            )
        )

        val result = client.listRequests()

        assertTrue(result.isSuccess)
        val list = result.getOrNull()!!
        assertEquals(1, list.size)
        assertEquals("denied", list[0].status)
        assertEquals("too short for price", list[0].note)
    }

    @Test
    fun `error matrix maps wire codes to typed failures`() = runBlocking {
        val cases = mapOf(
            409 to "request_exists" to VanityError.RequestExists,
            409 to "taken" to VanityError.Taken,
            422 to "not_priced" to VanityError.NotPriced,
            422 to "reserved" to VanityError.Reserved,
            422 to "invalid_local_part" to VanityError.InvalidLocalPart,
            422 to "invalid_kind" to VanityError.InvalidKind,
            403 to "registration_disabled_domain" to VanityError.RegistrationDisabledDomain,
            429 to "rate_limited" to VanityError.RateLimited,
            401 to "unauthorized" to VanityError.Unauthorized
        )
        for ((key, expected) in cases) {
            val (code, wire) = key
            enqueue(mockResponse(code, errorBody(wire)))
            val error = client.createRequest("abc", null, "alias").exceptionOrNull()
            assertTrue("expected $expected for $code/$wire, got $error", error == expected)
        }
    }

    @Test
    fun `unknown error body maps to Server with code`() = runBlocking {
        enqueue(mockResponse(500, errorBody("boom")))

        val error = client.createRequest("abc", null, "alias").exceptionOrNull()

        assertTrue(error is VanityError.Server)
        assertEquals(500, (error as VanityError.Server).code)
    }
}
