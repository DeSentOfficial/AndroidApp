package xyz.desent.presentation.ui.splash.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xyz.desent.data.RelayConfig
import xyz.desent.domain.repository.AccountRepository
import xyz.desent.domain.repository.RelayRepository
import xyz.desent.data.repository.NostrRepository
import xyz.desent.domain.usecase.AuthUseCase
import xyz.desent.domain.usecase.RefreshPrimaryAddressUseCase
import xyz.desent.domain.usecase.UserUseCase
import xyz.desent.presentation.ui.splash.SplashDestination
import xyz.desent.presentation.ui.splash.SplashUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SplashViewModel(
    private val authUseCase: AuthUseCase,
    private val userUseCase: UserUseCase,
    private val nostrRepository: NostrRepository,
    private val relayRepository: RelayRepository,
    private val accountRepository: AccountRepository,
    private val refreshPrimaryAddressUseCase: RefreshPrimaryAddressUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SplashUiState())
    val uiState: StateFlow<SplashUiState> = _uiState.asStateFlow()

    companion object {
        private const val TAG = "SplashViewModel"
    }
    init {
        checkLoginState()
    }
    
    private fun checkLoginState() {
        viewModelScope.launch {
            try {
                delay(500)

                // One-shot migration: if this is the first launch since the
                // v21 multi-account schema, seed the `accounts` table from the
                // legacy single-slot nsec/npub and stamp empty ownerNpub
                // columns. Idempotent — no-op on subsequent launches.
                try {
                    accountRepository.seedFromLegacySingleAccount()
                } catch (e: Exception) {
                    Log.w(TAG, "Legacy-account seed failed: ${e.message}")
                }

                // Check if user has stored NSEC from previous login
                val isLoggedIn = authUseCase.isNsecStored()
                
                if (isLoggedIn) {
                    // Restore identity first (required for wrap subscriptions)
                    try {
                        nostrRepository.restoreIdentity()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to restore identity: ${e.message}")
                    }

                    // Connect ONLY to persistent relays for instant email availability
                    try {
                        relayRepository.connectToPersistentRelays()

                        // Subscribe to gift wraps for incoming wrapped traffic (email, NIP-46, calendar)
                        try {
                            nostrRepository.subscribeToGiftWraps()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to subscribe to gift wraps: ${e.message}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to connect to persistent relays: ${e.message}")
                    }

                    // Cold-launch only: refresh the user's NIP-01 kind-0 profile from
                    // the public profile relays (newest wins). Runs once per process
                    // start (SplashViewModel.init), so warm/background resumes are
                    // excluded. Non-blocking so splash navigates immediately; the
                    // subscription persists on the relay clients and the profile
                    // trickles into Room, where the UI observes it.
                    viewModelScope.launch {
                        delay(2000) // give the freshly-promoted persistent relays time to connect
                        nostrRepository.getCurrentUserNpub()?.let { npub ->
                            nostrRepository.fetchOwnProfileFromRelays(npub, RelayConfig.PUBLIC_PROFILE_RELAYS)
                        }
                    }

                    // Cold-launch only: query the registration API (GET /me,
                    // per-account NIP-98) for EVERY stored account and cache
                    // the authoritative primary <local>@desent.xyz address in
                    // Room, so the account switcher never shows "No address"
                    // for registered accounts. Non-blocking; failures keep
                    // the cached value.
                    viewModelScope.launch {
                        refreshPrimaryAddressUseCase.refreshAll()
                    }

                    // Go to the email inbox (home screen)
                    _uiState.value = SplashUiState(navigationDestination = SplashDestination.INBOX)
                } else {
                    // No stored credentials - show login screen
                    _uiState.value = SplashUiState(navigationDestination = SplashDestination.LOGIN)
                }
            } catch (e: Exception) {
                _uiState.value = SplashUiState(navigationDestination = SplashDestination.LOGIN, error = e.message)
            }
        }
    }
}
