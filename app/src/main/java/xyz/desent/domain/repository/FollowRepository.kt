package xyz.desent.domain.repository

import xyz.desent.domain.model.Follow
import kotlinx.coroutines.flow.Flow

interface FollowRepository {
    
    suspend fun saveFollow(follow: Follow)
    
    suspend fun saveFollows(follows: List<Follow>)
    
    suspend fun updateFollow(follow: Follow)
    
    fun observeFollowsByFollower(followerNpub: String): Flow<List<Follow>>

    fun observeFollowersByNpub(npub: String): Flow<List<Follow>>

    fun observeFavoriteFollows(followerNpub: String): Flow<List<Follow>>
    
    suspend fun getFollowById(id: String): Follow?
    
    suspend fun toggleFavorite(followerNpub: String, followingNpub: String): Boolean

    suspend fun isFavorited(followerNpub: String, followingNpub: String): Boolean
    
    suspend fun deleteFollow(followerNpub: String, followingNpub: String)
    
    suspend fun deleteAllFollowsByFollower(followerNpub: String)
    
    suspend fun fetchFollowsFromRelays(userNpub: String): Result<List<Follow>>

    suspend fun fetchFollowersFromRelays(userNpub: String): Result<List<Follow>>

    /**
     * Follow [followingNpub] from [followerNpub] (the active user) and publish
     * an updated NIP-02 kind 3 contact list to relays. Optionally attaches a
     * petname (3rd p-tag param). Idempotent: re-following an existing follow
     * updates its petname and re-publishes.
     *
     * Publishes happen on a best-effort basis; the local row is written before
     * the publish so the UI reflects the new state as soon as the call returns.
     */
    suspend fun followUser(
        followerNpub: String,
        followingNpub: String,
        petname: String? = null
    ): Result<Unit>

    /** Unfollow [followingNpub] and republish the contact list without them. */
    suspend fun unfollowUser(followerNpub: String, followingNpub: String): Result<Unit>

    /** Hot stream of npubs the active user follows (outgoing follows only). */
    fun observeFollowingNpubs(followerNpub: String): Flow<Set<String>>
}