package xyz.desent.data.attachments

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.attachments.model.AttachmentDto
import xyz.desent.data.attachments.model.AttachmentErrorResponse
import xyz.desent.data.attachments.model.AttachmentListResponse
import xyz.desent.data.attachments.model.AttachmentDeleteResponse

/**
 * OkHttp REST client for the DeSent attachments API.
 *
 * Per refs/ATTACHMENTS_API_REFERENCE.md. Base URL defaults to
 * `https://desent.xyz/api/attachments`. Auth is NIP-98 (kind 27235),
 * identical to the alias/messages endpoints — built per-request via
 * [NostrHttpAuth].
 *
 * Note: the list response deliberately omits the encryption key. Download
 * requires the caller to supply `keyHex` (recovered from the gift-wrap rumor
 * tag, i.e. from a stored email's attachmentsJson).
 */
class AttachmentsClient(
    private val okHttpClient: OkHttpClient,
    private val auth: NostrHttpAuth,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** GET / — list the caller's attachments with blurhash + linked/orphaned status. */
    suspend fun listAttachments(limit: Int = DEFAULT_LIMIT): Result<AttachmentListResponse> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl?limit=$limit"
                // NIP-98 u tag = scheme+host+path only — the server rejects
                // `...?limit=500` with 401 url_mismatch.
                val header = auth.buildAuthHeader(url.substringBefore('?'), "GET").getOrThrow()
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", header)
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful) {
                        val msg = "List failed: HTTP ${response.code}${parseError(body)?.let { ": $it" } ?: ""}"
                        Log.w(TAG, "$msg  url=$url")
                        return@use Result.failure(Exception(msg))
                    }
                    val parsed = json.decodeFromString<AttachmentListResponse>(body ?: "{}")
                    Log.d(TAG, "GET / -> ${parsed.attachments.size} attachment(s)")
                    parsed.attachments.forEach { a ->
                        Log.d(TAG, "  ${a.sha256.take(12)} mime=${a.mimeType} " +
                            "blurhash=${a.blurhash != null} len=${a.blurhash?.length ?: 0} inline=${a.isInline}")
                    }
                    Result.success(parsed)
                }
            } catch (e: Exception) {
                Log.e(TAG, "listAttachments failed: ${e.message}", e)
                Result.failure(e)
            }
        }

    /** DELETE /{sha256} — permanently delete a blob. 404 treated as success (already gone). */
    suspend fun deleteAttachment(sha256: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/$sha256"
            val header = auth.buildAuthHeader(url, "DELETE").getOrThrow()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", header)
                .delete()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 404) {
                    Log.d(TAG, "DELETE $url -> ${response.code}")
                    return@use Result.success(Unit)
                }
                val body = response.body?.string()
                val err = parseError(body)
                val retryAfter = response.header("Retry-After")
                val msg = "HTTP ${response.code}${err?.let { ": $it" } ?: ""}${retryAfter?.let { " (retry ${it}s)" } ?: ""}"
                Log.w(TAG, "DELETE $url failed: $msg")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteAttachment failed for $sha256: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * GET /{sha256}?key={keyHex} — download + server-side decrypt.
     *
     * The caller MUST supply the 64-hex-char AES-256-GCM key recovered from the
     * gift-wrap rumor tag; the server does not store it. Returns plaintext bytes.
     */
    suspend fun download(sha256: String, keyHex: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/$sha256?key=$keyHex"
                // u tag must exclude the ?key= query (same url_mismatch rule).
                val header = auth.buildAuthHeader(url.substringBefore('?'), "GET").getOrThrow()
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", header)
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                            ?: return@use Result.failure(Exception("Empty response body"))
                        Log.d(TAG, "GET /$sha256?key=... -> ${bytes.size} bytes")
                        return@use Result.success(bytes)
                    }
                    val body = response.body?.string()
                    val err = parseError(body)
                    val msg = "HTTP ${response.code}${err?.let { ": $it" } ?: ""}"
                    Log.w(TAG, "Download $sha256 failed: $msg")
                    Result.failure(Exception(msg))
                }
            } catch (e: Exception) {
                Log.e(TAG, "download failed for $sha256: ${e.message}", e)
                Result.failure(e)
            }
        }

    private fun parseError(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            json.decodeFromString<AttachmentErrorResponse>(body).detail?.error
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "AttachmentsClient"
        const val DEFAULT_BASE_URL = "https://desent.xyz/api/attachments"
        private const val DEFAULT_LIMIT = 500
    }
}
