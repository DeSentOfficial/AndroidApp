package xyz.desent.data.relay

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caches the relay feature flags (initially the `calendar_enabled` flag) and
 * exposes them as a reactive [StateFlow]. A single in-flight [refresh] guard
 * prevents duplicate concurrent fetches.
 *
 * Degradation: until a successful fetch completes the flag defaults to `true`
 * (matches the protocol's `"open"` default), and any error leaves the last
 * good value in place — so a missing/misbehaving endpoint never hides the
 * feature. Only an explicit `"disabled"` hides it.
 */
class RelaySettingsRepository(
    private val client: RelaySettingsClient
) {
    private val _calendarEnabled = MutableStateFlow(true)
    val calendarEnabled: StateFlow<Boolean> = _calendarEnabled.asStateFlow()

    private val mutex = Mutex()

    suspend fun refresh() {
        if (!mutex.tryLock()) return
        try {
            val result = client.getRelaySettings()
            result.onSuccess { dto ->
                // Only flip when the server is explicit; missing/other → leave as-is.
                when (dto.calendar_enabled) {
                    "open" -> _calendarEnabled.value = true
                    "disabled" -> _calendarEnabled.value = false
                    else -> { /* unknown value — keep current */ }
                }
            }.onFailure {
                Log.w(TAG, "refresh: keeping cached value (${_calendarEnabled.value}): ${it.message}")
            }
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        private const val TAG = "RelaySettingsRepo"
    }
}
