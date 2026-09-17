package xyz.desent.domain.usecase

import android.util.Log
import kotlinx.coroutines.*
import xyz.desent.data.repository.NostrRepository
import xyz.desent.domain.model.User

private const val TAG = "BackgroundDataFetch"

class BackgroundDataFetchUseCaseImpl(
    private val nostrRepository: NostrRepository,
    private val userUseCase: UserUseCase,
    private val followUseCase: FollowUseCase
) : BackgroundDataFetchUseCase {

    private val taskTimeoutMs = 10000L // Increased from 4000L to 10000L for relay responses

    override suspend fun fetchType1Data(npub: String): FetchResult = coroutineScope {
        Log.d(TAG, "Starting Type 1 background fetch for npub=${npub.take(8)}")

        val errors = mutableListOf<String>()
        var profileSuccess = false
        var followsSuccess = false
        var followersSuccess = false

        val profileJob = async(Dispatchers.IO) {
            try {
                withTimeout(taskTimeoutMs) {
                    nostrRepository.fetchUserMetadata(npub)
                }
                profileSuccess = true
                Log.d(TAG, " Profile fetch succeeded")
            } catch (e: TimeoutCancellationException) {
                val error = "Profile fetch timeout after ${taskTimeoutMs}ms"
                errors.add(error)
                Log.w(TAG, " $error")
            } catch (e: Exception) {
                val error = "Profile fetch failed: ${e.message}"
                errors.add(error)
                Log.e(TAG, " $error", e)
            }
        }

        val followsJob = async(Dispatchers.IO) {
            try {
                withTimeout(taskTimeoutMs) {
                    nostrRepository.fetchUserContacts(npub)
                }
                followsSuccess = true
                Log.d(TAG, " Follows fetch succeeded")
            } catch (e: TimeoutCancellationException) {
                val error = "Follows fetch timeout after ${taskTimeoutMs}ms"
                errors.add(error)
                Log.w(TAG, " $error")
            } catch (e: Exception) {
                val error = "Follows fetch failed: ${e.message}"
                errors.add(error)
                Log.e(TAG, " $error", e)
            }
        }

        // Fetch followers (people who follow this user) - don't wait for completion
        // This runs in background since it's unreliable and slow
        val followersJob = async(Dispatchers.IO) {
            try {
                // Run without strict timeout - it's best effort
                followUseCase.fetchFollowersFromRelays(npub)
                followersSuccess = true
                Log.d(TAG, " Followers fetch completed (best effort)")
            } catch (e: Exception) {
                // Don't fail the whole operation if followers fetch fails
                val error = "Followers fetch failed (non-critical): ${e.message}"
                errors.add(error)
                Log.w(TAG, " $error")
            }
        }

        awaitAll(profileJob, followsJob, followersJob)

        val result = FetchResult(
            profileSuccess = profileSuccess,
            followsSuccess = followsSuccess,
            followersSuccess = followersSuccess,
            errors = errors
        )

        Log.d(TAG, "Type 1 fetch completed: profile=$profileSuccess, follows=$followsSuccess, followers=$followersSuccess, errors=${errors.size}")

        result
    }

    override suspend fun fetchType3RefreshData(npub: String): FetchResult = coroutineScope {
        Log.d(TAG, "Starting Type 3 refresh fetch for npub=${npub.take(8)}")

        val errors = mutableListOf<String>()
        var followsSuccess = false
        var followersSuccess = false

        val followsJob = async(Dispatchers.IO) {
            try {
                withTimeout(taskTimeoutMs) {
                    nostrRepository.fetchUserContacts(npub)
                }
                followsSuccess = true
                Log.d(TAG, " Follows refresh succeeded")
            } catch (e: TimeoutCancellationException) {
                val error = "Follows refresh timeout after ${taskTimeoutMs}ms"
                errors.add(error)
                Log.w(TAG, " $error")
            } catch (e: Exception) {
                val error = "Follows refresh failed: ${e.message}"
                errors.add(error)
                Log.e(TAG, " $error", e)
            }
        }

        // Fetch followers (people who follow this user) - don't wait for completion
        // This runs in background since it's unreliable and slow
        val followersJob = async(Dispatchers.IO) {
            try {
                // Run without strict timeout - it's best effort
                followUseCase.fetchFollowersFromRelays(npub)
                followersSuccess = true
                Log.d(TAG, " Followers refresh completed (best effort)")
            } catch (e: Exception) {
                // Don't fail the whole operation if followers fetch fails
                val error = "Followers refresh failed (non-critical): ${e.message}"
                errors.add(error)
                Log.w(TAG, " $error")
            }
        }

        awaitAll(followsJob, followersJob)

        val result = FetchResult(
            profileSuccess = true,
            followsSuccess = followsSuccess,
            followersSuccess = followersSuccess,
            errors = errors
        )

        Log.d(TAG, "Type 3 refresh completed: follows=$followsSuccess, followers=$followersSuccess, errors=${errors.size}")

        result
    }

    override suspend fun pushProfileToRelays(user: User): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Pushing profile to relays for npub=${user.npub}")

            nostrRepository.publishUserProfile(
                name = user.name,
                displayName = user.displayName,
                about = user.about,
                picture = user.picture
            )

            Log.d(TAG, " Profile push succeeded")
            Result.success(Unit)
        } catch (e: Exception) {
            val error = "Failed to push profile to relays: ${e.message}"
            Log.e(TAG, " $error", e)
            Result.failure(e)
        }
    }
}
