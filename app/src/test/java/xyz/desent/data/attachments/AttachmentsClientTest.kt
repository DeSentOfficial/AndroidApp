package xyz.desent.data.attachments

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.NostrHttpAuth

/**
 * [AttachmentsClient] coverage against refs/ATTACHMENTS_API_REFERENCE.md.
 *
 * Pins the NIP-98 `u`-tag rule that caused the 401 `url_mismatch` on the
 * files screen: the server matches scheme+host+path only, so the signed
 * URL must NOT carry the `limit`/`key` query params even though the
 * request URL does (refs/FROM_email.desent.xyz/ANDROID_REFERRALS.md §3).
 */
class AttachmentsClientTest {

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var auth: NostrHttpAuth
    private lateinit var client: AttachmentsClient

    private val requestSlot = slot<Request>()
    private val signedUrlSlot = slot<String>()

    @Before
    fun setUp() {
        okHttpClient = mockk(relaxed = true)
        auth = mockk()
        coEvery {
            auth.buildAuthHeader(capture(signedUrlSlot), any(), any())
        } returns Result.success("Nostr test-event")
        client = AttachmentsClient(okHttpClient, auth)
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
    fun `list sends limit query but signs query-free url`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"attachments":[{"sha256":"abc123","filename":"invoice.pdf",
                   "mime_type":"application/pdf","size":184320,"is_inline":true}],
                   "count":1}""".replace("\n", "")
            )
        )

        val result = client.listAttachments(limit = 500)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()!!.attachments.size)
        val request = requestSlot.captured
        assertEquals(
            "https://desent.xyz/api/attachments?limit=500",
            request.url.toString()
        )
        assertEquals("GET", request.method)
        // The u tag is what buildAuthHeader receives — must exclude the query.
        assertEquals("https://desent.xyz/api/attachments", signedUrlSlot.captured)
    }

    @Test
    fun `list default limit is 500`() = runBlocking {
        enqueue(mockResponse(200, """{"attachments":[],"count":0}"""))

        client.listAttachments()

        assertEquals(
            "https://desent.xyz/api/attachments?limit=500",
            requestSlot.captured.url.toString()
        )
    }

    @Test
    fun `download sends key query but signs query-free url`() = runBlocking {
        val bytes = ByteArray(8) { it.toByte() }
        val rb = mockk<ResponseBody>(relaxed = true)
        every { rb.bytes() } returns bytes
        val resp = mockk<Response>(relaxed = true)
        every { resp.isSuccessful } returns true
        every { resp.code } returns 200
        every { resp.body } returns rb
        val call = mockk<Call>()
        every { call.execute() } returns resp
        every { okHttpClient.newCall(capture(requestSlot)) } returns call

        val result = client.download("a1b2c3", "deadbeef".repeat(8))

        assertTrue(result.isSuccess)
        assertEquals(8, result.getOrNull()!!.size)
        assertEquals(
            "https://desent.xyz/api/attachments/a1b2c3?key=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
            requestSlot.captured.url.toString()
        )
        assertEquals("https://desent.xyz/api/attachments/a1b2c3", signedUrlSlot.captured)
    }

    @Test
    fun `delete signs the exact path url`() = runBlocking {
        enqueue(mockResponse(200, """{"status":"deleted","sha256":"a1b2c3"}"""))

        val result = client.deleteAttachment("a1b2c3")

        assertTrue(result.isSuccess)
        assertEquals("DELETE", requestSlot.captured.method)
        assertEquals(
            "https://desent.xyz/api/attachments/a1b2c3",
            requestSlot.captured.url.toString()
        )
        assertEquals("https://desent.xyz/api/attachments/a1b2c3", signedUrlSlot.captured)
    }

    @Test
    fun `list surfaces parsed error detail`() = runBlocking {
        enqueue(
            mockResponse(
                401,
                """{"detail":{"error":"url_mismatch"}}"""
            )
        )

        val result = client.listAttachments()

        assertTrue(result.isFailure)
        assertEquals(
            "List failed: HTTP 401: url_mismatch",
            result.exceptionOrNull()!!.message
        )
    }
}
