package xyz.desent.presentation.ui.settings.backup.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.domain.model.Account
import xyz.desent.domain.usecase.ExportBackupUseCase
import java.io.IOException

/**
 * Drives the "Backup accounts" export wizard: account selection → passphrase →
 * pick destination (SAF) → encrypt & write. The scrypt + AES-GCM work runs on
 * [Dispatchers.Default] inside [ExportBackupUseCase]; only the SAF byte-stream
 * write happens on the VM side via [Context]'s ContentResolver.
 *
 * [preselectNpub] (arriving from the account switcher's per-account section)
 * narrows the FIRST default selection to that one account; the user can still
 * re-select any combination before exporting. Null = select all (the plain
 * entry point).
 *
 * The ACTIVE account's nsec is also surfaced ([BackupUiState.activeNsec]) for
 * the standalone NIP-49 ncryptsec export on the first step.
 */
class BackupViewModel(
    private val exportBackupUseCase: ExportBackupUseCase,
    accountRepository: xyz.desent.domain.repository.AccountRepository,
    private val context: Context,
    private val secureKeyManager: SecureKeyManager,
    preselectNpub: String? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    init {
        accountRepository.observeAllAccounts()
            .onEach { accounts ->
                val selectable = accounts.map { it.toSelectable() }
                val selected = _uiState.value.selectedNpubs
                // Default-select on first non-empty load; preserve later user
                // toggles. `selected.isEmpty()` alone isn't enough for the
                // preselect case because Room may emit an empty list first.
                val newSelected = when {
                    selected.isNotEmpty() || selectable.isEmpty() -> selected
                    preselectNpub != null -> {
                        val preselected = selectable.filter { it.npub == preselectNpub }.map { it.npub }.toSet()
                        // Unknown npub (account removed mid-flight): fall back to all.
                        preselected.ifEmpty { selectable.map { it.npub }.toSet() }
                    }
                    else -> selectable.map { it.npub }.toSet()
                }
                _uiState.value = _uiState.value.copy(accounts = selectable, selectedNpubs = newSelected)
            }
            .launchIn(viewModelScope)

        // Active account's key for the ncryptsec export card (step 1). The
        // legacy single-slot mirror always holds the ACTIVE nsec; other
        // accounts' keys are intentionally NOT exposed here.
        viewModelScope.launch {
            val nsec = runCatching { secureKeyManager.getNSECKey().getOrNull() }.getOrNull()
            _uiState.value = _uiState.value.copy(activeNsec = nsec)
        }
    }

    fun toggleAccount(npub: String) {
        val current = _uiState.value.selectedNpubs.toMutableSet()
        if (!current.add(npub)) current.remove(npub)
        _uiState.value = _uiState.value.copy(selectedNpubs = current)
    }

    fun onPassphraseChange(value: String) {
        _uiState.value = _uiState.value.copy(passphrase = value)
    }

    fun onPassphraseConfirmChange(value: String) {
        _uiState.value = _uiState.value.copy(passphraseConfirm = value)
    }

    fun togglePassphraseVisible() {
        _uiState.value = _uiState.value.copy(passphraseVisible = !_uiState.value.passphraseVisible)
    }

    fun goToStep(step: BackupStep) {
        _uiState.value = _uiState.value.copy(step = step, error = null)
    }

    fun nextStep() {
        val order = BackupStep.entries
        val idx = order.indexOf(_uiState.value.step)
        if (idx >= 0 && idx < order.lastIndex) goToStep(order[idx + 1])
    }

    fun previousStep() {
        val order = BackupStep.entries
        val idx = order.indexOf(_uiState.value.step)
        if (idx > 0) goToStep(order[idx - 1])
    }

    /**
     * Called from the screen's `CreateDocument` launcher callback. Encrypts the
     * selected accounts under the chosen passphrase and streams the resulting
     * blob to [uri] (a SAF document the user picked).
     */
    fun writeBackupTo(uri: Uri) {
        val state = _uiState.value
        if (state.selectedNpubs.isEmpty()) {
            _uiState.value = state.copy(error = "Select at least one account to back up.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isWorking = true, error = null)
            val blob = exportBackupUseCase.execute(state.selectedNpubs, state.passphrase).getOrNull()
            if (blob == null) {
                _uiState.value = _uiState.value.copy(isWorking = false, error = "Failed to encrypt backup.")
                return@launch
            }
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(blob) }
                    ?: throw IOException("Could not open destination for writing.")
                _uiState.value = _uiState.value.copy(isWorking = false, step = BackupStep.Done)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isWorking = false,
                    error = "Failed to write backup: ${e.message}",
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

enum class BackupStep { SelectAccounts, SetPassphrase, ChooseDestination, Done }

data class BackupUiState(
    val accounts: List<SelectableAccount> = emptyList(),
    val selectedNpubs: Set<String> = emptySet(),
    val passphrase: String = "",
    val passphraseConfirm: String = "",
    val passphraseVisible: Boolean = false,
    val step: BackupStep = BackupStep.SelectAccounts,
    val isWorking: Boolean = false,
    val error: String? = null,
    /** ACTIVE account's nsec for the standalone ncryptsec export; null = unavailable. */
    val activeNsec: String? = null,
) {
    /** Whether the passphrase step can advance (warn-only strength, never blocks). */
    val passphraseReady: Boolean
        get() = passphrase.isNotEmpty() && passphrase == passphraseConfirm
}

data class SelectableAccount(
    val npub: String,
    val displayName: String?,
    val picture: String?,
    val selected: Boolean,
)

private fun Account.toSelectable(): SelectableAccount =
    SelectableAccount(npub = npub, displayName = displayName, picture = picture, selected = false)
