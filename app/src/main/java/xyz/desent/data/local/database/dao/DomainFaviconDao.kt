package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import xyz.desent.data.local.database.entity.DomainFaviconEntity

@Dao
interface DomainFaviconDao {

    @Query("SELECT * FROM domain_favicons WHERE domain = :domain")
    suspend fun getByDomain(domain: String): DomainFaviconEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favicon: DomainFaviconEntity)
}
