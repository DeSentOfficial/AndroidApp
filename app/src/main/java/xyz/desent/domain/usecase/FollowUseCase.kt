package xyz.desent.domain.usecase

import xyz.desent.domain.model.Follow
import xyz.desent.domain.repository.FollowRepository
import kotlinx.coroutines.flow.Flow

class FollowUseCase(
    private val followRepository: FollowRepository
) {
    
    suspend fun saveFollow(follow: Follow) {
        followRepository.saveFollow(follow)
    }
    
    suspend fun saveFollows(follows: List<Follow>) {
        followRepository.saveFollows(follows)
    }
    
    suspend fun updateFollow(follow: Follow) {
        followRepository.updateFollow(follow)
    }
    
    fun observeFollowsByFollower(followerNpub: String): Flow<List<Follow>> {
        return followRepository.observeFollowsByFollower(followerNpub)
    }

    fun observeFollowersByNpub(npub: String): Flow<List<Follow>> {
        return followRepository.observeFollowersByNpub(npub)
    }

    fun observeFavoriteFollows(followerNpub: String): Flow<List<Follow>> {
        return followRepository.observeFavoriteFollows(followerNpub)
    }
    
    suspend fun getFollowById(id: String): Follow? {
        return followRepository.getFollowById(id)
    }
    
    suspend fun toggleFavorite(followerNpub: String, followingNpub: String): Boolean {
        return followRepository.toggleFavorite(followerNpub, followingNpub)
    }

    suspend fun isFavorited(followerNpub: String, followingNpub: String): Boolean {
        return followRepository.isFavorited(followerNpub, followingNpub)
    }
    
    suspend fun deleteFollow(followerNpub: String, followingNpub: String) {
        followRepository.deleteFollow(followerNpub, followingNpub)
    }
    
    suspend fun deleteAllFollowsByFollower(followerNpub: String) {
        followRepository.deleteAllFollowsByFollower(followerNpub)
    }
    
    suspend fun fetchFollowsFromRelays(userNpub: String): Result<List<Follow>> {
        return followRepository.fetchFollowsFromRelays(userNpub)
    }

    suspend fun fetchFollowersFromRelays(userNpub: String): Result<List<Follow>> {
        return followRepository.fetchFollowersFromRelays(userNpub)
    }

    suspend fun followUser(
        followerNpub: String,
        followingNpub: String,
        petname: String? = null
    ): Result<Unit> {
        return followRepository.followUser(followerNpub, followingNpub, petname)
    }

    suspend fun unfollowUser(followerNpub: String, followingNpub: String): Result<Unit> {
        return followRepository.unfollowUser(followerNpub, followingNpub)
    }

    fun observeFollowingNpubs(followerNpub: String): Flow<Set<String>> {
        return followRepository.observeFollowingNpubs(followerNpub)
    }
}