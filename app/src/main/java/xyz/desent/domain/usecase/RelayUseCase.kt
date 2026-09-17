package xyz.desent.domain.usecase

import xyz.desent.domain.model.Relay
import xyz.desent.domain.model.ConnectionStatus
import xyz.desent.domain.repository.RelayRepository
import kotlinx.coroutines.flow.Flow

class RelayUseCase(
    private val relayRepository: RelayRepository
) {

    fun observeAllRelays(): Flow<List<Relay>> {
        return relayRepository.observeAllRelays()
    }

    fun observeActiveRelays(): Flow<List<Relay>> {
        return relayRepository.observeActiveRelays()
    }

    suspend fun updateConnectionStatus(url: String, status: ConnectionStatus, timestamp: Long? = null) {
        relayRepository.updateConnectionStatus(url, status, timestamp)
    }

    suspend fun updateFailureCount(url: String, failureCount: Int) {
        relayRepository.updateFailureCount(url, failureCount)
    }

    suspend fun updateActiveStatus(url: String, isActive: Boolean) {
        relayRepository.updateActiveStatus(url, isActive)
    }

    suspend fun connectToRelay(url: String) {
        relayRepository.connectToRelay(url)
    }

    suspend fun disconnectFromRelay(url: String) {
        relayRepository.disconnectFromRelay(url)
    }

    suspend fun disconnectFromAllRelays() {
        relayRepository.disconnectFromAllRelays()
    }

    fun observeConnectionStatus(url: String): Flow<ConnectionStatus> {
        return relayRepository.observeConnectionStatus(url)
    }

    suspend fun connectToPersistentRelays() {
        relayRepository.connectToPersistentRelays()
    }

    suspend fun isRefreshInProgress(): Boolean {
        return relayRepository.isRefreshInProgress()
    }

    suspend fun setRefreshInProgress(isInProgress: Boolean) {
        relayRepository.setRefreshInProgress(isInProgress)
    }

    fun isPersistentRelay(url: String): Boolean {
        return (relayRepository as xyz.desent.data.repository.RelayRepositoryImpl).isPersistentRelay(url)
    }
}
