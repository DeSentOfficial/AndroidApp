package xyz.desent.domain.usecase

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.desent.data.RelayConfig
import xyz.desent.data.repository.NostrRepository

/**
 * Refreshes the logged-in user's own NIP-01 kind-0 profile (display name,
 * picture, …) with the settled two-shot sequence the account-switch path
 * performs ([SwitchAccountUseCase]):
 *
 *  1. [NostrRepository.fetchUserMetadata] — kind-0 REQ against the
 *     authenticated DeSent service relay, where every DeSent account's kind-0
 *     lives (relay policy: the app publishes exclusively to desent.xyz).
 *  2. [NostrRepository.fetchOwnProfileFromRelays] — the bootstrap shot across
 *     [RelayConfig.PUBLIC_PROFILE_RELAYS], covering existing Nostr users whose
 *     kind-0 was never published to the DeSent relay.
 *
 * The refresh must never depend on the lifecycle of the screen that triggered
 * it: the login path fires it from the repository (not the ViewModel) exactly
 * because the login screen is torn down mid-navigation, and the splash
 * cold-start repair used to be cancelled inside its own grace delay when the
 * splash destination popped. [launchInBackground] therefore runs on this use
 * case's own scope, mirroring how the login path's gift-wrap subscription
 * survives on the app-scoped relay pool.
 */
class RefreshOwnProfileUseCase(
    private val nostrRepository: NostrRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    /**
     * Fire-and-forget refresh of [npub]'s own profile. [graceMs] delays the
     * first shot so freshly-(re)connected relays can resolve NIP-42 auth before
     * the REQs go out (the splash path has no ready-wait of its own).
     */
    fun launchInBackground(npub: String, graceMs: Long = 0L) {
        scope.launch {
            if (graceMs > 0) delay(graceMs)
            runCatching { nostrRepository.fetchUserMetadata(npub) }
                .onFailure { Log.w(TAG, "fetchUserMetadata failed: ${it.message}") }
            runCatching {
                nostrRepository.fetchOwnProfileFromRelays(npub, RelayConfig.PUBLIC_PROFILE_RELAYS)
            }.onFailure { Log.w(TAG, "fetchOwnProfileFromRelays failed: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "RefreshOwnProfile"
    }
}
