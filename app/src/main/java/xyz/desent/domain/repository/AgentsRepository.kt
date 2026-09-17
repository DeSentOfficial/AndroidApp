package xyz.desent.domain.repository

import xyz.desent.domain.model.Agent
import xyz.desent.domain.model.AgentMode
import xyz.desent.domain.model.AgentUpdate
import xyz.desent.domain.model.AgentsSnapshot

interface AgentsRepository {
    suspend fun listAgents(): Result<AgentsSnapshot>

    suspend fun createAgent(
        agentPubkey: String,
        addressLocal: String,
        mode: AgentMode,
        label: String?
    ): Result<Agent>

    suspend fun updateAgent(id: Long, update: AgentUpdate): Result<Agent>

    suspend fun deleteAgent(id: Long): Result<Unit>
}
