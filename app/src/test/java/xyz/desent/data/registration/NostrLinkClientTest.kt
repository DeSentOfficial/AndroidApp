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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.Nip44Encryption
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.registration.model.NostrLinkError
import java.security.SecureRandom

/**
 * Coverage for [NostrLinkClient] against
 * refs/FROM_email.desent.xyz/NOSTR_CUSTODIAL.md §1/§3: the 22242 identity
 * proof (template-signed, ±10 min), the `404 not_linked` probe semantics,
 * and the verify (password) phase.
 */
class NostrLinkClientTest {

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var auth: NostrHttpAuth
    private lateinit var client: NostrLinkClient

    private val requestSlot = slot<Request>()
    private val privateKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val responses = ArrayDeque<Response>()

    @Before
    fun setUp() {
        okHttpClient = mockk(relaxed = true)
        auth = mockk()
        client = NostrLinkClient(okHttpClient, auth)
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
        // login() makes TWO HTTP calls (challenge, then login) — queue the
        // responses so a single Call mock dispatches them in order.
        responses.add(resp)
        val call = mockk<Call>()
        every { call.execute() } answers { responses.removeFirst() }
        every { okHttpClient.newCall(capture(requestSlot)) } returns call
    }

    private fun Request.bodyText(): String =
        okio.Buffer().also { body!!.writeTo(it) }.readUtf8()

    @Test
    fun `login builds a verifiable 22242 proof from the server template`() = runBlocking {
        // The challenge carries the nonce already inside the template's tags.
        enqueue(
            mockResponse(
                200,
                """{"nonce":"n0nc3","event":{"kind":22242,"content":"desent-nostr-link-v1",""" +
                    """"tags":[["challenge","n0nc3"]]}}"""
            )
        )
        enqueue(
            mockResponse(
                200,
                """{"kdf":"argon2id","kdf_params":{"m":65536,"t":3,"p":1},""" +
                    """"salt":"c2FsdA==","v":2,"handoff":"h4nd0ff"}"""
            )
        )

        val result = client.login(privateKey)

        assertTrue(result.isSuccess)
        assertEquals("h4nd0ff", result.getOrNull()!!.handoff)

        // First call = the public challenge (no auth header).
        // Second call posts {nonce, event} with a REAL schnorr proof we can
        // verify ourselves, exactly like the server does.
        val body = requestSlot.captured.bodyText()
        assertTrue(body.contains("\"nonce\":\"n0nc3\""))

        val event = kotlinx.serialization.json.Json.parseToJsonElement(body)
            .let { it as kotlinx.serialization.json.JsonObject }
            .get("event") as kotlinx.serialization.json.JsonObject

        val pubkey = (event["pubkey"] as kotlinx.serialization.json.JsonPrimitive).content
        val sig = (event["sig"] as kotlinx.serialization.json.JsonPrimitive).content
        val tags = (event["tags"] as kotlinx.serialization.json.JsonArray)
            .map { tag -> (tag as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content } }
        val createdAt = (event["created_at"] as kotlinx.serialization.json.JsonPrimitive).content.toLong()
        val kind = (event["kind"] as kotlinx.serialization.json.JsonPrimitive).content.toInt()
        val content = (event["content"] as kotlinx.serialization.json.JsonPrimitive).content

        assertEquals(Nip44Encryption.bytesToHex(Nip44Encryption.derivePublicKey(privateKey)), pubkey)
        assertEquals(22242, kind)
        assertEquals("desent-nostr-link-v1", content)
        assertTrue(listOf("challenge", "n0nc3") in tags)
        assertTrue(Math.abs(createdAt - System.currentTimeMillis() / 1000) < 600)

        // The proof verifies over the canonical NIP-01 id.
        val idBytes = nostr.util.NostrUtil.sha256(
            xyz.desent.crypto.NostrEventCrypto.canonicalEventBytes(pubkey, createdAt, kind, tags, content)
        )
        assertTrue(
            xyz.desent.crypto.NostrEventCrypto.verifyDigest(
                idBytes, Nip44Encryption.hexToBytes(pubkey), Nip44Encryption.hexToBytes(sig)
            )
        )
    }

    @Test
    fun `404 not_linked maps to the fallback signal`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"nonce":"n","event":{"kind":22242,"content":"desent-nostr-link-v1",""" +
                    """"tags":[["challenge","n"]]}}"""
            )
        )
        enqueue(mockResponse(404, """{"detail":{"error":"not_linked"}}"""))

        val result = client.login(privateKey)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NostrLinkError.NotLinked)
    }

    @Test
    fun `invalid_proof and disabled slugs map`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"nonce":"n","event":{"kind":22242,"content":"c","tags":[["challenge","n"]]}}"""
            )
        )
        enqueue(mockResponse(401, """{"detail":{"error":"invalid_proof"}}"""))
        assertTrue(client.login(privateKey).exceptionOrNull() is NostrLinkError.InvalidProof)

        enqueue(mockResponse(403, """{"detail":{"error":"nostr_link_disabled"}}"""))
        assertTrue(client.verify("h", "v").exceptionOrNull() is NostrLinkError.NostrLinkDisabled)
    }

    @Test
    fun `verify posts handoff and verifier`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"username":"bravefalcon","pubkey":"${"ab".repeat(32)}",""" +
                    """"npub":"npub1x","nip05":"bravefalcon@desent.xyz",""" +
                    """"blob":{"v":2,"kdf":"argon2id","kdf_params":{"m":65536,"t":3,"p":1},""" +
                    """"salt":"c2FsdA==","ncryptsec":"ncryptsec1t"}}"""
            )
        )

        val result = client.verify("h4nd0ff", "ff".repeat(32))

        assertTrue(result.isSuccess)
        assertEquals("bravefalcon", result.getOrNull()!!.username)
        assertEquals("npub1x", result.getOrNull()!!.npub)
        val body = requestSlot.captured.bodyText()
        assertTrue(body.contains("\"handoff\":\"h4nd0ff\""))
        assertTrue(body.contains("\"verifier\":\"${"ff".repeat(32)}\""))
        val url = requestSlot.captured.url.toString()
        assertTrue(url.endsWith("/api/nostr/login/verify"))
    }
}
