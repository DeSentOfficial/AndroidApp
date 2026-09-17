package xyz.desent.data.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.data.RelayConfig

/**
 * Validates the NIP-51 spam-list manifest parser (see refs/SPAM_LIST_REFERENCE.md
 * §"content — manifest JSON"). The signature/publisher pinning is exercised by
 * [matches] at runtime; here we cover the content contract.
 */
class SpamListParserTest {

    private val syncer = SpamListSyncer(relayRepository = throwawayRelay())

    @Test
    fun parsesFullManifest_andLowercasesEntries() {
        val json = """
            {"version":7,"updatedAt":1700000000,"ttlHours":12,
             "blockedDomains":["Bad.Example","MAILINATOR.com"],
             "allowedDomains":["GitHub.com"],
             "blockedSenders":["spammer@BAD.example"],
             "spamPatterns":["(?i)buy\\\\s+crypto","viagra"],
             "trustedBridgeDomains":["desent.xyz"]}
        """.trimIndent()
        val manifest = syncer.parseManifest(json)
        assertNotNull(manifest)
        manifest!!
        assertEquals(7L, manifest.version)
        assertEquals(1700000000L, manifest.updatedAt)
        assertEquals(12, manifest.ttlHours)
        assertEquals(listOf("bad.example", "mailinator.com"), manifest.blockedDomains)
        assertEquals(listOf("github.com"), manifest.allowedDomains)
        assertEquals(listOf("spammer@bad.example"), manifest.blockedSenders)
        assertEquals(2, manifest.spamPatterns.size)
        assertEquals(listOf("desent.xyz"), manifest.trustedBridgeDomains)
    }

    @Test
    fun missingOptionalFields_defaultSafely() {
        val manifest = syncer.parseManifest("""{"version":1,"updatedAt":0}""")
        assertNotNull(manifest)
        manifest!!
        assertEquals(24, manifest.ttlHours)
        assertTrue(manifest.blockedDomains.isEmpty())
        assertTrue(manifest.allowedDomains.isEmpty())
    }

    @Test
    fun malformedJson_returnsNull() {
        assertNull(syncer.parseManifest("not json"))
        assertNull(syncer.parseManifest(""))
    }

    @Test
    fun publisherKeyAndDArePinned() {
        // Guards against accidental rotation of the trust root or list id.
        assertEquals(
            "fe910afb2f1330b95d024857f1abd951baaedbd26450e93bd09c241ca33379a6",
            RelayConfig.SPAM_LIST_PUBKEY_HEX
        )
        assertEquals("desent-spamlist-v1", RelayConfig.SPAM_LIST_D_TAG)
        assertEquals(30000, RelayConfig.SPAM_LIST_KIND)
    }
}

/** A no-op relay repo — the parser tests never touch the network. */
private fun throwawayRelay(): xyz.desent.domain.repository.RelayRepository =
    object : xyz.desent.domain.repository.RelayRepository {
        override suspend fun saveRelay(relay: xyz.desent.domain.model.Relay) {}
        override suspend fun saveRelays(relays: List<xyz.desent.domain.model.Relay>) {}
        override suspend fun updateRelay(relay: xyz.desent.domain.model.Relay) {}
        override fun observeAllRelays() = kotlinx.coroutines.flow.flowOf(emptyList<xyz.desent.domain.model.Relay>())
        override fun observeActiveRelays() = kotlinx.coroutines.flow.flowOf(emptyList<xyz.desent.domain.model.Relay>())
        override suspend fun updateConnectionStatus(url: String, status: xyz.desent.domain.model.ConnectionStatus, timestamp: Long?) {}
        override suspend fun updateFailureCount(url: String, failureCount: Int) {}
        override suspend fun updateActiveStatus(url: String, isActive: Boolean) {}
        override suspend fun deleteRelay(url: String) {}
        override suspend fun connectToRelay(url: String) {}
        override suspend fun waitForRelayReady(url: String, timeoutMs: Long): Boolean = true
        override suspend fun addPersistentRelay(url: String) {}
        override suspend fun connectToPersistentRelays() {}
        override suspend fun disconnectFromRelay(url: String) {}
        override suspend fun disconnectFromAllRelays() {}
        override fun observeConnectionStatus(url: String) = kotlinx.coroutines.flow.flowOf(xyz.desent.domain.model.ConnectionStatus.DISCONNECTED)
        override fun observeEvents() = kotlinx.coroutines.flow.MutableSharedFlow<nostr.event.impl.GenericEvent>()
        override val publishResults = kotlinx.coroutines.flow.MutableSharedFlow<xyz.desent.data.relay.PublishResult>()
        override val eoseEvents = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, String>>()
        override val closedEvents =
            kotlinx.coroutines.flow.MutableSharedFlow<xyz.desent.data.relay.NostrWebSocketClient.ClosedNotification>()
        override suspend fun publishEventToRelay(event: nostr.event.impl.GenericEvent, relayUrl: String) {}
        override suspend fun subscribeToEvents(filters: List<Map<String, Any>>, subscriptionId: String, persistent: Boolean) {}
        override suspend fun subscribeToEventsOnRelay(filters: List<Map<String, Any>>, subscriptionId: String, relayUrl: String, persistent: Boolean) {}
        override suspend fun unsubscribeFromEvents(subscriptionId: String) {}
        override suspend fun getConnectedRelays(): List<String> = emptyList()
        override suspend fun isRefreshInProgress(): Boolean = false
        override suspend fun setRefreshInProgress(isInProgress: Boolean) {}
    }
