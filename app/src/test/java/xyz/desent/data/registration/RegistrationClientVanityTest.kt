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
import xyz.desent.data.registration.model.RegistrationError
import xyz.desent.data.registration.model.RegisterRequest

/**
 * Vanity pricing surface of [RegistrationClient] against
 * refs/FromServer/ANDROID_VANITY_PRICING.md §2.
 */
class RegistrationClientVanityTest {

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

    @Test
    fun `available prices the probe`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"available":true,"local":"abc","domain":"desent.xyz",
                    "price_sats":50000,"vanity":true}"""
            )
        )

        val result = client.checkAvailable("abc")

        assertTrue(result.isSuccess)
        val info = result.getOrNull()!!
        assertTrue(info.available)
        assertEquals(50_000L, info.priceSats)
        assertTrue(info.vanity)
        assertNull(requestSlot.captured.header("Authorization"))
    }

    @Test
    fun `available free name defaults to zero price`() = runBlocking {
        enqueue(mockResponse(200, """{"available":true,"local":"alice"}"""))

        val result = client.checkAvailable("alice")

        assertTrue(result.isSuccess)
        assertEquals(0L, result.getOrNull()!!.priceSats)
        assertEquals(false, result.getOrNull()!!.vanity)
    }

    @Test
    fun `register 402 vanity_price maps to typed error`() = runBlocking {
        enqueue(
            mockResponse(
                402,
                """{"detail":{"error":"vanity_price","length":3,"price_sats":50000,
                    "ladder":{"1":250000,"3":50000},"free_length":8}}"""
            )
        )

        val error = client.register(RegisterRequest(local = "abc", displayName = "Alice"))
            .exceptionOrNull()

        assertTrue(error is RegistrationError.VanityPrice)
        error as RegistrationError.VanityPrice
        assertEquals(3, error.length)
        assertEquals(50_000L, error.priceSats)
        assertEquals(8, error.freeLength)
        assertEquals(2, error.ladder.size)
    }

    @Test
    fun `register 402 other detail maps to Server`() = runBlocking {
        enqueue(mockResponse(402, """{"detail":{"error":"other_gate"}}"""))

        val error = client.register(RegisterRequest(local = "abc", displayName = "Alice"))
            .exceptionOrNull()

        assertTrue(error is RegistrationError.Server)
        assertEquals(402, (error as RegistrationError.Server).code)
    }
}
