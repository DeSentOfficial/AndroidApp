package xyz.desent.data.directory.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/relays` — the relay directory search response
 * (refs/FROM_directory.desent.xyz/API.md). Only the fields the mirroring
 * relay picker consumes are modeled; everything else is ignored.
 */
@Serializable
data class DirectoryRelaysResponse(
    val stats: DirectoryStatsDto? = null,
    val relays: List<DirectoryRelayDto> = emptyList()
)

@Serializable
data class DirectoryStatsDto(
    val total: Int? = null,
    val online: Int? = null,
    val offline: Int? = null,
    val unprobeable: Int? = null,
    val monitors: Int? = null
)

@Serializable
data class DirectoryRelayDto(
    val id: Long = 0,
    /** Normalized relay URL (`wss://host[:port][/path]`) — canonical identity. */
    val url: String,
    val host: String? = null,
    /** clearnet | tor | i2p | loki. */
    val network: String? = null,
    /** online | offline | unknown | unprobeable (the directory's own probe). */
    val status: String? = null,
    val name: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val software: String? = null,
    @SerialName("supported_nips")
    val supportedNips: List<Int>? = null,
    @SerialName("rtt_open_ms")
    val rttOpenMs: Long? = null,
    /** Directory probe: REQ → first-frame time, ms. */
    @SerialName("rtt_read_ms")
    val rttReadMs: Long? = null,
    /** 0.0–1.0 own-probe uptime; null = no checks in the window. */
    @SerialName("uptime_7d")
    val uptime7d: Double? = null,
    @SerialName("is_premium")
    val isPremium: Boolean = false,
    @SerialName("monitor_count")
    val monitorCount: Int = 0,
    /** NIP-66 `T` tag, e.g. "PublicOpen". */
    @SerialName("relay_type")
    val relayType: String? = null
)

/** Typed failures raised by [xyz.desent.data.directory.RelayDirectoryClient]. */
sealed class DirectoryError(message: String) : Exception(message) {
    /** 429 — nginx per-IP limit (5 rps, burst 10); retry honoring Retry-After. */
    object RateLimited : DirectoryError("Directory rate limited — try again shortly")
    object Unauthorized : DirectoryError("Directory request rejected")
    class Server(message: String, val code: Int) : DirectoryError(message)
    class Unknown(message: String) : DirectoryError(message)
}

@Serializable
data class DirectoryErrorResponse(
    val detail: String? = null
)
