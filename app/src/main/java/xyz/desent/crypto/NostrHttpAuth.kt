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
import java.security.MessageDigest

/**
 * Builds NIP-98 (kind 27235) HTTP authorization headers.
 *
 * Produces the value for an `Authorization: Nostr <base64>` header by signing a
 * kind 27235 event with the user's stored nsec and base64-encoding the JSON.
 *
 * Reusable: the BlossomClient kind-24242 flow and any future HTTP-auth endpoint
 * can share this. Correctness notes (see refs/ALIAS_API_REFERENCE.md):
 *  - `u` tag MUST be the request URL's scheme + host + path ONLY — the query
 *    string and fragment are stripped (refs/FROM_email.desent.xyz/
 *    ANDROID_REFERRALS.md §3: "the server matches scheme+host+path only";
 *    signing `...?limit=500` yields 401 `url_mismatch`).
 *  - `method` tag MUST be uppercase.
 *  - `payload` tag is lowercase-hex SHA-256 of the exact request body bytes
 *    (required for POST, optional elsewhere).
 *  - `created_at` is Unix seconds and must be within ±60s of server time.
 *  - base64 must use NO_WRAP.
 */
class NostrHttpAuth(private val secureKeyManager: SecureKeyManager) {

    @Serializable
    private data class SignedEvent(
        val id: String,
        val pubkey: String,
        @kotlinx.serialization.SerialName("created_at") val createdAt: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val content: String,
        val sig: String
    )

    /**
     * @param identity explicit signer for per-account requests (e.g. the
     * address refresh for a non-active account). Null — the default — signs
     * with the active account's legacy single-slot key.
     */
    suspend fun buildAuthHeader(
        url: String,
        method: String,
        bodyBytes: ByteArray? = null,
        identity: nostr.id.Identity? = null
    ): Result<String> = withContext(Dispatchers.Default) {
        try {
            val signer = identity ?: secureKeyManager.getIdentityFromStoredNSEC().getOrThrow()
            val createdAt = System.currentTimeMillis() / 1000

            val uTag = url.substringBefore('#').substringBefore('?')

            val tags = mutableListOf<GenericTag>()
            tags.add(GenericTag("u", listOf(uTag)))
            tags.add(GenericTag("method", listOf(method.uppercase())))
            if (bodyBytes != null) {
                tags.add(GenericTag("payload", listOf(sha256Hex(bodyBytes))))
            }

            val event = GenericEvent.builder()
                .pubKey(signer.publicKey)
                .kind(KIND)
                .createdAt(createdAt)
                .content("")
                .tags(tags as List<nostr.event.BaseTag>)
                .build()

            signer.sign(event)

            val signed = SignedEvent(
                id = event.id,
                pubkey = signer.publicKey.toHexString(),
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

            Log.d(TAG, "Built NIP-98 header for ${method.uppercase()} $url")
            Result.success(header)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build NIP-98 auth header: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "NostrHttpAuth"
        private const val KIND = 27235
        private val json = Json { encodeDefaults = true }
    }
}
