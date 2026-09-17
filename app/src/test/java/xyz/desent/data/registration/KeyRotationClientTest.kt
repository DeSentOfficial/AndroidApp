package xyz.desent.data.registration

import io.mockk.coEvery
import io.mockk.coVerify
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
import xyz.desent.crypto.CustodialCrypto
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.registration.model.KeyRotateRequest
import xyz.desent.data.registration.model.KeyRotationError
import java.security.SecureRandom

/**
 * Endpoint coverage for [KeyRotationClient] against
 * refs/FROM_email.desent.xyz/KEY_ROTATION.md §3 (challenge / rotate /
 * restore / cleanup) — URL shapes, explicit old/new-key NIP-98 signing,
 * and the error-slug matrix.
 */
class KeyRotationClientTest {

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var auth: NostrHttpAuth
    private lateinit var client: KeyRotationClient
    private val oldIdentity: nostr.id.Identity = mockk(relaxed = true)
    private val newIdentity: nostr.id.Identity = mockk(relaxed = true)

    private val requestSlot = slot<Request>()

    private val blob = CustodialCrypto.Blob(salt = "c2FsdA==", ncryptsec = "ncryptsec1test")

    @Before
    fun setUp() {
        okHttpClient = mockk(relaxed = true)
        auth = mockk()
        coEvery { auth.buildAuthHeader(any(), any(), any(), any()) } returns
            Result.success("Nostr test-event")
        client = KeyRotationClient(okHttpClient, auth)
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

    private fun Request.bodyText(): String =
        okio.Buffer().also { body!!.writeTo(it) }.readUtf8()

    // ---- happy paths ------------------------------------------------------

    @Test
    fun `challenge posts and parses the nonce`() = runBlocking {
        enqueue(mockResponse(200, """{"nonce":"abc","proof_message":"x","expires_in":300}"""))

        val result = client.challenge(oldIdentity)

        assertTrue(result.isSuccess)
        assertEquals("abc", result.getOrNull()!!.nonce)
        val url = requestSlot.captured.url.toString()
        assertTrue(url.endsWith("/api/account/key-rotate/challenge"))
        coVerify { auth.buildAuthHeader(any(), "POST", any(), oldIdentity) }
    }

    @Test
    fun `rotate sends the full body and signs with the old key`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"ok":true,"npub":"npub1new","custodial":true,"cleanup_required":true,"identity_linked":true}"""
            )
        )

        val result = client.rotate(
            KeyRotateRequest(
                nonce = "abc",
                newPubkey = "ab".repeat(32),
                newKeyProof = "cd".repeat(64),
                migrateMail = true,
                oldVerifier = "ef".repeat(32),
                newVerifier = "12".repeat(32),
                newBlob = blob,
                keepOldIdentity = true
            ),
            oldIdentity
        )

        assertTrue(result.isSuccess)
        assertEquals(true, result.getOrNull()!!.identityLinked)
        val url = requestSlot.captured.url.toString()
        assertTrue(url.endsWith("/api/account/key-rotate"))
        coVerify { auth.buildAuthHeader(any(), "POST", any(), oldIdentity) }

        val body = requestSlot.captured.bodyText()
        assertTrue(body.contains("\"new_pubkey\":\"${"ab".repeat(32)}\""))
        assertTrue(body.contains("\"new_key_proof\":\"${"cd".repeat(64)}\""))
        assertTrue(body.contains("\"old_verifier\":\"${"ef".repeat(32)}\""))
        assertTrue(body.contains("\"keep_old_identity\":true"))
    }

    @Test
    fun `restore batches pre-serialized events and signs with the new key`() = runBlocking {
        enqueue(mockResponse(200, """{"ok":true,"restored":2,"skipped":0}"""))

        val events = listOf(
            """{"id":"a","pubkey":"b","created_at":1,"kind":1059,"tags":[],"content":"c","sig":"d"}"""
        )
        val result = client.restore(events, newIdentity)

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()!!.restored)
        val url = requestSlot.captured.url.toString()
        assertTrue(url.endsWith("/api/account/key-rotate/restore"))
        coVerify { auth.buildAuthHeader(any(), "POST", any(), newIdentity) }

        val body = requestSlot.captured.bodyText()
        assertTrue(body.startsWith("{\"events\":["))
        assertTrue(body.contains("\"kind\":1059"))
    }

    @Test
    fun `cleanup posts with the new key`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"ok":true,"old_pubkey":"${"ab".repeat(32)}"}"""
            )
        )

        val result = client.cleanup(newIdentity)

        assertTrue(result.isSuccess)
        val url = requestSlot.captured.url.toString()
        assertTrue(url.endsWith("/api/account/key-rotate/cleanup"))
        coVerify { auth.buildAuthHeader(any(), "POST", any(), newIdentity) }
    }

    // ---- error-slug matrix (KEY_ROTATION.md §3.2, NOSTR_CUSTODIAL.md §2/§4) --

    @Test
    fun `premium_required maps with tier`() = runBlocking {
        enqueue(mockResponse(402, errorBody("premium_required", ",\"tier\":\"free\"")))
        val error = client.challenge(oldIdentity).exceptionOrNull()
        assertTrue(error is KeyRotationError.PremiumRequired)
        assertEquals("free", (error as KeyRotationError.PremiumRequired).tier)
    }

    @Test
    fun `rotation_cooldown maps`() = runBlocking {
        enqueue(mockResponse(429, errorBody("rotation_cooldown")))
        assertTrue(
            client.rotate(dummyRequest(), oldIdentity).exceptionOrNull()
                is KeyRotationError.RotationCooldown
        )
    }

    @Test
    fun `pubkey_taken maps`() = runBlocking {
        enqueue(mockResponse(409, errorBody("pubkey_taken")))
        assertTrue(
            client.rotate(dummyRequest(), oldIdentity).exceptionOrNull()
                is KeyRotationError.PubkeyTaken
        )
    }

    @Test
    fun `invalid_credentials maps`() = runBlocking {
        enqueue(mockResponse(401, errorBody("invalid_credentials")))
        assertTrue(
            client.rotate(dummyRequest(), oldIdentity).exceptionOrNull()
                is KeyRotationError.InvalidCredentials
        )
    }

    @Test
    fun `invalid_nonce and invalid_new_key_proof map`() = runBlocking {
        enqueue(mockResponse(401, errorBody("invalid_nonce")))
        assertTrue(
            client.rotate(dummyRequest(), oldIdentity).exceptionOrNull()
                is KeyRotationError.InvalidNonce
        )
        enqueue(mockResponse(422, errorBody("invalid_new_key_proof")))
        assertTrue(
            client.rotate(dummyRequest(), oldIdentity).exceptionOrNull()
                is KeyRotationError.InvalidNewKeyProof
        )
    }

    @Test
    fun `keep_identity and nostr_link slugs map`() = runBlocking {
        enqueue(mockResponse(422, errorBody("keep_identity_not_allowed")))
        assertTrue(
            client.rotate(dummyRequest(), oldIdentity).exceptionOrNull()
                is KeyRotationError.KeepIdentityNotAllowed
        )
        enqueue(mockResponse(422, errorBody("keep_identity_needs_conversion")))
        assertTrue(
            client.rotate(dummyRequest(), oldIdentity).exceptionOrNull()
                is KeyRotationError.KeepIdentityNeedsConversion
        )
        enqueue(mockResponse(403, errorBody("nostr_link_disabled")))
        assertTrue(
            client.rotate(dummyRequest(), oldIdentity).exceptionOrNull()
                is KeyRotationError.NostrLinkDisabled
        )
        enqueue(mockResponse(409, errorBody("identity_already_linked")))
        assertTrue(
            client.rotate(dummyRequest(), oldIdentity).exceptionOrNull()
                is KeyRotationError.IdentityAlreadyLinked
        )
    }

    @Test
    fun `key_rotation_disabled and fallback codes map`() = runBlocking {
        enqueue(mockResponse(403, errorBody("key_rotation_disabled")))
        assertTrue(
            client.challenge(oldIdentity).exceptionOrNull()
                is KeyRotationError.KeyRotationDisabled
        )
        enqueue(mockResponse(423, errorBody("account_locked", ",\"retry_after_seconds\":120")))
        val locked = client.rotate(dummyRequest(), oldIdentity).exceptionOrNull()
        assertTrue(locked is KeyRotationError.AccountLocked)
        assertEquals(120L, (locked as KeyRotationError.AccountLocked).retryAfterSeconds)
        // Unknown slug + unexpected code → generic server error, never a crash.
        enqueue(mockResponse(500, errorBody("boom")))
        assertTrue(
            client.cleanup(newIdentity).exceptionOrNull() is KeyRotationError.Server
        )
    }

    @Test
    fun `malformed restore event json fails without a request`() = runBlocking {
        val result = client.restore(listOf("not json {"), newIdentity)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is KeyRotationError.Unknown)
    }

    private fun dummyRequest() = KeyRotateRequest(
        nonce = "abc",
        newPubkey = "ab".repeat(32),
        newKeyProof = "cd".repeat(64),
        newVerifier = "12".repeat(32),
        newBlob = blob
    )
}
