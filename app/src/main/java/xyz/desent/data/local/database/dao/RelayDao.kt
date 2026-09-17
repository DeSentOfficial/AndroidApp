package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import xyz.desent.data.local.database.entity.RelayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RelayDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelay(relay: RelayEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelays(relays: List<RelayEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnoreRelay(relay: RelayEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnoreRelays(relays: List<RelayEntity>)

    @Query("SELECT * FROM relays WHERE isPersistent = 1")
    suspend fun getPersistentRelays(): List<RelayEntity>

    @Query("SELECT * FROM relays WHERE isPersistent = 0")
    suspend fun getTemporaryRelays(): List<RelayEntity>

    @Update
    suspend fun updateRelay(relay: RelayEntity)

    @Query("SELECT * FROM relays WHERE url = :url")
    suspend fun getRelayByUrl(url: String): RelayEntity?

    @Query("SELECT * FROM relays ORDER BY createdAt ASC")
    fun observeAllRelays(): Flow<List<RelayEntity>>

    @Query("SELECT * FROM relays WHERE isActive = 1 ORDER BY createdAt ASC")
    fun observeActiveRelays(): Flow<List<RelayEntity>>

    @Query("UPDATE relays SET connectionStatus = :status, lastConnectedAt = :timestamp WHERE url = :url")
    suspend fun updateConnectionStatus(url: String, status: String, timestamp: Long? = null)

    @Query("UPDATE relays SET failureCount = :failureCount WHERE url = :url")
    suspend fun updateFailureCount(url: String, failureCount: Int)

    @Query("UPDATE relays SET isActive = :isActive WHERE url = :url")
    suspend fun updateActiveStatus(url: String, isActive: Boolean)

    @Query("UPDATE relays SET isWrite = :isWrite WHERE url = :url")
    suspend fun updateWritePermission(url: String, isWrite: Boolean)

    @Query("DELETE FROM relays WHERE url = :url")
    suspend fun deleteRelay(url: String)

    @Query("UPDATE relays SET nip11Name = :name, nip11Version = :version, nip11Icon = :icon, nip11CachedAt = :timestamp WHERE url = :url")
    suspend fun updateBasicNip11Metadata(url: String, name: String?, version: String?, icon: String?, timestamp: Long)

    @Query("""
        UPDATE relays SET 
            nip11Name = :name,
            nip11Description = :description,
            nip11Pubkey = :pubkey,
            nip11Contact = :contact,
            nip11SupportedNips = :supportedNips,
            nip11Version = :version,
            nip11Icon = :icon,
            nip11Software = :software,
            nip11RelayCountries = :countries,
            nip11LanguageTags = :languageTags,
            nip11PostingPolicy = :postingPolicy,
            nip11LimitationsJson = :limitationsJson,
            nip11FeesJson = :feesJson,
            nip11Payments = :payments,
            nip11CachedAt = :cachedAt
        WHERE url = :url
    """)
    suspend fun updateFullNip11Metadata(
        url: String,
        name: String?,
        description: String?,
        pubkey: String?,
        contact: String?,
        supportedNips: String?,
        version: String?,
        icon: String?,
        software: String?,
        countries: String?,
        languageTags: String?,
        postingPolicy: String?,
        limitationsJson: String?,
        feesJson: String?,
        payments: String?,
        cachedAt: Long
    )
}