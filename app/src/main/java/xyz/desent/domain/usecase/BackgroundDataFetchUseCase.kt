package xyz.desent.domain.usecase

import xyz.desent.domain.model.User

interface BackgroundDataFetchUseCase {
    
    suspend fun fetchType1Data(npub: String): FetchResult
    
    suspend fun fetchType3RefreshData(npub: String): FetchResult
    
    suspend fun pushProfileToRelays(user: User): Result<Unit>
}

data class FetchResult(
    val profileSuccess: Boolean = false,
    val followsSuccess: Boolean = false,
    val followersSuccess: Boolean = false,
    val errors: List<String> = emptyList()
) {
    val hasAnySuccess: Boolean
        get() = profileSuccess || followsSuccess || followersSuccess

    val hasAnyError: Boolean
        get() = errors.isNotEmpty()
}
