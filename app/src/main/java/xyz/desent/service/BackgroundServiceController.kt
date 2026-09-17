package xyz.desent.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import xyz.desent.data.local.preferences.PreferencesManager

/**
 * Starts / stops [NostrForegroundService] in response to the
 * "Keep running in background" setting ([PreferencesManager.isBackgroundServiceEnabled]).
 *
 * Call [syncFromPreference] once at app start to (re)start the service if the
 * user had previously enabled it (e.g. after a process restart). The reactive
 * observer then keeps the service state in sync with the preference thereafter.
 */
class BackgroundServiceController(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    scope: CoroutineScope
) {
    private val tag = "BackgroundServiceCtrl"

    init {
        // Keep the service state in sync with the preference as it changes.
        preferencesManager.isBackgroundServiceEnabled
            .distinctUntilChanged()
            .onEach { enabled -> apply(enabled) }
            .launchIn(scope)
    }

    /** Starts/stops the service to match the current preference value. */
    suspend fun syncFromPreference() {
        apply(preferencesManager.isBackgroundServiceEnabled.first())
    }

    private fun apply(enabled: Boolean) {
        if (enabled) start() else stop()
    }

    private fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(Intent(context, NostrForegroundService::class.java))
        } else {
            context.startService(Intent(context, NostrForegroundService::class.java))
        }
        Log.d(tag, "Foreground service start requested")
    }

    private fun stop() {
        runCatching { context.stopService(Intent(context, NostrForegroundService::class.java)) }
        Log.d(tag, "Foreground service stop requested")
    }
}
