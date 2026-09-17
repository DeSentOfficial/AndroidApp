package xyz.desent.domain.usecase

import xyz.desent.domain.model.Agent
import xyz.desent.domain.model.AgentMode
import xyz.desent.domain.model.AgentUpdate
import xyz.desent.domain.model.AgentsSnapshot
import xyz.desent.domain.repository.AgentsRepository

class AgentsUseCase(
    private val agentsRepository: AgentsRepository
) {
    suspend fun listAgents(): Result<AgentsSnapshot> =
        agentsRepository.listAgents()

    suspend fun createAgent(
        agentPubkey: String,
        addressLocal: String,
        mode: AgentMode,
        label: String?
    ): Result<Agent> =
        agentsRepository.createAgent(agentPubkey, addressLocal, mode, label)

    suspend fun updateAgent(id: Long, update: AgentUpdate): Result<Agent> =
        agentsRepository.updateAgent(id, update)

    suspend fun deleteAgent(id: Long): Result<Unit> =
        agentsRepository.deleteAgent(id)
}
