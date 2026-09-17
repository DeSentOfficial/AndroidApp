package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.desent.data.local.database.entity.SecurityAlertEntity

/** Row of [SecurityAlertDao.observeUnseenCountsByOwner]. */
data class OwnerUnseenCount(val npub: String, val unseen: Int)

@Dao
interface SecurityAlertDao {

    /** IGNORE keeps the first insert — event-id dedup per the protocol doc §7. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(alert: SecurityAlertEntity): Long

    @Query("SELECT * FROM security_alerts WHERE eventId = :eventId")
    suspend fun getById(eventId: String): SecurityAlertEntity?

    @Query("SELECT * FROM security_alerts WHERE ownerNpub = :npub ORDER BY receivedAt DESC")
    fun observeForOwner(npub: String): Flow<List<SecurityAlertEntity>>

    @Query("SELECT * FROM security_alerts WHERE ownerNpub = :npub ORDER BY receivedAt DESC")
    suspend fun getForOwner(npub: String): List<SecurityAlertEntity>

    @Query("SELECT COUNT(*) FROM security_alerts WHERE ownerNpub = :npub AND isSeen = 0")
    fun observeUnseenCount(npub: String): Flow<Int>

    /**
     * Unseen count per owner across ALL accounts. Powers the per-account
     * badges in the account switcher sheet's drill-in section.
     */
    @Query("SELECT ownerNpub AS npub, COUNT(*) AS unseen FROM security_alerts WHERE isSeen = 0 GROUP BY ownerNpub")
    fun observeUnseenCountsByOwner(): Flow<List<OwnerUnseenCount>>

    @Query("UPDATE security_alerts SET isSeen = 1 WHERE eventId = :eventId")
    suspend fun markSeen(eventId: String)

    @Query("UPDATE security_alerts SET isSeen = 1 WHERE ownerNpub = :npub AND isSeen = 0")
    suspend fun markAllSeen(npub: String)

    /**
     * Local mirror of the relay's fixed 30-day NIP-40 expiration: rows older
     * than the cutoff are dropped so a purged relay event doesn't linger
     * forever client-side.
     */
    @Query("DELETE FROM security_alerts WHERE receivedAt < :cutoffMillis")
    suspend fun purgeOlderThan(cutoffMillis: Long)

    /** Bulk-delete alerts for an account. Used by account removal. */
    @Query("DELETE FROM security_alerts WHERE ownerNpub = :npub")
    suspend fun deleteAllForOwner(npub: String)
}
