package xyz.desent.domain.repository

import xyz.desent.domain.model.StorageBreakdown

interface StorageRepository {
    /** Per-category byte breakdown of the caller's unified storage quota. */
    suspend fun getStorageBreakdown(): Result<StorageBreakdown>
}
