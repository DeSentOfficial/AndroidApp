package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import xyz.desent.data.local.database.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<UserEntity>)

    @Update
    suspend fun updateUser(user: UserEntity)

    @Query("SELECT * FROM users WHERE npub = :npub")
    suspend fun getUserByNpub(npub: String): UserEntity?

    @Query("SELECT * FROM users WHERE npub = :npub")
    fun observeUserByNpub(npub: String): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE npub IN (:npubs)")
    fun observeUsersByNpubs(npubs: List<String>): Flow<List<UserEntity>>

    @Query("SELECT * FROM users ORDER BY name ASC")
    fun observeAllUsers(): Flow<List<UserEntity>>

    @Query("DELETE FROM users WHERE npub = :npub")
    suspend fun deleteUser(npub: String)

    @Query("SELECT COUNT(*) FROM users")
    suspend fun getUserCount(): Int
    




    @Query("UPDATE users SET nip05Verified = :verified WHERE npub = :npub")
    suspend fun setNip05Verified(npub: String, verified: Boolean)

    /**
     * Refreshes the cache-freshness marker without rewriting profile fields —
     * used after a contact-profile lookup confirms the cached kind-0 is still
     * current, so unchanged profiles aren't re-queried on every app open.
     */
    @Query("UPDATE users SET lastUpdated = :now WHERE npub = :npub")
    suspend fun touchLastUpdated(npub: String, now: Long)

}
