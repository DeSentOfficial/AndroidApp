package xyz.desent.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One user-uploaded encrypted file synced via NIP-78 kind 30078
 * (`d = "desent:file:<sha256>"`, refs/FROM_email.desent.xyz/ANDROID_USER_FILES.md
 * §2 / END-23). The bytes are a client-side AES-256-GCM-encrypted blob on the
 * attachments server; the AES key + nonce live only inside this
 * NIP-44-encrypted payload, so the server never sees plaintext or keys —
 * same custody chain as note attachments.
 */
@Serializable
data class UserFile(
    /** Ciphertext sha256 — also the `d` tag suffix (`desent:file:<sha256>`). */
    val sha256: String,
    val filename: String,
    val mimeType: String,
    /** Plaintext byte count (for display). */
    val size: Long,
    val keyHex: String,
    val nonceHex: String,
    /** Client-set unix seconds of the upload (payload `uploaded_at`). */
    val uploadedAt: Long,
    /** Client-computed preview hash for images (payload `blurhash`); null otherwise. */
    val blurhash: String? = null,
    /** Natural image dimensions; 0 for non-images. */
    val width: Int = 0,
    val height: Int = 0,
    /** The owner's npub (multi-account scoping). */
    val ownerNpub: String,
    /** The full `d` tag value (`desent:file:<sha256>`). */
    val dTag: String
)

/**
 * Wire shape inside the 30078 ciphertext for a user file (END-23 v1).
 * Keyed by the `sha256` suffix of the `d` tag — no sha field on the wire.
 */
@Serializable
data class UserFilePayload(
    val v: Int = 1,
    val filename: String,
    @SerialName("mime_type") val mimeType: String,
    val size: Long,
    @SerialName("key_hex") val keyHex: String,
    @SerialName("nonce_hex") val nonceHex: String,
    @SerialName("uploaded_at") val uploadedAt: Long,
    val blurhash: String? = null,
    val width: Int = 0,
    val height: Int = 0
)
