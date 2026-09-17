package xyz.desent.data.fanout.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `GET /api/fanout/status` — the fan-out delivery health surface
 * (refs/FROM_email.desent.xyz/ANDROID_DM_FANOUT.md §4). NIP-98 auth.
 */
@Serializable
data class FanoutStatusResponse(
    val relays: List<FanoutRelayStatusDto> = emptyList(),
    @SerialName("total_pending") val totalPending: Int = 0,
    @SerialName("total_dead") val totalDead: Int = 0
)

@Serializable
data class FanoutRelayStatusDto(
    val url: String,
    val pending: Int = 0,
    val done: Int = 0,
    val dead: Int = 0,
    /** ISO timestamp of the last queue activity; null when never active. */
    @SerialName("last_activity") val lastActivity: String? = null
)

// ---------------------------------------------------------------------------
// §6 mirror surface (migration 056)
// ---------------------------------------------------------------------------

/** `GET /api/fanout/relay-check?url=…` (§6.1) — advisory, never blocks a save. */
@Serializable
data class RelayCheckResponse(
    val url: String = "",
    val usable: Boolean = false,
    @SerialName("unusable_reason") val unusableReason: String? = null,
    val directory: RelayCheckDirectoryDto? = null,
    val nip11: RelayCheckNip11Dto? = null,
    val capabilities: RelayCheckCapabilitiesDto = RelayCheckCapabilitiesDto(),
    @SerialName("checked_at") val checkedAt: String? = null
)

@Serializable
data class RelayCheckDirectoryDto(
    val status: String? = null,
    @SerialName("uptime_7d") val uptime7d: Double? = null,
    @SerialName("rtt_open_ms") val rttOpenMs: Long? = null,
    @SerialName("last_online_at") val lastOnlineAt: String? = null,
    val name: String? = null,
    @SerialName("supported_nips") val supportedNips: Set<Int>? = null
)

@Serializable
data class RelayCheckNip11Dto(
    /** "directory" | "relay". */
    val source: String? = null,
    val name: String? = null,
    @SerialName("supported_nips") val supportedNips: Set<Int>? = null
)

@Serializable
data class RelayCheckCapabilitiesDto(
    val missing: List<RelayCheckNipGapDto> = emptyList(),
    val unknown: Boolean = false,
    val notes: List<RelayCheckNipGapDto> = emptyList()
)

@Serializable
data class RelayCheckNipGapDto(
    val nip: Int = 0,
    /** User-ready explanation for the gap. */
    val why: String? = null
)

/** `POST /api/fanout/events-status` (§6.2) body — batch ≤ 200 ids. */
@Serializable
data class EventsStatusRequest(
    @SerialName("event_ids") val eventIds: List<String>
)

@Serializable
data class EventsStatusResponse(
    val events: List<EventMirrorStatusDto> = emptyList()
)

@Serializable
data class EventMirrorStatusDto(
    @SerialName("event_id") val eventId: String = "",
    val relays: List<EventRelayStatusDto> = emptyList()
)

@Serializable
data class EventRelayStatusDto(
    val url: String = "",
    /** done | pending | dead. */
    val status: String = "",
    val attempts: Int = 0
)

/** `POST /api/fanout/reconcile` (§6.3) — explicit user action only (~5/hour). */
@Serializable
data class ReconcileResponse(
    val mirrors: List<ReconcileMirrorDto> = emptyList()
)

@Serializable
data class ReconcileMirrorDto(
    val url: String = "",
    val error: String? = null,
    val seen: Int = 0,
    @SerialName("missing_locally") val missingLocally: List<String> = emptyList(),
    @SerialName("missing_locally_truncated") val missingLocallyTruncated: Boolean = false,
    /**
     * Tombstoned ids — NEVER importable. Doc leaves the wire shape open
     * (count or id list); tolerate both.
     */
    @SerialName("locally_deleted") val locallyDeleted: JsonElement? = null
)

/** `POST /api/fanout/import` (§6.3) body — batch ≤ 100 ids. */
@Serializable
data class ImportRequest(
    @SerialName("event_ids") val eventIds: List<String>
)

@Serializable
data class ImportResponse(
    val ok: Boolean = false,
    val imported: Int = 0,
    val skipped: Int = 0
)

/** Typed failures raised by [xyz.desent.data.fanout.FanoutClient]. */
sealed class FanoutError(message: String) : Exception(message) {
    object Unauthorized : FanoutError("Authentication failed — try re-login")
    /** 403 not_entitled (§6.3) — fail closed. */
    object NotEntitled : FanoutError("Relay mirroring isn't available for your account")
    /** 503 fanout_disabled — master switch off. */
    object FanoutDisabled : FanoutError("Relay mirroring isn't enabled on this relay yet")
    object RateLimited : FanoutError("Too many requests — try again later")
    class Server(message: String, val code: Int) : FanoutError(message)
    class Unknown(message: String) : FanoutError(message)
}

/**
 * FastAPI `detail` is a plain string on some endpoints and an
 * `{"error": …}` object on others — keep both readable.
 */
@Serializable
data class FanoutErrorResponse(
    val detail: JsonElement? = null
) {
    val errorCode: String?
        get() = when (val el = detail) {
            is kotlinx.serialization.json.JsonObject ->
                (el["error"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            is kotlinx.serialization.json.JsonPrimitive -> el.contentOrNull
            else -> null
        }

    private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
        get() = if (isString) content else null
}
