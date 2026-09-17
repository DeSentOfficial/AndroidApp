package xyz.desent.data.nip05

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import xyz.desent.crypto.Bech32Utils
import xyz.desent.data.local.preferences.PreferencesManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Nip05VerificationServiceTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var client: OkHttpClient
    private lateinit var service: Nip05VerificationService

    @Before
    fun setUp() {
        client = mockk(relaxed = true)
        service = Nip05VerificationService(client)
    }

    private fun mockResponse(body: String): Response {
        val rb = mockk<ResponseBody>(relaxed = true)
        every { rb.string() } returns body
        val resp = mockk<Response>(relaxed = true)
        every { resp.isSuccessful } returns true
        every { resp.body } returns rb
        return resp
    }

    private fun mockCall(resp: Response) {
        val call = mockk<Call>()
        every { call.execute() } returns resp
        every { client.newCall(any()) } returns call
    }

    @Test
    fun fetchNip05Record_returnsRelaysFieldWhenPresent() = runBlocking {
        // The hex pubkey for "alice"; Bech32Utils.hexToNpub is stubbed.
        mockkObject(Bech32Utils)
        every { Bech32Utils.npubToHex(HEX_NPUB) } returns HEX_PUBKEY
        every { Bech32Utils.hexToNpub(HEX_PUBKEY) } returns HEX_NPUB

        val json = """
            {
              "names": { "alice": "$HEX_PUBKEY" },
              "relays": { "$HEX_PUBKEY": ["wss://relay.alice.com", "wss://nos.lol"] }
            }
        """.trimIndent()
        mockCall(mockResponse(json))

        val record = service.fetchNip05Record("alice@example.com")

        assertNotNull(record)
        assertEquals("alice@example.com", record!!.nip05)
        assertEquals(HEX_NPUB, record.npub)
        assertEquals(HEX_PUBKEY, record.hexPubkey)
        assertEquals(listOf("wss://relay.alice.com", "wss://nos.lol"), record.relays)
    }

    @Test
    fun fetchNip05Record_returnsEmptyRelaysWhenFieldAbsent() = runBlocking {
        mockkObject(Bech32Utils)
        every { Bech32Utils.npubToHex(HEX_NPUB) } returns HEX_PUBKEY
        every { Bech32Utils.hexToNpub(HEX_PUBKEY) } returns HEX_NPUB

        val json = """{ "names": { "alice": "$HEX_PUBKEY" } }""".trimIndent()
        mockCall(mockResponse(json))

        val record = service.fetchNip05Record("alice@example.com")

        assertNotNull(record)
        assertTrue(record!!.relays.isEmpty())
    }

    @Test
    fun fetchNip05Record_acceptsNpubFormattedNamesValue() = runBlocking {
        // Some servers return npub1... instead of hex in the names map.
        mockkObject(Bech32Utils)
        every { Bech32Utils.npubToHex(HEX_NPUB) } returns HEX_PUBKEY
        every { Bech32Utils.hexToNpub(HEX_PUBKEY) } returns HEX_NPUB

        val json = """
            {
              "names": { "alice": "$HEX_NPUB" },
              "relays": { "$HEX_PUBKEY": ["wss://relay.alice.com"] }
            }
        """.trimIndent()
        mockCall(mockResponse(json))

        val record = service.fetchNip05Record("alice@example.com")

        assertNotNull(record)
        assertEquals(listOf("wss://relay.alice.com"), record!!.relays)
    }

    @Test
    fun fetchNip05Record_returnsNullOnHttp404() = runBlocking {
        val resp = mockk<Response>(relaxed = true)
        every { resp.isSuccessful } returns false
        every { resp.code } returns 404
        mockCall(resp)

        val record = service.fetchNip05Record("nobody@example.com")

        assertNull(record)
    }

    @Test
    fun fetchNip05Record_returnsNullForMalformedIdentifier() = runBlocking {
        val record = service.fetchNip05Record("not-an-identifier")
        assertNull(record)
    }

    // ---- Persisted negative verdicts ---------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.negativeCacheBackedService(store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>): Nip05VerificationService {
        val preferencesManager = mockk<PreferencesManager>()
        every { preferencesManager.dataStore } returns store
        return Nip05VerificationService(client, preferencesManager)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.newNegativeStore() = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
    ) { tmpFolder.newFile("negatives-${System.nanoTime()}.preferences_pb") }

    @Test
    fun `definitive 404 verdicts persist and skip the network after a restart`() = runTest {
        val resp = mockk<Response>(relaxed = true)
        every { resp.isSuccessful } returns false
        every { resp.code } returns 404
        mockCall(resp)
        val store = newNegativeStore()

        val first = negativeCacheBackedService(store)
        assertTrue(first.verifyNip05("ghost@example.com") is Nip05VerificationService.Nip05Result.NotFound)

        // Fresh instance = process death; the in-memory cache is empty. The
        // persisted negative must answer without a second HTTP probe.
        val second = negativeCacheBackedService(store)
        assertTrue(second.verifyNip05("ghost@example.com") is Nip05VerificationService.Nip05Result.NotFound)

        verify(exactly = 1) { client.newCall(any()) }
    }

    @Test
    fun `transient failures are not persisted as negative`() = runTest {
        val resp = mockk<Response>(relaxed = true)
        every { resp.isSuccessful } returns false
        every { resp.code } returns 500
        mockCall(resp)
        val store = newNegativeStore()

        val first = negativeCacheBackedService(store)
        assertTrue(first.verifyNip05("flaky@example.com") is Nip05VerificationService.Nip05Result.DomainUnreachable)

        // No persisted negative → the fresh instance retries over the network.
        val second = negativeCacheBackedService(store)
        assertTrue(second.verifyNip05("flaky@example.com") is Nip05VerificationService.Nip05Result.DomainUnreachable)

        verify(exactly = 2) { client.newCall(any()) }
    }

    @After
    fun tearDown() {
        // Best-effort unmock; ignore if nothing was mocked (hexToNpub path may
        // not have run in 404/malformed tests).
        try {
            unmockkObject(Bech32Utils)
        } catch (e: Exception) {
            // not mocked in this test — fine
        }
    }

    companion object {
        private const val HEX_PUBKEY = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val HEX_NPUB = "npub1aaaa"
    }
}
