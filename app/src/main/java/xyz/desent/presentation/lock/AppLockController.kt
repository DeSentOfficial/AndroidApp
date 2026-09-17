package xyz.desent.presentation.lock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.desent.data.local.preferences.PreferencesManager

/** Which authentication, if any, gates app entry. */
enum class AppLockGate { NONE, BIOMETRIC, PIN }

/**
 * Process-scoped holder for the app-lock (biometric / PIN) gate.
 *
 * - [gateMode] is derived reactively from the security preferences, so toggling
 *   a setting in the UI takes effect immediately.
 * - [isLocked] drives a full-screen [LockScreen] overlay in the Activity.
 * - Re-lock behaviour is user-configurable: lock on every foreground transition,
 *   or only after the app has been backgrounded for longer than a threshold.
 */
class AppLockController(
    private val preferencesManager: PreferencesManager,
    private val scope: CoroutineScope
) {
    private val _isLocked = MutableStateFlow(true)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    val gateMode: StateFlow<AppLockGate> = combine(
        preferencesManager.isRequireBiometricOnOpenEnabled,
        preferencesManager.isRequirePinOnOpenEnabled,
        preferencesManager.userPin
    ) { biometric, pin, storedPin ->
        when {
            biometric -> AppLockGate.BIOMETRIC
            pin && !storedPin.isNullOrBlank() -> AppLockGate.PIN
            else -> AppLockGate.NONE
        }
    }.stateIn(scope, SharingStarted.Eagerly, AppLockGate.NONE)

    /** Whether a PIN is currently stored (for biometric-unavailable fallback). */
    val hasPin: StateFlow<Boolean> = preferencesManager.userPin
        .map { !it.isNullOrBlank() }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var lastBackgroundedAt: Long = 0L

    /** Call from the Activity's foreground lifecycle callback. */
    fun onForegrounded() {
        scope.launch {
            if (gateMode.value == AppLockGate.NONE) return@launch
            val relockSeconds = preferencesManager.appLockRelockSeconds.first()
            if (relockSeconds <= 0L) {
                lock()
            } else {
                val bgFor = System.currentTimeMillis() - lastBackgroundedAt
                if (bgFor >= relockSeconds * 1000L) lock()
            }
        }
    }

    /** Call from the Activity's background lifecycle callback. */
    fun onBackgrounded() {
        lastBackgroundedAt = System.currentTimeMillis()
    }

    fun lock() { _isLocked.value = true }

    fun unlock() { _isLocked.value = false }
}
