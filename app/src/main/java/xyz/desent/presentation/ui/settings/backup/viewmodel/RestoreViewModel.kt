package xyz.desent.presentation.ui.settings.backup.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.desent.domain.usecase.ImportBackupUseCase

/**
 * Restores accounts + relays from a `DSBK1` backup blob. The user picks (or
 * deep-links into) a `.desentbackup` file via the Storage Access Framework;
 * this VM reads its bytes via [Context]'s ContentResolver and runs the
 * [ImportBackupUseCase]. [activateFirst] should be true on the post-reinstall
 * login flow (so the first restored account is fully logged in) and false from
 * Settings (where the user is already logged in).
 */
class RestoreViewModel(
    private val importBackupUseCase: ImportBackupUseCase,
    private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RestoreUiState())
    val uiState: StateFlow<RestoreUiState> = _uiState.asStateFlow()

    fun onPassphraseChange(value: String) {
        _uiState.value = _uiState.value.copy(passphrase = value, error = null)
    }

    fun togglePassphraseVisible() {
        _uiState.value = _uiState.value.copy(passphraseVisible = !_uiState.value.passphraseVisible)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Read [fileUri] (a SAF document) and attempt restore with the current
     * passphrase. On success, [RestoreUiState.outcome] is populated and the
     * screen can act on it.
     */
    fun restore(fileUri: Uri, activateFirst: Boolean) {
        if (_uiState.value.passphrase.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "Enter your backup passphrase.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isWorking = true, error = null)
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                        ?: throw java.io.IOException("Could not open backup file.")
                }
            }
            val blob = bytes.getOrElse { e ->
                _uiState.value = _uiState.value.copy(isWorking = false, error = "Could not read file: ${e.message}")
                return@launch
            }
            importBackupUseCase.execute(blob, _uiState.value.passphrase, activateFirst)
                .onSuccess { outcome ->
                    _uiState.value = _uiState.value.copy(isWorking = false, outcome = outcome, error = null)
                }
                .onFailure { e ->
                    val msg = when (e) {
                        is xyz.desent.crypto.WrongPassphraseException -> "Wrong passphrase."
                        is xyz.desent.crypto.InvalidBackupFormatException -> "Not a valid DeSent backup file."
                        is xyz.desent.crypto.UnsupportedBackupVersionException ->
                            "This backup was made by a newer version of DeSent."
                        else -> "Restore failed: ${e.message}"
                    }
                    _uiState.value = _uiState.value.copy(isWorking = false, error = msg)
                }
        }
    }

    /** Called after the screen has acted on [RestoreUiState.outcome]. */
    fun consumeOutcome() {
        _uiState.value = _uiState.value.copy(outcome = null)
    }
}

data class RestoreUiState(
    val passphrase: String = "",
    val passphraseVisible: Boolean = false,
    val isWorking: Boolean = false,
    val outcome: ImportBackupUseCase.Outcome? = null,
    val error: String? = null,
)
