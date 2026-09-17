package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Device-local "favorite" pin on a private note. Deliberately kept out of the
 * NIP-78 payload so the inbound REPLACE at onInboundPrivateStorageEvent cannot
 * clobber it on relay re-sync. Composite-keyed by (ownerNpub, noteId) for
 * multi-account scoping; [noteId] is the `desent:note:<uuid>` uuid portion.
 */
@Entity(
    tableName = "favorite_notes",
    primaryKeys = ["ownerNpub", "noteId"],
    indices = [Index("ownerNpub")]
)
data class FavoriteNoteEntity(
    val ownerNpub: String,
    val noteId: String,
    val addedAt: Long
)
