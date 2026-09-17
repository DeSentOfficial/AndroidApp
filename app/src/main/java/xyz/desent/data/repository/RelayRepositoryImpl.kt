package xyz.desent.data.repository

import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.database.dao.RelayDao
import xyz.desent.data.local.database.entity.RelayEntity
import xyz.desent.data.mapper.RelayMapper
import xyz.desent.data.nostr.NostrEventProcessor
import xyz.desent.data.nostr.NostrKinds
import xyz.desent.data.relay.NostrWebSocketClient
import xyz.desent.domain.model.ConnectionStatus
import xyz.desent.domain.model.Relay
import xyz.desent.domain.repository.RelayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nostr.event.impl.GenericEvent

open class RelayRepositoryImpl(
    private val relayDao: RelayDao,
    private val relayMapper: RelayMapper,
    private val eventProcessor: NostrEventProcessor,
    private val secureKeyManager: SecureKeyManager
) : RelayRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val reconnectDelays = mutableMapOf<String, Long>()
    // Prevent concurrent reconnect loops for the same relay
    private val reconnectingRelays = mutableSetOf<String>()

    private val connectionStatuses = MutableStateFlow<Map<String, ConnectionStatus>>(emptyMap())
    private val mutex = Mutex()
    private val relayClients = mutableMapOf<String, NostrWebSocketClient>()

    private val persistentRelays = mutableSetOf<String>()
    private var refreshInProgress = false

    // Track individual relay connection attempts to prevent duplicates
    private val connectingRelays = mutableSetOf<String>()
    private val connectionMutex = Mutex()

    // Track persistent subscriptions (subscriptionId -> filters) so they can be
    // replayed to a relay when it reconnects. Without this, a persistent relay
    // dropping and reconnecting would silently lose all active subscriptions.
    private val persistentSubscriptions = mutableMapOf<String, List<Map<String, Any>>>()
    private val subscriptionMutex = Mutex()

    // Buffered so bursty one-shot fetches (e.g. the key-rotation snapshot)
    // never lose a frame to tryEmit back-pressure.
    private val _eventsFlow = MutableSharedFlow<GenericEvent>(extraBufferCapacity = 1024)
    val eventsFlow: SharedFlow<GenericEvent> = _eventsFlow.asSharedFlow()

    /**
     * Merged publish verdicts (`OK` frames with an event id) from every
     * client in the pool. Each client's flow is collected for the lifetime
     * of its connection (wired in [connectToRelay]).
     */
    private val _publishResults = MutableSharedFlow<xyz.desent.data.relay.PublishResult>(extraBufferCapacity = 128)
    override val publishResults: SharedFlow<xyz.desent.data.relay.PublishResult> =
        _publishResults.asSharedFlow()

    /**
     * Merged `EOSE` frames ((subscriptionId, relayUrl)) from every client in
     * the pool, wired per-connection like [publishResults]. Consumers can
     * detect "all queried relays are done" for one-shot subscriptions.
     */
    private val _eoseEvents = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 256)
    override val eoseEvents: SharedFlow<Pair<String, String>> = _eoseEvents.asSharedFlow()

    /**
     * Merged `CLOSED` frames from every client in the pool, wired
     * per-connection like [publishResults]. A relay that terminates a
     * subscription server-side (e.g. `auth-required`) never sends EOSE —
     * one-shot fetches watch this to fail fast with the relay's reason.
     */
    private val _closedEvents =
        MutableSharedFlow<NostrWebSocketClient.ClosedNotification>(extraBufferCapacity = 256)
    override val closedEvents: SharedFlow<NostrWebSocketClient.ClosedNotification> =
        _closedEvents.asSharedFlow()

    override suspend fun saveRelay(relay: Relay) {
        relayDao.insertRelay(relayMapper.mapToEntity(relay))
    }

    override suspend fun saveRelays(relays: List<Relay>) {
        relayDao.insertRelays(relayMapper.mapToEntityList(relays))
    }

    override suspend fun updateRelay(relay: Relay) {
        relayDao.updateRelay(relayMapper.mapToEntity(relay))
    }

    override fun observeAllRelays(): Flow<List<Relay>> {
        return relayDao.observeAllRelays().map { entities ->
            relayMapper.mapToDomainList(entities)
        }
    }

    override fun observeActiveRelays(): Flow<List<Relay>> {
        return relayDao.observeActiveRelays().map { entities ->
            relayMapper.mapToDomainList(entities)
        }
    }

    override suspend fun updateConnectionStatus(url: String, status: ConnectionStatus, timestamp: Long?) {
        mutex.withLock {
            val currentStatuses = connectionStatuses.value
            connectionStatuses.value = currentStatuses + (url to status)
        }

        relayDao.updateConnectionStatus(url, status.name, timestamp)
    }

    override suspend fun updateFailureCount(url: String, failureCount: Int) {
        relayDao.updateFailureCount(url, failureCount)
    }

    override suspend fun updateActiveStatus(url: String, isActive: Boolean) {
        relayDao.updateActiveStatus(url, isActive)
    }

    override suspend fun deleteRelay(url: String) {
        connectionMutex.withLock {
            connectingRelays.remove(url)
        }

        relayDao.deleteRelay(url)
        mutex.withLock {
            val currentStatuses = connectionStatuses.value
            connectionStatuses.value = currentStatuses - url
        }

        relayClients[url]?.let { client ->
            client.close()
            relayClients.remove(url)
        }
    }

    override suspend fun connectToRelay(url: String) {
        connectionMutex.withLock {
            // Check if already connected or currently connecting
            if (url in connectingRelays) {
                android.util.Log.d("RelayManager", " Already connecting to $url, skipping duplicate connection")
                return
            }

            val existingClient = relayClients[url]
            if (existingClient != null && existingClient.isConnected) {
                android.util.Log.d("RelayManager", " Already connected to $url, skipping duplicate connection")
                return
            }

            // Mark as connecting
            connectingRelays.add(url)
        }

        try {
            android.util.Log.d("RelayManager", "Connecting to relay: $url")
            updateConnectionStatus(url, ConnectionStatus.CONNECTING, null)

            val client = createClient(
                url = url,
                readOnly = url != xyz.desent.data.RelayConfig.EMAIL_RELAY_URL
            )

            relayClients[url] = client
            // Fan this client's publish verdicts into the pool-level flow so
            // publishers can await OK/OK-false per event id.
            scope.launch {
                client.publishResults.collect { _publishResults.tryEmit(it) }
            }
            // Same for EOSE frames: pool-level consumers (one-shot fetches)
            // need per-relay completion signals.
            scope.launch {
                client.eoseEvents.collect { _eoseEvents.tryEmit(it) }
            }
            // And CLOSED frames: relays that kill a subscription server-side
            // (auth-required) never answer with EOSE.
            scope.launch {
                client.closedEvents.collect { _closedEvents.tryEmit(it) }
            }
            client.connect()

            // WAIT FOR ACTUAL onOpen() CALLBACK (not just delay guess)
            try {
                client.waitForConnection()
                // Only update status if connection actually succeeded
                updateConnectionStatus(url, ConnectionStatus.CONNECTED, System.currentTimeMillis())
                android.util.Log.d("RelayManager", " Connected to relay: $url at ${System.currentTimeMillis()}")
            } catch (e: Exception) {
                updateConnectionStatus(url, ConnectionStatus.ERROR, null)
                android.util.Log.e("RelayManager", " Failed to connect to relay $url: ${e.message}")
            }
        } catch (e: Exception) {
            updateConnectionStatus(url, ConnectionStatus.ERROR, null)
            android.util.Log.e("RelayManager", " Exception connecting to relay $url: ${e.message}")
            throw e
        } finally {
            connectionMutex.withLock {
                connectingRelays.remove(url)
            }
        }
    }

    /**
     * Build the WebSocket client for [url]. Every relay other than the DeSent
     * service relay is created read-only (REQ-only) — DeSent is a *consumer* of
     * third-party relays, never a poster (see AGENTS.md relay policy).
     *
     * Internal + open so tests can substitute per-URL mock clients.
     */
    internal open fun createClient(url: String, readOnly: Boolean): NostrWebSocketClient =
        NostrWebSocketClient(
            relayUrl = url,
            coroutineScope = scope,
            signAuthEvent = { challenge, relayUrl -> signNip42AuthEvent(challenge, relayUrl) },
            onEvent = { event ->
                _eventsFlow.tryEmit(event)
                eventProcessor.submitEvent(event, url)
            },
            onConnected = { relay ->
                scope.launch {
                    reconnectingRelays.remove(relay)
                    reconnectDelays.remove(relay)
                    updateConnectionStatus(relay, ConnectionStatus.CONNECTED, System.currentTimeMillis())
                    // Re-establish any persistent subscriptions that were active
                    // before this relay dropped, so they survive reconnects.
                    replayPersistentSubscriptions(relay)
                }
            },
            onError = { relay, error ->
                android.util.Log.e("RelayManager", "Relay error for $relay: ${error.message}")
                if (relay in persistentRelayUrls) {
                    // Only start one reconnect loop per relay — ignore if one is already running
                    if (reconnectingRelays.contains(relay)) {
                        android.util.Log.d("RelayManager", " Reconnect already pending for $relay, skipping")
                        return@NostrWebSocketClient
                    }
                    reconnectingRelays.add(relay)
                    scope.launch {
                        try {
                            updateConnectionStatus(relay, ConnectionStatus.DISCONNECTED, null)
                            val delayMs = reconnectDelays.getOrDefault(relay, 5_000L)
                            reconnectDelays[relay] = minOf(delayMs * 2, 60_000L)
                            android.util.Log.d("RelayManager", " Reconnecting to $relay in ${delayMs}ms")
                            delay(delayMs)
                            val existingClient = relayClients[relay]
                            if (existingClient != null && !existingClient.isConnected) {
                                updateConnectionStatus(relay, ConnectionStatus.CONNECTING, null)
                                existingClient.connect()
                            }
                        } finally {
                            reconnectingRelays.remove(relay)
                        }
                    }
                }
            },
            readOnly = readOnly
        )

    /**
     * Sign a NIP-42 kind 22242 auth event in response to a relay's AUTH challenge.
     * Tags: ["challenge", challenge], ["relay", relayUrl]. Content empty.
     * Returns null if the user has no stored key (not logged in) so the WS client
     * can skip sending an AUTH response.
     *
     * AUTH is only ever answered for the DeSent service relay — the app must not
     * prove identity to third-party relays (temporary bootstrap connections just
     * skip AUTH; reading public kind-0 data does not require it).
     */
    internal suspend fun signNip42AuthEvent(challenge: String, relayUrl: String): GenericEvent? {
        if (relayUrl != xyz.desent.data.RelayConfig.EMAIL_RELAY_URL) {
            android.util.Log.d("RelayManager", " Skipping NIP-42 AUTH for non-DeSent relay $relayUrl")
            return null
        }
        return try {
            val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrNull()
                ?: return null

            val createdAt = System.currentTimeMillis() / 1000
            val tags = listOf(
                nostr.event.tag.GenericTag("challenge", listOf(challenge)),
                nostr.event.tag.GenericTag("relay", listOf(relayUrl))
            )

            val event = GenericEvent.builder()
                .pubKey(identity.publicKey)
                .kind(NostrKinds.CLIENT_AUTH)
                .createdAt(createdAt)
                .content("")
                .tags(tags as List<nostr.event.BaseTag>)
                .build()

            identity.sign(event)
            event
        } catch (e: Exception) {
            android.util.Log.e("RelayManager", " Failed to sign NIP-42 auth event for $relayUrl: ${e.message}", e)
            null
        }
    }

    override suspend fun disconnectFromRelay(url: String) {
        connectionMutex.withLock {
            connectingRelays.remove(url)
        }

        relayClients[url]?.let { client ->
            client.close()
            relayClients.remove(url)
        }
        updateConnectionStatus(url, ConnectionStatus.DISCONNECTED, null)
    }

    override suspend fun waitForRelayReady(url: String, timeoutMs: Long): Boolean {
        val client = relayClients[url] ?: return false
        if (client.isReady()) return true
        return try {
            client.waitForReady(timeoutMs)
            client.isReady()
        } catch (e: Exception) {
            android.util.Log.w("RelayManager", " waitForRelayReady timed out for $url: ${e.message}")
            false
        }
    }

    override suspend fun addPersistentRelay(url: String) {
        if (url in persistentRelays) return
        persistentRelays.add(url)
        android.util.Log.d("RelayManager", " Registered persistent relay: $url")
    }

    override suspend fun disconnectFromAllRelays() {
        connectionMutex.withLock {
            connectingRelays.clear()
        }

        // Drop the replay registry too: it belongs to the session/account that
        // is being torn down (logout / account switch). Keeping it would make
        // every future reconnect replay the previous account's REQs — pure
        // wasted downloads for events another account already ingested.
        subscriptionMutex.withLock {
            persistentSubscriptions.clear()
        }

        relayClients.values.forEach { client ->
            client.close()
        }
        relayClients.clear()
        connectionStatuses.value = emptyMap()
        android.util.Log.d("RelayManager", "Disconnected from all relays")
    }

    override fun observeConnectionStatus(url: String): Flow<ConnectionStatus> {
        return connectionStatuses.map { statuses ->
            statuses[url] ?: ConnectionStatus.DISCONNECTED
        }
    }

    /**
     * The one persistent relay of the locked-down pool: the DeSent service
     * relay. All publishing and app-data sync happens through it; third-party
     * relays are only ever connected transiently by the login profile
     * bootstrap (see NostrRepository.fetchOwnProfileFromRelays).
     */
    private val persistentRelayUrls = listOf(
        xyz.desent.data.RelayConfig.EMAIL_RELAY_URL
    )

    private fun toRelayEntity(url: String, isPersistent: Boolean, isWrite: Boolean) = RelayEntity(
        url = url,
        isActive = true,
        connectionStatus = ConnectionStatus.DISCONNECTED.name,
        failureCount = 0,
        lastConnectedAt = null,
        isPersistent = isPersistent,
        isWrite = isWrite,
        nip11Name = null, nip11Description = null, nip11Pubkey = null,
        nip11Contact = null, nip11SupportedNips = null, nip11Version = null,
        nip11Icon = null, nip11Software = null, nip11RelayCountries = null,
        nip11LanguageTags = null, nip11PostingPolicy = null, nip11LimitationsJson = null,
        nip11FeesJson = null, nip11Payments = null, nip11CachedAt = null
    )

    override fun observeEvents(): SharedFlow<GenericEvent> {
        return eventsFlow
    }

    override suspend fun publishEventToRelay(event: GenericEvent, relayUrl: String) {
        // Relay policy: DeSent is a consumer of the Nostr network, never a
        // poster — every published event targets the DeSent service relay only.
        // This is a deliberate hard crash, not a recoverable failure.
        check(relayUrl == xyz.desent.data.RelayConfig.EMAIL_RELAY_URL) {
            "DeSent relay policy: refusing to publish kind-${event.kind} event to $relayUrl"
        }
        val client = relayClients[relayUrl]
        if (client != null) {
            try {
                client.publish(event)
                android.util.Log.d("RelayManager", " Published event to specific relay: $relayUrl")
            } catch (e: Exception) {
                android.util.Log.e("RelayManager", " Failed to publish event to relay $relayUrl: ${e.message}")
                throw e
            }
        } else {
            android.util.Log.w("RelayManager", " Not connected to relay: $relayUrl, cannot publish event")
            throw Exception("Not connected to relay: $relayUrl")
        }
    }

    override suspend fun subscribeToEvents(filters: List<Map<String, Any>>, subscriptionId: String, persistent: Boolean) {
        android.util.Log.d("Relay", " Subscribing to events: subscriptionId=$subscriptionId, filters=$filters")

        if (persistent) {
            subscriptionMutex.withLock {
                persistentSubscriptions[subscriptionId] = filters
            }
        }

        relayClients.values.forEach { client ->
            // Consumer-only sockets never receive broadcast subscriptions —
            // third-party relays must only ever see explicit targeted REQs
            // (kind-0 profile fetches via subscribeToEventsOnRelay).
            if (client.readOnly) return@forEach
            try {
                client.subscribe(subscriptionId, filters)
                android.util.Log.d("Relay", " Sent subscription $subscriptionId to relay")
            } catch (e: Exception) {
                android.util.Log.e("Relay", " Failed to subscribe to relay: ${e.message}")
            }
        }
    }

    /**
     * Re-send all tracked persistent subscriptions to a relay after it (re)connects.
     * Called from the NostrWebSocketClient onConnected callback.
     *
     * Third-party (read-only) sockets never receive replays: persistent
     * subscriptions carry the user's own pubkey and DeSent-specific kinds, which
     * must not leak beyond the DeSent relay. Internal for test coverage.
     */
    internal suspend fun replayPersistentSubscriptions(relayUrl: String) {
        if (relayUrl != xyz.desent.data.RelayConfig.EMAIL_RELAY_URL) return
        val snapshot = subscriptionMutex.withLock { persistentSubscriptions.toMap() }
        if (snapshot.isEmpty()) return
        val client = relayClients[relayUrl] ?: return
        snapshot.forEach { (subId, filters) ->
            try {
                client.subscribe(subId, filters)
            } catch (e: Exception) {
                android.util.Log.e("RelayManager", " Failed to replay subscription $subId to $relayUrl: ${e.message}")
            }
        }
    }

    override suspend fun subscribeToEventsOnRelay(filters: List<Map<String, Any>>, subscriptionId: String, relayUrl: String, persistent: Boolean) {
        android.util.Log.d("Relay", " Subscribing to events on SPECIFIC relay: subscriptionId=$subscriptionId, relay=$relayUrl, persistent=$persistent, filters=$filters")

        if (persistent) {
            // Track so the subscription is replayed after this relay reconnects
            // (see replayPersistentSubscriptions). Without this, a single socket
            // drop orphans the subscription and no further events arrive.
            subscriptionMutex.withLock {
                persistentSubscriptions[subscriptionId] = filters
            }
        }

        val client = relayClients[relayUrl]
        if (client != null) {
            try {
                client.subscribe(subscriptionId, filters)
                android.util.Log.d("Relay", " Sent subscription $subscriptionId to specific relay: $relayUrl")
            } catch (e: Exception) {
                android.util.Log.e("Relay", " Failed to subscribe to relay $relayUrl: ${e.message}")
            }
        } else {
            android.util.Log.w("Relay", " Not connected to relay: $relayUrl, cannot subscribe")
            throw Exception("Not connected to relay: $relayUrl")
        }
    }

    override suspend fun unsubscribeFromEvents(subscriptionId: String) {
        subscriptionMutex.withLock {
            persistentSubscriptions.remove(subscriptionId)
        }
        relayClients.values.forEach { client ->
            try {
                client.unsubscribe(subscriptionId)
            } catch (e: Exception) {
                android.util.Log.w("Relay", " Failed to unsubscribe $subscriptionId: ${e.message}")
            }
        }
        android.util.Log.d("Relay", " Closed subscription $subscriptionId on ${relayClients.size} relay(s)")
    }

    override suspend fun getConnectedRelays(): List<String> {
        return connectionStatuses.value.filter { it.value == ConnectionStatus.CONNECTED }.keys.toList()
    }

    override suspend fun connectToPersistentRelays() {
        relayDao.insertOrIgnoreRelays(
            persistentRelayUrls.map { toRelayEntity(it, isPersistent = true, isWrite = true) }
        )
        // Defense in depth: even if a foreign row somehow carries isPersistent=1
        // (e.g. a backup restored by an older build), the persistent pool is the
        // DeSent service relay only — see AGENTS.md relay policy.
        val urls = relayDao.getPersistentRelays().map { it.url }
            .filter { it == xyz.desent.data.RelayConfig.EMAIL_RELAY_URL }
        persistentRelays.addAll(urls)
        urls.forEach { url ->
            try { connectToRelay(url) } catch (e: Exception) {
                android.util.Log.e("RelayManager", "Failed to connect to persistent relay $url: ${e.message}")
            }
        }
    }

    override suspend fun isRefreshInProgress(): Boolean = refreshInProgress

    override suspend fun setRefreshInProgress(isInProgress: Boolean) {
        refreshInProgress = isInProgress
    }

    fun isPersistentRelay(url: String): Boolean = url in persistentRelays
}
