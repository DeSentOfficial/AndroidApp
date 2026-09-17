package xyz.desent.presentation.ui.email.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import xyz.desent.data.attachment.AttachmentDownloader
import xyz.desent.data.pgp.PgpAttachmentCache
import xyz.desent.data.pgp.PgpFeatureGate
import xyz.desent.data.pgp.PgpMimeParser
import xyz.desent.data.spam.RemoteImagePolicyState
import xyz.desent.data.spam.RemoteImagePolicyStateFactory
import xyz.desent.domain.model.EmailAttachment
import xyz.desent.domain.model.PgpDecryptedMessage
import xyz.desent.domain.repository.ContactProfileResolver
import xyz.desent.domain.repository.EmailRepository
import xyz.desent.domain.repository.PgpKeyRepository
import xyz.desent.domain.usecase.EmailUseCase
import xyz.desent.domain.usecase.PrivateStorageUseCase
import java.io.File

data class EmailDetailUiState(
    val email: xyz.desent.domain.model.Email? = null,
    val isLoading: Boolean = true,
    val isDeleting: Boolean = false,
    val isForwarding: Boolean = false,
    val downloadStates: Map<String, AttachmentDownloadState> = emptyMap(),
    val deleted: Boolean = false,
    val toast: String? = null,
    val error: String? = null,
    /** PGP read state; NotPgp for ordinary mail. */
    val pgp: PgpBodyState = PgpBodyState.NotPgp,
    /** Mail-folder assignment (path for the chip; null = unfiled). */
    val folderPath: String? = null
)

/**
 * PGP read path (ANDROID_PGP.md §3): decrypt with the account key on open,
 * sniff the inner body for HTML, and extract inner-MIME attachments to the
 * app-private cache. Failures render a locked placeholder — the raw armor is
 * NEVER shown as though it were the message body.
 */
sealed class PgpBodyState {
    data object NotPgp : PgpBodyState()
    data object Decrypting : PgpBodyState()
    data class Decrypted(val message: PgpDecryptedMessage, val attachmentFiles: List<File>) : PgpBodyState()
    data class Locked(val reason: String) : PgpBodyState()
}

sealed class AttachmentDownloadState {
    object Idle : AttachmentDownloadState()
    object Loading : AttachmentDownloadState()
    data class Saved(val file: File) : AttachmentDownloadState()
    data class Failed(val message: String) : AttachmentDownloadState()
}

