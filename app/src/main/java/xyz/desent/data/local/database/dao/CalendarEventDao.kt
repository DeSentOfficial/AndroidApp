package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.desent.data.local.database.entity.CalendarEventEntity

@Dao
interface CalendarEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvent(event: CalendarEventEntity)

    @Query("SELECT * FROM calendar_events WHERE ownerNpub = :ownerNpub ORDER BY startSec ASC")
    fun observeEvents(ownerNpub: String): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE ownerNpub = :ownerNpub AND id = :id")
    fun observeEvent(ownerNpub: String, id: String): Flow<CalendarEventEntity?>

    @Query("SELECT * FROM calendar_events WHERE ownerNpub = :ownerNpub AND id = :id")
    suspend fun getEvent(ownerNpub: String, id: String): CalendarEventEntity?

    /**
     * Events whose `[startSec, endSec)` overlaps `[rangeStart, rangeEnd)`. A
     * null `endSec` is treated as an instantaneous event at `startSec`
     * (included iff `startSec` ∈ the range).
     */
    @Query(
        "SELECT * FROM calendar_events WHERE ownerNpub = :ownerNpub " +
            "AND startSec < :rangeEnd AND COALESCE(endSec, startSec) > :rangeStart " +
            "ORDER BY startSec ASC"
    )
    suspend fun eventsInRange(ownerNpub: String, rangeStart: Long, rangeEnd: Long): List<CalendarEventEntity>

    /**
     * Every recurring event of the owner (denormalized `recurFreq`), the input
     * to client-side occurrence expansion — see
     * [xyz.desent.domain.usecase.RecurringEventExpander].
     */
    @Query("SELECT * FROM calendar_events WHERE ownerNpub = :ownerNpub AND recurFreq IS NOT NULL")
    suspend fun recurringEvents(ownerNpub: String): List<CalendarEventEntity>

    @Query("DELETE FROM calendar_events WHERE ownerNpub = :ownerNpub AND id = :id")
    suspend fun deleteEvent(ownerNpub: String, id: String)

    /** Bulk-delete every calendar event owned by [ownerNpub]. Used by account removal. */
    @Query("DELETE FROM calendar_events WHERE ownerNpub = :ownerNpub")
    suspend fun deleteAllForOwner(ownerNpub: String)
}
