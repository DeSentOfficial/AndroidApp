package xyz.desent.data.pgp

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.pgp.model.PgpError

/**
 * [PgpClient] coverage against refs/FROM_email.desent.xyz/PGP_ENCRYPTION.md
 * § Key registry + WKD discovery (the updated contract incl. the
 * SSRF-hardened external fetch path — client-visible shapes unchanged).
 */
class PgpClientTest {

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var auth: NostrHttpAuth
    private lateinit var client: PgpClient

    private val requestSlot = slot<Request>()

    @Before
    fun setUp() {
        okHttpClient = mockk(relaxed = true)
        auth = mockk()
        coEvery { auth.buildAuthHeader(any(), any(), any()) } returns
            Result.success("Nostr test-event")
        client = PgpClient(okHttpClient, auth)
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
    fun `config parses the feature gate without auth`() = runBlocking {
        enqueue(mockResponse(200, """{"pgp_enabled":true}"""))

        val result = client.getConfig()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!.pgpEnabled)
        assertEquals("https://desent.xyz/api/pgp/config", requestSlot.captured.url.toString())
        assertNull(requestSlot.captured.header("Authorization"))
    }

    @Test
    fun `putKey sends armored public half with NIP-98`() = runBlocking {
        enqueue(mockResponse(200, """{"fingerprint":"ABCD"}"""))

        val armor = "-----BEGIN PGP PUBLIC KEY BLOCK-----\nabc\n-----END PGP PUBLIC KEY BLOCK-----"
        val result = client.putKey(armor)

        assertTrue(result.isSuccess)
        assertEquals("ABCD", result.getOrNull()!!.fingerprint)
        val request = requestSlot.captured
        assertEquals("PUT", request.method)
        assertEquals("https://desent.xyz/api/pgp/key", request.url.toString())
        assertNotNull(request.header("Authorization"))
        val body = request.body!!.let {
            val sink = okio.Buffer()
            it.writeTo(sink)
            sink.readUtf8()
        }
        assertTrue(body.contains("BEGIN PGP PUBLIC KEY BLOCK"))
        assertFalse(body.contains("PRIVATE"))
    }

    @Test
    fun `getKey maps 404 no_pgp_key to typed error`() = runBlocking {
        enqueue(
            mockResponse(
                404,
                """{"detail":{"error":"no_pgp_key","message":"No key registered"}}"""
            )
        )

        val result = client.getKey()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PgpError.NoPgpKey)
    }

    @Test
    fun `putKey maps 422 invalid_key with user-safe message`() = runBlocking {
        enqueue(
            mockResponse(
                422,
                """{"detail":{"error":"invalid_key","message":"no encryption-capable subkey"}}"""
            )
        )

        val result = client.putKey("bad armor")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as PgpError.InvalidKey
        assertEquals("no encryption-capable subkey", error.reason)
    }

    @Test
    fun `putKey maps 403 pgp_disabled`() = runBlocking {
        enqueue(mockResponse(403, """{"detail":{"error":"pgp_disabled"}}"""))

        val result = client.putKey("armor")

        assertTrue(result.exceptionOrNull() is PgpError.PgpDisabled)
    }

    @Test
    fun `deleteKey tolerates empty body`() = runBlocking {
        enqueue(mockResponse(200, ""))

        val result = client.deleteKey()

        assertTrue(result.isSuccess)
        assertEquals("DELETE", requestSlot.captured.method)
    }

    @Test
    fun `wkdLookup servicedDomain returns armor`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"found":true,"armored":"-----BEGIN PGP PUBLIC KEY BLOCK-----","domain":"desent.xyz"}"""
            )
        )

        val result = client.lookupRecipientKey("alice@desent.xyz")

        assertTrue(result.isSuccess)
        assertEquals("-----BEGIN PGP PUBLIC KEY BLOCK-----", result.getOrNull())
        // Public proxy endpoint — no auth.
        assertNull(requestSlot.captured.header("Authorization"))
        assertTrue(requestSlot.captured.url.toString().endsWith("/wkd-lookup?email=alice%40desent.xyz"))
    }

    @Test
    fun `wkdLookup externalDomain decodes binary key into armor`() = runBlocking {
        // A real (trivial) binary key: generate one, strip armor, base64 it.
        val material = xyz.desent.crypto.OpenPgpCrypto.generate("T <t@desent.xyz>")
        val binary = java.io.ByteArrayInputStream(material.publicArmored.toByteArray()).use { ins ->
            org.bouncycastle.bcpg.ArmoredInputStream(ins).use { it.readBytes() }
        }
        val b64 = java.util.Base64.getEncoder().encodeToString(binary)
        enqueue(mockResponse(200, """{"found":true,"key_base64":"$b64","domain":"example.com"}"""))

        val result = client.lookupRecipientKey("bob@example.com")

        val armored = result.getOrNull()
        assertNotNull(armored)
        assertTrue(armored!!.startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----"))
        assertTrue(xyz.desent.crypto.OpenPgpCrypto.hasEncryptionKey(armored))
    }

    @Test
    fun `wkdLookup miss returns null`() = runBlocking {
        enqueue(mockResponse(200, """{"found":false}"""))

        val result = client.lookupRecipientKey("nobody@example.com")

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }
}
