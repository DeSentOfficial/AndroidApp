package xyz.desent.data.contacts

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import xyz.desent.crypto.Bech32Utils
import xyz.desent.data.RelayConfig
import xyz.desent.data.local.database.dao.ContactProfileLinkDao
import xyz.desent.data.local.database.dao.UserDao
import xyz.desent.data.local.database.entity.ContactProfileLinkEntity
import xyz.desent.data.local.database.entity.UserEntity
import xyz.desent.data.nip05.Nip05VerificationService
import xyz.desent.data.nostr.NostrEventProcessor
import xyz.desent.data.nostr.NostrKinds
import xyz.desent.domain.model.ContactProfile
import xyz.desent.domain.repository.ContactProfileResolver
import xyz.desent.domain.repository.RelayRepository

/**
 * Room-first [ContactProfileResolver].
 *
 * Cache layers (fastest first):
 *  1. in-memory TTL map (incl. negative entries) — dedupes within a session;
 *  2. Room `users` rows written by the normal kind-0 event pipeline — fresh
 *     rows (< [ROOM_FRESH_MS]) are served with ZERO network traffic, stale
 *     rows are served instantly and refreshed from the relays in the
 *     background (callers observe Room to pick up refreshed pictures);
 *  3. one-shot `REQ {kinds:[0], authors:[pub], limit:1}` per relay, falling
 *     through [RelayConfig.PUBLIC_PROFILE_RELAYS] in order. Only
 *     `wss://desent.xyz` is part of the persistent pool; every other relay
 *     is connected on demand under a reference count and disconnected again
 *     once the last concurrent lookup finishes (AGENTS.md relay policy).
 *
 * NIP-05 identifiers (`user@domain`, e.g. a contact's email) resolve to a
 * pubkey via `.well-known/nostr.json`; that mapping is persisted in
 * `contact_profile_links` and re-verified at most once per [LINK_FRESH_MS].
 */
