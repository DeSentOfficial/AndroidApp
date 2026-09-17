package xyz.desent.crypto

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nostr.event.impl.GenericEvent
import nostr.event.tag.GenericTag

/**
 * Builds signed kind 22242 (NIP-42) auth headers for the DeSent Blossom server
 * (`https://desent.xyz/blobs`). The server accepts a directly-signed event
 * (no challenge dance) — see [data.attachment.AttachmentDownloader] for the
 * confirmed working path.
 *
 * @param action "upload" or "download"
 * @param sha256 hex SHA-256 of the blob (the `x` tag)
 */
object BlossomAuth {

    @Serializable
    private data class SignedAuthEvent(
        val id: String,
        val pubkey: String,
        @kotlinx.serialization.SerialName("created_at") val createdAt: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val content: String,
        val sig: String
    )

    private const val TAG = "BlossomAuth"
    private const val NIP42_KIND = 22242
    private const val AUTH_TTL_SECONDS = 3600L
    private val json = Json { encodeDefaults = true }

    suspend fun buildHeader(
        secureKeyManager: SecureKeyManager,
        action: String,
        sha256: String
    ): Result<String> = withContext(Dispatchers.Default) {
        try {
            val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrThrow()
            val createdAt = System.currentTimeMillis() / 1000
            val expiration = createdAt + AUTH_TTL_SECONDS

            val tags = mutableListOf<GenericTag>()
            tags.add(GenericTag("t", listOf(action)))
            tags.add(GenericTag("x", listOf(sha256)))
            tags.add(GenericTag("expiration", listOf(expiration.toString())))

            val event = GenericEvent.builder()
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
}
