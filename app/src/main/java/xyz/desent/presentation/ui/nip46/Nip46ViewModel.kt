package xyz.desent.presentation.ui.nip46

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.nip46.Nip46BunkerService
import xyz.desent.data.nip46.Nip46Pairing
import xyz.desent.data.nip46.Nip46PairingUri
import xyz.desent.data.nip46.Nip46PairingUriParser
import xyz.desent.data.nip46.Nip46PermissionProfile
import xyz.desent.data.nip46.Nip46SignPrompt

/** Pairing duration options offered on the pair-confirm screen. */
enum class Nip46PairDuration(val seconds: Long?, val labelRes: Int) {
    MIN_5(300, xyz.desent.R.string.nip46_pair_duration_5min),
    HOUR_1(3600, xyz.desent.R.string.nip46_pair_duration_1hour),
    DAY_1(86400, xyz.desent.R.string.nip46_pair_duration_1day),
    ALWAYS(null, xyz.desent.R.string.nip46_pair_duration_always)
}

class Nip46ViewModel(
    private val bunkerService: Nip46BunkerService,
    private val preferencesManager: PreferencesManager,
    private val secureKeyManager: SecureKeyManager
) : ViewModel() {

    private val _activeUserHex = MutableStateFlow<String?>(null)

    /**
     * Account whose pairings are being viewed — set from the remote-signing
     * route's `npub` argument. Null (or the active account's npub) means the
     * bunker runtime's own identity is shown. The bunker signs with the ACTIVE
     * identity, so a different account is view/manage-only (see
     * [isViewingOtherAccount]).
     */
    private val _viewedNpub = MutableStateFlow<String?>(null)

    /** Pairing list scope: the viewed account when set, else the active one. */
    private val viewedUserHex =
        combine(_viewedNpub, _activeUserHex) { viewed, activeHex ->
            if (viewed == null) {
                activeHex
            } else {
                runCatching { Bech32Utils.npubToHex(viewed) }.getOrNull() ?: activeHex
            }
        }

    /** Pairings for the viewed (or active) account only. */
    val activePairings: StateFlow<List<Nip46Pairing>> =
        combine(bunkerService.pairings, viewedUserHex) { all, hex ->
            hex?.let { h -> all.filter { it.userPubkey == h } } ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** True while the remote-signing screen shows a non-active account. */
    val isViewingOtherAccount: StateFlow<Boolean> =
        combine(_viewedNpub, _activeUserHex) { viewed, activeHex ->
            // An undecodable npub resolves to the active account everywhere
            // (see [viewedUserHex]) — including here, so the screen never
            // gates its buttons away from the data it is actually showing.
            val viewedHex = viewed?.let { runCatching { Bech32Utils.npubToHex(it) }.getOrNull() }
            viewedHex != null && activeHex != null && viewedHex != activeHex
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** A pending sign request awaiting the user (null = none). */
    val pendingPrompt: StateFlow<Nip46SignPrompt?> = bunkerService.pendingSignPrompt

    // ----- pair flow (scan a nostrconnect:// QR) -----
    private val _pendingPairing = MutableStateFlow<Nip46PairingUri?>(null)
    val pendingPairing: StateFlow<Nip46PairingUri?> = _pendingPairing.asStateFlow()

    private val _pairError = MutableStateFlow<String?>(null)
    val pairError: StateFlow<String?> = _pairError.asStateFlow()

    private val _pairSuccess = MutableStateFlow<String?>(null)
    val pairSuccess: StateFlow<String?> = _pairSuccess.asStateFlow()

    // ----- bunker:// code (this device displays, the other app connects) -----
    private val _bunkerUri = MutableStateFlow<String?>(null)
    val bunkerUri: StateFlow<String?> = _bunkerUri.asStateFlow()

    init {
        viewModelScope.launch {
            _activeUserHex.value = secureKeyManager.getIdentityFromStoredNSEC().getOrNull()
                ?.publicKey?.toHexString()
        }
    }

    /**
     * Scope the pairing list to [npub] (null = active account). Called on
     * every entry to the remote-signing destination so a previously-viewed
     * account never bleeds into a later visit.
     */
    fun setViewedAccount(npub: String?) {
        _viewedNpub.value = npub?.takeIf { it.isNotBlank() }
    }

    /** Called from the QR scanner with decoded text. Returns false if not a valid pairing QR. */
    fun onQrScanned(text: String): Boolean {
        val result = Nip46PairingUriParser.parse(text)
        return result.fold(
            onSuccess = {
                _pendingPairing.value = it
                // Reset the pair-flow feedback so a stale success/error from a
                // previous pairing (the VM is activity-scoped) doesn't bleed in
                // — otherwise the Approve button (gated on success == null)
                // shows disabled on a fresh scan.
                _pairError.value = null
                _pairSuccess.value = null
                true
            },
            onFailure = {
                _pairError.value = it.message ?: "Invalid pairing QR"
                false
            }
        )
    }

    fun cancelPairing() {
        _pendingPairing.value = null
        _pairError.value = null
        _pairSuccess.value = null
    }

    /** Profile pre-selected on the pair-confirm screen for the scanned URI. */
    fun suggestedProfile(uri: Nip46PairingUri): Nip46PermissionProfile =
        Nip46PermissionProfile.suggested(uri.permissions, uri.perms, uri.transport)

    fun approvePairing(profile: Nip46PermissionProfile, duration: Nip46PairDuration) {
        val uri = _pendingPairing.value ?: return
        viewModelScope.launch {
            bunkerService.approvePairing(uri, profile, duration.seconds)
                .onSuccess {
                    _pairSuccess.value = uri.label ?: "device"
                    _pendingPairing.value = null
                }
                .onFailure { _pairError.value = it.message ?: "Pairing failed" }
        }
    }

    fun clearPairFeedback() {
        _pairError.value = null
        _pairSuccess.value = null
    }

    /** Generate a fresh single-use `bunker://` URI to show as a QR / copy. */
    fun generateBunkerCode() {
        viewModelScope.launch {
            bunkerService.generateBunkerUri()
                .onSuccess { _bunkerUri.value = it }
                .onFailure { _pairError.value = it.message ?: "Could not create bunker code" }
        }
    }

    /** Invalidate the displayed bunker:// secret (screen closed / regenerated). */
    fun clearBunkerCode() {
        bunkerService.clearPendingBunkerSecret()
        _bunkerUri.value = null
    }

    fun approveSign(alwaysAllow: Boolean = false) = bunkerService.approvePending(alwaysAllow)
    fun denySign() = bunkerService.denyPending()
    fun revoke(sessionPubkey: String) = bunkerService.revoke(sessionPubkey)

    fun pairingFor(sessionPubkey: String): Nip46Pairing? =
        activePairings.value.firstOrNull { it.sessionPubkey == sessionPubkey }
}
