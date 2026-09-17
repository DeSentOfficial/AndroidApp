package xyz.desent.presentation.ui.files.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.desent.data.attachment.NoteAttachmentOpener
import xyz.desent.data.attachment.UploadPreviewGenerator
import xyz.desent.data.attachment.UploadPreviewGenerator.DecodedPreview
import xyz.desent.data.attachment.UserFileOpener
import xyz.desent.domain.model.AttachmentFile
import xyz.desent.domain.model.AttachmentMeta
import xyz.desent.domain.model.AliasTierInfo
import xyz.desent.domain.model.UserFile
import xyz.desent.domain.usecase.AliasUseCase
import xyz.desent.domain.usecase.AttachmentsUseCase
import xyz.desent.domain.usecase.EmailUseCase
import xyz.desent.domain.usecase.PrivateStorageUseCase
import java.io.File

/**
 * One row of the Files screen (END-23 / ANDROID_USER_FILES.md §1): the row
 * set is the server blob list (`GET /api/attachments`), each row classified
 * client-side by where its decryption key lives. Priority: upload entry →
 * note attachment → email gift-wrap → orphan.
 */
data class FilesRow(
    val serverFile: AttachmentFile,
    val userFile: UserFile? = null,
    val noteMeta: AttachmentMeta? = null,
    val emailKeyHex: String? = null
) {
    val isUpload: Boolean get() = userFile != null
    val isNote: Boolean get() = !isUpload && noteMeta != null
    val isOrphan: Boolean get() = !isUpload && !isNote && emailKeyHex == null
    val canDownload: Boolean get() = isUpload || isNote || emailKeyHex != null
    val sha256: String get() = serverFile.sha256

    /** Display filename with upload/note priority over the (always-null) server name. */
    val filename: String?
        get() = userFile?.filename?.ifBlank { null }
            ?: noteMeta?.filename?.ifBlank { null }
            ?: serverFile.filename
}

data class FilesUiState(
    val rows: List<FilesRow> = emptyList(),
    val tierInfo: AliasTierInfo? = null,
    val isLoading: Boolean = true,
    val isDeleting: Boolean = false,
    val isUploading: Boolean = false,
    val openStates: Map<String, RowOpenState> = emptyMap(),
    val pendingPreview: PendingPreview? = null,
    val error: String? = null,
    val toast: String? = null
)

sealed class RowOpenState {
    object Loading : RowOpenState()
    /** Decrypted to a content Uri (upload/note rows) — hand to ACTION_VIEW. */
    data class Ready(val uri: Uri, val mimeType: String) : RowOpenState()
    /** Decrypted to a cached File (email-attachment rows). */
    data class SavedFile(val file: File, val mimeType: String?) : RowOpenState()
    data class Failed(val message: String) : RowOpenState()
}

/**
 * The preview-quality dialog's backing state. The slider re-encodes the
 * blurhash from the held downscaled pixels (blurhash detail is fixed at
 * encode time — the component count is part of the hash string).
 */
sealed class PendingPreview {
    /** New image upload: pick the detail level before the entry is published. */
    data class NewUpload(
        val data: ByteArray,
        val mimeType: String,
        val fileName: String,
        val decoded: DecodedPreview,
        val components: Int,
        val blurhash: String
    ) : PendingPreview()

    /** Re-encode an existing upload's preview: decrypt → decode → slider. */
    data class Reencode(
        val file: UserFile,
        val decoded: DecodedPreview,
        val components: Int,
        val blurhash: String
    ) : PendingPreview()

    /** Downloading + decrypting the source image for a re-encode. */
    data class ReencodeLoading(val file: UserFile) : PendingPreview()
}

