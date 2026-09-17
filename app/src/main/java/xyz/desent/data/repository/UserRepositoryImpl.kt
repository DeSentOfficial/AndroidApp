package xyz.desent.data.repository

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import xyz.desent.crypto.Bech32Utils
import xyz.desent.data.local.database.dao.UserDao
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.mapper.UserMapper
import xyz.desent.data.nip05.Nip05VerificationService
import xyz.desent.data.nostr.NostrEventProcessor
import xyz.desent.data.nostr.NostrKinds
import xyz.desent.domain.model.User
import xyz.desent.domain.repository.RelayRepository
import xyz.desent.domain.repository.UserRepository
import kotlinx.coroutines.flow.firstOrNull

class UserRepositoryImpl(
    private val userDao: UserDao,
    private val userMapper: UserMapper,
    private val relayRepository: RelayRepository,
    private val nostrEventProcessor: NostrEventProcessor,
    private val nip05VerificationService: Nip05VerificationService? = null,
    private val preferencesManager: PreferencesManager? = null
) : UserRepository {

    private companion object {
        /**
         * Backstop for one-shot kind-0 fetches. The EOSE race normally
         * resolves "not on these relays" in well under a second; this only
         * bounds relays that never answer at all.
         */
        const val FETCH_TIMEOUT_MS = 3000L
    }
    
    override suspend fun getUserByNpub(npub: String): User? {
        return userDao.getUserByNpub(npub)?.let { userMapper.mapToDomain(it) }
    }
    
    override fun observeUserByNpub(npub: String): Flow<User?> {
        return userDao.observeUserByNpub(npub).map { entity ->
            entity?.let { userMapper.mapToDomain(it) }
        }
    }

    override fun observeUsersByNpubs(npubs: List<String>): Flow<List<User>> {
        if (npubs.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyList())
        return userDao.observeUsersByNpubs(npubs).map { entities ->
            userMapper.mapToDomainList(entities)
        }
    }
    
    override suspend fun saveUser(user: User) {
        userDao.insertUser(userMapper.mapToEntity(user))
    }
    
    override suspend fun saveUsers(users: List<User>) {
        userDao.insertUsers(userMapper.mapToEntityList(users))
    }
    
    override suspend fun upsertUser(user: User) {
        userDao.insertUser(userMapper.mapToEntity(user))
    }
    
    override fun observeAllUsers(): Flow<List<User>> {
        return userDao.observeAllUsers().map { entities ->
            userMapper.mapToDomainList(entities)
        }
    }
    
    override suspend fun deleteUser(npub: String) {
        userDao.deleteUser(npub)
    }
    
    /**
     * Fetch kind-0 metadata for [npub] from the connected relays.
     *
     * Completes as soon as EITHER a metadata event arrives OR every relay
     * that received the REQ reports EOSE without one (the old behaviour —
     * idling to a fixed timeout even though the relays had already said
     * "nothing stored" in ~150 ms — stalled the DM roster by 8 s per
     * uncached contact). A [FETCH_TIMEOUT_MS] backstop bounds relays that
     * never send EOSE (auth buffering, half-open sockets), and the one-shot
     * subscription is always CLOSED afterwards.
     */
    override suspend fun fetchUserFromRelays(npub: String): Result<User> {
        return try {
            val pubkeyHex = Bech32Utils.npubToHex(npub)
            val subscriptionId = "user_${pubkeyHex.take(8)}"  // Short ID to avoid relay limits (max 71 chars)

            val relayCount = relayRepository.getConnectedRelays().size
            if (relayCount == 0) {
                return Result.failure(Exception("No relays connected"))
            }

            Log.d("UserRepository", "Fetching user metadata from relays for npub=${npub.take(8)} ($relayCount relay(s))")

            val metadataArrived = CompletableDeferred<Boolean>()
            try {
                withTimeout(FETCH_TIMEOUT_MS) {
                    coroutineScope {
                        // Collectors attach first (UNDISPATCHED runs them to
                        // their first suspension) so no frame can slip past
                        // between subscribe and collect.
                        val metadataJob = launch(start = CoroutineStart.UNDISPATCHED) {
                            nostrEventProcessor.metadataArrivals.first { it == npub }
                            metadataArrived.complete(true)
                        }
                        val eoseJob = launch(start = CoroutineStart.UNDISPATCHED) {
                            val seenRelays = mutableSetOf<String>()
                            relayRepository.eoseEvents.first { (subId, relayUrl) ->
                                subId == subscriptionId &&
                                    seenRelays.add(relayUrl) &&
                                    seenRelays.size >= relayCount
                            }
                            metadataArrived.complete(false)
                        }
                        try {
                            relayRepository.subscribeToEvents(
                                listOf(mapOf(
                                    "authors" to listOf(pubkeyHex),
                                    "kinds" to listOf(NostrKinds.SET_METADATA),
                                    "limit" to 1
                                )),
                                subscriptionId,
                                persistent = false
                            )
                            metadataArrived.await()
                        } finally {
                            metadataJob.cancel()
                            eoseJob.cancel()
                        }
                    }
                }
            } finally {
                runCatching { relayRepository.unsubscribeFromEvents(subscriptionId) }
            }

            userFromDb(npub)
        } catch (e: TimeoutCancellationException) {
            Log.w("UserRepository", "Timeout fetching user from relays: $npub")
            // The kind-0 may still have landed via another subscription this
            // session (the processor dedups repeat event ids and wouldn't
            // re-emit) — check the cache before declaring failure.
            userFromDb(npub, fallbackError = "Fetch timeout")
        } catch (e: Exception) {
            Log.e("UserRepository", "Error fetching user from relays: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun userFromDb(npub: String, fallbackError: String = "User metadata not found"): Result<User> {
        val user = userDao.getUserByNpub(npub)?.let { userMapper.mapToDomain(it) }
        return if (user != null) {
            Log.d("UserRepository", "Successfully fetched and cached user: ${npub.take(8)}")
            Result.success(user)
        } else {
            Log.w("UserRepository", "User metadata not found after relay fetch: $npub")
            Result.failure(Exception(fallbackError))
        }
    }
    


}