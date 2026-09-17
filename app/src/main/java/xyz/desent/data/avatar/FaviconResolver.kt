package xyz.desent.data.avatar

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.RelayConfig
import xyz.desent.data.local.database.dao.DomainFaviconDao
import xyz.desent.data.local.database.entity.DomainFaviconEntity

/** Outcome of a favicon-cache endpoint call for one domain. */
enum class ServerFaviconOutcome { HIT, MISS, UNAVAILABLE }

/**
 * Favicon fallback for email-sender avatars. Two interchangeable sources:
 *
 *  - **Server cache** (user preference, default on): the NIP-98-authenticated
 *    `https://desent.xyz/api/favicon/<domain>` endpoint (kind 27235 GET, the
 *    same dialect as the alias API). One SSRF-hardened origin probe per domain
 *    per TTL serves every client; positives AND negatives are cached
 *    server-side for 24 h.
 *  - **Direct**: `https://<sender-domain>/favicon.ico` fetched from the
 *    sender's domain (no third-party icon service — consistent with NIP-05's
 *    direct `.well-known` fetches).
 *
 * The source is chosen per call by [serverCacheEnabled]; when the endpoint
 * answers `401`/`403`/`429`/`5xx` or times out, the resolver reverts to
 * direct probes for the rest of the session (process lifetime) — the feature
 * must not regress if the API is unavailable. Mirrors the live webmail
 * behavior; see refs/FROM_email.desent.xyz/FAVICON_CACHE.md §7.
 *
 * Availability is persisted in Room (`domain_favicons`) either way: confirmed
 * icons and known misses are served from the cache for [RECHECK_MS] (aligned
 * with the server's 24 h TTL), so list renders and app opens don't re-probe.
 * In-flight probes dedupe per domain.
 */
