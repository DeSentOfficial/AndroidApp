package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.desent.data.local.database.entity.MailShardStateEntity
import xyz.desent.data.local.database.entity.MailStateEntity

/**
 * Data access for the synced mail-state overlay (`desent:mail-state:<i>`
 * namespaces, refs/FROM_email.desent.xyz/ANDROID_MAIL_FOLDERS.md §2).
 */
@Dao
interface MailStateDao {

    /**
     * Per-entry LWW upsert: an inbound entry applies only when its `ts` is
     * strictly greater than the local one. Returns the affected-row count so
     * callers can detect state changes (anti-entropy re-marks the shard dirty
     * when an inbound merge changed local state).
     */
    @Query(
        """
        INSERT INTO mail_state (ownerNpub, folderKey, folderId, isRead, ts)
        VALUES (:ownerNpub, :folderKey, :folderId, :isRead, :ts)
        ON CONFLICT(ownerNpub, folderKey) DO UPDATE SET
            folderId = excluded.folderId,
            isRead = excluded.isRead,
            ts = excluded.ts
        WHERE excluded.ts > mail_state.ts
        """
    )
    suspend fun upsertLww(
        ownerNpub: String,
        folderKey: String,
        folderId: String?,
        isRead: Boolean,
        ts: Long
    ): Long

    @Query("SELECT * FROM mail_state WHERE ownerNpub = :ownerNpub AND folderKey = :folderKey")
    suspend fun getByKey(ownerNpub: String, folderKey: String): MailStateEntity?

    @Query("SELECT * FROM mail_state WHERE ownerNpub = :ownerNpub")
    fun observeAll(ownerNpub: String): Flow<List<MailStateEntity>>

    @Query("SELECT * FROM mail_state WHERE ownerNpub = :ownerNpub")
    suspend fun getAll(ownerNpub: String): List<MailStateEntity>

    /** Pinned keys of every entry currently filed in [folderId]. */
    @Query("SELECT folderKey FROM mail_state WHERE ownerNpub = :ownerNpub AND folderId = :folderId")
    suspend fun getKeysInFolder(ownerNpub: String, folderId: String): List<String>

    @Query("DELETE FROM mail_state WHERE ownerNpub = :ownerNpub")
    suspend fun deleteAll(ownerNpub: String)

    /**
     * Write the synced read flag through to the local mail cache. The pinned
     * key is derived exactly as at ingest: `message_id` when present, else
     * `"ev:" + row id`.
     */
    @Query(
        """
        UPDATE emails SET isRead = :isRead
        WHERE recipientNpub = :ownerNpub
        AND COALESCE(messageId, 'ev:' || id) = :folderKey
        """
    )
    suspend fun applyReadToEmails(ownerNpub: String, folderKey: String, isRead: Boolean)

    // ==================== SHARD PUBLISH BOOKKEEPING ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShardState(entity: MailShardStateEntity)

    @Query("SELECT * FROM mail_shard_state WHERE ownerNpub = :ownerNpub")
    suspend fun getShardStates(ownerNpub: String): List<MailShardStateEntity>

    /** Clear the dirty flag after a successful shard publish. */
    @Query("UPDATE mail_shard_state SET dirty = 0 WHERE ownerNpub = :ownerNpub AND shardIndex = :shardIndex")
    suspend fun clearDirty(ownerNpub: String, shardIndex: Int)

    /** Drop bookkeeping for a shard that emptied and was tombstoned off the wire. */
    @Query("DELETE FROM mail_shard_state WHERE ownerNpub = :ownerNpub AND shardIndex = :shardIndex")
    suspend fun deleteShardState(ownerNpub: String, shardIndex: Int)

    @Query("DELETE FROM mail_shard_state WHERE ownerNpub = :ownerNpub")
    suspend fun deleteAllShardStates(ownerNpub: String)
}
