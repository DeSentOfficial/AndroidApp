package xyz.desent.data.attachment

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.desent.crypto.BlossomAuth
import xyz.desent.crypto.SecureKeyManager
import java.security.MessageDigest

/**
 * Uploads blobs to the DeSent Blossom server (`POST https://desent.xyz/blobs/upload`).
 *
 * Auth is a signed kind 22242 (NIP-42) event with `t=upload`/`x=<sha256>`/`expiration`,
 * sent directly in the `Authorization: Nostr <base64>` header. The server encrypts
 * the blob at rest (AES-256-GCM) and returns the `encryption_key`, which the caller
 * MUST retain (forward secrecy — the key is never persisted server-side).
 *
 * See refs/BLOSSOM_API_REFERENCE.md §"Upload Blob".
 */
class DesentBlobUploader(
    private val okHttpClient: OkHttpClient,
    private val secureKeyManager: SecureKeyManager,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class UploadResponse(
        val sha256: String? = null,
        @kotlinx.serialization.SerialName("encryption_key") val encryptionKey: String? = null
    )

    data class UploadedBlob(val url: String, val sha256: String, val encryptionKey: String)

    suspend fun upload(
        data: ByteArray,
        mimeType: String,
        fileName: String = "upload"
    ): Result<UploadedBlob> = withContext(Dispatchers.IO) {
        try {
            val sha256 = sha256Hex(data)
            val header = BlossomAuth.buildHeader(secureKeyManager, "upload", sha256).getOrThrow()

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, data.toRequestBody(mimeType.toMediaType()))
                .build()

            val request = Request.Builder()
                .url("$baseUrl/upload")
                .header("Authorization", header)
                .post(multipart)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Upload failed: HTTP ${response.code} - ${body?.take(300)}")
                    return@use Result.failure(Exception("Upload failed: HTTP ${response.code}${body?.take(120)?.let { ": $it" } ?: ""}"))
                }
                val parsed = json.decodeFromString<UploadResponse>(body ?: "{}")
                val sha = parsed.sha256 ?: sha256
                val key = parsed.encryptionKey
                    ?: return@use Result.failure(Exception("Upload response missing encryption_key"))
                // The download URL carries the encryption key (server decrypts at read time).
                val url = "$baseUrl/$sha?encryption_key=$key"
                Log.d(TAG, "Upload OK: sha=${sha.take(12)}, ${data.size} bytes")
                Result.success(UploadedBlob(url = url, sha256 = sha, encryptionKey = key))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "DesentBlobUploader"
        const val DEFAULT_BASE_URL = "https://desent.xyz/blobs"
    }
}
