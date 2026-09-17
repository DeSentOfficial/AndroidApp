package xyz.desent.data.attachment

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.domain.model.EmailAttachment
import java.io.File

/**
 * Downloads and saves email attachments from the DeSent blossom server.
 *
 * Per refs/BLOSSOM_API_REFERENCE.md §Encryption, the client passes the
 * per-attachment key as a query param and the server performs the AES-256-GCM
 * decryption at read time, returning plaintext bytes. The key is never
 * persisted server-side; it is carried in the gift-wrap rumor tag.
 *
 * Auth: a signed **kind 22242** (NIP-42) auth event is sent directly in the
 * `Authorization: Nostr <base64>` header on the first request. Tags: `t`
 * (action), `x` (blob sha256), `expiration`; content empty. The server rejects
 * kind 24242 with `401 wrong_event_kind` and crashes (HTTP 500) on NIP-98
 * kind 27235, so 22242 is the confirmed kind. The Blossom doc's NIP-42
 * challenge-response dance isn't implemented by this endpoint (no challenge is
 * emitted), so a direct signed event is the working path.
 *
 * Files are written to `filesDir/attachments/{filename}` and the absolute path
 * returned so the caller can open/share via FileProvider.
 */
class AttachmentDownloader(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val secureKeyManager: SecureKeyManager,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val json = Json { encodeDefaults = true }

    suspend fun download(attachment: EmailAttachment): Result<File> = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, ATTACHMENT_DIR).apply { mkdirs() }
            val safeName = sanitizeFilename(attachment.filename, attachment.sha256, attachment.mimeType)
            val outFile = File(dir, safeName)

            // Complete files only ever appear via the atomic .part rename
            // below, so existence means a previous download fully landed —
            // reuse it instead of re-fetching the blob on every open.
            if (outFile.isFile && outFile.length() > 0L) {
                Log.d(TAG, "Attachment ${attachment.sha256.take(12)} already cached: ${outFile.absolutePath}")
                return@withContext Result.success(outFile)
            }

            val url = "$baseUrl/${attachment.sha256}?encryption_key=${attachment.keyHex}"
            val bytes = executeAuthenticated(url, attachment.sha256).getOrThrow()

            writeAtomically(outFile, bytes)

            Log.d(TAG, "Saved attachment ${attachment.sha256.take(12)} -> ${outFile.absolutePath} (${bytes.size} bytes)")
            Result.success(outFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download attachment ${attachment.sha256}: ${e.message}", e)
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

    /**
     * Sends a signed kind 22242 (NIP-42) auth event directly in the
     * Authorization header on the first (and only) request. No challenge dance.
     */
    private suspend fun executeAuthenticated(url: String, sha256: String): Result<ByteArray> {
        val header = buildBlossomAuthHeader(sha256).getOrThrow()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", header)
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return readBody(url, response)
            }
            val errBody = response.peekErrorBody()
            return failWithBody(url, response.code, errBody, null)
        }
    }

    private fun readBody(url: String, response: okhttp3.Response): Result<ByteArray> {
        val bytes = response.body?.bytes()
            ?: return Result.failure(Exception("Empty response body for $url"))
        return Result.success(bytes)
    }

    private fun failWithBody(
        url: String,
        code: Int,
        errBody: String?,
        context: String?
    ): Result<ByteArray> {
        val ctx = context?.let { " ($it)" } ?: ""
        val msg = if (errBody.isNullOrBlank()) {
            "Attachment download failed$ctx: HTTP $code"
        } else {
            "Attachment download failed$ctx: HTTP $code — ${errBody.take(500)}"
        }
        Log.w(TAG, "$msg  [url=$url]")
        return Result.failure(Exception("HTTP $code${errBody?.take(200)?.let { ": $it" } ?: ""}"))
    }

    private fun okhttp3.Response.peekErrorBody(maxBytes: Long = 2048): String? {
        return try {
            // peekBody lets us read the error without consuming the response stream.
            peekBody(maxBytes.coerceAtLeast(1)).string()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Builds and signs a kind 22242 (NIP-42) auth event:
     * tags `t` (action), `x` (blob sha256), `expiration`; content empty.
     * The kind was confirmed by the server rejecting 24242 with
     * `wrong_event_kind`. The event is fully signed (id/pubkey/sig populated).
     */
    private suspend fun buildBlossomAuthHeader(sha256: String): Result<String> =
        withContext(Dispatchers.Default) {
            try {
                val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrThrow()
                val createdAt = System.currentTimeMillis() / 1000
                val expiration = createdAt + AUTH_TTL_SECONDS

                val tags = mutableListOf<nostr.event.tag.GenericTag>()
                tags.add(nostr.event.tag.GenericTag("t", listOf("download")))
                tags.add(nostr.event.tag.GenericTag("x", listOf(sha256)))
                tags.add(nostr.event.tag.GenericTag("expiration", listOf(expiration.toString())))

                val event = nostr.event.impl.GenericEvent.builder()
                    .pubKey(identity.publicKey)
                    .kind(NIP42_KIND)
                    .createdAt(createdAt)
                    .content("")
                    .tags(tags as List<nostr.event.BaseTag>)
                    .build()

                identity.sign(event)

                val signed = SignedAuthEvent(
                    id = event.id,
                    pubkey = identity.publicKey.toHexString(),
                    createdAt = event.createdAt,
                    kind = event.kind,
                    tags = tags.map { listOf(it.getCode()) + it.getParams() },
                    content = event.content ?: "",
                    sig = event.signature?.toString() ?: ""
                )

                val jsonStr = json.encodeToString(signed)
                val header = "Nostr " + Base64.encodeToString(
                    jsonStr.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                Result.success(header)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build Blossom auth header: ${e.message}", e)
                Result.failure(e)
            }
        }

    private fun sanitizeFilename(name: String, sha256: String, mimeType: String): String {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty() && trimmed.length <= MAX_FILENAME && trimmed.none { it in INVALID_CHARS }) {
            return trimmed
        }
        // Fall back to sha-derived name + an extension from the mime type.
        val ext = mimeType.substringAfter('/', "").takeIf { it.isNotEmpty() }
            ?.let { ".$it" }
        return "${sha256.take(16)}${ext ?: ""}"
    }

    @Serializable
    private data class SignedAuthEvent(
        val id: String,
        val pubkey: String,
        val createdAt: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val content: String,
        val sig: String
    )

    companion object {
        private const val TAG = "AttachmentDownloader"
        private const val ATTACHMENT_DIR = "attachments"
        private const val NIP42_KIND = 22242
        private const val AUTH_TTL_SECONDS = 3600L
        private const val MAX_FILENAME = 180
        private const val DEFAULT_BASE_URL = "https://desent.xyz/blobs"
        private const val INVALID_CHARS = "/\\:*?\"<>|\u0000\n\r\t"
    }
}
