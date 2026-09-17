package xyz.desent.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `cal` rumor-tag payload of an AI-agent calendar proposal
 * (refs/ANDROID_AI_AGENTS.md §6): `{"title","start_unix","end_unix",
 * "location","details","timezone"}`. Unknown keys round-trip on the wire
 * and are ignored here — never reject a proposal over a field this build
 * doesn't know.
 */
@Serializable
data class AgentCalPayload(
    val title: String = "",
    @SerialName("start_unix") val startUnix: Long = 0L,
    @SerialName("end_unix") val endUnix: Long = 0L,
    val location: String? = null,
    val details: String? = null,
    val timezone: String? = null
)
