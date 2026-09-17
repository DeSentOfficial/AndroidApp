package xyz.desent.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import xyz.desent.crypto.AesGcm
import xyz.desent.data.attachments.NoteAttachmentClient
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.model.AttachmentMeta
import xyz.desent.domain.model.PrivateContact
import xyz.desent.domain.model.PrivateNote
import xyz.desent.domain.model.UserFile
import xyz.desent.domain.repository.PrivateStorageRepository

/**
 * Use case for NIP-78 private storage (notes + contacts). Wraps the repository
 * and the client-side-encrypted note-attachment flow so the UI layer stays
 * thin. The active account's npub is resolved via [PreferencesManager].
 */
class PrivateStorageUseCase(
    private val repository: PrivateStorageRepository,
    private val preferencesManager: PreferencesManager,
    private val attachmentClient: NoteAttachmentClient
) {

    suspend fun activeOwnerNpub(): String? =
        preferencesManager.npubKey.firstOrNull()

    fun observeNotes(ownerNpub: String): Flow<List<PrivateNote>> =
        repository.observeNotes(ownerNpub)

    fun observeNote(ownerNpub: String, id: String): Flow<PrivateNote?> =
        repository.observeNote(ownerNpub, id)

    suspend fun saveNote(note: PrivateNote): Result<Unit> =
        repository.saveNote(note)

    suspend fun deleteNote(ownerNpub: String, id: String): Result<Unit> =
        repository.deleteNote(ownerNpub, id)

    fun observeContacts(ownerNpub: String): Flow<List<PrivateContact>> =
        repository.observeContacts(ownerNpub)

    suspend fun saveContacts(ownerNpub: String, contacts: List<PrivateContact>): Result<Unit> =
        repository.saveContacts(ownerNpub, contacts)

    // ------------------------------------------------------------------
    // User files (`desent:file:<sha256>`) — the Files screen uploads
    // ------------------------------------------------------------------

    fun observeUserFiles(ownerNpub: String): Flow<List<UserFile>> =
        repository.observeUserFiles(ownerNpub)

    /** Republish an entry as-is (used by the preview-quality re-encode flow). */
    suspend fun saveUserFileEntry(file: UserFile): Result<Unit> =
        repository.saveUserFile(file)

    /** Last blurhash component count chosen on the preview-quality slider. */
    suspend fun previewDetailComponents(): Int =
        preferencesManager.previewDetailComponents.firstOrNull() ?: DEFAULT_PREVIEW_COMPONENTS

    suspend fun setPreviewDetailComponents(components: Int) {
        preferencesManager.setPreviewDetailComponents(components)
    }

    /**
     * Encrypt [data] with a fresh AES-256-GCM key, upload the ciphertext blob,
     * then publish the key metadata inside a self-encrypted kind-30078 entry
     * (`d = "desent:file:<sha256>"`). Per END-23 §2 there is NO rollback if
     * the entry publish is rejected — the blob stays behind as a deletable
     * orphan and the caller surfaces the failure to the user.
     *
     * [blurhash]/[width]/[height] are the client-computed preview fields for
     * images (ANDROID_USER_FILES.md §2 step 2); pass null/0 to omit.
     */
    suspend fun uploadUserFile(
        data: ByteArray,
        mimeType: String,
        fileName: String,
        blurhash: String? = null,
        width: Int = 0,
        height: Int = 0
    ): Result<UserFile> {
        if (data.size > MAX_USER_FILE_BYTES) {
            return Result.failure(Exception("File exceeds the 25 MiB limit"))
        }
        val ownerNpub = activeOwnerNpub()
            ?: return Result.failure(Exception("No active account"))

        val encrypted = AesGcm.encrypt(data)
        val uploaded = attachmentClient.uploadCiphertext(encrypted.wireBytes, mimeType)
            .getOrElse { return Result.failure(it) }

        val file = UserFile(
            sha256 = uploaded.sha256,
            filename = fileName,
            mimeType = mimeType,
            size = data.size.toLong(),
            keyHex = encrypted.keyHex,
            nonceHex = encrypted.nonceHex,
            uploadedAt = System.currentTimeMillis() / 1000,
            blurhash = blurhash,
            width = width,
            height = height,
            ownerNpub = ownerNpub,
            dTag = "desent:file:${uploaded.sha256}"
        )

        return repository.saveUserFile(file).map { file }
    }

    /**
     * Delete per END-23 §4: `DELETE /api/attachments/{sha256}` FIRST (frees
     * quota immediately; 404 = already gone), then publish the empty-content
     * tombstone so the key material leaves private storage. If the blob
     * delete is rejected, nothing is tombstoned — the entry keeps its key
     * and the caller may retry.
     */
    suspend fun deleteUserFile(ownerNpub: String, sha256: String): Result<Unit> {
        val blobDelete = attachmentClient.deleteCiphertextBlobStrict(sha256)
        return if (blobDelete.isSuccess) {
            repository.deleteUserFile(ownerNpub, sha256)
        } else {
            blobDelete
        }
    }

    /** Download a user file's ciphertext and AES-GCM decrypt it to plaintext. */
    suspend fun downloadUserFile(file: UserFile): Result<ByteArray> =
        downloadAttachment(
            AttachmentMeta(
                sha256 = file.sha256,
                keyHex = file.keyHex,
                nonceHex = file.nonceHex,
                mimeType = file.mimeType,
                filename = file.filename,
                size = file.size
            )
        )

    suspend fun subscribeToOwnPrivateStorage(): Result<Unit> =
        repository.subscribeToOwnPrivateStorage()

    /**
     * Client-side-encrypt [data] and upload it via the note-attachment
     * ciphertext endpoint. Returns the [AttachmentMeta] (including the AES key
     * + nonce) to embed inside the NIP-44-encrypted note payload.
     */
    suspend fun uploadAttachment(data: ByteArray, mimeType: String, fileName: String): Result<AttachmentMeta> {
        val encrypted = AesGcm.encrypt(data)
        return attachmentClient.uploadCiphertext(encrypted.wireBytes, mimeType).map { resp ->
            AttachmentMeta(
                sha256 = resp.sha256,
                keyHex = encrypted.keyHex,
                nonceHex = encrypted.nonceHex,
                mimeType = mimeType,
                filename = fileName,
                size = data.size.toLong()
            )
        }
    }

    /** Download a note attachment's ciphertext and AES-GCM decrypt it to plaintext. */
    suspend fun downloadAttachment(meta: AttachmentMeta): Result<ByteArray> =
        attachmentClient.downloadCiphertext(meta.sha256).map { wire ->
            AesGcm.decrypt(wire, meta.keyHex, meta.nonceHex)
        }

    /**
     * Best-effort orphan-blob cleanup: after an attachment is dropped from a
     * note (or the note is deleted), `DELETE /api/attachments/{sha}` the blob
     * row when **no remaining note** references it — via its metadata or an
     * inline `attachment:<sha>` body reference. Failures are silent (the
     * endpoint is advisory for the ciphertext flow; a lingering blob only
     * shows up in the storage breakdown).
     */
    suspend fun deleteAttachmentIfOrphaned(ownerNpub: String, sha256: String) {
        val notes = repository.observeNotes(ownerNpub).firstOrNull().orEmpty()
        val stillReferenced = notes.any { note ->
            note.attachments.any { it.sha256 == sha256 } ||
                note.body.contains("attachment:$sha256")
        }
        if (!stillReferenced) {
            attachmentClient.deleteCiphertextBlob(sha256)
        }
    }

    companion object {
        /** END-23 §2 default: an 8×6-component blurhash. */
        const val DEFAULT_PREVIEW_COMPONENTS = 8

        /**
         * Server caps ciphertext blobs at 25 MiB (raised from 10 MiB 2026-09
         * for END-23 user files — refs/FROM_email.desent.xyz/
         * PRIVATE_STORAGE_PROTOCOL.md §"Attachment wire endpoints"); AES-GCM
         * adds 28 bytes (nonce + tag), so accept plaintext up to 25 MiB − 28
         * to guarantee the server never 413s a client-approved upload.
         */
        const val MAX_USER_FILE_BYTES: Long = 25L * 1024 * 1024 - 28
    }
}

