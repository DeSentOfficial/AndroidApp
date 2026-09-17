package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.desent.data.local.database.entity.CalendarEntity

@Dao
interface CalendarDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCalendar(calendar: CalendarEntity)

    @Query("SELECT * FROM calendars WHERE ownerNpub = :ownerNpub ORDER BY updatedAt DESC")
    fun observeCalendars(ownerNpub: String): Flow<List<CalendarEntity>>

    @Query("SELECT * FROM calendars WHERE ownerNpub = :ownerNpub AND id = :id")
    fun observeCalendar(ownerNpub: String, id: String): Flow<CalendarEntity?>

    @Query("SELECT * FROM calendars WHERE ownerNpub = :ownerNpub AND id = :id")
    suspend fun getCalendar(ownerNpub: String, id: String): CalendarEntity?

    @Query("DELETE FROM calendars WHERE ownerNpub = :ownerNpub AND id = :id")
    suspend fun deleteCalendar(ownerNpub: String, id: String)

    /** Bulk-delete every calendar owned by [ownerNpub]. Used by account removal. */
    @Query("DELETE FROM calendars WHERE ownerNpub = :ownerNpub")
    suspend fun deleteAllForOwner(ownerNpub: String)
}
