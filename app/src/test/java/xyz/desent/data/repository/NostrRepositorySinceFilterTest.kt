package xyz.desent.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import nostr.id.Identity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.nostr.NostrEventProcessor
import xyz.desent.data.relay.RelaySyncWatermarks
import xyz.desent.domain.repository.RelayRepository

/**
 * The `since` cursor injection: after an account has synced a scope once,
 * its persistent REQ filters must ask the relay only for events newer than
 * the newest one already ingested (minus a clock-skew margin) — the fix for
 * re-downloading the full limit-N backlogs on every app open and reconnect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NostrRepositorySinceFilterTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private val identity = Identity.create("33".repeat(32))
    private val pubkeyHex = identity.publicKey.toHexString()

    private lateinit var relayRepository: RelayRepository
    private lateinit var secureKeyManager: SecureKeyManager

    /** Every filter list handed to subscribeToEvents / subscribeToEventsOnRelay. */
    private val subscribeCalls = mutableListOf<List<Map<String, Any>>>()
    private val relaySubscribeCalls = mutableListOf<List<Map<String, Any>>>()

    @Before
    fun setUp() {
        relayRepository = mockk(relaxed = true)
        secureKeyManager = mockk()
        coEvery { secureKeyManager.getIdentityFromStoredNSEC() } returns Result.success(identity)
        coEvery {
            relayRepository.subscribeToEvents(any(), any<String>(), any<Boolean>())
        } coAnswers {
            subscribeCalls.add(firstArg<List<Map<String, Any>>>())
            Unit
        }
        coEvery {
            relayRepository.subscribeToEventsOnRelay(any(), any<String>(), any<String>(), any<Boolean>())
        } coAnswers {
            relaySubscribeCalls.add(firstArg<List<Map<String, Any>>>())
            Unit
        }
    }

    private fun TestScope.repositoryWith(watermarks: RelaySyncWatermarks?): NostrRepository =
        NostrRepository(relayRepository, secureKeyManager, mockk(relaxed = true), watermarks)

    private fun TestScope.watermarks(): RelaySyncWatermarks {
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        ) { tmpFolder.newFile("since-${System.nanoTime()}.preferences_pb") }
        return RelaySyncWatermarks(store, CoroutineScope(StandardTestDispatcher(testScheduler)))
    }

    /** A cursor timestamp safely in the past so the "now" clamp never bites. */
    private val recordedAt = System.currentTimeMillis() / 1000L - 86_400L

    @Test
    fun `own-kind REQs carry since from the recorded cursor`() = runTest {
        val watermarks = watermarks()
        watermarks.record(pubkeyHex, RelaySyncWatermarks.SCOPE_PRIVATE_STORAGE, recordedAt)
        watermarks.flush()

        repositoryWith(watermarks).restoreIdentity()

        val ownPriv = subscribeCalls.flatten()
            .first { filter -> (filter["kinds"] as? List<*>)?.contains(30078) == true }
        assertEquals(
            recordedAt - RelaySyncWatermarks.SINCE_MARGIN_SECONDS,
            ownPriv["since"]
        )
    }

    @Test
    fun `first-ever sync omits since and covers full history`() = runTest {
        repositoryWith(watermarks()).restoreIdentity()

        assertTrue(subscribeCalls.isNotEmpty())
        subscribeCalls.flatten().forEach {
            assertFalse("no filter should carry since on first sync", it.containsKey("since"))
        }
    }

    @Test
    fun `gift-wrap REQ carries since in both filters`() = runTest {
        val watermarks = watermarks()
        watermarks.record(pubkeyHex, RelaySyncWatermarks.SCOPE_GIFT_WRAP, recordedAt)
        watermarks.flush()

        val repository = repositoryWith(watermarks)
        repository.restoreIdentity()
        repository.subscribeToGiftWraps()

        val giftWrapFilters = relaySubscribeCalls.flatten()
            .filter { filter -> (filter["kinds"] as? List<*>)?.contains(1059) == true }
        assertEquals(2, giftWrapFilters.size)
        giftWrapFilters.forEach { filter ->
            assertEquals(
                recordedAt - RelaySyncWatermarks.SINCE_MARGIN_SECONDS,
                filter["since"]
            )
        }
    }
}
