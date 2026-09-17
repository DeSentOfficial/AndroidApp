package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.desent.data.local.database.entity.PrivateContactsEntity

@Dao
interface PrivateContactsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContacts(entity: PrivateContactsEntity)

    @Query("SELECT * FROM private_contacts WHERE ownerNpub = :ownerNpub")
    fun observeContacts(ownerNpub: String): Flow<PrivateContactsEntity?>

    @Query("SELECT * FROM private_contacts WHERE ownerNpub = :ownerNpub")
    suspend fun getContacts(ownerNpub: String): PrivateContactsEntity?

    @Query("DELETE FROM private_contacts WHERE ownerNpub = :ownerNpub")
    suspend fun deleteContacts(ownerNpub: String)
}
