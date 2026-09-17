package xyz.desent.domain.usecase

import android.util.Log
import kotlinx.coroutines.delay
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.RelayConfig
import xyz.desent.data.local.database.dao.AccountDao
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.repository.NostrRepository
import xyz.desent.data.session.SessionManager
import xyz.desent.domain.repository.AccountRepository
import xyz.desent.domain.repository.RelayRepository

/**
 * Orchestrates flipping the active account.
 *
 * The sequence is the relay/identity equivalent of `SplashViewModel`'s cold
 * start path, parameterized by an explicit npub:
 *
 *  1. Promote [npub]'s key to the legacy active slot so every existing
 *     single-slot consumer (NIP-42 auth, NIP-44 encryption, NIP-98 HTTP auth,
 *     gift-wrap) sees the new identity without each one being multi-account
 *     aware.
 *  2. Persist [npub] as active in [PreferencesManager] + bump its
 *     `lastActiveAt` so it sorts to the top of the switcher.
 *  3. Tear down all relay state: disconnects every WebSocket, which drops
 *     every subscription. This is the hard boundary that guarantees we will
 *     not ingest the *previous* account's emails / statuses from in-flight
 *     subscriptions.
 *  4. Swap the in-memory identity ([NostrRepository] + [NostrEventProcessor])
 *     so the ingestion guard ([NostrEventProcessor.currentUserPubKeyHex])
 *     only admits events addressed to the new account.
 *  5. Reconnect to the persistent relays and re-open the new account's
 *     subscriptions: gift wraps (NIP-59), own status (NIP-38), own
 *     presence (NIP-30316), and the public profile refresh.
 *
 * The UI completes the switch by performing a full nav-stack reset to
 * [xyz.desent.presentation.navigation.Screen.Emails.Inbox]; every ViewModel is
 * recreated and observes the new active npub at init.
 */
class SwitchAccountUseCase(
    private val secureKeyManager: SecureKeyManager,
    private val preferencesManager: PreferencesManager,
    private val accountDao: AccountDao,
    private val accountRepository: AccountRepository,
    private val sessionManager: SessionManager,
    private val nostrRepository: NostrRepository,
    private val relayRepository: RelayRepository,
    private val refreshPrimaryAddressUseCase: RefreshPrimaryAddressUseCase
) {

    suspend fun switchTo(npub: String): Result<String> {
        // Signal the switch up-front so any observing UI immediately
        // drops the previous account's data and shows skeletons while the new
        // account loads. Cleared in [finally] so a failed switch restores the
        // current account's view.
        sessionManager.setAccountSwitching(true)
        return try {
            runCatching {
                require(secureKeyManager.hasAccount(npub)) {
                    "Cannot switch to $npub — no key material stored for that account"
                }

                Log.i(TAG, "Switching active account -> $npub")

                // 1. Promote key to legacy active slot (so existing single-slot
                //    consumers see the new identity).
                secureKeyManager.setActiveAccount(npub).getOrThrow()

                // 2. Persist active npub + bump lastActiveAt.
                sessionManager.setActive(npub)

                // 3. Tear down relay state. disconnectFromAllRelays() closes the
                //    WebSockets and thereby drops every active subscription.
                relayRepository.disconnectFromAllRelays()

                // 4. Swap in-memory identity. This MUST happen before we open new
                //    subscriptions so the event-processor guard is already pointed
                //    at the new account if any in-flight EVENT frames arrive.
                nostrRepository.switchIdentity(npub).getOrThrow()

                // 5. Reconnect + resubscribe for the new account.
                relayRepository.connectToPersistentRelays()
                // Brief grace period so the persistent relays can authenticate
                // (NIP-42) before we send REQ frames; mirrors SplashViewModel's
                // behaviour.
                delay(1500L)

                runCatching { nostrRepository.subscribeToGiftWraps() }
                    .onFailure { Log.w(TAG, "subscribeToGiftWraps failed: ${it.message}") }
                runCatching { nostrRepository.subscribeToOwnPrivateStorage() }
                    .onFailure { Log.w(TAG, "subscribeToOwnPrivateStorage failed: ${it.message}") }
                runCatching { nostrRepository.subscribeToOwnMailboxConfig() }
                    .onFailure { Log.w(TAG, "subscribeToOwnMailboxConfig failed: ${it.message}") }
                runCatching { nostrRepository.subscribeToOwnUserSettings() }
                    .onFailure { Log.w(TAG, "subscribeToOwnUserSettings failed: ${it.message}") }
                runCatching { nostrRepository.subscribeToOwnCalendar() }
                    .onFailure { Log.w(TAG, "subscribeToOwnCalendar failed: ${it.message}") }

                // Refresh the kind-0 profile from public relays in the background;
                // non-blocking so the switch returns immediately.
                runCatching { nostrRepository.fetchUserMetadata(npub) }
                    .onFailure { Log.w(TAG, "fetchUserMetadata failed: ${it.message}") }
                runCatching {
                    nostrRepository.fetchOwnProfileFromRelays(npub, RelayConfig.PUBLIC_PROFILE_RELAYS)
                }.onFailure { Log.w(TAG, "fetchOwnProfileFromRelays failed: ${it.message}") }

                // Refresh the registered primary address for the newly-active
                // account (fire-and-forget; the switcher row updates via the
                // Room flow).
                runCatching { refreshPrimaryAddressUseCase.refreshOne(npub) }
                    .onFailure { Log.w(TAG, "primary-address refresh failed: ${it.message}") }

                Log.i(TAG, " Account switch to $npub complete")
                npub
            }
        } finally {
            sessionManager.setAccountSwitching(false)
        }
    }

    companion object {
        private const val TAG = "SwitchAccountUseCase"
    }
}
