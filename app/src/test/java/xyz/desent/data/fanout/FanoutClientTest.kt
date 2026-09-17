package xyz.desent.data.fanout

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
import xyz.desent.data.fanout.model.FanoutError

/**
 * [FanoutClient] coverage against
 * refs/FROM_email.desent.xyz/ANDROID_DM_FANOUT.md §4.
 */
class FanoutClientTest {

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var auth: NostrHttpAuth
    private lateinit var client: FanoutClient

    private val requestSlot = slot<Request>()

    @Before
    fun setUp() {
        okHttpClient = mockk(relaxed = true)
        auth = mockk()
        coEvery { auth.buildAuthHeader(any(), any(), any()) } returns
            Result.success("Nostr test-event")
        client = FanoutClient(okHttpClient, auth)
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
    fun `status parses per-relay queue health`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"relays":[
                      {"url":"wss://relay.example.com","pending":2,"done":12,"dead":0,
                       "last_activity":"2026-08-29T12:00:00+00:00"},
                      {"url":"wss://other.example.com","pending":0,"done":0,"dead":1}
                    ],
                    "total_pending":2,"total_dead":1}"""
            )
        )

        val result = client.getStatus()

        assertTrue(result.isSuccess)
        val status = result.getOrNull()!!
        assertEquals(2, status.relays.size)
        assertEquals(2, status.relays[0].pending)
        assertEquals(12, status.relays[0].done)
        assertEquals("2026-08-29T12:00:00+00:00", status.relays[0].lastActivity)
        assertEquals(2, status.totalPending)
        assertEquals(1, status.totalDead)
        assertTrue(requestSlot.captured.url.toString().endsWith("/api/fanout/status"))
    }

    @Test
    fun `401 maps to unauthorized`() = runBlocking {
        enqueue(mockResponse(401, """{"detail":"auth required"}"""))

        assertTrue(client.getStatus().exceptionOrNull() is FanoutError.Unauthorized)
    }

    // ---------------- §6 mirror surface (migration 056) ----------------

    @Test
    fun `relayCheck carries the url in the query and parses the verdict`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"url":"wss://relay.example.com","usable":true,
                    "directory":{"status":"online","uptime_7d":0.98,"rtt_open_ms":120,
                                 "name":"Example","supported_nips":[1,2,17]},
                    "nip11":{"source":"relay","name":"Example"},
                    "capabilities":{"missing":[{"nip":40,"why":"deletes wouldn't propagate"}],
                                    "unknown":false,"notes":[]},
                    "checked_at":"2026-09-16T12:00:00+00:00"}"""
            )
        )

        val result = client.relayCheck("wss://relay.example.com")

        assertTrue(result.isSuccess)
        val check = result.getOrNull()!!
        assertTrue(check.usable)
        assertEquals(0.98, check.directory!!.uptime7d!!, 0.0001)
        assertEquals(listOf(40), check.capabilities.missing.map { it.nip })
        val url = requestSlot.captured.url.toString()
        assertTrue("relay-check URL: $url", url.contains("/api/fanout/relay-check?url="))
        // NIP-98 `u` tag is scheme+host+path only — the query is signed-less context.
        assertEquals("Nostr test-event", requestSlot.captured.header("Authorization"))
    }

    @Test
    fun `eventsStatus posts the id batch and parses per-relay delivery`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"events":[
                      {"event_id":"aa11","relays":[
                        {"url":"wss://relay.example.com","status":"done","attempts":1},
                        {"url":"wss://other.example.com","status":"pending","attempts":2}]}
                    ]}"""
            )
        )

        val result = client.eventsStatus(listOf("aa11", "bb22"))

        assertTrue(result.isSuccess)
        val events = result.getOrNull()!!.events
        assertEquals(1, events.size)
        assertEquals("aa11", events[0].eventId)
        assertEquals(2, events[0].relays.size)
        assertEquals("pending", events[0].relays[1].status)
        val sent = okio.Buffer().also { requestSlot.captured.body!!.writeTo(it) }.readUtf8()
        assertTrue("event_ids missing from body: $sent", sent.contains("\"event_ids\":[\"aa11\",\"bb22\"]"))
        assertTrue(requestSlot.captured.url.toString().endsWith("/api/fanout/events-status"))
    }

    @Test
    fun `reconcile posts empty and parses the mirror report`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"mirrors":[
                      {"url":"wss://relay.example.com","seen":12,
                       "missing_locally":["aa11"],"missing_locally_truncated":false,
                       "locally_deleted":2}
                    ]}"""
            )
        )

        val result = client.reconcile()

        assertTrue(result.isSuccess)
        val mirror = result.getOrNull()!!.mirrors.single()
        assertEquals(12, mirror.seen)
        assertEquals(listOf("aa11"), mirror.missingLocally)
        assertEquals(2, (mirror.locallyDeleted as kotlinx.serialization.json.JsonPrimitive).content.toInt())
    }

    @Test
    fun `import posts the id batch and parses the outcome`() = runBlocking {
        enqueue(mockResponse(200, """{"ok":true,"imported":2,"skipped":1}"""))

        val result = client.importWraps(listOf("aa11", "bb22"))

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()!!.imported)
        assertEquals(1, result.getOrNull()!!.skipped)
        assertTrue(requestSlot.captured.url.toString().endsWith("/api/fanout/import"))
    }

    @Test
    fun `mirror endpoints map not_entitled and fanout_disabled`() = runBlocking {
        enqueue(mockResponse(403, """{"detail":{"error":"not_entitled"}}"""))
        assertTrue(client.reconcile().exceptionOrNull() is FanoutError.NotEntitled)

        enqueue(mockResponse(503, """{"detail":{"error":"fanout_disabled"}}"""))
        assertTrue(client.importWraps(listOf("aa")).exceptionOrNull() is FanoutError.FanoutDisabled)

        // Plain-string detail (older error shape) still maps by code.
        enqueue(mockResponse(401, """{"detail":"auth required"}"""))
        assertTrue(client.eventsStatus(listOf("aa")).exceptionOrNull() is FanoutError.Unauthorized)
    }
}
