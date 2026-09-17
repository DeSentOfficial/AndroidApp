package xyz.desent.data.repository

import xyz.desent.data.agents.AgentsClient
import xyz.desent.data.agents.model.AgentDto
import xyz.desent.data.agents.model.AgentModeDto
import xyz.desent.domain.model.Agent
import xyz.desent.domain.model.AgentMode
import xyz.desent.domain.model.AgentsSnapshot
import xyz.desent.domain.repository.AgentsRepository

class AgentsRepositoryImpl(
    private val agentsClient: AgentsClient
) : AgentsRepository {

    override suspend fun listAgents(): Result<AgentsSnapshot> {
        return agentsClient.listAgents().map { resp ->
            AgentsSnapshot(
                agents = resp.agents.map { it.toDomain() },
                used = resp.used,
                cap = resp.cap,
                tier = resp.tier,
                enabled = resp.enabled,
                emailDomain = resp.emailDomain
            )
        }
    }

    override suspend fun createAgent(
        agentPubkey: String,
        addressLocal: String,
        mode: AgentMode,
        label: String?
    ): Result<Agent> {
        return agentsClient.createAgent(
            agentPubkey = agentPubkey,
            addressLocal = addressLocal,
            mode = mode.toDto(),
            label = label
        ).map { it.toDomain() }
    }

    override suspend fun updateAgent(
        id: Long,
        update: xyz.desent.domain.model.AgentUpdate
    ): Result<Agent> {
        return agentsClient.updateAgent(
            id = id,
            mode = update.mode?.toDto(),
            triggerKeywords = update.triggerKeywords,
            policyNote = update.policyNote,
            systemPrompt = update.systemPrompt,
            displayName = update.displayName,
            pictureUrl = update.pictureUrl,
            about = update.about
        ).map { it.toDomain() }
    }

    override suspend fun deleteAgent(id: Long): Result<Unit> {
        return agentsClient.deleteAgent(id).map { }
    }

    private fun AgentDto.toDomain(): Agent = Agent(
        id = id,
        agentPubkey = agentPubkey,
        addressLocal = addressLocal,
        addressDomain = addressDomain,
        address = address,
        label = label,
        mode = when (mode) {
            "respond" -> AgentMode.RESPOND
            else -> AgentMode.DIGEST
        },
        triggerKeywords = triggerKeywords,
        policyNote = policyNote,
        systemPrompt = systemPrompt,
        displayName = displayName,
        pictureUrl = pictureUrl,
        about = about,
        status = status,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt
    )

    private fun AgentMode.toDto(): AgentModeDto = when (this) {
        AgentMode.DIGEST -> AgentModeDto.DIGEST
        AgentMode.RESPOND -> AgentModeDto.RESPOND
    }
}