class FilesViewModel(
    private val attachmentsUseCase: AttachmentsUseCase,
    private val emailUseCase: EmailUseCase,
    private val aliasUseCase: AliasUseCase,
    private val privateStorageUseCase: PrivateStorageUseCase,
    private val userFileOpener: UserFileOpener,
    private val noteAttachmentOpener: NoteAttachmentOpener,
    private val previewGenerator: UploadPreviewGenerator,
    val pendingUploads: xyz.desent.presentation.ui.files.PendingUploads
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    /** Live mirror of the `desent:file:*` kind-30078 cache (relay echoes land here). */
    private var userFiles: List<UserFile> = emptyList()

    /** sha256 → note attachment metadata (key lives in the note payload). */
    private var noteIndex: Map<String, AttachmentMeta> = emptyMap()

    /** sha256 → email attachment key (recovered from stored gift-wrap tags). */
    private var emailKeys: Map<String, String> = emptyMap()

    init {
        loadFiles()
        observeUserFiles()
        observeNoteAttachments()
    }

    private fun observeUserFiles() {
        viewModelScope.launch {
            val owner = privateStorageUseCase.activeOwnerNpub() ?: return@launch
            privateStorageUseCase.observeUserFiles(owner).collect { files ->
                userFiles = files
                rebuildRows()
            }
        }
    }

    private fun observeNoteAttachments() {
        viewModelScope.launch {
            val owner = privateStorageUseCase.activeOwnerNpub() ?: return@launch
            privateStorageUseCase.observeNotes(owner).collect { notes ->
                noteIndex = notes.flatMap { note -> note.attachments }
                    .associateBy { it.sha256 }
                rebuildRows()
            }
        }
    }

    fun loadFiles() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val tier = aliasUseCase.getTierInfo().getOrNull()
            val result = attachmentsUseCase.listAttachments()
            if (result.isSuccess) {
                emailKeys = result.getOrDefault(emptyList())
                    .mapNotNull { file ->
                        emailUseCase.findAttachmentKey(file.sha256)?.let { file.sha256 to it }
                    }
                    .toMap()
                _uiState.value = _uiState.value.copy(
                    rows = buildRows(result.getOrDefault(emptyList())),
                    tierInfo = tier,
                    isLoading = false
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    tierInfo = tier,
                    isLoading = false,
                    error = result.exceptionOrNull()?.message ?: "Failed to load files"
                )
            }
        }
    }

    fun refresh() = loadFiles()

    private fun rebuildRows() {
        val current = _uiState.value.rows
        if (current.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(rows = buildRows(current.map { it.serverFile }))
        }
    }

    private fun buildRows(files: List<AttachmentFile>): List<FilesRow> {
        val userBySha = userFiles.associateBy { it.sha256 }
        return files.map { file ->
            FilesRow(
                serverFile = file,
                userFile = userBySha[file.sha256],
                noteMeta = noteIndex[file.sha256],
                emailKeyHex = emailKeys[file.sha256]
            )
        }
    }

    /**
     * Direct upload (share-intent path, non-images, or decode failures):
     * previews for images are encoded at the stored default detail level.
     */
    fun uploadFile(data: ByteArray, mimeType: String, fileName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, toast = null)
            val preview = if (mimeType.startsWith("image/")) {
                defaultPreview(data)
            } else {
                null
            }
            performUpload(data, mimeType, fileName, preview?.blurhash, preview?.width ?: 0, preview?.height ?: 0)
        }
    }

    /**
     * Picker path for images: open the preview-quality dialog instead of
     * uploading immediately. Non-images and decode failures fall through to
     * a direct upload.
     */
    fun beginImageUpload(data: ByteArray, mimeType: String, fileName: String) {
        if (!mimeType.startsWith("image/")) {
            uploadFile(data, mimeType, fileName)
            return
        }
        viewModelScope.launch {
            val decoded = previewGenerator.decode(data)
            if (decoded == null) {
                uploadFile(data, mimeType, fileName)
                return@launch
            }
            val components = privateStorageUseCase.previewDetailComponents()
            val (numX, numY) = componentsToGrid(components)
            val hash = previewGenerator.encodePreview(decoded, numX, numY)?.blurhash
            if (hash == null) {
                uploadFile(data, mimeType, fileName)
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                pendingPreview = PendingPreview.NewUpload(data, mimeType, fileName, decoded, components, hash)
            )
        }
    }

    /** Slider tick: re-encode the held pixels at the new detail level. */
    fun setPreviewComponents(components: Int) {
        val pending = _uiState.value.pendingPreview ?: return
        val clamped = components.coerceIn(1, 9)
        val decoded = when (pending) {
            is PendingPreview.NewUpload -> pending.decoded
            is PendingPreview.Reencode -> pending.decoded
            is PendingPreview.ReencodeLoading -> return
        }
        val (numX, numY) = componentsToGrid(clamped)
        val hash = previewGenerator.encodePreview(decoded, numX, numY)?.blurhash ?: return
        _uiState.value = _uiState.value.copy(
            pendingPreview = when (pending) {
                is PendingPreview.NewUpload -> pending.copy(components = clamped, blurhash = hash)
                is PendingPreview.Reencode -> pending.copy(components = clamped, blurhash = hash)
                is PendingPreview.ReencodeLoading -> pending
            }
        )
    }

    /** Dialog confirmed: upload / republish with the chosen preview detail. */
    fun confirmPendingPreview() {
        val pending = _uiState.value.pendingPreview ?: return
        val components = when (pending) {
            is PendingPreview.NewUpload -> pending.components
            is PendingPreview.Reencode -> pending.components
            is PendingPreview.ReencodeLoading -> return
        }
        _uiState.value = _uiState.value.copy(pendingPreview = null)
        viewModelScope.launch {
            // Remember the choice as the slider's next starting point.
            privateStorageUseCase.setPreviewDetailComponents(components)
            when (pending) {
                is PendingPreview.NewUpload -> {
                    _uiState.value = _uiState.value.copy(isUploading = true)
                    performUpload(
                        pending.data, pending.mimeType, pending.fileName,
                        pending.blurhash, pending.decoded.naturalWidth, pending.decoded.naturalHeight
                    )
                }
                is PendingPreview.Reencode -> {
                    val result = privateStorageUseCase.saveUserFileEntry(
                        pending.file.copy(
                            blurhash = pending.blurhash,
                            width = pending.decoded.naturalWidth,
                            height = pending.decoded.naturalHeight
                        )
                    )
                    _uiState.value = _uiState.value.copy(
                        toast = result.fold(
                            onSuccess = { "Preview updated" },
                            onFailure = { "Preview update failed: ${it.message}" }
                        )
                    )
                }
                is PendingPreview.ReencodeLoading -> Unit
            }
        }
    }

    fun cancelPendingPreview() {
        _uiState.value = _uiState.value.copy(pendingPreview = null)
    }

    /**
     * "Adjust preview" on an uploaded file: download + decrypt the source
     * image (we hold the key), decode it, then open the same slider dialog.
     */
    fun beginReencode(file: UserFile) {
        _uiState.value = _uiState.value.copy(
            pendingPreview = PendingPreview.ReencodeLoading(file),
            toast = null
        )
        viewModelScope.launch {
            val bytes = privateStorageUseCase.downloadUserFile(file).getOrNull()
            val decoded = bytes?.let { previewGenerator.decode(it) }
            if (decoded == null) {
                _uiState.value = _uiState.value.copy(
                    pendingPreview = null,
                    toast = "Couldn't decode the image for preview"
                )
                return@launch
            }
            val components = privateStorageUseCase.previewDetailComponents()
            val (numX, numY) = componentsToGrid(components)
            val hash = previewGenerator.encodePreview(decoded, numX, numY)?.blurhash
            if (hash == null) {
                _uiState.value = _uiState.value.copy(
                    pendingPreview = null,
                    toast = "Couldn't encode a preview"
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                pendingPreview = PendingPreview.Reencode(file, decoded, components, hash)
            )
        }
    }

    /** Slider value → blurhash component grid (8 → 8×6, the END-23 default). */
    private fun componentsToGrid(v: Int): Pair<Int, Int> = v to maxOf(1, v * 3 / 4)

    private suspend fun defaultPreview(data: ByteArray): UploadPreviewGenerator.Preview? {
        val decoded = previewGenerator.decode(data) ?: return null
        val (numX, numY) = componentsToGrid(privateStorageUseCase.previewDetailComponents())
        return previewGenerator.encodePreview(decoded, numX, numY)
    }

    private suspend fun performUpload(
        data: ByteArray,
        mimeType: String,
        fileName: String,
        blurhash: String?,
        width: Int,
        height: Int
    ) {
        val result = privateStorageUseCase.uploadUserFile(
            data = data,
            mimeType = mimeType,
            fileName = fileName,
            blurhash = blurhash,
            width = width,
            height = height
        )
        _uiState.value = _uiState.value.copy(
            isUploading = false,
            toast = result.fold(
                onSuccess = { "Uploaded ${it.filename}" },
                onFailure = { "Upload failed: ${it.message}" }
            )
        )
        if (result.isSuccess) loadFiles()
    }

    fun deleteRow(row: FilesRow) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true)
            val result = if (row.isUpload) {
                // END-23 §4 ordering: blob DELETE first, then the tombstone.
                val owner = privateStorageUseCase.activeOwnerNpub()
                if (owner != null) {
                    privateStorageUseCase.deleteUserFile(owner, row.sha256)
                } else {
                    Result.failure(Exception("No active account"))
                }
            } else {
                attachmentsUseCase.deleteAttachment(row.sha256)
            }
            _uiState.value = _uiState.value.copy(
                isDeleting = false,
                toast = if (result.isSuccess) "File deleted" else "Delete failed: ${result.exceptionOrNull()?.message}"
            )
            if (result.isSuccess) loadFiles()
        }
    }

    fun openRow(row: FilesRow) {
        viewModelScope.launch {
            updateOpenState(row.sha256, RowOpenState.Loading)
            val state = when {
                row.isUpload -> userFileOpener.open(row.userFile!!).fold(
                    onSuccess = { RowOpenState.Ready(it.uri, it.mimeType) },
                    onFailure = { RowOpenState.Failed(it.message ?: "Download failed") }
                )
                row.isNote -> noteAttachmentOpener.open(row.noteMeta!!).fold(
                    onSuccess = { RowOpenState.Ready(it.uri, it.mimeType) },
                    onFailure = { RowOpenState.Failed(it.message ?: "Download failed") }
                )
                row.emailKeyHex != null -> {
                    val result = attachmentsUseCase.downloadAttachment(
                        sha256 = row.sha256,
                        keyHex = row.emailKeyHex,
                        filename = row.filename ?: row.sha256,
                        mimeType = row.serverFile.mimeType ?: "application/octet-stream"
                    )
                    result.fold(
                        onSuccess = { RowOpenState.SavedFile(it, row.serverFile.mimeType) },
                        onFailure = { RowOpenState.Failed(it.message ?: "Download failed") }
                    )
                }
                else -> RowOpenState.Failed("No key — the delivering message was deleted")
            }
            _uiState.value = _uiState.value.copy(
                openStates = _uiState.value.openStates + (row.sha256 to state),
                toast = if (state is RowOpenState.Failed) "Download failed: ${state.message}" else null
            )
        }
    }

    fun consumePendingUploads() {
        pendingUploads.clear()
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun updateOpenState(sha256: String, state: RowOpenState) {
        _uiState.value = _uiState.value.copy(
            openStates = _uiState.value.openStates + (sha256 to state)
        )
    }
}
