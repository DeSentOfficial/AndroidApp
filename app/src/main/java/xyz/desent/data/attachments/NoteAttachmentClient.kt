package xyz.desent.data.attachments

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.attachments.model.AttachmentErrorResponse

/**
 * OkHttp client for the two **client-side-encrypted** note-attachment
 * endpoints from refs/PRIVATE_STORAGE_PROTOCOL.md §"Attachment wire endpoints".
 *
 *  - [uploadCiphertext] → `POST /api/attachments/upload-ciphertext`
 *  - [downloadCiphertext] → `GET /api/attachments/{sha}/ciphertext`
 *
 * Both require NIP-98 (kind 27235) auth via [NostrHttpAuth]; the upload
 * additionally carries a `payload` tag (SHA-256 of the ciphertext body) which
 * [NostrHttpAuth.buildAuthHeader] derives from the request bytes. The server
 * stores opaque ciphertext only — it never sees the AES key.
 *
 * This is distinct from [AttachmentsClient], which speaks the existing
 * server-side-encrypted routes used by email attachments.
 */
class NoteAttachmentClient(
    private val okHttpClient: OkHttpClient,
    private val auth: NostrHttpAuth,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class UploadResponse(
        val sha256: String,
        @kotlinx.serialization.SerialName("size_stored") val sizeStored: Long
    )

    /**
     * Upload raw ciphertext (nonce ‖ ciphertext ‖ tag). Server computes the
     * sha256 over these bytes and returns it; that hash is what the caller
     * stores in the note's [xyz.desent.domain.model.AttachmentMeta].
     */
    suspend fun uploadCiphertext(ciphertext: ByteArray, mimeType: String): Result<UploadResponse> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/upload-ciphertext"
                val header = auth.buildAuthHeader(url, "POST", ciphertext).getOrThrow()
                val body = ciphertext.toRequestBody("application/octet-stream".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", header)
                    .header("X-Attachment-Mime", mimeType)
                    .post(body)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    val respBody = response.body?.string()
                    if (!response.isSuccessful) {
                        val msg = "upload-ciphertext failed: HTTP ${response.code}${
                            parseError(respBody)?.let { ": $it" } ?: ""
                        }"
                        Log.w(TAG, msg)
                        return@use Result.failure(Exception(msg))
                    }
                    val parsed = json.decodeFromString<UploadResponse>(respBody ?: "{}")
                    Log.d(TAG, "upload-ciphertext -> ${parsed.sha256.take(12)} (${parsed.sizeStored} bytes stored)")
                    Result.success(parsed)
                }
            } catch (e: Exception) {
                Log.e(TAG, "uploadCiphertext failed: ${e.message}", e)
                Result.failure(e)
            }
        }

    /** Download the raw ciphertext bytes for [sha256]. Owner-only on the server side. */
    suspend fun downloadCiphertext(sha256: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/$sha256/ciphertext"
                val header = auth.buildAuthHeader(url, "GET").getOrThrow()
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", header)
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                            ?: return@use Result.failure(Exception("Empty response body"))
                        Log.d(TAG, "download $sha256 -> ${bytes.size} ciphertext bytes")
                        return@use Result.success(bytes)
                    }
                    val respBody = response.body?.string()
                    val msg = "download $sha256 failed: HTTP ${response.code}${
                        parseError(respBody)?.let { ": $it" } ?: ""
                    }"
                    Log.w(TAG, msg)
                    Result.failure(Exception(msg))
                }
            } catch (e: Exception) {
                Log.e(TAG, "downloadCiphertext failed for $sha256: ${e.message}", e)
                Result.failure(e)
            }
        }

    /**
     * Best-effort orphan cleanup: `DELETE /api/attachments/{sha256}` the
     * blob row once no note references it. 404 (already gone) and 405
     * (endpoint disabled server-side) are treated as success — the docs
     * define no dedicated delete for the ciphertext flow, so failures are
     * silent and the blob simply lingers in the quota breakdown.
     */
    suspend fun deleteCiphertextBlob(sha256: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/$sha256"
                val header = auth.buildAuthHeader(url, "DELETE").getOrThrow()
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", header)
                    .delete()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 404 || response.code == 405) {
                        Log.d(TAG, "DELETE $sha256 -> ${response.code} (cleanup)")
                        return@use Result.success(Unit)
                    }
                    Log.w(TAG, "DELETE $sha256 -> HTTP ${response.code} (orphan left in place)")
                    Result.success(Unit) // cleanup is advisory; never surface an error
                }
            } catch (e: Exception) {
                Log.w(TAG, "deleteCiphertextBlob failed for $sha256: ${e.message}")
                Result.success(Unit)
            }
        }

    /**
     * END-23 §4 ordering variant: DELETE the blob and **fail** on real errors
     * (only 404 "already gone" counts as success). Unlike the advisory
     * [deleteCiphertextBlob], callers use this when a subsequent step — the
     * tombstone publish — must not run when the blob delete was rejected
     * (429, 5xx, …), so the entry keeps its key and the delete can be retried.
     */
    suspend fun deleteCiphertextBlobStrict(sha256: String): Result<Unit> =
        withContext(Dispatchers.IO) {
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
                        Log.d(TAG, "DELETE $sha256 -> ${response.code} (strict)")
                        return@use Result.success(Unit)
                    }
                    val respBody = response.body?.string()
                    val msg = "DELETE $sha256 failed: HTTP ${response.code}${
                        parseError(respBody)?.let { ": $it" } ?: ""
                    }"
                    Log.w(TAG, msg)
                    Result.failure(Exception(msg))
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteCiphertextBlobStrict failed for $sha256: ${e.message}", e)
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
        private const val TAG = "NoteAttachmentClient"
        const val DEFAULT_BASE_URL = "https://desent.xyz/api/attachments"
    }
}
