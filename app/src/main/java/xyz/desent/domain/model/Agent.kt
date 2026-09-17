package xyz.desent.domain.model

/** Agent mode (END-21): `digest` = read-only, `respond` = may also send. */
enum class AgentMode {
    DIGEST,
    RESPOND
}

data class Agent(
    val id: Long,
    /** 64-hex x-only public key of the agent's own Nostr keypair. */
    val agentPubkey: String,
    val addressLocal: String,
    val addressDomain: String,
    val address: String,
    val label: String?,
    val mode: AgentMode,
    val triggerKeywords: List<String>,
    val policyNote: String?,
    /** Bot persona (§7): multiline, ≤4000 chars, owner-authored; served to the bot via /api/agents/self. */
    val systemPrompt: String? = null,
    /** NIP-01 profile fields (§8); the bot publishes its own kind 0 from these. */
    val displayName: String? = null,
    val pictureUrl: String? = null,
    val about: String? = null,
    val status: String,
    val createdAt: String?,
    val lastUsedAt: String?
)

/** PATCH /api/agents/{id} payload (full replacement semantics per field). */
data class AgentUpdate(
    val mode: AgentMode? = null,
    val triggerKeywords: List<String>? = null,
    val policyNote: String? = null,
    val systemPrompt: String? = null,
    val displayName: String? = null,
    val pictureUrl: String? = null,
    val about: String? = null
)

data class AgentsSnapshot(
    val agents: List<Agent>,
    val used: Int,
    /** Null = unlimited (lifetime tier). */
    val cap: Int?,
    val tier: String,
    val enabled: Boolean,
    val emailDomain: String
) {
    val isLifetime: Boolean get() = tier.equals("lifetime", ignoreCase = true)

    /** True when the agent-count cap is reached; lifetime (null cap) never is. */
    val isAtCap: Boolean get() = cap != null && used >= cap
}

/**
 * One-time connection code for handing an agent its own key: the NIP-49
 * ncryptsec block plus the passphrase needed to decrypt it. Lives only in
 * memory between key generation and the one-time handoff screen — it is
 * never persisted and never sent over the network.
 */
data class AgentConnectionCode(
    val ncryptsec: String,
    val passphrase: String
)
