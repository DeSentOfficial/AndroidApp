package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import xyz.desent.data.local.database.entity.FollowEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FollowDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFollow(follow: FollowEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFollows(follows: List<FollowEntity>)

    @Update
    suspend fun updateFollow(follow: FollowEntity)

    @Query("SELECT * FROM follows WHERE followerNpub = :followerNpub")
    fun observeFollowsByFollower(followerNpub: String): Flow<List<FollowEntity>>

    @Query("SELECT * FROM follows WHERE followerNpub = :followerNpub")
    suspend fun getFollowsByFollower(followerNpub: String): List<FollowEntity>

    @Query("SELECT MAX(createdAt) FROM follows WHERE followerNpub = :followerNpub")
    suspend fun getLatestContactListCreatedAt(followerNpub: String): Long?

    @Query("SELECT * FROM follows WHERE followingNpub = :followingNpub")
    fun observeFollowersByNpub(followingNpub: String): Flow<List<FollowEntity>>

    @Query("SELECT * FROM follows WHERE followerNpub = :followerNpub AND isFavorite = 1 ORDER BY createdAt DESC")
    fun observeFavoriteFollows(followerNpub: String): Flow<List<FollowEntity>>

    /** One-shot favorite npub set for the wear feed build (★ section). */
    @Query("SELECT followingNpub FROM follows WHERE followerNpub = :followerNpub AND isFavorite = 1")
    suspend fun getFavoriteNpubs(followerNpub: String): List<String>

    @Query("SELECT * FROM follows WHERE id = :id")
    suspend fun getFollowById(id: String): FollowEntity?

    @Query("UPDATE follows SET isFavorite = :isFavorite WHERE followingNpub = :followingNpub AND followerNpub = :followerNpub")
    suspend fun updateFavoriteStatus(followingNpub: String, followerNpub: String, isFavorite: Boolean)

    @Query("DELETE FROM follows WHERE followerNpub = :followerNpub AND followingNpub = :followingNpub")
    suspend fun deleteFollow(followerNpub: String, followingNpub: String)

    @Query("DELETE FROM follows WHERE followerNpub = :followerNpub")
    suspend fun deleteAllFollowsByFollower(followerNpub: String)

    /** Delete every follow where [followingNpub] is the followed party (the account's *Followers* list). */
    @Query("DELETE FROM follows WHERE followingNpub = :followingNpub")
    suspend fun deleteAllFollowsByFollowing(followingNpub: String)

    /**
     * Atomically replace all follows for a follower: delete old rows then insert new ones.
     * The @Transaction annotation ensures the Flow never emits an empty intermediate state.
     */
    @Transaction
    suspend fun replaceFollows(followerNpub: String, follows: List<FollowEntity>, extra: List<FollowEntity> = emptyList()) {
        deleteAllFollowsByFollower(followerNpub)
        insertFollows(follows)
        if (extra.isNotEmpty()) insertFollows(extra)
    }
}