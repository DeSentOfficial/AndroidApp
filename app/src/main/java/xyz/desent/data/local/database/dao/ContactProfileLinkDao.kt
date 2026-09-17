package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.desent.data.local.database.entity.ContactProfileLinkEntity

@Dao
interface ContactProfileLinkDao {

    @Query("SELECT * FROM contact_profile_links WHERE identifier = :identifier")
    suspend fun getByIdentifier(identifier: String): ContactProfileLinkEntity?

    @Query("SELECT * FROM contact_profile_links WHERE identifier = :identifier")
    fun observeByIdentifier(identifier: String): Flow<ContactProfileLinkEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: ContactProfileLinkEntity)
}
