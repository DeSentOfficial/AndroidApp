package xyz.desent.data.message

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.desent.crypto.NostrHttpAuth

/**
 * Inbox message management API for the DeSent email relay.
 *
 * Per refs/MESSAGES_API_REFERENCE.md, recipient-initiated deletion of a kind 1059
 * gift wrap CANNOT use NIP-09 (the recipient isn't the gift wrap's author, so the
 * relay correctly rejects kind 5 events). This HTTP endpoint authorizes by
 * recipient instead: the caller's NIP-98 pubkey must appear in the gift wrap's
 * `p` tag. Auth is NIP-98 (kind 27235), identical to the alias API.
 *
 * Hard delete: removes the gift wrap, the relay's plaintext mirror, and cascades
 * to attachment linkage rows. Permanent and irreversible.
 */
class MessageClient(
    private val okHttpClient: OkHttpClient,
    private val auth: NostrHttpAuth,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Permanently delete one inbox message (gift wrap) by its event id.
     *
     * Per the spec, `404` is treated as success: the message is already gone
     * (or wasn't owned by the caller), and the caller should drop it from its
     * local cache regardless.
     */
    suspend fun deleteMessage(eventId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/$eventId"
            val header = auth.buildAuthHeader(url, "DELETE").getOrThrow()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", header)
                .delete()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "DELETE $url -> ${response.code} OK")
                    return@use Result.success(Unit)
                }

                // 404 = already gone / not owned by caller. Spec: drop locally anyway.
                if (response.code == 404) {
                    Log.d(TAG, "DELETE $url -> 404 (already gone / not owned), treating as success")
                    return@use Result.success(Unit)
                }

                val body = response.body?.string()
                val code = parseErrorCode(body)
                val retryAfter = response.header("Retry-After")
                val msg = buildString {
                    append("HTTP ${response.code}")
                    code?.let { append(": $it") }
                    retryAfter?.let { append(" (retry after ${it}s)") }
                }
                Log.w(TAG, "DELETE $url failed: $msg  body=${body?.take(300)}")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteMessage failed for $eventId: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun parseErrorCode(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            json.decodeFromString<MessagesErrorResponse>(body).detail?.error
        } catch (e: Exception) {
            null
        }
    }

    @Serializable
    private data class ErrorDetail(val error: String? = null)

    @Serializable
    private data class MessagesErrorResponse(val detail: ErrorDetail? = null)

    companion object {
        private const val TAG = "MessageClient"
        const val DEFAULT_BASE_URL = "https://desent.xyz/api/messages"
    }
}
