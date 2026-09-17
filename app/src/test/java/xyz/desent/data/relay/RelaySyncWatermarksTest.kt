package xyz.desent.data.relay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The relay-sync cursors that put `since` into the persistent REQ filters:
 * monotonic max per (account, scope), clamped to "now", persisted in
 * DataStore, and clearable per account.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelaySyncWatermarksTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private val pubkey = "aa".repeat(32)
    private val otherPubkey = "bb".repeat(32)

    private fun TestScope.newStore(fileName: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())) {
            tmpFolder.newFile(fileName)
        }

    private fun TestScope.newWatermarks(store: DataStore<Preferences>) =
        RelaySyncWatermarks(store, CoroutineScope(StandardTestDispatcher(testScheduler)))

    @Test
    fun `since is null before the scope ever synced`() = runTest {
        val watermarks = newWatermarks(newStore("never.preferences_pb"))

        assertNull(watermarks.sinceFilterFor(pubkey, RelaySyncWatermarks.SCOPE_GIFT_WRAP))
    }

    @Test
    fun `since is the newest recorded event minus the safety margin`() = runTest {
        val watermarks = newWatermarks(newStore("basic.preferences_pb"))

        watermarks.record(pubkey, RelaySyncWatermarks.SCOPE_GIFT_WRAP, 10_000L)

        assertEquals(
            10_000L - RelaySyncWatermarks.SINCE_MARGIN_SECONDS,
            watermarks.sinceFilterFor(pubkey, RelaySyncWatermarks.SCOPE_GIFT_WRAP)
        )
    }

    @Test
    fun `older events never lower the cursor`() = runTest {
        val watermarks = newWatermarks(newStore("monotonic.preferences_pb"))

        watermarks.record(pubkey, RelaySyncWatermarks.SCOPE_CALENDAR, 10_000L)
        watermarks.record(pubkey, RelaySyncWatermarks.SCOPE_CALENDAR, 4_000L)

        assertEquals(
            10_000L - RelaySyncWatermarks.SINCE_MARGIN_SECONDS,
            watermarks.sinceFilterFor(pubkey, RelaySyncWatermarks.SCOPE_CALENDAR)
        )
    }

    @Test
    fun `future event timestamps are clamped to now`() = runTest {
        val watermarks = newWatermarks(newStore("clamp.preferences_pb"))
        val farFuture = System.currentTimeMillis() / 1000L + 100_000L

        watermarks.record(pubkey, RelaySyncWatermarks.SCOPE_PRIVATE_STORAGE, farFuture)

        // A skewed sender clock must not push the cursor past events that
        // have not arrived yet — the cursor can never lead "now".
        val since = watermarks.sinceFilterFor(pubkey, RelaySyncWatermarks.SCOPE_PRIVATE_STORAGE)!!
        assertTrue("since $since should not lead now", since <= System.currentTimeMillis() / 1000L)
    }

    @Test
    fun `cursors persist through the debounced flush`() = runTest {
        val store = newStore("debounce.preferences_pb")
        val watermarks = newWatermarks(store)

        watermarks.record(pubkey, RelaySyncWatermarks.SCOPE_MAILBOX, 50_000L)
        // No explicit flush — the debounce job (5s) must land the write.
        advanceTimeBy(10_000)
        runCurrent()

        // A fresh instance (same DataStore = "app restart") reads it back.
        val reopened = newWatermarks(store)
        assertEquals(
            50_000L - RelaySyncWatermarks.SINCE_MARGIN_SECONDS,
            reopened.sinceFilterFor(pubkey, RelaySyncWatermarks.SCOPE_MAILBOX)
        )
    }

    @Test
    fun `reset clears only the targeted account`() = runTest {
        val watermarks = newWatermarks(newStore("reset.preferences_pb"))

        watermarks.record(pubkey, RelaySyncWatermarks.SCOPE_GIFT_WRAP, 10_000L)
        watermarks.record(otherPubkey, RelaySyncWatermarks.SCOPE_GIFT_WRAP, 20_000L)
        watermarks.flush()

        watermarks.reset(pubkey)

        assertNull(watermarks.sinceFilterFor(pubkey, RelaySyncWatermarks.SCOPE_GIFT_WRAP))
        assertEquals(
            20_000L - RelaySyncWatermarks.SINCE_MARGIN_SECONDS,
            watermarks.sinceFilterFor(otherPubkey, RelaySyncWatermarks.SCOPE_GIFT_WRAP)
        )
    }

    @Test
    fun `withSince leaves the filter untouched when never synced`() {
        val filter = mapOf("kinds" to listOf(1059), "limit" to 100)

        assertEquals(filter, filter.withSince(null))
        assertEquals(
            filter + ("since" to 42L),
            filter.withSince(42L)
        )
    }
}
