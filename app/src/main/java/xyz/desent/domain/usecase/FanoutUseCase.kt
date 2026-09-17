package xyz.desent.domain.usecase

import xyz.desent.domain.model.BackupFetchResult
import xyz.desent.domain.model.FanoutEventsStatus
import xyz.desent.domain.model.FanoutHealth
import xyz.desent.domain.model.FanoutImportResult
import xyz.desent.domain.model.FanoutReconcile
import xyz.desent.domain.model.FanoutRelayCheck
import xyz.desent.domain.model.FanoutRelayEntry
import xyz.desent.domain.model.FanoutRelayInfo
import xyz.desent.domain.repository.FanoutRepository

class FanoutUseCase(
    private val fanoutRepository: FanoutRepository
) {
    suspend fun fetchRelayList(): Result<List<FanoutRelayEntry>> =
        fanoutRepository.fetchRelayList()

    suspend fun publishRelayList(entries: List<FanoutRelayEntry>): Result<Unit> =
        fanoutRepository.publishRelayList(entries)

    suspend fun getHealth(): Result<FanoutHealth> =
        fanoutRepository.getHealth()

    suspend fun searchDirectory(query: String?): Result<List<FanoutRelayInfo>> =
        fanoutRepository.searchDirectory(query)

    suspend fun fetchRelayInfo(url: String): Result<FanoutRelayInfo?> =
        fanoutRepository.fetchRelayInfo(url)

    suspend fun fetchFromBackupRelays(entries: List<FanoutRelayEntry>): Result<List<BackupFetchResult>> =
        fanoutRepository.fetchFromBackupRelays(entries)

    fun syncBackupIfEnabled() =
        fanoutRepository.syncBackupIfEnabled()

    /** §6.1 — advisory add-time relay check; never blocks a save. */
    suspend fun relayCheck(url: String): Result<FanoutRelayCheck> =
        fanoutRepository.relayCheck(url)

    /** §6.2 — per-event mirror delivery (batch ≤ 200 visible-page ids). */
    suspend fun eventsStatus(eventIds: List<String>): Result<FanoutEventsStatus> =
        fanoutRepository.eventsStatus(eventIds)

    /** §6.3 — gap detection; explicit user action only. */
    suspend fun reconcile(): Result<FanoutReconcile> =
        fanoutRepository.reconcile()

    /** §6.3 — re-store missing wraps (batch ≤ 100 ids). */
    suspend fun importWraps(eventIds: List<String>): Result<FanoutImportResult> =
        fanoutRepository.importWraps(eventIds)
}
