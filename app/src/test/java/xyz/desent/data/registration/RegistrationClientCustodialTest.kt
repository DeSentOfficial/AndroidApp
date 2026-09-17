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
import xyz.desent.crypto.CustodialCrypto
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.registration.model.CustodialCredentialsRequest
import xyz.desent.data.registration.model.CustodialLoginChallengeRequest
import xyz.desent.data.registration.model.CustodialLoginRequest
import xyz.desent.data.registration.model.CustodialRegisterRequest
import xyz.desent.data.registration.model.RegistrationError

/**
 * Custodial-account endpoint coverage for [RegistrationClient] against
 * refs/FromServer/CUSTODIAL_ACCOUNTS.md §2–§6.
 */
class RegistrationClientCustodialTest {

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var auth: NostrHttpAuth
    private lateinit var client: RegistrationClient

    private val requestSlot = slot<Request>()

    // Official NIP-49 vector string — the client treats it as opaque.
    private val ncryptsec =
        "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd" +
            "67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r" +
            "89qk5z2zxfmu5gv8th8wclt0h4p"

    private val blob = CustodialCrypto.Blob(
        salt = "c2FsdA==",
        ncryptsec = ncryptsec
    )

    private val legacyBlob = CustodialCrypto.Blob(
        v = 1,
        salt = "c2FsdA==",
        nonce = "bm9uY2U=",
        ct = "Y3Q="
    )

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
    fun suggest_isPublicGet() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"names":["amberfalcon","bravelotus"],"domain":"desent.xyz","min_length":8}"""
            )
        )

        val result = client.suggestUsernames(count = 2)

        assertTrue(result.isSuccess)
        assertEquals(listOf("amberfalcon", "bravelotus"), result.getOrNull()!!.names)
        assertEquals(8, result.getOrNull()!!.minLength)
        val url = requestSlot.captured.url.toString()
        assertTrue("unexpected suggest URL: $url", url.endsWith("/api/custodial/suggest?count=2"))
        assertNull(requestSlot.captured.header("Authorization"))
    }

    @Test
    fun challenge_parsesKdfParams_andSendsNoAuth() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"kdf":"argon2id","kdf_params":{"m":65536,"t":3,"p":1},""" +
                    """"salt":"c2FsdA==","v":2,"domain":"desent.xyz"}"""
            )
        )

        val result = client.custodialLoginChallenge(
            CustodialLoginChallengeRequest(username = "bravefalcon")
        )

        assertTrue(result.isSuccess)
        val challenge = result.getOrNull()!!
        assertEquals("argon2id", challenge.kdf)
        assertEquals(CustodialCrypto.KdfParams(m = 65536, t = 3, p = 1), challenge.kdfParams)
        assertEquals("c2FsdA==", challenge.salt)
        assertEquals(2, challenge.v)
        assertNull(requestSlot.captured.header("Authorization"))
        val sent = okio.Buffer().also { requestSlot.captured.body!!.writeTo(it) }.readUtf8()
        assertTrue("username missing from body: $sent", sent.contains("\"username\":\"bravefalcon\""))
    }

    @Test
    fun challenge_withoutVersionField_defaultsToLegacyV1() = runBlocking {
        // An older server (or a decoy response) omitting `v` must not crash
        // and must route to the legacy path.
        enqueue(
            mockResponse(
                200,
                """{"kdf":"argon2id","kdf_params":{"m":65536,"t":3,"p":1},"salt":"c2FsdA=="}"""
            )
        )

        val challenge = client.custodialLoginChallenge(
            CustodialLoginChallengeRequest(username = "bravefalcon")
        ).getOrNull()!!

        assertEquals(1, challenge.v)
    }

    @Test
    fun login_parsesLegacyBlobVerbatim() = runBlocking {
        val blobJson = """{"v":1,"kdf":"argon2id","kdf_params":{"m":65536,"t":3,"p":1},""" +
            """"salt":"c2FsdA==","nonce":"bm9uY2U=","ct":"Y3Q="}"""
        enqueue(
            mockResponse(
                200,
                """{"username":"bravefalcon","domain":"desent.xyz",""" +
                    """"nip05":"bravefalcon@desent.xyz","pubkey":"ab12","npub":"npub1x",""" +
                    """"blob":$blobJson}"""
            )
        )

        val result = client.custodialLogin(
            CustodialLoginRequest(username = "bravefalcon", verifier = "ff".repeat(32))
        )

        assertTrue(result.isSuccess)
        val response = result.getOrNull()!!
        assertEquals("ab12", response.pubkey)
        assertEquals(legacyBlob, response.blob)
        assertNull(requestSlot.captured.header("Authorization"))
    }

    @Test
    fun login_parsesV2BlobVerbatim() = runBlocking {
        val blobJson = """{"v":2,"kdf":"argon2id","kdf_params":{"m":65536,"t":3,"p":1},""" +
            """"salt":"c2FsdA==","ncryptsec":"$ncryptsec"}"""
        enqueue(
            mockResponse(
                200,
                """{"username":"bravefalcon","domain":"desent.xyz",""" +
                    """"nip05":"bravefalcon@desent.xyz","pubkey":"ab12","npub":"npub1x",""" +
                    """"blob":$blobJson}"""
            )
        )

        val result = client.custodialLogin(
            CustodialLoginRequest(username = "bravefalcon", verifier = "ff".repeat(32))
        )

        assertTrue(result.isSuccess)
        assertEquals(blob, result.getOrNull()!!.blob)
    }

    @Test
    fun custodialRegister_serializesBlobAndSignsPayload() = runBlocking {
        var signedPayload: ByteArray? = null
        coEvery { auth.buildAuthHeader(any(), any(), any()) } answers {
            signedPayload = thirdArg()
            Result.success("Nostr test-event")
        }
        enqueue(
            mockResponse(
                201,
                """{"registered":true,"username":"bravefalcon","domain":"desent.xyz",""" +
                    """"nip05":"bravefalcon@desent.xyz","pubkey":"ab12","npub":"npub1x",""" +
                    """"mode":"open","referral_redeemed":false}"""
            )
        )

        val result = client.custodialRegister(
            CustodialRegisterRequest(
                username = "bravefalcon",
                verifier = "ff".repeat(32),
                blob = blob,
                referralCode = "DS-ARMXH2-MH7YFC"
            )
        )

        assertTrue(result.isSuccess)
        assertEquals("bravefalcon@desent.xyz", result.getOrNull()!!.nip05)
        assertEquals("Nostr test-event", requestSlot.captured.header("Authorization"))
        val sent = okio.Buffer().also { requestSlot.captured.body!!.writeTo(it) }.readUtf8()
        assertTrue("verifier missing: $sent", sent.contains("\"verifier\":\"${"ff".repeat(32)}\""))
        assertTrue("referral_code missing: $sent",
            sent.contains("\"referral_code\":\"DS-ARMXH2-MH7YFC\""))
        assertTrue("blob salt missing: $sent", sent.contains("\"salt\":\"c2FsdA==\""))
        assertTrue("v2 marker missing: $sent", sent.contains("\"v\":2"))
        assertTrue("ncryptsec missing: $sent", sent.contains("\"ncryptsec\":\"ncryptsec1"))
        assertTrue("legacy fields must be omitted: $sent", !sent.contains("\"nonce\"") && !sent.contains("\"ct\""))
        assertTrue("payload bytes not signed", signedPayload != null)
    }

    @Test
    fun changeCredentials_signsWithAuth_andRoundTripsOk() = runBlocking {
        enqueue(mockResponse(200, """{"ok":true,"username":"bravefalcon","domain":"desent.xyz"}"""))

        val result = client.changeCustodialCredentials(
            CustodialCredentialsRequest(
                oldVerifier = "11".repeat(32),
                newVerifier = "22".repeat(32),
                newBlob = blob
            )
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!.ok)
        assertEquals("Nostr test-event", requestSlot.captured.header("Authorization"))
        val sent = okio.Buffer().also { requestSlot.captured.body!!.writeTo(it) }.readUtf8()
        assertTrue("old_verifier missing: $sent", sent.contains("\"old_verifier\":\"${"11".repeat(32)}\""))
        assertTrue("new_blob missing: $sent", sent.contains("\"new_blob\""))
    }

    // ---- error matrix (CUSTODIAL_ACCOUNTS.md §2/§3) ------------------------

    @Test
    fun loginErrors_mapToTypedFailures() = runBlocking {
        enqueue(mockResponse(401, errorBody("invalid_credentials")))
        assertTrue(
            client.custodialLogin(CustodialLoginRequest("bravefalcon", null, "ff".repeat(32)))
                .exceptionOrNull() is RegistrationError.InvalidCredentials
        )

        enqueue(mockResponse(403, errorBody("custodial_disabled")))
        assertTrue(
            client.custodialLogin(CustodialLoginRequest("bravefalcon", null, "ff".repeat(32)))
                .exceptionOrNull() is RegistrationError.CustodialDisabled
        )
    }

    @Test
    fun accountLocked_parsesRetryAfter() = runBlocking {
        enqueue(mockResponse(423, errorBody("account_locked", ",\"retry_after_seconds\":900")))

        val error = client.custodialLogin(
            CustodialLoginRequest("bravefalcon", null, "ff".repeat(32))
        ).exceptionOrNull()

        assertTrue(error is RegistrationError.AccountLocked)
        assertEquals(900L, (error as RegistrationError.AccountLocked).retryAfterSeconds)
    }

    @Test
    fun registerErrors_mapToTypedFailures() = runBlocking {
        val request = CustodialRegisterRequest(
            username = "bravefalcon",
            verifier = "ff".repeat(32),
            blob = blob
        )
        val cases: List<Triple<Int, String, RegistrationError>> = listOf(
            Triple(403, "custodial_disabled", RegistrationError.CustodialDisabled),
            Triple(422, "too_short", RegistrationError.TooShort(8)),
            Triple(409, "taken", RegistrationError.Taken),
            Triple(409, "already_registered", RegistrationError.AlreadyRegistered),
            Triple(403, "referral_required", RegistrationError.ReferralRequired),
            Triple(403, "invalid_referral_code", RegistrationError.InvalidReferralCode),
            Triple(413, "blob_too_large", RegistrationError.BlobTooLarge),
            Triple(422, "invalid_verifier", RegistrationError.InvalidVerifier)
        )
        for ((code, wire, expected) in cases) {
            enqueue(mockResponse(code, errorBody(wire)))
            val error = client.custodialRegister(request).exceptionOrNull()
            assertTrue("expected $expected for $wire, got $error", error == expected)
        }
    }

    @Test
    fun tooShort_carriesServerMinLength() = runBlocking {
        enqueue(mockResponse(422, errorBody("too_short", ",\"length\":8")))

        val error = client.custodialRegister(
            CustodialRegisterRequest(username = "short", verifier = "ff".repeat(32), blob = blob)
        ).exceptionOrNull()

        assertTrue(error is RegistrationError.TooShort)
        assertEquals(8, (error as RegistrationError.TooShort).minLength)
    }

    @Test
    fun credentialsErrors_mapToTypedFailures() = runBlocking {
        val request = CustodialCredentialsRequest("11".repeat(32), "22".repeat(32), blob)

        enqueue(mockResponse(401, errorBody("invalid_credentials")))
        assertTrue(
            client.changeCustodialCredentials(request).exceptionOrNull()
                is RegistrationError.InvalidCredentials
        )

        enqueue(mockResponse(404, errorBody("not_custodial")))
        assertTrue(
            client.changeCustodialCredentials(request).exceptionOrNull()
                is RegistrationError.NotCustodial
        )
    }

    @Test
    fun rateLimited_mapsWithRetryAfter() = runBlocking {
        enqueue(mockResponse(429, errorBody("rate_limited", ",\"retry_after_seconds\":120")))

        val error = client.custodialLoginChallenge(
            CustodialLoginChallengeRequest("bravefalcon")
        ).exceptionOrNull()

        assertTrue(error is RegistrationError.RateLimited)
        assertEquals(120L, (error as RegistrationError.RateLimited).retryAfterSeconds)
    }
}
