package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.desent.data.local.database.entity.CalendarRsvpEntity

@Dao
interface CalendarRsvpDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRsvp(rsvp: CalendarRsvpEntity)

    @Query("SELECT * FROM calendar_rsvps WHERE ownerNpub = :ownerNpub AND eventD = :eventD ORDER BY updatedAt DESC")
    fun observeRsvpsForEvent(ownerNpub: String, eventD: String): Flow<List<CalendarRsvpEntity>>

    @Query("SELECT * FROM calendar_rsvps WHERE ownerNpub = :ownerNpub AND eventD = :eventD")
    suspend fun getRsvpsForEvent(ownerNpub: String, eventD: String): List<CalendarRsvpEntity>

    @Query("DELETE FROM calendar_rsvps WHERE ownerNpub = :ownerNpub AND eventD = :eventD")
    suspend fun deleteRsvpsForEvent(ownerNpub: String, eventD: String)

    /** Bulk-delete every RSVP owned by [ownerNpub]. Used by account removal. */
    @Query("DELETE FROM calendar_rsvps WHERE ownerNpub = :ownerNpub")
    suspend fun deleteAllForOwner(ownerNpub: String)
}
