package xyz.desent.data.relay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-account "newest event already downloaded" cursors for the persistent
 * relay subscriptions (gift wraps, own private storage, calendar, mailbox
 * config, user settings, NIP-46 raw). REQ filters inject
 * `since = cursor - [SINCE_MARGIN_SECONDS]` so an app re-open, resume, or
 * relay reconnect fetches only new events instead of replaying the full
 * limit-N backlog every time — that replay is what produced multi-GB data
 * usage over a couple of weeks of testing.
 *
 * A cursor is the max `created_at` of events already ingested, clamped to
 * "now" so a sender with a skewed (future) clock cannot push a cursor past
 * events that have not arrived yet. Writes are debounced because a first
 * full sync can deliver hundreds of events in quick succession.
 *
 * Replaceable kinds (30078/30079/31922-31925/35050) stay correct: every
 * replacement carries a fresh `created_at`, so `since` still catches edits
 * and tombstones.
 */
class RelaySyncWatermarks(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        const val SCOPE_GIFT_WRAP = "giftwrap"
        const val SCOPE_PRIVATE_STORAGE = "ownpriv"
        const val SCOPE_CALENDAR = "owncal"
        const val SCOPE_MAILBOX = "ownmailbox"
        const val SCOPE_USER_SETTINGS = "ownsettings"
        const val SCOPE_NIP46_RAW = "nip46raw"

        /**
         * Slack subtracted from a cursor before it becomes a REQ `since`, so
         * clock skew between publishing clients and this device cannot skip
         * events. Any re-sent overlap is discarded downstream by the
         * processor's seen-LRU and the DB insert-IGNORE dedup.
         */
        const val SINCE_MARGIN_SECONDS = 60L * 60L

        private const val KEY_PREFIX = "relay_since_"
        private const val FLUSH_DELAY_MS = 5_000L
    }

    private val mutex = Mutex()
    private val cursors = mutableMapOf<String, Long>()
    private val dirty = mutableSetOf<String>()
    private var flushJob: Job? = null

    /**
     * The `since` value for a REQ filter, or null when this account/scope has
     * never synced — the first REQ then keeps the full-history fetch and
     * seeds the cursor.
     */
    suspend fun sinceFilterFor(pubkeyHex: String, scopeName: String): Long? {
        val key = storageKey(pubkeyHex, scopeName)
        ensureLoaded(key)
        val cursor = mutex.withLock { cursors[key] ?: 0L }
        if (cursor <= 0L) return null
        return maxOf(1L, cursor - SINCE_MARGIN_SECONDS)
    }

    /** Monotonically advance the cursor for an already-ingested event. */
    suspend fun record(pubkeyHex: String, scopeName: String, createdAtSeconds: Long) {
        if (createdAtSeconds <= 0L || pubkeyHex.isBlank()) return
        val effective = minOf(createdAtSeconds, System.currentTimeMillis() / 1000L)
        val key = storageKey(pubkeyHex, scopeName)
        ensureLoaded(key)
        mutex.withLock {
            if (effective > (cursors[key] ?: 0L)) {
                cursors[key] = effective
                dirty.add(key)
                flushJob?.cancel()
                flushJob = scope.launch {
                    delay(FLUSH_DELAY_MS)
                    runCatching { flush() }
                }
            }
        }
    }

    /** Persist any pending cursor updates immediately (logout, tests). */
    suspend fun flush() {
        val pending: List<Pair<String, Long>>
        mutex.withLock {
            if (dirty.isEmpty()) return
            pending = dirty.map { it to (cursors[it] ?: 0L) }
            dirty.clear()
        }
        dataStore.edit { preferences ->
            pending.forEach { (key, value) ->
                preferences[longPreferencesKey(key)] = value
            }
        }
    }

    /**
     * Drop cursors for one account (or every account when null). The next REQ
     * for the affected scopes omits `since` and performs one full catch-up.
     */
    suspend fun reset(pubkeyHex: String? = null) {
        val prefix = KEY_PREFIX + (pubkeyHex ?: "")
        mutex.withLock {
            flushJob?.cancel()
            flushJob = null
            cursors.keys.retainAll { !it.startsWith(prefix) }
            dirty.retainAll { !it.startsWith(prefix) }
        }
        dataStore.edit { preferences ->
            preferences.asMap().keys
                .filter { it.name.startsWith(prefix) }
                .forEach { preferences.remove(it) }
        }
    }

    /**
     * Seed the in-memory cursor from DataStore once per key. The DataStore
     * read happens outside the lock; the put is under the lock and
     * only-if-absent, so a concurrent [record] bump can never be overwritten
     * by a stale stored value.
     */
    private suspend fun ensureLoaded(key: String) {
        if (mutex.withLock { cursors.containsKey(key) }) return
        val stored = dataStore.data.first()[longPreferencesKey(key)] ?: 0L
        mutex.withLock {
            if (!cursors.containsKey(key)) {
                cursors[key] = stored
            }
        }
    }

    private fun storageKey(pubkeyHex: String, scopeName: String) = "${KEY_PREFIX}${pubkeyHex}_$scopeName"
}

/**
 * Attach a relay-sync cursor to a REQ filter. `null` (never synced) leaves
 * the filter unchanged so the first fetch still covers full history.
 */
internal fun Map<String, Any>.withSince(since: Long?): Map<String, Any> =
    if (since == null) this else this + ("since" to since)
