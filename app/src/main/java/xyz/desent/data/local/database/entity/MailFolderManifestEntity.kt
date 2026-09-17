package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached mail-folder manifest (NIP-78 kind 30078, `d = "desent:mail-folders"`).
 * One row per account; the raw decrypted JSON is stored verbatim so unknown
 * keys on folder entries survive ingest → cache → republish (round-trip rule,
 * refs/FROM_email.desent.xyz/PRIVATE_STORAGE_PROTOCOL.md §Mail folders).
 * Typed access goes through [xyz.desent.domain.model.MailFoldersCodec].
 */
@Entity(tableName = "mail_folder_manifest")
data class MailFolderManifestEntity(
    @PrimaryKey
    val ownerNpub: String,
    val manifestJson: String,
    val updatedAt: Long,
    val dTag: String,
    val createdAt: Long
)
