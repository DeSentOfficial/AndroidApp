package xyz.desent.data.repository

import android.content.Context
import android.util.Log
import xyz.desent.data.attachments.AttachmentsClient
import xyz.desent.domain.model.AttachmentFile
import xyz.desent.domain.repository.AttachmentsRepository
import java.io.File

class AttachmentsRepositoryImpl(
    private val context: Context,
    private val client: AttachmentsClient
) : AttachmentsRepository {

    override suspend fun listAttachments(): Result<List<AttachmentFile>> {
        return client.listAttachments().map { resp ->
            resp.attachments.map { it.toDomain() }
        }
    }

    override suspend fun deleteAttachment(sha256: String): Result<Unit> {
        return client.deleteAttachment(sha256)
    }

    override suspend fun downloadAttachment(
        sha256: String,
        keyHex: String,
        filename: String,
        mimeType: String
    ): Result<File> {
        return try {
            val dir = File(context.filesDir, ATTACHMENT_DIR).apply { mkdirs() }
            val safeName = sanitizeFilename(filename, sha256, mimeType)
            val outFile = File(dir, safeName)

            // Complete files only ever appear via the atomic .part rename
            // below, so existence means a previous download fully landed —
            // reuse it instead of re-fetching the blob on every open.
            if (outFile.isFile && outFile.length() > 0L) {
                Log.d(TAG, "Attachment $sha256 already cached: ${outFile.absolutePath}")
                return Result.success(outFile)
            }

            val bytes = client.download(sha256, keyHex).getOrThrow()
            writeAtomically(outFile, bytes)
            Log.d(TAG, "Saved $sha256 -> ${outFile.absolutePath} (${bytes.size} bytes)")
            Result.success(outFile)
        } catch (e: Exception) {
            Log.e(TAG, "downloadAttachment failed for $sha256: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Write via a `.part` temp file + rename so a crash mid-write can never
     * leave a truncated file that a later download would mistake for complete.
     */
    private fun writeAtomically(outFile: File, bytes: ByteArray) {
        val part = File(outFile.parentFile, outFile.name + ".part")
        part.outputStream().use { it.write(bytes) }
        if (!part.renameTo(outFile)) {
            outFile.outputStream().use { it.write(bytes) }
            part.delete()
        }
    }

    private fun xyz.desent.data.attachments.model.AttachmentDto.toDomain(): AttachmentFile = AttachmentFile(
        sha256 = sha256,
        filename = filename,
        mimeType = mimeType,
        size = size,
        createdAt = createdAt,
        blurhash = blurhash,
        width = width,
        height = height,
        isInline = isInline
    )

    private fun sanitizeFilename(name: String, sha256: String, mimeType: String): String {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty() && trimmed.length <= MAX_FILENAME && trimmed.none { it in INVALID_CHARS }) {
            return trimmed
        }
        val ext = mimeType.substringAfter('/', "").takeIf { it.isNotEmpty() }?.let { ".$it" }
        return "${sha256.take(16)}${ext ?: ""}"
    }

    companion object {
        private const val TAG = "AttachmentsRepository"
        private const val ATTACHMENT_DIR = "attachments"
        private const val MAX_FILENAME = 180
        private const val INVALID_CHARS = "/\\:*?\"<>|\u0000\n\r\t"
    }
}
