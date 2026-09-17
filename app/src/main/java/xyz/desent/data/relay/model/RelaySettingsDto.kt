package xyz.desent.data.relay.model

import kotlinx.serialization.Serializable

/**
 * Response of `GET /api/relay-settings` (refs/CALENDAR_PROTOCOL.md §Feature
 * flag). Tolerant: unknown keys are ignored, and a missing/null field falls
 * back to the caller's default.
 */
@Serializable
data class RelaySettingsDto(
    /** One of `"open"` or `"disabled"`. Missing/null when the server is silent. */
    val calendar_enabled: String? = null
)
