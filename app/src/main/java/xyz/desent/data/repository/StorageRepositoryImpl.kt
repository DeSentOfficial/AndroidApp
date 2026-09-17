package xyz.desent.data.repository

import xyz.desent.data.storage.StorageClient
import xyz.desent.data.storage.model.StorageBreakdownDto
import xyz.desent.data.storage.model.StorageCategoryDto
import xyz.desent.domain.model.STORAGE_CATEGORY_ORDER
import xyz.desent.domain.model.StorageBreakdown
import xyz.desent.domain.model.StorageCategory
import xyz.desent.domain.repository.StorageRepository

class StorageRepositoryImpl(
    private val storageClient: StorageClient
) : StorageRepository {

    override suspend fun getStorageBreakdown(): Result<StorageBreakdown> {
        return storageClient.getStorageBreakdown().map { dto -> dto.toDomain() }
    }

    private fun StorageBreakdownDto.toDomain(): StorageBreakdown = StorageBreakdown(
        used = used,
        cap = cap,
        tier = tier,
        source = source,
        categories = categories.toDomainSorted(),
        refreshedAt = System.currentTimeMillis()
    )

    /**
     * Server response order is not guaranteed — sort by [STORAGE_CATEGORY_ORDER]
     * before rendering. Any unknown keys are appended at the end, preserving
     * their relative server order, so future categories stay visible.
     */
    private fun List<StorageCategoryDto>.toDomainSorted(): List<StorageCategory> {
        val known = STORAGE_CATEGORY_ORDER
        val rank = known.withIndex().associate { (i, key) -> key to i }
        return sortedWith(
            compareBy(
                { rank[it.key] ?: known.size },
                { it.key }
            )
        ).map { StorageCategory(key = it.key, label = it.label, bytes = it.bytes) }
    }
}
