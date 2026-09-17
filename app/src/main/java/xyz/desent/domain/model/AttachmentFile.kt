package xyz.desent.domain.model

/**
 * A blob stored on the DeSent blossom/attachments server.
 *
 * `isInline` (from the API's `is_inline`) is true when an `email_attachments`
 * row links this blob to a message; false means the message was deleted (or it
 * was a self-upload) — i.e. the bytes exist but no message "owns" them.
 */
data class AttachmentFile(
    val sha256: String,
    val filename: String?,
    val mimeType: String?,
    val size: Long,
    val createdAt: String?,
    val blurhash: String?,
    val width: Long?,
    val height: Long?,
    val isInline: Boolean
) {
    val isOrphaned: Boolean get() = !isInline
    val isImage: Boolean get() = mimeType?.startsWith("image/") == true
}
