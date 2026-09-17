package xyz.desent.data.repository

import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import xyz.desent.crypto.Bech32Utils
import xyz.desent.data.local.database.dao.FollowDao
import xyz.desent.data.mapper.FollowMapper
import xyz.desent.data.nostr.NostrEventProcessor
import xyz.desent.data.nostr.NostrKinds
import xyz.desent.domain.model.Follow
import xyz.desent.domain.repository.ContactListEntry
import xyz.desent.domain.repository.RelayRepository
import xyz.desent.domain.repository.FollowRepository
import xyz.desent.domain.repository.NostrPublisher

class FollowRepositoryImpl(
    private val followDao: FollowDao,
    private val followMapper: FollowMapper,
    private val relayRepository: RelayRepository,
    private val nostrEventProcessor: NostrEventProcessor,
    private val nostrPublisher: NostrPublisher
) : FollowRepository {
    
    override suspend fun saveFollow(follow: Follow) {
        followDao.insertFollow(followMapper.mapToEntity(follow))
    }
    
    override suspend fun saveFollows(follows: List<Follow>) {
        followDao.insertFollows(followMapper.mapToEntityList(follows))
    }
    
    override suspend fun updateFollow(follow: Follow) {
        followDao.updateFollow(followMapper.mapToEntity(follow))
    }
    
    override fun observeFollowsByFollower(followerNpub: String): Flow<List<Follow>> {
        return followDao.observeFollowsByFollower(followerNpub).map { entities ->
            followMapper.mapToDomainList(entities)
        }
    }

    override fun observeFollowersByNpub(npub: String): Flow<List<Follow>> {
        return followDao.observeFollowersByNpub(npub).map { entities ->
            followMapper.mapToDomainList(entities)
        }
    }

    override fun observeFavoriteFollows(followerNpub: String): Flow<List<Follow>> {
        return followDao.observeFavoriteFollows(followerNpub).map { entities ->
            followMapper.mapToDomainList(entities)
        }
    }
    
    override suspend fun getFollowById(id: String): Follow? {
        return followDao.getFollowById(id)?.let { followMapper.mapToDomain(it) }
    }
    
    override suspend fun toggleFavorite(followerNpub: String, followingNpub: String): Boolean {
        // Favorites live on the primary outgoing row (me -> contact): the
        // Favorites tab, wear feed and widgets all read rows where
        // followerNpub = me.
        val followRecordId = "$followerNpub-$followingNpub"
        val reverseRecordId = "$followingNpub-$followerNpub"

        val follow = followDao.getFollowById(followRecordId)
        val reverseFollow = followDao.getFollowById(reverseRecordId)

        val newFavoriteStatus = follow?.isFavorite != true

        if (follow != null) {
            if (newFavoriteStatus) {
                followDao.updateFavoriteStatus(follow.followerNpub, follow.followingNpub, true)
            } else if (follow.isLocalOnly) {
                // Favorite-only row losing its star: nothing real remains, so
                // delete it instead of leaving a phantom "following" row.
                followDao.deleteFollow(follow.followerNpub, follow.followingNpub)
            } else {
                followDao.updateFavoriteStatus(follow.followerNpub, follow.followingNpub, false)
            }
        } else {
            // Favoriting a user this account doesn't follow: keep the row
            // device-local (isLocalOnly) so it is never published to the
            // kind-3 contact list and never counted as a real follow. No
            // synthetic reverse (contact -> me) row is created — that would
            // fabricate a follower and fire a false "started following you"
            // notification (see SystemNotificationDispatcher).
            val newFollow = xyz.desent.domain.model.Follow(
                followerNpub = followerNpub,
                followingNpub = followingNpub,
                isFavorite = true,
                isLocalOnly = true,
                createdAt = System.currentTimeMillis()
            )
            followDao.insertFollow(followMapper.mapToEntity(newFollow))
        }

        // A real reverse row (contact actually follows me) keeps its star flag
        // in sync so the favorite state matches in both directions. This is
        // only ever an UPDATE on an existing row — never an insert.
        if (reverseFollow != null && reverseFollow.id != follow?.id) {
            followDao.updateFavoriteStatus(reverseFollow.followerNpub, reverseFollow.followingNpub, newFavoriteStatus)
        }

        return newFavoriteStatus
    }

    override suspend fun isFavorited(followerNpub: String, followingNpub: String): Boolean {
        val followRecordId = "$followerNpub-$followingNpub"
        val reverseRecordId = "$followingNpub-$followerNpub"

        // Check if there's a favorited follow in either direction
        val follow = followDao.getFollowById(followRecordId)
        if (follow?.isFavorite == true) return true

        val reverseFollow = followDao.getFollowById(reverseRecordId)
        if (reverseFollow?.isFavorite == true) return true

        return false
    }
    
    override suspend fun deleteFollow(followerNpub: String, followingNpub: String) {
        followDao.deleteFollow(followerNpub, followingNpub)
    }
    
    override suspend fun deleteAllFollowsByFollower(followerNpub: String) {
        followDao.deleteAllFollowsByFollower(followerNpub)
    }
    
    override suspend fun fetchFollowsFromRelays(userNpub: String): Result<List<Follow>> {
        return try {
            val pubkeyHex = Bech32Utils.npubToHex(userNpub)

            android.util.Log.d("FollowRepository", " Fetching follows from relays for user=$userNpub (hex=${pubkeyHex.take(8)})")

            // Subscribe to contact list events (kind 3) from relays
            val fetchSubId = "follows_fetch_$pubkeyHex"
            relayRepository.subscribeToEvents(
                listOf(mapOf(
                    "authors" to listOf(pubkeyHex),
                    "kinds" to listOf(NostrKinds.CONTACT_LIST),
                    "limit" to 1
                )),
                fetchSubId,
                persistent = false
            )
            android.util.Log.d("FollowRepository", " Subscription sent for user's contacts, waiting for follow arrival signal...")

            // Wait for follow arrival signal (with 12-second timeout)
            try {
                withTimeout(12000L) {
                    val arrivedNpub = nostrEventProcessor.followArrivals.first { arrivingNpub ->
                        arrivingNpub == userNpub
                    }
                    android.util.Log.d("FollowRepository", " Follow arrival signal received for $arrivedNpub")
                }
            } catch (e: TimeoutCancellationException) {
                android.util.Log.w("FollowRepository", " Timeout waiting for follow arrival signal")
                android.util.Log.w("FollowRepository", " This might mean: no contact list on relays, or relays didn't respond")
                // Check database anyway in case events arrived but signal didn't fire
                val follows = followDao.getFollowsByFollower(userNpub).map { followMapper.mapToDomain(it) }
                if (follows.isNotEmpty()) {
                    android.util.Log.d("FollowRepository", " Found ${follows.size} follows in database despite timeout")
                    return Result.success(follows)
                }
                throw e
            } finally {
                // One-shot fetch: CLOSE so the REQ is never replayed on reconnect.
                runCatching { relayRepository.unsubscribeFromEvents(fetchSubId) }
            }

            // Fetch follows from local database (they were just saved by NostrEventProcessor)
            val follows = followDao.getFollowsByFollower(userNpub)
                .map { followMapper.mapToDomain(it) }

            if (follows.isNotEmpty()) {
                android.util.Log.d("FollowRepository", " Successfully fetched ${follows.size} follows for $userNpub")
                Result.success(follows)
            } else {
                android.util.Log.w("FollowRepository", " User has no follows in contact list (or contact list is empty)")
                Result.success(emptyList())
            }
        } catch (e: TimeoutCancellationException) {
            android.util.Log.e("FollowRepository", " Timeout fetching follows from relays: $userNpub")
            Result.failure(Exception("Fetch timeout - relays may not have your contact list"))
        } catch (e: Exception) {
            android.util.Log.e("FollowRepository", " Error fetching follows from relays: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun fetchFollowersFromRelays(userNpub: String): Result<List<Follow>> {
        return try {
            val pubkeyHex = Bech32Utils.npubToHex(userNpub)

            android.util.Log.d("FollowRepository", " Fetching followers for user=$userNpub (hex=${pubkeyHex.take(8)})")

            // Get current follower count before fetching
            val followersBefore = followDao.observeFollowersByNpub(userNpub)
                .firstOrNull()
                ?.size ?: 0
            android.util.Log.d("FollowRepository", "   Followers before fetch: $followersBefore")

            // Strategy 1: Try tag-based filtering (works on some relays)
            relayRepository.subscribeToEvents(
                listOf(mapOf(
                    "kinds" to listOf(NostrKinds.CONTACT_LIST),
                    "#p" to listOf(pubkeyHex),
                    "limit" to 500
                )),
                "followers_tag",
                persistent = false
            )
            android.util.Log.d("FollowRepository", " Strategy 1: Subscribed with #p filter")

            try {
                // Wait and check
                delay(5000L)
                val followers = followDao.observeFollowersByNpub(userNpub)
                    .firstOrNull()
                    ?.map { followMapper.mapToDomain(it) }
                    ?: emptyList()

                android.util.Log.d("FollowRepository", " Final follower count: ${followers.size} for $userNpub")

                Result.success(followers)
            } finally {
                // One-shot fetch: CLOSE so the REQ is never replayed on reconnect.
                runCatching { relayRepository.unsubscribeFromEvents("followers_tag") }
            }
        } catch (e: Exception) {
            android.util.Log.e("FollowRepository", " Error fetching followers: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun followUser(
        followerNpub: String,
        followingNpub: String,
        petname: String?
    ): Result<Unit> {
        return try {
            val now = System.currentTimeMillis()
            // upsert (REPLACE strategy on the DAO handles existing rows); keep
            // any existing favorite flag rather than clobbering it. A real
            // follow upgrades any prior favorite-only row: the fresh row has
            // isLocalOnly = false, so it now publishes with the contact list.
            val existing = followDao.getFollowById("$followerNpub-$followingNpub")
            val follow = Follow(
                followerNpub = followerNpub,
                followingNpub = followingNpub,
                isFavorite = existing?.isFavorite ?: false,
                isLocalOnly = false,
                createdAt = now,
                petname = petname
            )
            followDao.insertFollow(followMapper.mapToEntity(follow))

            publishCurrentContactList(followerNpub)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("FollowRepository", " followUser failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun unfollowUser(followerNpub: String, followingNpub: String): Result<Unit> {
        return try {
            followDao.deleteFollow(followerNpub, followingNpub)
            publishCurrentContactList(followerNpub)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("FollowRepository", " unfollowUser failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun observeFollowingNpubs(followerNpub: String): Flow<Set<String>> {
        return followDao.observeFollowsByFollower(followerNpub).map { entities ->
            // Defensive filter: only real outgoing follows for this follower.
            // Excluded: the reverse-direction rows other users' kind-3 sync
            // stores (contact -> me) and device-local favorite-only rows
            // (see toggleFavorite) — neither is "my follows".
            entities
                .filter { it.followerNpub == followerNpub && !it.isLocalOnly }
                .map { it.followingNpub }
                .toSet()
        }
    }

    /**
     * Re-publish the active user's NIP-02 contact list from the local DB.
     *
     * Filters to rows where [followerNpub] is the follower and the row is a
     * real follow (not device-local): this excludes both the reverse rows
     * the inbound kind-3 processor stores (contact -> me) and the
     * favorite-only rows [toggleFavorite] creates, which would otherwise be
     * broadcast as the active user's own contacts.
     */
    private suspend fun publishCurrentContactList(followerNpub: String) {
        val entries = followDao.getFollowsByFollower(followerNpub)
            .filter { it.followerNpub == followerNpub && !it.isLocalOnly }
            .map { ContactListEntry(it.followingNpub, it.petname) }
        val result = nostrPublisher.publishContactList(entries)
        if (result.isFailure) {
            android.util.Log.w(
                "FollowRepository",
                "⚠️ Contact list publish failed: ${result.exceptionOrNull()?.message}"
            )
        }
    }
}