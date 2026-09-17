package xyz.desent.domain.repository

import xyz.desent.domain.model.Relay
import xyz.desent.domain.model.ConnectionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import nostr.event.impl.GenericEvent

interface RelayRepository {

    suspend fun saveRelay(relay: Relay)

    suspend fun saveRelays(relays: List<Relay>)

    suspend fun updateRelay(relay: Relay)

    fun observeAllRelays(): Flow<List<Relay>>

    fun observeActiveRelays(): Flow<List<Relay>>

    suspend fun updateConnectionStatus(url: String, status: ConnectionStatus, timestamp: Long? = null)

    suspend fun updateFailureCount(url: String, failureCount: Int)

    suspend fun updateActiveStatus(url: String, isActive: Boolean)

    suspend fun deleteRelay(url: String)

    suspend fun connectToRelay(url: String)

    /**
     * Wait until the relay at [url] is ready to carry REQ/EVENT traffic
     * (WebSocket open + NIP-42 auth resolved). Returns true if ready,
     * false on timeout. Callers should use this before subscribing to
     * ensure REQs aren't silently buffered indefinitely.
     */
    suspend fun waitForRelayReady(url: String, timeoutMs: Long = 5000L): Boolean

    /**
     * Register a relay as persistent so it auto-reconnects on drop.
     * Used by the NIP-46 bunker to keep its pairing relays alive.
     */
    suspend fun addPersistentRelay(url: String)

    suspend fun connectToPersistentRelays()

    suspend fun disconnectFromRelay(url: String)

    suspend fun disconnectFromAllRelays()

    fun observeConnectionStatus(url: String): Flow<ConnectionStatus>

    fun observeEvents(): SharedFlow<GenericEvent>

    /**
     * Publish verdicts (`["OK", <event-id>, bool, message]` frames) from
     * every connected relay, merged. Lets publishers await a specific
     * event's acceptance — and surface the relay's rejection message
     * (e.g. the badge pin validator's `"invalid: …"` text).
     */
    val publishResults: SharedFlow<xyz.desent.data.relay.PublishResult>

    /**
     * `EOSE` frames from every connected relay, merged as
     * (subscriptionId, relayUrl). One-shot fetches race against this to
     * complete as soon as every queried relay has reported "no more stored
     * events" instead of idling to a fixed timeout.
     */
    val eoseEvents: SharedFlow<Pair<String, String>>

    /**
     * `CLOSED` frames from every connected relay, merged. A relay that
     * terminates a subscription server-side (e.g. `auth-required`) never
     * sends EOSE — one-shot fetches watch this to fail fast with the
     * relay's reason.
     */
    val closedEvents: SharedFlow<xyz.desent.data.relay.NostrWebSocketClient.ClosedNotification>

    suspend fun publishEventToRelay(event: GenericEvent, relayUrl: String)

    suspend fun subscribeToEvents(filters: List<Map<String, Any>>, subscriptionId: String, persistent: Boolean = true)

    suspend fun subscribeToEventsOnRelay(filters: List<Map<String, Any>>, subscriptionId: String, relayUrl: String, persistent: Boolean = false)

    suspend fun unsubscribeFromEvents(subscriptionId: String)

    suspend fun getConnectedRelays(): List<String>

    suspend fun isRefreshInProgress(): Boolean

    suspend fun setRefreshInProgress(isInProgress: Boolean)
}
