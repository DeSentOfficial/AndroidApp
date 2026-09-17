package xyz.desent.data.local.database.entity

import androidx.room.Entity

/**
 * Publish bookkeeping for one mail-state shard: [dirty] means local changes
 * await a flush; [published] means the shard has ever been on the wire and
 * must therefore keep being republished (a shard that empties is published
 * as the empty-content tombstone so the relay hard-deletes it).
 */
@Entity(tableName = "mail_shard_state", primaryKeys = ["ownerNpub", "shardIndex"])
data class MailShardStateEntity(
    val ownerNpub: String,
    val shardIndex: Int,
    val dirty: Boolean,
    val published: Boolean
)
