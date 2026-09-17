package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.desent.data.local.database.entity.PrivateNoteEntity

@Dao
interface PrivateNoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: PrivateNoteEntity)

    @Query("SELECT * FROM private_notes WHERE ownerNpub = :ownerNpub ORDER BY updatedAt DESC")
    fun observeNotes(ownerNpub: String): Flow<List<PrivateNoteEntity>>

    @Query("SELECT * FROM private_notes WHERE ownerNpub = :ownerNpub AND id = :id")
    fun observeNote(ownerNpub: String, id: String): Flow<PrivateNoteEntity?>

    @Query("SELECT * FROM private_notes WHERE ownerNpub = :ownerNpub AND id = :id")
    suspend fun getNote(ownerNpub: String, id: String): PrivateNoteEntity?

    @Query("DELETE FROM private_notes WHERE ownerNpub = :ownerNpub AND id = :id")
    suspend fun deleteNote(ownerNpub: String, id: String)

    /** Bulk-delete every note owned by [ownerNpub]. Used by account removal. */
    @Query("DELETE FROM private_notes WHERE ownerNpub = :ownerNpub")
    suspend fun deleteAllForOwner(ownerNpub: String)
}
