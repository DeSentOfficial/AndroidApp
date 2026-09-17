package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached encrypted email address book (NIP-78 kind 30078, `d = "desent:contacts"`).
 * One row per account; the whole contacts list lives in [contactsJson].
 */
@Entity(tableName = "private_contacts")
data class PrivateContactsEntity(
    @PrimaryKey
    val ownerNpub: String,
    val contactsJson: String,
    val updatedAt: Long,
    val dTag: String,
    val createdAt: Long
)