class FaviconResolver(
    okHttpClient: OkHttpClient,
    private val dao: DomainFaviconDao,
    private val nostrHttpAuth: NostrHttpAuth? = null,
    private val serverCacheEnabled: suspend () -> Boolean = { false },
    /** Injectable for tests; receives the direct favicon URL, returns availability. */
    private val probe: (suspend (String) -> Boolean)? = null,
    /** Injectable for tests; receives the cache-endpoint URL, returns the outcome. */
    private val serverCall: (suspend (String) -> ServerFaviconOutcome)? = null
) {

    private val probeClient = okHttpClient.newBuilder()
        .callTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val probeFn: suspend (String) -> Boolean =
        probe ?: ::defaultProbe

    private val serverCallFn: suspend (String) -> ServerFaviconOutcome =
        serverCall ?: ::defaultServerCall

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Set once the cache endpoint proves unusable; cleared only by process restart. */
    @Volatile
    private var serverFallbackForSession = false

    private suspend fun useServerCache(): Boolean =
        !serverFallbackForSession && serverCacheEnabled()

    /**
     * Best avatar URL for a sender email address, or null when neither a
     * favicon nor a probe-worthy domain exists (initials render then).
     */
    suspend fun faviconUrlFor(emailAddress: String): String? {
        val domain = domainOf(emailAddress) ?: return null

        val cached = runCatching { dao.getByDomain(domain) }.getOrNull()
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.checkedAt < RECHECK_MS) {
            return cached.available.takeIf { it }?.let { urlFor(domain) }
        }

        val available = dedupedProbe(domain)
        runCatching { dao.upsert(DomainFaviconEntity(domain, available, now)) }
            .onFailure { Log.w(TAG, "persist favicon state for $domain failed: ${it.message}") }
        return if (available) urlFor(domain) else null
    }

    /**
     * Room-only variant for latency-sensitive paths (system notifications):
     * returns the favicon URL only when a FRESH `available` row exists.
     * Never probes, never records — a miss here just means "no icon right
     * now"; the normal [faviconUrlFor] path still warms it later.
     */
    suspend fun cachedUrlFor(emailAddress: String): String? {
        val domain = domainOf(emailAddress) ?: return null
        val cached = runCatching { dao.getByDomain(domain) }.getOrNull() ?: return null
        if (System.currentTimeMillis() - cached.checkedAt >= RECHECK_MS) return null
        return cached.available.takeIf { it }?.let { urlFor(domain) }
    }

    private suspend fun dedupedProbe(domain: String): Boolean {
        inFlight[domain]?.let { return it.await() }
        val deferred = CompletableDeferred<Boolean>()
        inFlight[domain] = deferred
        try {
            val result = try {
                probeDomain(domain)
            } catch (e: CancellationException) {
                deferred.complete(false)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "probe for $domain failed: ${e.message}")
                false
            }
            deferred.complete(result)
        } finally {
            inFlight.remove(domain, deferred)
        }
        return deferred.await()
    }

    /** Strategy probe: the cache endpoint in server mode, direct origin otherwise. */
    private suspend fun probeDomain(domain: String): Boolean =
        if (useServerCache()) {
            val outcome = runCatching { serverCallFn(serverUrlFor(domain)) }
                .getOrElse {
                    Log.w(TAG, "favicon cache call for $domain failed: ${it.message}")
                    ServerFaviconOutcome.UNAVAILABLE
                }
            when (outcome) {
                ServerFaviconOutcome.HIT -> true
                ServerFaviconOutcome.MISS -> false
                ServerFaviconOutcome.UNAVAILABLE -> {
                    Log.w(TAG, "favicon cache unavailable; using direct probes for this session")
                    serverFallbackForSession = true
                    probeFn(directUrlFor(domain))
                }
            }
        } else {
            probeFn(directUrlFor(domain))
        }

    /** GET with an abandoned body: headers (status + content type) decide. */
    private suspend fun defaultProbe(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            probeClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                response.isSuccessful &&
                    response.header("Content-Type")?.trim()?.startsWith("image/", ignoreCase = true) == true
            }
        }.getOrDefault(false)
    }

    /**
     * Authenticated GET on the cache endpoint (kind 27235; GET carries no
     * `payload` tag). `200` = hit, `404` = cached-negative miss, anything
     * else (`401`/`403`/`429`/`5xx`/timeout/sign failure) = endpoint
     * unavailable → session fallback to direct probes.
     */
    private suspend fun defaultServerCall(url: String): ServerFaviconOutcome = withContext(Dispatchers.IO) {
        runCatching {
            val auth = nostrHttpAuth ?: return@runCatching ServerFaviconOutcome.UNAVAILABLE
            val header = auth.buildAuthHeader(url, "GET").getOrNull()
                ?: return@runCatching ServerFaviconOutcome.UNAVAILABLE
            probeClient.newCall(
                Request.Builder().url(url).get().header("Authorization", header).build()
            ).execute().use { response ->
                when {
                    response.isSuccessful -> ServerFaviconOutcome.HIT
                    response.code == 404 -> ServerFaviconOutcome.MISS
                    else -> ServerFaviconOutcome.UNAVAILABLE
                }
            }
        }.getOrElse {
            Log.w(TAG, "favicon cache call failed: ${it.message}")
            ServerFaviconOutcome.UNAVAILABLE
        }
    }

    /** URL for the currently selected source. */
    private suspend fun urlFor(domain: String): String =
        if (useServerCache()) serverUrlFor(domain) else directUrlFor(domain)

    companion object {
        private const val TAG = "FaviconResolver"

        /** How long a recorded availability/miss is trusted before re-probing. */
        private const val RECHECK_MS: Long = 24 * 60 * 60 * 1000L

        /** Sender-domain extraction + sanity validation (lowercased). */
        fun domainOf(emailAddress: String): String? {
            val domain = emailAddress.trim().lowercase().substringAfterLast('@', "")
            return domain.takeIf {
                it.contains('.') &&
                    !it.startsWith('.') && !it.endsWith('.') &&
                    it.none { ch -> ch.isWhitespace() || ch in "/\\:[]?#%" }
            }
        }

        /** Direct origin URL (also the session-fallback source). */
        fun directUrlFor(domain: String): String = "https://$domain/favicon.ico"

        /** desent.xyz favicon-cache endpoint URL (NIP-98-authenticated). */
        fun serverUrlFor(domain: String): String = RelayConfig.FAVICON_CACHE_BASE_URL + domain
    }
}
