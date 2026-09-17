package xyz.desent.domain.repository

import xyz.desent.domain.model.User
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    
    suspend fun getUserByNpub(npub: String): User?
    
    fun observeUserByNpub(npub: String): Flow<User?>

    fun observeUsersByNpubs(npubs: List<String>): Flow<List<User>>
    
    suspend fun saveUser(user: User)
    
    suspend fun upsertUser(user: User)
    
    suspend fun saveUsers(users: List<User>)
    
    fun observeAllUsers(): Flow<List<User>>
    
    suspend fun deleteUser(npub: String)
    
    suspend fun fetchUserFromRelays(npub: String): Result<User>



    /**
     * Resolve an exact identifier query (npub, hex pubkey, or NIP-05 address)
     * to a user. Falls back to a minimal placeholder user for valid npub/hex
     * lookups whose metadata cannot be fetched, so the profile card can still
     * open. Returns an empty list when the identifier doesn't resolve.
     */
}