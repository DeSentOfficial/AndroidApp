package xyz.desent.domain.usecase

import xyz.desent.domain.model.StorageBreakdown
import xyz.desent.domain.repository.StorageRepository

class StorageUseCase(
    private val storageRepository: StorageRepository
) {
    suspend fun getStorageBreakdown(): Result<StorageBreakdown> =
        storageRepository.getStorageBreakdown()
}
