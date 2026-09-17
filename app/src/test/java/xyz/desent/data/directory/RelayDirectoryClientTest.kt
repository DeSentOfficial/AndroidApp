package xyz.desent.data.directory

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
import xyz.desent.data.directory.model.DirectoryError

/**
 * [RelayDirectoryClient] coverage against
 * refs/FROM_directory.desent.xyz/API.md (pulled from directory.yadha.net).
 */
class RelayDirectoryClientTest {

    private lateinit var okHttpClient: OkHttpClient
    private lateinit var client: RelayDirectoryClient

    private val requestSlot = slot<Request>()

    @Before
    fun setUp() {
        okHttpClient = mockk(relaxed = true)
        client = RelayDirectoryClient(okHttpClient, baseUrl = "https://directory.yadha.net")
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
    fun `fanout search targets clearnet online free relays`() = runBlocking {
        enqueue(mockResponse(200, """{"stats":{"total":81},"relays":[]}"""))

        client.searchFanoutRelays(query = "primal").getOrThrow()

        val url = requestSlot.captured.url
        assertEquals("directory.yadha.net", url.host)
        assertEquals("/api/relays", url.encodedPath)
        assertEquals("clearnet", url.queryParameter("network"))
        assertEquals("online", url.queryParameter("status"))
        assertEquals("free", url.queryParameter("plan"))
        assertEquals("primal", url.queryParameter("q"))
    }

    @Test
    fun `blank query is omitted from the request`() = runBlocking {
        enqueue(mockResponse(200, """{"relays":[]}"""))

        client.searchFanoutRelays(query = "  ").getOrThrow()

        assertNull(requestSlot.captured.url.queryParameter("q"))
    }

    @Test
    fun `relays parse with supported nips and uptime`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"relays":[
                      {"id":624,"url":"wss://premium.primal.net","host":"premium.primal.net",
                       "network":"clearnet","status":"online","name":"Primal Premium Relay",
                       "supported_nips":[1,2,4,9,11,22,28,40,70,77],
                       "rtt_open_ms":424,"rtt_read_ms":143,"uptime_7d":1.0,
                       "is_premium":false,"monitor_count":1}
                    ]}"""
            )
        )

        val relays = client.searchFanoutRelays().getOrThrow()

        assertEquals(1, relays.size)
        val relay = relays[0]
        assertEquals("wss://premium.primal.net", relay.url)
        assertEquals(listOf(1, 2, 4, 9, 11, 22, 28, 40, 70, 77), relay.supportedNips)
        assertEquals(424L, relay.rttOpenMs)
        assertEquals(143L, relay.rttReadMs)
        assertEquals(1.0, relay.uptime7d!!, 0.0001)
        assertEquals(false, relay.isPremium)
    }

    @Test
    fun `429 maps to rate limited`() = runBlocking {
        enqueue(mockResponse(429, ""))

        assertTrue(client.searchFanoutRelays().exceptionOrNull() is DirectoryError.RateLimited)
    }

    @Test
    fun `detail encodes the id as stripped base64url`() = runBlocking {
        enqueue(mockResponse(200, """{"id":624,"url":"wss://premium.primal.net"}"""))

        client.getRelayDetail("wss://premium.primal.net").getOrThrow()

        val url = requestSlot.captured.url
        assertEquals("/api/relays/d3NzOi8vcHJlbWl1bS5wcmltYWwubmV0", url.encodedPath)
    }

    @Test
    fun `detail 404 is null not failure`() = runBlocking {
        enqueue(mockResponse(404, """{"detail":"relay not found"}"""))

        val result = client.getRelayDetail("wss://unknown.example.com")

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun `detail parses icon and probes for a full row`() = runBlocking {
        enqueue(
            mockResponse(
                200,
                """{"id":624,"url":"wss://premium.primal.net","name":"Primal Premium Relay",
                    "icon":"https://blossom.primal.net/icon.png","status":"online",
                    "network":"clearnet","supported_nips":[1,9],
                    "rtt_open_ms":424,"rtt_read_ms":143,"uptime_7d":0.99,
                    "is_premium":false}"""
            )
        )

        val relay = client.getRelayDetail("wss://premium.primal.net").getOrThrow()!!

        assertEquals("Primal Premium Relay", relay.name)
        assertEquals("https://blossom.primal.net/icon.png", relay.icon)
        assertEquals(424L, relay.rttOpenMs)
        assertEquals(143L, relay.rttReadMs)
        assertEquals(0.99, relay.uptime7d!!, 0.0001)
    }
}
