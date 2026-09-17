package xyz.desent.data.storage.model

import kotlinx.serialization.Serializable

@Serializable
data class StorageBreakdownDto(
    val used: Long = 0L,
    val cap: Long = 0L,
    val tier: String = "free",
    val source: String = "free",
    val categories: List<StorageCategoryDto> = emptyList()
)

@Serializable
data class StorageCategoryDto(
    val key: String = "",
    val label: String = "",
    val bytes: Long = 0L
)

// RFC 7807-ish: { "detail": { "error": "...", ... } }. FastAPI occasionally
// returns detail as a plain string, so keep every field nullable.
@Serializable
data class StorageErrorDetail(
    val error: String? = null
)

@Serializable
data class StorageErrorResponse(
    val detail: StorageErrorDetail? = null
)

/** Typed failures raised by [xyz.desent.data.storage.StorageClient]. */
sealed class StorageError(message: String) : Exception(message) {
    object Unauthorized : StorageError("Authentication failed")
    object Forbidden : StorageError("Account not registered on this relay")
    /** Raised on 429. [retryAfterSeconds] mirrors the `Retry-After` header. */
    class RateLimited(val retryAfterSeconds: Int?) :
        StorageError("Too many requests" + retryAfterSeconds?.let { " (retry in ${it}s)" }.orEmpty())
    class Server(message: String, val code: Int) : StorageError(message)
    class Unknown(message: String) : StorageError(message)
}
