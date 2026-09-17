package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cached private note (NIP-78 kind 30078, `d = "desent:note:<id>"`).
 * Keyed by the note uuid; scoped to the owning account via [ownerNpub].
 */
@Entity(
    tableName = "private_notes",
    indices = [Index("ownerNpub")]
)
data class PrivateNoteEntity(
    @PrimaryKey
    val id: String,
    val ownerNpub: String,
    val title: String,
    val body: String,
    val updatedAt: Long,
    val folder: String,
    val attachmentsJson: String,
    val dTag: String,
    val createdAt: Long
)
