package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.desent.data.local.database.entity.FavoriteNoteEntity

@Dao
interface FavoriteNoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteNoteEntity)

    @Query("SELECT * FROM favorite_notes WHERE ownerNpub = :ownerNpub ORDER BY addedAt DESC")
    fun observe(ownerNpub: String): Flow<List<FavoriteNoteEntity>>

    @Query("SELECT noteId FROM favorite_notes WHERE ownerNpub = :ownerNpub")
    fun observeNoteIds(ownerNpub: String): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_notes WHERE ownerNpub = :ownerNpub AND noteId = :noteId)")
    suspend fun isFavorite(ownerNpub: String, noteId: String): Boolean

    @Query("DELETE FROM favorite_notes WHERE ownerNpub = :ownerNpub AND noteId = :noteId")
    suspend fun delete(ownerNpub: String, noteId: String)

    /** Bulk-delete every favorite note owned by [ownerNpub]. Used by account removal. */
    @Query("DELETE FROM favorite_notes WHERE ownerNpub = :ownerNpub")
    suspend fun deleteAllForOwner(ownerNpub: String)
}
