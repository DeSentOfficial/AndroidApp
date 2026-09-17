package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val npub: String,
    val name: String?,
    val displayName: String?,
    val about: String?,
    val picture: String?,
    val banner: String? = null,
    val website: String? = null,
    val lud06: String? = null,
    val lud16: String? = null,
    val nip05: String?,
    val nip05Verified: Boolean = false,
    val createdAt: Long,
    val lastUpdated: Long = System.currentTimeMillis(),
    val relayListJson: String? = null
)