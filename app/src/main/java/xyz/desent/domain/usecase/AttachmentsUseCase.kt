package xyz.desent.domain.usecase

import xyz.desent.domain.repository.AttachmentsRepository
import java.io.File

class AttachmentsUseCase(
    private val repository: AttachmentsRepository
) {
    suspend fun listAttachments(): Result<List<xyz.desent.domain.model.AttachmentFile>> =
        repository.listAttachments()

    suspend fun deleteAttachment(sha256: String): Result<Unit> =
        repository.deleteAttachment(sha256)

    suspend fun downloadAttachment(
        sha256: String,
        keyHex: String,
        filename: String,
        mimeType: String
    ): Result<File> =
        repository.downloadAttachment(sha256, keyHex, filename, mimeType)
}