class ContactProfileResolverImpl(
    private val relayRepository: RelayRepository,
    private val eventProcessor: NostrEventProcessor,
    private val userDao: UserDao,
    private val nip05Service: Nip05VerificationService,
    private val contactProfileLinkDao: ContactProfileLinkDao,
    /** Per-relay kind-0 arrival wait; injectable for tests. */
    private val lookupTimeoutMs: Long = 8_000L,
    /** Grace before re-trying a subscribe on a just-connected temp socket. */
    private val connectSettleMs: Long = 750L
) : ContactProfileResolver {

    private data class CacheEntry(val profile: ContactProfile?, val cachedAtMs: Long)

    private val memoryCache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ContactProfile?>>()
    private val subCounter = AtomicLong(0)

    /** Caps concurrent lookups so a long contact list can't flood the pool. */
    private val lookupSemaphore = Semaphore(4)

    /** Reference count per third-party relay URL — 0 → connect, back to 0 → disconnect. */
    private val tempRelayRefs = ConcurrentHashMap<String, AtomicInteger>()

    /** Fire-and-forget scope for stale-cache refreshes; never blocks the caller. */
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun resolve(pubkeyHex: String): ContactProfile? {
        val key = pubkeyHex.trim().lowercase()
        if (!key.matches(Regex("^[0-9a-f]{64}$"))) return null

        cachedEntry(hexKey(key))?.let { return it.profile }

        val npub = runCatching { Bech32Utils.hexToNpub(key) }.getOrNull() ?: return null
        val now = System.currentTimeMillis()
        val cached = runCatching { userDao.getUserByNpub(npub) }.getOrNull()

        if (cached != null && cached.hasRealContent()) {
            if (now - cached.lastUpdated < ROOM_FRESH_MS) {
                // Fresh on disk: serve with zero network traffic.
                memoryCache[hexKey(key)] = CacheEntry(cached.toProfile(), now)
                return cached.toProfile()
            }
            // Stale on disk: serve the snapshot immediately, refresh behind.
            refreshScope.launch { runLookup(key, npub) }
            return cached.toProfile()
        }

        return runLookup(key, npub)
    }

    override suspend fun resolveByIdentifier(identifier: String): ContactProfile? {
        val id = identifier.trim().lowercase()
        if (!id.contains('@')) return null

        val memKey = idKey(id)
        val now = System.currentTimeMillis()
        cachedEntry(memKey)?.let { return it.profile }

        val link = runCatching { contactProfileLinkDao.getByIdentifier(id) }.getOrNull()
        if (link != null && now - link.resolvedAt < LINK_FRESH_MS) {
            // Durable link still fresh: resolve through the (Room-first) pubkey path.
            val profile = resolve(link.hexPubkey)
            memoryCache[memKey] = CacheEntry(profile, now)
            return profile
        }
        if (link != null) {
            // Stale link: serve the linked profile now, re-verify behind.
            val profile = resolve(link.hexPubkey)
            refreshScope.launch { reverifyIdentifier(id) }
            return profile
        }

        return reverifyIdentifier(id)
    }

    override fun observeProfile(pubkeyHex: String): Flow<ContactProfile?> {
        val npub = runCatching {
            Bech32Utils.hexToNpub(pubkeyHex.trim().lowercase())
        }.getOrNull() ?: return flowOf(null)
        return userDao.observeUserByNpub(npub).map { it?.takeIf { e -> e.hasRealContent() }?.toProfile() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeProfileByIdentifier(identifier: String): Flow<ContactProfile?> {
        val id = identifier.trim().lowercase()
        if (!id.contains('@')) return flowOf(null)
        return contactProfileLinkDao.observeByIdentifier(id)
            .flatMapLatest { link ->
                if (link == null) flowOf(null) else observeProfile(link.hexPubkey)
            }
    }

    /** Fetch the identifier record and persist the link; negative-cached in memory. */
    private suspend fun reverifyIdentifier(id: String): ContactProfile? {
        val record = runCatching { nip05Service.fetchNip05Record(id) }
            .onFailure { Log.w(TAG, "NIP-05 fetch failed for $id: ${it.message}") }
            .getOrNull()

        val profile = if (record != null) {
            runCatching {
                contactProfileLinkDao.upsert(
                    ContactProfileLinkEntity(
                        identifier = id,
                        hexPubkey = record.hexPubkey,
                        resolvedAt = System.currentTimeMillis()
                    )
                )
            }
            resolve(record.hexPubkey)
        } else {
            null
        }
        memoryCache[idKey(id)] = CacheEntry(profile, System.currentTimeMillis())
        return profile
    }

    /** Deduped network path shared by direct resolves and background refreshes. */
    private suspend fun runLookup(hexKeyRaw: String, npub: String): ContactProfile? {
        val key = hexKey(hexKeyRaw)
        inFlight[key]?.let { return it.await() }
        val deferred = CompletableDeferred<ContactProfile?>()
        inFlight[key] = deferred
        try {
            lookupSemaphore.withPermit {
                try {
                    val profile = lookupViaRelays(key.removePrefix(HEX_PREFIX), npub)
                    memoryCache[key] = CacheEntry(profile, System.currentTimeMillis())
                    // Mark the row fresh even when unchanged so the next app
                    // open doesn't re-query for identical data.
                    runCatching { userDao.touchLastUpdated(npub, System.currentTimeMillis()) }
                    deferred.complete(profile)
                } catch (e: TimeoutCancellationException) {
                    // Treated as "no kind 0 found" — negative-cached per spec.
                    memoryCache[key] = CacheEntry(null, System.currentTimeMillis())
                    deferred.complete(null)
                } catch (e: CancellationException) {
                    // Caller cancelled — transient for other waiters, no cache.
                    deferred.complete(null)
                    throw e
                } catch (e: Exception) {
                    // Transient failure (relay down etc.) — do NOT cache so a
                    // later attempt can succeed once the relay is back.
                    Log.w(TAG, "resolve($key) failed: ${e.message}")
                    deferred.complete(null)
                } finally {
                    inFlight.remove(key, deferred)
                }
            }
        } catch (e: CancellationException) {
            // Caller cancelled while waiting on the semaphore — unblock any
            // other waiters and propagate the cancellation.
            deferred.complete(null)
            throw e
        }
        return deferred.await()
    }

    /** Sequential relay fallback over the configured profile-relay list. */
    private suspend fun lookupViaRelays(hex: String, npub: String): ContactProfile? {
        for (relayUrl in RelayConfig.PUBLIC_PROFILE_RELAYS) {
            val satisfied = if (relayUrl == RelayConfig.EMAIL_RELAY_URL) {
                lookupOnRelay(hex, npub, relayUrl)
            } else {
                withTempRelay(relayUrl) { lookupOnRelay(hex, npub, relayUrl) }
            }
            if (satisfied) {
                val profile = runCatching { userDao.getUserByNpub(npub) }.getOrNull()?.toProfile()
                if (profile != null && profile.hasContent()) return profile
            }
        }
        return null
    }

    /** True when this relay yielded a kind-0 arrival for [npub] with content. */
    private suspend fun lookupOnRelay(hex: String, npub: String, relayUrl: String): Boolean {
        val subId = "cprof_${hex.take(8)}_${subCounter.incrementAndGet()}"
        val filters = listOf(
            mapOf(
                "authors" to listOf(hex),
                "kinds" to listOf(NostrKinds.SET_METADATA),
                "limit" to 1
            )
        )
        try {
            // A just-connected temp socket may not be ready yet — retry once.
            var subscribed = runCatching {
                relayRepository.subscribeToEventsOnRelay(filters, subId, relayUrl)
            }.isSuccess
            if (!subscribed) {
                delay(connectSettleMs)
                subscribed = runCatching {
                    relayRepository.subscribeToEventsOnRelay(filters, subId, relayUrl)
                }.isSuccess
            }
            if (!subscribed) return false // relay not connected — try the next one

            return try {
                withTimeout(lookupTimeoutMs) {
                    eventProcessor.metadataArrivals.first { it == npub }
                }
                // Arrival fired; the read-back (and content check) happens in
                // lookupViaRelays once this returns true.
                true
            } catch (e: TimeoutCancellationException) {
                false // nothing at EOSE-equivalent → next relay
            } catch (e: Exception) {
                false
            }
        } finally {
            runCatching { relayRepository.unsubscribeFromEvents(subId) }
        }
    }

    /**
     * Runs [block] while holding a reference on the temporary connection to
     * [url]: the first holder connects, the last holder disconnects, and
     * concurrent lookups share one socket.
     */
    private suspend fun <T> withTempRelay(url: String, block: suspend () -> T): T {
        val refs = tempRelayRefs.getOrPut(url) { AtomicInteger(0) }
        if (refs.incrementAndGet() == 1) {
            runCatching { relayRepository.connectToRelay(url) }
                .onFailure { Log.w(TAG, "connect to $url failed: ${it.message}") }
        }
        try {
            return block()
        } finally {
            if (refs.decrementAndGet() <= 0) {
                tempRelayRefs.remove(url)
                runCatching { relayRepository.disconnectFromRelay(url) }
                    .onFailure { Log.w(TAG, "disconnect from $url failed: ${it.message}") }
            }
        }
    }

    private fun cachedEntry(key: String): CacheEntry? =
        memoryCache[key]?.takeIf { System.currentTimeMillis() - it.cachedAtMs < MEMORY_TTL_MS }

    private fun UserEntity.hasRealContent(): Boolean =
        // Mirrors the event pipeline's placeholder guard: rows that only ever
        // carried a truncated-npub name must not satisfy the cache fast path.
        listOfNotNull(picture, displayName, about, nip05, banner, website)
            .any { !it.isNullOrBlank() }

    private fun UserEntity.toProfile() = ContactProfile(
        name = name,
        displayName = displayName,
        picture = picture,
        banner = banner,
        about = about,
        nip05 = nip05,
        website = website
    )

    private fun ContactProfile.hasContent(): Boolean =
        listOfNotNull(name, displayName, picture, banner, about, nip05, website)
            .any { it.isNotBlank() }

    private fun hexKey(hex: String) = "$HEX_PREFIX$hex"
    private fun idKey(id: String) = "$ID_PREFIX$id"

    companion object {
        private const val TAG = "ContactProfileResolver"

        /** In-memory cache lifetime (negative entries included). */
        private const val MEMORY_TTL_MS = 10 * 60 * 1000L

        /** Room `users` row freshness — beyond this, serve stale + refresh behind. */
        private const val ROOM_FRESH_MS = 24 * 60 * 60 * 1000L

        /** `contact_profile_links` re-verification window. */
        private const val LINK_FRESH_MS = 24 * 60 * 60 * 1000L

        private const val HEX_PREFIX = "hex:"
        private const val ID_PREFIX = "id:"
    }
}
