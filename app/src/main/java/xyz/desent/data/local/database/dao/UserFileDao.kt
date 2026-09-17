package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.desent.data.local.database.entity.UserFileEntity

@Dao
interface UserFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFile(file: UserFileEntity)

    @Query("SELECT * FROM user_files WHERE ownerNpub = :ownerNpub ORDER BY uploadedAt DESC")
    fun observeFiles(ownerNpub: String): Flow<List<UserFileEntity>>

    @Query("SELECT * FROM user_files WHERE ownerNpub = :ownerNpub AND sha256 = :sha256")
    suspend fun getFile(ownerNpub: String, sha256: String): UserFileEntity?

    @Query("DELETE FROM user_files WHERE ownerNpub = :ownerNpub AND sha256 = :sha256")
    suspend fun deleteFile(ownerNpub: String, sha256: String)

    /** Bulk-delete every user file owned by [ownerNpub]. Used by account removal. */
    @Query("DELETE FROM user_files WHERE ownerNpub = :ownerNpub")
    suspend fun deleteAllForOwner(ownerNpub: String)
}
