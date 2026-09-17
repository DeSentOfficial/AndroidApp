package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cached user-uploaded encrypted file (NIP-78 kind 30078,
 * `d = "desent:file:<sha256>"`). Keyed by the ciphertext sha256; scoped to
 * the owning account via [ownerNpub]. The AES key/nonce mirror what is inside
 * the self-encrypted 30078 payload — Room is the device-local cache only.
 */
@Entity(
    tableName = "user_files",
    indices = [Index("ownerNpub")]
)
data class UserFileEntity(
    @PrimaryKey
    val sha256: String,
    val ownerNpub: String,
    val filename: String,
    val mimeType: String,
    val size: Long,
    val keyHex: String,
    val nonceHex: String,
    val uploadedAt: Long,
    val blurhash: String?,
    val width: Int,
    val height: Int,
    val dTag: String
)
