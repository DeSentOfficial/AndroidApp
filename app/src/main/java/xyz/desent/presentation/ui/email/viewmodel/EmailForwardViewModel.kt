package xyz.desent.presentation.ui.email.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.mail.MailTransferManager
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailForwardSummary
import xyz.desent.domain.usecase.EmailUseCase
import java.io.IOException

/**
 * UI state for the Forward / Migrate screen (NIP-EMAIL forwarding): inbox
 * thread picker + target npub + run progress. Selecting every thread and
 * forwarding is a full mailbox migration; interrupted runs resume (already
 * SENT (email, target) pairs are skipped by the forward ledger).
 */
data class EmailForwardUiState(
    val threads: List<Email> = emptyList(),
    val selectedThreadKeys: Set<String> = emptySet(),
    val targetNpub: String = "",
    val isLoading: Boolean = true,
    val isRunning: Boolean = false,
    val processed: Int = 0,
    val total: Int = 0,
    val result: EmailForwardSummary? = null,
    val isTransferring: Boolean = false,
    val transferToast: String? = null,
    val error: String? = null
) {
    val isTargetValid: Boolean
        get() = targetNpub.trim().startsWith("npub1") && targetNpub.trim().length > 20
}

class EmailForwardViewModel(
    private val emailUseCase: EmailUseCase,
    private val preferencesManager: PreferencesManager,
    private val mailTransfer: MailTransferManager,
    private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmailForwardUiState())
    val uiState: StateFlow<EmailForwardUiState> = _uiState.asStateFlow()

    private var currentUserNpub: String? = null

    init {
        viewModelScope.launch {
            currentUserNpub = preferencesManager.npubKey.firstOrNull()
            val npub = currentUserNpub ?: return@launch
            emailUseCase.observeThreads(npub).collect { threads ->
                _uiState.value = _uiState.value.copy(
                    threads = threads,
                    isLoading = false,
                    // Keep only keys that still exist as threads.
                    selectedThreadKeys = _uiState.value.selectedThreadKeys.intersect(
                        threads.map { it.threadKey }.toSet()
                    )
                )
            }
        }
    }

    fun onTargetChange(value: String) {
        _uiState.value = _uiState.value.copy(targetNpub = value, error = null)
    }

    fun toggleThread(threadKey: String) {
        val current = _uiState.value.selectedThreadKeys
        _uiState.value = _uiState.value.copy(
            selectedThreadKeys = if (threadKey in current) current - threadKey else current + threadKey
        )
    }

    fun selectAll() {
        _uiState.value = _uiState.value.copy(
            selectedThreadKeys = _uiState.value.threads.map { it.threadKey }.toSet()
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedThreadKeys = emptySet())
    }

    /**
     * Forward every eligible message of the selected threads to the target
     * key. Messages already delivered there (forward ledger) are skipped, so
     * re-running after an interruption resumes instead of duplicating.
     */
    fun startForward() {
        val npub = currentUserNpub ?: return
        val state = _uiState.value
        if (state.isRunning || !state.isTargetValid || state.selectedThreadKeys.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = state.copy(isRunning = true, processed = 0, total = 0, result = null, error = null)

            val ids = emailUseCase.getForwardEligibleEmailIds(npub, state.selectedThreadKeys.toList())
            if (ids.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isRunning = false,
                    error = "No forwardable messages in the selected threads"
                )
                return@launch
            }

            val result = emailUseCase.forwardEmails(
                emailIds = ids,
                targetNpub = state.targetNpub.trim(),
                onProgress = { processed, total ->
                    _uiState.value = _uiState.value.copy(processed = processed, total = total)
                }
            )

            result.fold(
                onSuccess = { summary ->
                    _uiState.value = _uiState.value.copy(isRunning = false, result = summary)
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(
                        isRunning = false,
                        error = it.message ?: "Forward failed"
                    )
                }
            )
        }
    }

    fun dismissResult() {
        _uiState.value = _uiState.value.copy(result = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // ==================== Offline transfer (.dsme) ====================

    /**
     * SAF callback: encrypt the selected threads (or the whole mailbox when
     * nothing is selected) and write the `.dsme` envelope to [uri].
     */
    fun writeExportTo(uri: Uri, passphrase: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTransferring = true, transferToast = null)
            val scope = _uiState.value.selectedThreadKeys.toList().takeIf { it.isNotEmpty() }
            val export = mailTransfer.export(scope, passphrase)
            val blob = export.getOrNull()
            if (blob == null) {
                _uiState.value = _uiState.value.copy(
                    isTransferring = false,
                    transferToast = "Export failed: ${export.exceptionOrNull()?.message}"
                )
                return@launch
            }
            try {
                appContext.contentResolver.openOutputStream(uri)?.use { it.write(blob) }
                    ?: throw IOException("Could not open destination")
                _uiState.value = _uiState.value.copy(
                    isTransferring = false,
                    transferToast = "Mail exported"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isTransferring = false,
                    transferToast = "Export failed: ${e.message}"
                )
            }
        }
    }

    /** SAF callback: read a `.dsme` envelope and import it under this account. */
    fun importFrom(uri: Uri, passphrase: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTransferring = true, transferToast = null)
            try {
                val blob = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IOException("Could not open file")
                val result = mailTransfer.import(blob, passphrase)
                _uiState.value = _uiState.value.copy(
                    isTransferring = false,
                    transferToast = result.fold(
                        onSuccess = { inserted ->
                            if (inserted > 0) "Imported $inserted message(s)" else "Nothing new to import"
                        },
                        onFailure = { "Import failed: ${it.message}" }
                    )
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isTransferring = false,
                    transferToast = "Import failed: ${e.message}"
                )
            }
        }
    }

    fun clearTransferToast() {
        _uiState.value = _uiState.value.copy(transferToast = null)
    }
}
