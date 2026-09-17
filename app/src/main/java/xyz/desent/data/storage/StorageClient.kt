package xyz.desent.data.storage

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.storage.model.StorageBreakdownDto
import xyz.desent.data.storage.model.StorageError
import xyz.desent.data.storage.model.StorageErrorResponse

/**
 * OkHttp REST client for the DeSent storage-breakdown API.
 *
 * Per refs/STORAGE_TAB_ANDROID.md. Base URL defaults to
 * `https://desent.xyz/api/storage`. Auth is NIP-98 (kind 27235), built
 * per-request via [NostrHttpAuth]. This is a GET with no body, so the auth
 * event carries only `u` + `method` tags (no `payload`) — [buildAuthHeader]
 * omits the `payload` tag when `bodyBytes` is null.
 */
class StorageClient(
    private val okHttpClient: OkHttpClient,
    private val auth: NostrHttpAuth,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** GET /api/storage — per-category byte breakdown for the caller. */
    suspend fun getStorageBreakdown(): Result<StorageBreakdownDto> = withContext(Dispatchers.IO) {
        runRequest<StorageBreakdownDto>(
            url = baseUrl,
            method = "GET",
            authRequired = true
        )
    }

    private suspend inline fun <reified T> runRequest(
        url: String,
        method: String,
        authRequired: Boolean,
        bodyBytes: ByteArray? = null
    ): Result<T> {
        return try {
            val requestBuilder = Request.Builder().url(url)

            if (authRequired) {
                val header = auth.buildAuthHeader(url, method, bodyBytes).getOrThrow()
                requestBuilder.header("Authorization", header)
            }

            when (method) {
                "GET" -> requestBuilder.get()
                "DELETE" -> requestBuilder.delete()
            }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                val error = parseError(response.code, responseBody, response.header("Retry-After"))
                Log.w(TAG, "$method $url -> HTTP ${response.code}: ${error.message}")
                return Result.failure(error)
            }

            if (responseBody.isNullOrBlank()) {
                return Result.failure(StorageError.Unknown("Empty response body"))
            }

            val parsed = json.decodeFromString<T>(responseBody)
            Log.d(TAG, "$method $url -> HTTP ${response.code} OK")
            Result.success(parsed)
        } catch (e: StorageError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "$method $url failed: ${e.message}", e)
            Result.failure(StorageError.Unknown(e.message ?: "Network error"))
        }
    }

    private fun parseError(code: Int, body: String?, retryAfter: String?): StorageError {
        val parsed = try {
            Json.decodeFromString<StorageErrorResponse>(body ?: "{}").detail
        } catch (e: Exception) {
            null
        }
        val codeStr = parsed?.error

        return when (code) {
            401 -> StorageError.Unauthorized
            403 -> StorageError.Forbidden
            429 -> StorageError.RateLimited(retryAfterSeconds = retryAfter?.toIntOrNull())
            else -> StorageError.Server(
                codeStr ?: "Server error",
                code
            )
        }
    }

    companion object {
        private const val TAG = "StorageClient"
        const val DEFAULT_BASE_URL = "https://desent.xyz/api/storage"
    }
}
