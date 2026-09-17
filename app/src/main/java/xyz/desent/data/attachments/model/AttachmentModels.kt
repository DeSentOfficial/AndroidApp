package xyz.desent.data.attachments.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AttachmentDto(
    val sha256: String,
    val filename: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    val size: Long = 0,
    @SerialName("created_at") val createdAt: String? = null,
    val blurhash: String? = null,
    val width: Long? = null,
    val height: Long? = null,
    @SerialName("is_inline") val isInline: Boolean = false
)

@Serializable
data class AttachmentListResponse(
    val attachments: List<AttachmentDto> = emptyList(),
    val count: Int = 0
)

@Serializable
data class AttachmentDeleteResponse(
    val status: String = "deleted",
    val sha256: String = ""
)

// RFC 7807-ish error envelope shared with the alias/messages endpoints.
@Serializable
data class AttachmentErrorResponse(
    val detail: AttachmentErrorDetail? = null
)

@Serializable
data class AttachmentErrorDetail(
    val error: String? = null,
    val action: String? = null,
    @SerialName("retry_after") val retryAfter: Int? = null
)
