package xyz.desent.domain.repository

import java.io.File

interface AttachmentsRepository {
    suspend fun listAttachments(): Result<List<xyz.desent.domain.model.AttachmentFile>>
    suspend fun deleteAttachment(sha256: String): Result<Unit>
    suspend fun downloadAttachment(sha256: String, keyHex: String, filename: String, mimeType: String): Result<File>
}
