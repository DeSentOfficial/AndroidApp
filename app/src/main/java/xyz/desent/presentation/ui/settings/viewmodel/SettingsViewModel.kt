package xyz.desent.presentation.ui.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xyz.desent.data.local.preferences.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class SettingsUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val requirePinOnOpen: Boolean = false,
    val requireBiometricOnOpen: Boolean = false,
    val requireBiometricOnSigning: Boolean = false,
    val userPin: String? = null,
    val appLockRelockSeconds: Long = 0L,
    val showPinDialog: Boolean = false
)

/**
 * App-lock settings (PIN / biometrics / re-lock) for the Settings APP tab.
 */
class SettingsViewModel(
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSecuritySettings()
    }

    private fun loadSecuritySettings() {
        // Collect each flow CONCURRENTLY. A sequential chain of terminal
        // .collect{} calls here would suspend forever on the first one (DataStore
        // flows never complete) and the rest — biometric-on-open, biometric-on-
        // signing, userPin, re-lock — would never update, making every toggle
        // after the first appear dead.
        preferencesManager.isRequirePinOnOpenEnabled
            .onEach { enabled -> _uiState.value = _uiState.value.copy(requirePinOnOpen = enabled) }
            .launchIn(viewModelScope)

        preferencesManager.isRequireBiometricOnOpenEnabled
            .onEach { enabled -> _uiState.value = _uiState.value.copy(requireBiometricOnOpen = enabled) }
            .launchIn(viewModelScope)

        preferencesManager.isRequireBiometricOnSigningEnabled
            .onEach { enabled -> _uiState.value = _uiState.value.copy(requireBiometricOnSigning = enabled) }
            .launchIn(viewModelScope)

        preferencesManager.userPin
            .onEach { pin -> _uiState.value = _uiState.value.copy(userPin = pin) }
            .launchIn(viewModelScope)

        preferencesManager.appLockRelockSeconds
            .onEach { seconds -> _uiState.value = _uiState.value.copy(appLockRelockSeconds = seconds) }
            .launchIn(viewModelScope)
    }

    fun setRequirePinOnOpen(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setRequirePinOnOpen(enabled)
            if (enabled) {
                _uiState.value = _uiState.value.copy(showPinDialog = true)
            }
        }
    }

    fun setRequireBiometricOnOpen(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setRequireBiometricOnOpen(enabled)
        }
    }

    fun setRequireBiometricOnSigning(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setRequireBiometricOnSigning(enabled)
        }
    }

    fun setAppLockRelockSeconds(seconds: Long) {
        viewModelScope.launch {
            preferencesManager.setAppLockRelockSeconds(seconds)
        }
    }

    fun onPinEntered(pin: String) {
        viewModelScope.launch {
            preferencesManager.setUserPin(pin)
            _uiState.value = _uiState.value.copy(showPinDialog = false)
        }
    }

    fun dismissPinDialog() {
        viewModelScope.launch {
            preferencesManager.setRequirePinOnOpen(false)
            _uiState.value = _uiState.value.copy(showPinDialog = false)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