class EmailDetailViewModel(
    private val emailId: String,
    private val emailUseCase: EmailUseCase,
    private val emailRepository: EmailRepository,
    private val attachmentDownloader: AttachmentDownloader,
    private val trainSpamUseCase: xyz.desent.domain.usecase.TrainSpamUseCase,
    imagePolicyFactory: RemoteImagePolicyStateFactory,
    private val privateStorageUseCase: PrivateStorageUseCase,
    contactProfileResolver: ContactProfileResolver,
    faviconResolver: xyz.desent.data.avatar.FaviconResolver,
    private val pgpKeyRepository: PgpKeyRepository? = null,
    private val pgpFeatureGate: PgpFeatureGate? = null,
    private val pgpMimeParser: PgpMimeParser? = null,
    private val pgpAttachmentCache: PgpAttachmentCache? = null,
    private val mailFolderRepository: xyz.desent.domain.repository.MailFolderRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmailDetailUiState())
    val uiState: StateFlow<EmailDetailUiState> = _uiState.asStateFlow()

    /** Folder tree for the move-to-folder sheet. */
    val folders = MutableStateFlow<List<xyz.desent.domain.model.MailFolder>>(emptyList())

    /** Assigned folder id (sheet checkmark); null = unfiled. */
    val assignedFolderId = MutableStateFlow<String?>(null)

    /** Remote-image blocking state for this message. */
    val imagePolicy: RemoteImagePolicyState by lazy { imagePolicyFactory.create(viewModelScope) }

    /** Sender avatar for the opened message. */
    val senderProfiles = SenderProfileStore(contactProfileResolver, faviconResolver, viewModelScope)

    init {
        loadEmail()
        observeFolderAssignment()
    }

    /** Folder assignment chip state: overlay entry for this message's pinned key. */
    private fun observeFolderAssignment() {
        val repo = mailFolderRepository ?: return
        viewModelScope.launch {
            val npub = privateStorageUseCase.activeOwnerNpub() ?: return@launch
            kotlinx.coroutines.flow.combine(repo.observeFolders(npub), repo.observeState(npub)) { folders, state ->
                val email = _uiState.value.email
                val assigned = email?.let { state[it.folderKey]?.f }
                assignedFolderId.value = assigned
                folders.firstOrNull { it.id == assigned }?.displayPath(folders)
            }.collect { path ->
                _uiState.value = _uiState.value.copy(folderPath = path)
            }
        }
        viewModelScope.launch {
            val npub = privateStorageUseCase.activeOwnerNpub() ?: return@launch
            repo.observeFolders(npub).collect { folders.value = it }
        }
    }

    /** File this message into [folderId], or unfile it (null). */
    fun moveToFolder(folderId: String?) {
        val repo = mailFolderRepository ?: return
        val email = _uiState.value.email ?: return
        viewModelScope.launch {
            val npub = privateStorageUseCase.activeOwnerNpub() ?: return@launch
            val result = repo.moveToFolder(npub, email.folderKey, folderId)
            _uiState.value = _uiState.value.copy(
                toast = if (result.isSuccess) {
                    if (folderId == null) "Removed from folder" else "Moved to folder"
                } else {
                    "Move failed: ${result.exceptionOrNull()?.message}"
                }
            )
        }
    }

    /** Train the spam filter on this message and file it as spam ("Report as spam"). */
    fun reportSpam() {
        val email = _uiState.value.email ?: return
        viewModelScope.launch {
            trainSpamUseCase(email, isSpam = true)
            _uiState.value = _uiState.value.copy(toast = "Reported as spam")
        }
    }

    /** Create a folder and immediately file this message into it. */
    fun createFolderAndFile(name: String) {
        val repo = mailFolderRepository ?: return
        val email = _uiState.value.email ?: return
        viewModelScope.launch {
            val npub = privateStorageUseCase.activeOwnerNpub() ?: return@launch
            repo.createFolder(npub, name)
                .onSuccess { folder ->
                    repo.moveToFolder(npub, email.folderKey, folder.id)
                    _uiState.value = _uiState.value.copy(toast = "Filed into \"${folder.name}\"")
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(toast = "Create failed: ${it.message}")
                }
        }
    }

    private fun loadEmail() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val email = emailRepository.getEmailById(emailId)

            _uiState.value = _uiState.value.copy(
                email = email,
                isLoading = false
            )

            // Mark as read
            email?.let {
                emailUseCase.markAsRead(it.id)
                senderProfiles.ensure(it)
            }

            if (email != null && email.isPgpEncrypted && email.direction ==
                xyz.desent.domain.model.EmailDirection.INBOUND
            ) {
                decryptPgpBody()
            }
        }
    }

    /** Decrypt the armored body (own outbound rows already carry plaintext). */
    private fun decryptPgpBody() {
        val email = _uiState.value.email ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(pgp = PgpBodyState.Decrypting)

            val repo = pgpKeyRepository
            val gate = pgpFeatureGate
            if (repo == null || gate == null) {
                _uiState.value = _uiState.value.copy(pgp = PgpBodyState.Locked("PGP is not available"))
                return@launch
            }
            if (!gate.enabled.value) {
                _uiState.value = _uiState.value.copy(
                    pgp = PgpBodyState.Locked("PGP is disabled on this relay")
                )
                return@launch
            }

            val decryptResult = repo.decryptMessage(email.content)
            val decrypted = decryptResult.getOrNull()
            if (decrypted == null) {
                val reason = when (decryptResult.exceptionOrNull()?.message) {
                    xyz.desent.data.pgp.PgpKeyManager.DECRYPT_NO_KEY_MESSAGE ->
                        "No PGP key on this account"
                    else -> "Could not decrypt with this account's key"
                }
                _uiState.value = _uiState.value.copy(pgp = PgpBodyState.Locked(reason))
                return@launch
            }

            val parsed = runCatching {
                pgpMimeParser?.parse(decrypted) ?: PgpDecryptedMessage(
                    body = String(decrypted, Charsets.UTF_8),
                    isHtml = xyz.desent.crypto.OpenPgpCrypto.sniffHtml(String(decrypted, Charsets.UTF_8))
                )
            }.getOrNull() ?: PgpDecryptedMessage(
                body = String(decrypted, Charsets.UTF_8),
                isHtml = false
            )

            val files = pgpAttachmentCache?.let { cache ->
                parsed.attachments.mapNotNull { cache.save(email.id, it) }
            }.orEmpty()

            _uiState.value = _uiState.value.copy(pgp = PgpBodyState.Decrypted(parsed, files))
        }
    }

    fun onRequestDeletion() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true)

            val result = emailUseCase.requestDeletion(emailId)

            if (result.isSuccess) {
                // Server hard-deleted the message and the local row is gone.
                // Signal the screen to pop back to the inbox.
                _uiState.value = _uiState.value.copy(
                    isDeleting = false,
                    deleted = true
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isDeleting = false,
                    error = "Failed to delete message: ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    /**
     * Re-deliver this message to another Nostr key (NIP-EMAIL forwarding):
     * rebuilt as a fresh kind-1010 rumor with a `forwarded_by` provenance tag,
     * sealed with the user's key and gift-wrapped to [targetNpub].
     */
    fun forwardToNpub(targetNpub: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isForwarding = true)

            val result = emailUseCase.forwardEmails(listOf(emailId), targetNpub.trim())

            _uiState.value = _uiState.value.copy(
                isForwarding = false,
                toast = result.fold(
                    onSuccess = { summary ->
                        if (summary.sent > 0) "Forwarded to ${targetNpub.take(20)}…" else "Already delivered to that key"
                    },
                    onFailure = { "Forward failed: ${it.message}" }
                )
            )
        }
    }

    fun downloadAttachment(attachment: EmailAttachment) {
        viewModelScope.launch {
            updateDownloadState(attachment.sha256, AttachmentDownloadState.Loading)

            val result = attachmentDownloader.download(attachment)

            _uiState.value = _uiState.value.copy(
                downloadStates = _uiState.value.downloadStates + (
                    attachment.sha256 to result.fold(
                        onSuccess = { AttachmentDownloadState.Saved(it) },
                        onFailure = { AttachmentDownloadState.Failed(it.message ?: "Download failed") }
                    )
                ),
                toast = result.fold(
                    onSuccess = { "Saved ${attachment.filename.ifBlank { "attachment" }}" },
                    onFailure = { "Download failed: ${it.message}" }
                )
            )
        }
    }

    /**
     * "Save to contacts" (ANDROID_CONTACTS.md §4): dedup against every stored
     * email slot, then append a v2 entry with a `From: <subject>` origin note.
     * The legacy `threadToken` of the message is deliberately ignored.
     */
    fun saveSenderToContacts() {
        val email = _uiState.value.email ?: return
        if (email.direction != xyz.desent.domain.model.EmailDirection.INBOUND) return
        viewModelScope.launch {
            val npub = privateStorageUseCase.activeOwnerNpub() ?: run {
                _uiState.value = _uiState.value.copy(toast = "No active account")
                return@launch
            }
            val sender = email.senderEmail.trim()
            if (sender.isEmpty()) {
                _uiState.value = _uiState.value.copy(toast = "No sender address to save")
                return@launch
            }
            val contacts = privateStorageUseCase.observeContacts(npub).firstOrNull().orEmpty()
            if (contacts.any { c -> c.allEmails().any { it.equals(sender, ignoreCase = true) } }) {
                _uiState.value = _uiState.value.copy(toast = "Already in contacts")
                return@launch
            }
            val entry = xyz.desent.domain.model.PrivateContact(
                name = email.senderName.orEmpty(),
                emails = listOf(xyz.desent.domain.model.ContactEmailAddress(label = "", value = sender)),
                domain = sender.substringAfterLast("@", ""),
                notes = email.subject.takeIf { it.isNotBlank() }?.let { "From: $it" }
            )
            val result = privateStorageUseCase.saveContacts(npub, contacts + entry)
            _uiState.value = _uiState.value.copy(
                toast = if (result.isSuccess) "Saved to contacts" else "Save failed: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun updateDownloadState(sha256: String, state: AttachmentDownloadState) {
        _uiState.value = _uiState.value.copy(
            downloadStates = _uiState.value.downloadStates + (sha256 to state)
        )
    }
}
