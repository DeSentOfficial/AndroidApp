package xyz.desent.presentation.ui.email.viewmodel

import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import xyz.desent.data.pgp.PgpFeatureGate
import xyz.desent.domain.repository.PgpKeyRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared lock-toggle state for the three send surfaces (compose, reply,
 * thread quick-reply) — the ANDROID_PGP.md §4.2 compose contract:
 *
 *  - hidden when the relay's `pgp_enabled` gate is off (fail closed);
 *  - disabled with "no PGP key published for this recipient" while the WKD
 *    lookup is in flight or misses;
 *  - auto-checked when `pgp_auto_encrypt` is on AND the user holds a key AND
 *    the lookup found a key;
 *  - default ON when answering a message that arrived PGP-encrypted;
 *  - a manual toggle always wins for the lifetime of the screen.
 */
class PgpComposeLock(
    private val scope: CoroutineScope,
    private val pgpKeyRepository: PgpKeyRepository,
    private val featureGate: PgpFeatureGate,
    autoEncrypt: Flow<Boolean>? = null
) {

    data class State(
        val featureEnabled: Boolean = false,
        val hasKey: Boolean = false,
        val autoEncrypt: Boolean = false,
        val checkingRecipient: Boolean = false,
        /** null = unknown (no valid recipient yet / lookup in flight). */
        val recipientHasKey: Boolean? = null,
        val locked: Boolean = false
    ) {
        val visible: Boolean get() = featureEnabled
        val canLock: Boolean get() = featureEnabled && hasKey && recipientHasKey == true

        /** User-facing reason line under the chip; null when lockable. */
        val hint: String? get() = when {
            !featureEnabled -> null
            !hasKey -> "No PGP key on this account — generate or import one in Settings"
            checkingRecipient -> "Checking recipient's PGP key…"
            recipientHasKey == false -> "No PGP key published for this recipient"
            else -> null
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var userToggled = false
    private var anchorWasPgp = false
    private var lookupJob: Job? = null

    /** WKD lookups memoized per recipient for the lifetime of the screen. */
    private val lookupCache = ConcurrentHashMap<String, Boolean>()

    init {
        scope.launch {
            combine(
                featureGate.enabled,
                pgpKeyRepository.keyState,
                autoEncrypt ?: flowOf(false)
            ) { gateEnabled, keyState, autoEncryptOn ->
                Triple(gateEnabled, keyState is xyz.desent.domain.model.PgpKeyState.Available, autoEncryptOn)
            }.collect { (gateEnabled, hasKey, autoEncryptOn) ->
                val prev = _state.value
                val next = prev.copy(
                    featureEnabled = gateEnabled,
                    hasKey = hasKey,
                    autoEncrypt = autoEncryptOn
                )
                _state.value = relock(next)
            }
        }
    }

    /** Debounced recipient edit → WKD key lookup. */
    fun onRecipientChange(email: String?) {
        val value = email?.trim().orEmpty()
        if (!value.contains('@') || value.contains(' ')) {
            lookupJob?.cancel()
            _state.value = _state.value.copy(checkingRecipient = false, recipientHasKey = null)
            return
        }
        lookupJob?.cancel()
        lookupJob = scope.launch {
            delay(LOOKUP_DEBOUNCE_MS)
            // Reset unless cached (a cached miss must not flip the lock on).
            if (lookupCache[value] == null) {
                _state.value = _state.value.copy(checkingRecipient = true, recipientHasKey = null)
            }
            val hasKey = lookupCache.getOrPut(value) {
                pgpKeyRepository.hasRecipientKey(value)
            }
            _state.value = relock(_state.value.copy(checkingRecipient = false, recipientHasKey = hasKey))
        }
    }

    /** Reply default: the message being answered arrived encrypted. */
    fun setAnchorWasPgp(wasPgp: Boolean) {
        anchorWasPgp = wasPgp
        _state.value = relock(_state.value)
    }

    fun onToggle(locked: Boolean) {
        userToggled = true
        _state.value = _state.value.copy(locked = locked)
    }

    /** Effective send flag — never encrypt without a verified recipient key. */
    fun shouldEncrypt(): Boolean = _state.value.locked && _state.value.canLock

    private fun relock(state: State): State {
        if (userToggled) {
            // A manual choice stands, but a lock that became impossible
            // (recipient changed / key removed) must lift itself.
            return if (state.locked && !state.canLock) state.copy(locked = false) else state
        }
        if (state.canLock && (state.autoEncrypt || anchorWasPgp)) {
            return state.copy(locked = true)
        }
        if (!state.canLock) return state.copy(locked = false)
        return state
    }

    companion object {
        private const val LOOKUP_DEBOUNCE_MS = 500L
    }
}
