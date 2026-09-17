package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "follows")
data class FollowEntity(
    @PrimaryKey
    val id: String, // Composite key of followerNpub + followingNpub
    val followerNpub: String,
    val followingNpub: String,
    val isFavorite: Boolean = false,
    val createdAt: Long,
    val petname: String? = null,
    /**
     * Device-local favorite-only row: created by favoriting a user this
     * account doesn't actually follow. Never published to the Nostr kind-3
     * contact list, excluded from "my follows" queries, and deleted when
     * the favorite is removed. Flipped to false once a real follow exists
     * (local followUser or inbound kind-3 sync).
     */
    val isLocalOnly: Boolean = false
)