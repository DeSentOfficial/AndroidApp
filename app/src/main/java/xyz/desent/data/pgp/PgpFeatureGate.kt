package xyz.desent.data.pgp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client-side mirror of the relay's `pgp_enabled` master switch
 * (`GET /api/pgp/config`, PGP_ENCRYPTION.md § Master switch).
 *
 * Fails CLOSED: until a successful fetch reports `true`, every PGP surface
 * stays hidden (key management, compose lock, decrypt affordance) and
 * `["pgp","encrypted"]` rumors render as a locked placeholder saying the
 * feature is disabled. Fetched once per app start — the relay re-reads its
 * switch per request, so the relay-side remains immediate.
 */
class PgpFeatureGate(
    private val pgpClient: PgpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val fetchStarted = AtomicBoolean(false)
    @Volatile
    private var lastFetchAtMs: Long = 0L

    /** Idempotent per app start; safe to call from any screen's init. */
    fun ensureLoaded() {
        if (!fetchStarted.compareAndSet(false, true)) return
        refresh()
    }

    /** Force a re-fetch (pull-to-refresh on the settings screen, relogin). */
    fun refresh() {
        scope.launch {
            val result = pgpClient.getConfig()
            result.fold(
                onSuccess = { config ->
                    _enabled.value = config.pgpEnabled
                    lastFetchAtMs = System.currentTimeMillis()
                    Log.d(TAG, "pgp_enabled=${config.pgpEnabled}")
                },
                onFailure = {
                    // Fail closed: leave the current value (false until a
                    // successful fetch ever lands).
                    Log.w(TAG, "config fetch failed (fail-closed): ${it.message}")
                }
            )
        }
    }

    /**
     * Throttled refresh for UI surfaces that gate on the flag (avatar sheet,
     * Settings): heals a stale fail-closed value after the relay toggle
     * flips without hammering the endpoint — nginx caches it 5 min anyway.
     */
    fun maybeRefresh() {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastFetchAtMs < THROTTLE_MS) return
            lastFetchAtMs = now
        }
        refresh()
    }

    companion object {
        private const val TAG = "PgpFeatureGate"
        private const val THROTTLE_MS = 60_000L
    }
}
