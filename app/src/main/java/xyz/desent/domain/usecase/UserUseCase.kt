package xyz.desent.domain.usecase

import xyz.desent.domain.model.User
import xyz.desent.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow

class UserUseCase(
    private val userRepository: UserRepository
) {
    
    suspend fun getUserByNpub(npub: String): User? {
        return userRepository.getUserByNpub(npub)
    }
    
    fun observeUserByNpub(npub: String): Flow<User?> {
        return userRepository.observeUserByNpub(npub)
    }

    fun observeUsersByNpubs(npubs: List<String>): Flow<List<User>> {
        return userRepository.observeUsersByNpubs(npubs)
    }
    
    suspend fun saveUser(user: User) {
        userRepository.saveUser(user)
    }
    
    suspend fun saveUsers(users: List<User>) {
        userRepository.saveUsers(users)
    }
    
    fun observeAllUsers(): Flow<List<User>> {
        return userRepository.observeAllUsers()
    }
    
    suspend fun deleteUser(npub: String) {
        userRepository.deleteUser(npub)
    }
    
    suspend fun fetchUserFromRelays(npub: String): Result<User> {
        return userRepository.fetchUserFromRelays(npub)
    }

}