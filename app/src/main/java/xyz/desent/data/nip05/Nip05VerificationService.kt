package xyz.desent.data.nip05

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import xyz.desent.crypto.Bech32Utils
import xyz.desent.data.local.preferences.PreferencesManager
import java.util.concurrent.TimeUnit

class Nip05VerificationService(
    private val okHttpClient: OkHttpClient,
    // Optional persisted negative cache (definitive 404/name-absent verdicts).
    // Without it every unresolvable sender address is re-probed against its
    // domain on every app open — the in-memory cache dies with the process.
    private val preferencesManager: PreferencesManager? = null
) {

    companion object {
        private const val TAG = "Nip05VerificationService"
        private const val CACHE_TTL_MILLIS = 3 * 60 * 1000L
        private const val RATE_LIMIT_MAX_REQUESTS = 5
        private const val RATE_LIMIT_WINDOW_MILLIS = 5 * 60 * 1000L
        private const val HTTP_TIMEOUT_SECONDS = 10L

        /** How long a definitive "not found" verdict stays trusted. */
        private const val NEGATIVE_TTL_MILLIS = 24 * 60 * 60 * 1000L

        /** Cap on persisted negatives so the set cannot grow unbounded. */
        private const val MAX_NEGATIVE_ENTRIES = 500

        private val NEGATIVE_CACHE_KEY = stringSetPreferencesKey("nip05_negative_cache")
    }
    
    sealed class Nip05Result {
        data class Success(val npub: String) : Nip05Result()
        data class NotFound(val nip05: String) : Nip05Result()
        data class DomainUnreachable(val domain: String) : Nip05Result()
        data class InvalidJson(val domain: String) : Nip05Result()
        data class TimeoutError(val domain: String) : Nip05Result()
        data class InvalidNpub(val nip05: String) : Nip05Result()
        data class RateLimited(val accountNpub: String) : Nip05Result()
    }
    
    @Serializable
    data class NostrJsonResponse(
        @SerialName("names")
        val names: Map<String, String>? = null,
        
        @SerialName("relays")
        val relays: Map<String, List<String>>? = null
    )
    
    private data class CacheEntry(
        val npub: String?,
        val timestamp: Long
    )
    
    private data class RateLimitWindow(
        var requestCount: Int = 0,
        var windowStart: Long = System.currentTimeMillis()
    )
    
    private val cache = mutableMapOf<String, CacheEntry>()
    private val rateLimits = mutableMapOf<String, RateLimitWindow>()
    private val recordCache = mutableMapOf<String, RecordCacheEntry>()

    /** Lazily loaded persisted negatives: nip05 → recorded-at millis. */
    private var negativeCache: MutableMap<String, Long>? = null

    /**
     * Full NIP-05 record: the verified npub for [nip05] plus any relay hints
     * advertised in the optional `relays` field of `.well-known/nostr.json`
     * (NIP-05: a pubkey → relay-URLs map). [relays] may be empty when the
     * domain publishes no `relays` field.
     *
     * Used by the outbox resolver to locate a peer's relay list when the
     * connected pool doesn't have it.
     */
    data class Nip05Record(
        val nip05: String,
        val npub: String,
        val hexPubkey: String,
        val relays: List<String>
    )

    private data class RecordCacheEntry(val record: Nip05Record, val timestamp: Long)

    /**
     * Fetch a peer's NIP-05 record without [verifyNip05]'s self-verification
     * semantics (no per-account rate limit, no "match accountNpub" check).
     * Returns null on any failure (malformed identifier, network error,
     * not found, invalid json, invalid pubkey). Result is cached with the
     * same TTL as [verifyNip05].
     *
     * Callers that need to confirm the record belongs to a specific contact
     * should compare [Nip05Record.npub] against the contact's npub.
     */
    suspend fun fetchNip05Record(nip05: String): Nip05Record? {
        val parts = nip05.split("@")
        if (parts.size != 2) return null
        val username = parts[0]
        val domain = parts[1]

        recordCache[nip05]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MILLIS) {
                return entry.record
            }
            recordCache.remove(nip05)
        }

        return try {
            withContext(Dispatchers.IO) {
                val url = "https://$domain/.well-known/nostr.json?name=$username"
                Log.d(TAG, "Fetching record: $url")

                val request = Request.Builder().url(url).get().build()
                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.d(TAG, "HTTP ${response.code} fetching record for $nip05")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString<NostrJsonResponse>(body)

                val rawPubkey = parsed.names?.get(username)
                    ?: return@withContext null

                val hexPubkey = when {
                    rawPubkey.matches(Regex("^[a-fA-F0-9]{64}$")) -> rawPubkey
                    rawPubkey.startsWith("npub1") -> try {
                        Bech32Utils.npubToHex(rawPubkey)
                    } catch (e: Exception) {
                        return@withContext null
                    }
                    else -> return@withContext null
                }

                val npub = try {
                    Bech32Utils.hexToNpub(hexPubkey)
                } catch (e: Exception) {
                    return@withContext null
                }

                val relays = parsed.relays?.get(hexPubkey)
                    ?.filter { it.isNotBlank() }
                    .orEmpty()

                Nip05Record(nip05 = nip05, npub = npub, hexPubkey = hexPubkey, relays = relays)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchNip05Record failed for $nip05: ${e.message}")
            null
        }?.also { record ->
            recordCache[nip05] = RecordCacheEntry(record, System.currentTimeMillis())
        }
    }
    
    suspend fun verifyNip05(nip05: String, accountNpub: String? = null): Nip05Result {
        val parts = nip05.split("@")
        if (parts.size != 2) {
            return Nip05Result.NotFound(nip05)
        }
        
        val username = parts[0]
        val domain = parts[1]
        
        Log.d(TAG, "Verifying NIP-05: $username@$domain")
        
        accountNpub?.let { npub ->
            val rateLimitResult = checkRateLimit(npub)
            if (rateLimitResult != null) {
                Log.w(TAG, "Rate limit exceeded for account: $npub")
                return rateLimitResult
            }
        }
        
        val cachedResult = checkCache(nip05)
        if (cachedResult != null) {
            Log.d(TAG, "Cache hit for $nip05")
            return cachedResult
        }

        if (isKnownNegative(nip05)) {
            Log.d(TAG, "Persisted negative verdict for $nip05")
            return Nip05Result.NotFound(nip05)
        }
        
        return try {
            withContext(Dispatchers.IO) {
                val url = "https://$domain/.well-known/nostr.json?name=$username"
                Log.d(TAG, "Fetching: $url")
                
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
                
                val response = okHttpClient.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} for $domain")
                    if (response.code == 404) {
                        recordNegative(nip05)
                        return@withContext Nip05Result.NotFound(nip05)
                    }
                    return@withContext Nip05Result.DomainUnreachable(domain)
                }
                
                val responseBody = response.body?.string()
                if (responseBody == null) {
                    Log.w(TAG, "Empty response body from $domain")
                    return@withContext Nip05Result.DomainUnreachable(domain)
                }
                
                val nostrJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString<NostrJsonResponse>(responseBody)
                
                val npubValue = nostrJson.names?.get(username)
                if (npubValue == null) {
                    Log.w(TAG, "User '$username' not found in $domain's nostr.json")
                    recordNegative(nip05)
                    return@withContext Nip05Result.NotFound(nip05)
                }
                
                // Normalize to npub format - accept both hex and npub1 formats
                val normalizedNpub = try {
                    when {
                        npubValue.startsWith("npub1") -> {
                            // Already in npub format, validate it
                            Bech32Utils.npubToHex(npubValue)
                            npubValue
                        }
                        npubValue.matches(Regex("^[a-fA-F0-9]{64}$")) -> {
                            // Hex format - convert to npub
                            Log.d(TAG, "Converting hex to npub for $username@$domain: ${npubValue.take(8)}")
                            Bech32Utils.hexToNpub(npubValue)
                        }
                        else -> {
                            Log.w(TAG, "Invalid npub format from $domain: $npubValue")
                            return@withContext Nip05Result.InvalidNpub(nip05)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to validate/convert npub from $domain: ${e.message}")
                    return@withContext Nip05Result.InvalidNpub(nip05)
                }
                
                cache[nip05] = CacheEntry(
                    npub = normalizedNpub,
                    timestamp = System.currentTimeMillis()
                )
                clearNegative(nip05)

                Log.d(TAG, "Successfully verified NIP-05: $nip05 -> $normalizedNpub")
                Nip05Result.Success(normalizedNpub)
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.w(TAG, "Timeout verifying $domain")
            Nip05Result.TimeoutError(domain)
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying NIP-05 $nip05: ${e.message}", e)
            Nip05Result.DomainUnreachable(domain)
        }
    }
    
    private fun checkRateLimit(accountNpub: String): Nip05Result.RateLimited? {
        val window = rateLimits.getOrPut(accountNpub) { RateLimitWindow() }
        val now = System.currentTimeMillis()
        
        if (now - window.windowStart > RATE_LIMIT_WINDOW_MILLIS) {
            window.requestCount = 0
            window.windowStart = now
            rateLimits[accountNpub] = window
            return null
        }
        
        if (window.requestCount >= RATE_LIMIT_MAX_REQUESTS) {
            return Nip05Result.RateLimited(accountNpub)
        }
        
        window.requestCount++
        rateLimits[accountNpub] = window
        return null
    }
    
    private fun checkCache(nip05: String): Nip05Result? {
        val entry = cache[nip05] ?: return null
        val now = System.currentTimeMillis()
        
        if (now - entry.timestamp > CACHE_TTL_MILLIS) {
            cache.remove(nip05)
            Log.d(TAG, "Cache expired for $nip05")
            return null
        }
        
        return when (entry.npub) {
            null -> Nip05Result.NotFound(nip05)
            else -> Nip05Result.Success(entry.npub!!)
        }
    }
    
    fun clearCache() {
        cache.clear()
        recordCache.clear()
        Log.d(TAG, "Cache cleared")
    }

    fun clearRateLimits() {
        rateLimits.clear()
        Log.d(TAG, "Rate limits cleared")
    }

    // ---- Persisted negative verdicts --------------------------------------
    // Only definitive 404 / name-absent results are recorded; transient
    // failures (unreachable domain, timeout, bad JSON) must retry later.

    private suspend fun isKnownNegative(nip05: String): Boolean {
        val prefs = preferencesManager ?: return false
        val negatives = loadNegatives(prefs)
        val recordedAt = negatives[nip05] ?: return false
        return System.currentTimeMillis() - recordedAt <= NEGATIVE_TTL_MILLIS
    }

    private suspend fun recordNegative(nip05: String) {
        val prefs = preferencesManager ?: return
        val negatives = loadNegatives(prefs)
        negatives[nip05] = System.currentTimeMillis()
        persistNegatives(prefs, negatives)
    }

    private suspend fun clearNegative(nip05: String) {
        val prefs = preferencesManager ?: return
        val negatives = negativeCache ?: return
        if (negatives.remove(nip05) != null) {
            persistNegatives(prefs, negatives)
        }
    }

    private suspend fun loadNegatives(prefs: PreferencesManager): MutableMap<String, Long> {
        negativeCache?.let { return it }
        val stored = prefs.dataStore.data.first()[NEGATIVE_CACHE_KEY] ?: emptySet()
        val loaded = mutableMapOf<String, Long>()
        for (entry in stored) {
            val sep = entry.lastIndexOf('=')
            if (sep <= 0) continue
            val recordedAt = entry.substring(sep + 1).toLongOrNull() ?: continue
            loaded[entry.substring(0, sep)] = recordedAt
        }
        negativeCache = loaded
        return loaded
    }

    private suspend fun persistNegatives(prefs: PreferencesManager, negatives: MutableMap<String, Long>) {
        negativeCache = negatives
        val now = System.currentTimeMillis()
        // Prune expired entries first; then drop oldest if still over the cap.
        negatives.entries.removeAll { now - it.value > NEGATIVE_TTL_MILLIS }
        if (negatives.size > MAX_NEGATIVE_ENTRIES) {
            negatives.entries.sortedBy { it.value }
                .take(negatives.size - MAX_NEGATIVE_ENTRIES)
                .forEach { negatives.remove(it.key) }
        }
        prefs.dataStore.edit { it[NEGATIVE_CACHE_KEY] = negatives.entries
            .map { (name, at) -> "$name=$at" }
            .toSet() }
    }
}
