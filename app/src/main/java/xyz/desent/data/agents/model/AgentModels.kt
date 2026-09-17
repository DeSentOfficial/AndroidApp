package xyz.desent.data.agents.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Agent mode (END-21): `digest` = read-only, `respond` = may also send. */
enum class AgentModeDto {
    @SerialName("digest") DIGEST,
    @SerialName("respond") RESPOND
}

@Serializable
data class AgentDto(
    val id: Long,
    @SerialName("agent_pubkey") val agentPubkey: String,
    @SerialName("address_local") val addressLocal: String,
    @SerialName("address_domain") val addressDomain: String = "",
    val address: String = "",
    val label: String? = null,
    val mode: String = "digest",
    @SerialName("trigger_keywords") val triggerKeywords: List<String> = emptyList(),
    @SerialName("policy_note") val policyNote: String? = null,
    /** Bot persona (§7): multiline, ≤4000 chars, owner-authored. */
    @SerialName("system_prompt") val systemPrompt: String? = null,
    /** NIP-01 profile fields (§8, migration 048). */
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("picture_url") val pictureUrl: String? = null,
    val about: String? = null,
    val status: String = "active",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_used_at") val lastUsedAt: String? = null
)

@Serializable
data class AgentListResponse(
    val agents: List<AgentDto> = emptyList(),
    val used: Int = 0,
    /** Null = unlimited (lifetime tier). */
    val cap: Int? = null,
    val tier: String = "free",
    val enabled: Boolean = false,
    @SerialName("email_domain") val emailDomain: String = "desent.xyz"
)

/**
 * POST /api/agents body. Only the agent's PUBLIC key ever goes on the
 * wire — the private key, passphrase, and ncryptsec never leave the
 * device (ANDROID_AI_AGENTS.md §3).
 */
@Serializable
data class CreateAgentRequest(
    @SerialName("agent_pubkey") val agentPubkey: String,
    @SerialName("address_local") val addressLocal: String,
    val mode: String,
    val label: String? = null
)

/** PATCH /api/agents/{id} — keywords use full-list replacement semantics. */
@Serializable
data class UpdateAgentRequest(
    val mode: String? = null,
    @SerialName("trigger_keywords") val triggerKeywords: List<String>? = null,
    @SerialName("policy_note") val policyNote: String? = null,
    @SerialName("system_prompt") val systemPrompt: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("picture_url") val pictureUrl: String? = null,
    val about: String? = null
)

@Serializable
data class DeleteAgentResponse(
    val status: String = "deleted",
    val id: Long = 0
)

// RFC 7807-ish: { "detail": { "error": "...", tier?, cap?, used?, length?, price_sats?, ladder? } }
@Serializable
data class AgentErrorDetail(
    val error: String? = null,
    val tier: String? = null,
    val cap: Int? = null,
    val used: Int? = null,
    val length: Int? = null,
    @SerialName("price_sats") val priceSats: Long? = null,
    val ladder: Map<String, Long>? = null,
    @SerialName("free_length") val freeLength: Int? = null
)

@Serializable
data class AgentErrorResponse(
    val detail: AgentErrorDetail? = null
)

/** Typed failures raised by [xyz.desent.data.agents.AgentsClient]. */
sealed class AgentsError(message: String) : Exception(message) {
    /** 403 `agents_disabled` — the section should have been locked. */
    object Disabled : AgentsError("AI agents are disabled on this server")
    /** 409 `taken` — address local-part in use. */
    object Taken : AgentsError("This address is already taken")
    /** 409 `agent_already_registered` — this agent pubkey already has an agent. */
    object AlreadyRegistered : AgentsError("This agent key is already registered")
    object InvalidLocalPart : AgentsError("Invalid address format")
    object InvalidPubkey : AgentsError("Invalid agent public key")
    object InvalidMode : AgentsError("Invalid agent mode")
    object InvalidDomain : AgentsError("Invalid domain")
    object Unauthorized : AgentsError("Authentication failed")
    object NotFound : AgentsError("Agent not found")
    object RateLimited : AgentsError("Too many requests — try again later")
    /**
     * 402 `vanity_price`: the short local-part carries a one-time price.
     * Route to the vanity request flow (same sheet as aliases).
     */
    class VanityPrice(
        val length: Int,
        val priceSats: Long,
        val ladder: Map<String, Long>,
        val freeLength: Int
    ) : AgentsError("Short address pricing applies")
    /** 402 `agent_cap_reached` — route to billing upsell. */
    class CapReached(
        val cap: Int?,
        val used: Int?,
        val tier: String?
    ) : AgentsError("Agent cap reached for your tier")
    class Server(message: String, val code: Int) : AgentsError(message)
    class Unknown(message: String) : AgentsError(message)
}
