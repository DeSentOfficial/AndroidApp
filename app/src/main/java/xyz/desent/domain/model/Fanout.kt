package xyz.desent.domain.model

/**
 * Relay Mirroring domain models ("Relay Mirroring" is the feature's
 * user-facing name; the wire calls it fan-out —
 * refs/FROM_email.desent.xyz/ANDROID_DM_FANOUT.md, DM_FANOUT.md).
 *
 * The relay mirrors the user's inbound email gift wraps + delivery receipts
 * to the write-capable relays of their NIP-65 list (kind 10002). The client
 * publishes that list, flips the 30079 `dm_fanout` opt-in, and reads mail
 * back from those relays as a backup inbox.
 */

/** Access marker on an `r` tag of the NIP-65 relay list (kind 10002). */
enum class RelayListMarker(val wire: String?) {
    /** No marker — read+write; a mirroring target. */
    READ_WRITE(null),

    /** `"write"` — a mirroring target, excluded from read hints. */
    WRITE("write"),

    /** `"read"` — excluded from mirroring server-side. */
    READ("read");

    companion object {
        fun fromWire(value: String?): RelayListMarker = when (value?.lowercase()) {
            null, "" -> READ_WRITE
            "write" -> WRITE
            "read" -> READ
            // Unknown markers stay in the published list untouched where
            // possible; for our own model we treat them as read+write (the
            // server's marker rules are authoritative anyway).
            else -> READ_WRITE
        }
    }
}

/** One entry of the user's NIP-65 relay list. */
data class FanoutRelayEntry(
    val url: String,
    val marker: RelayListMarker = RelayListMarker.READ_WRITE
)

/** Pill colour for one relay row of the mirroring health surface (§4). */
enum class FanoutRelayHealthPill { GREEN, AMBER, RED }

/** Per-relay delivery health from `GET /api/fanout/status`. */
data class FanoutRelayHealth(
    val url: String,
    val pending: Int,
    val done: Int,
    val dead: Int,
    /** ISO timestamp of the last queue activity; null when never active. */
    val lastActivity: String? = null
) {
    /** Green = delivering, amber = retrying (pending > 0), red = dead-lettered. */
    val pill: FanoutRelayHealthPill
        get() = when {
            dead > 0 -> FanoutRelayHealthPill.RED
            pending > 0 -> FanoutRelayHealthPill.AMBER
            else -> FanoutRelayHealthPill.GREEN
        }
}

data class FanoutHealth(
    val relays: List<FanoutRelayHealth> = emptyList(),
    val totalPending: Int = 0,
    val totalDead: Int = 0
)

// ---------------------------------------------------------------------------
// §6 mirror surface (migration 056)
// ---------------------------------------------------------------------------

/** Advisory verdict pill for a relay-check (ANDROID_DM_FANOUT.md §6.1). */
enum class FanoutRelayCheckPill { GREEN, AMBER, RED }

/** One required/advisory NIP the relay doesn't cover; [why] is user-ready. */
data class FanoutNipGap(
    val nip: Int,
    val why: String?
)

/**
 * `GET /api/fanout/relay-check` verdict — advisory only, NEVER blocks a
 * save. Green = online + required NIPs covered; amber = missing NIP-40/09
 * or capabilities unknown; red = offline / `usable: false`.
 */
data class FanoutRelayCheck(
    val url: String,
    val usable: Boolean,
    val unusableReason: String? = null,
    val name: String? = null,
    val supportedNips: Set<Int>? = null,
    val uptime7d: Double? = null,
    val rttOpenMs: Long? = null,
    val missingNips: List<FanoutNipGap> = emptyList(),
    val capabilitiesUnknown: Boolean = false,
    val notes: List<FanoutNipGap> = emptyList(),
    val checkedAt: String? = null
) {
    val pill: FanoutRelayCheckPill
        get() = when {
            !usable -> FanoutRelayCheckPill.RED
            capabilitiesUnknown || missingNips.isNotEmpty() -> FanoutRelayCheckPill.AMBER
            else -> FanoutRelayCheckPill.GREEN
        }

    /** One-line advisory for the relay row; null when nothing to flag. */
    val advisoryText: String?
        get() = when {
            !usable -> unusableReason ?: "Relay reported unusable"
            else -> missingNips.firstOrNull()?.let { gap ->
                "NIP-${gap.nip} missing${gap.why?.let { " — $it" } ?: ""}"
            }
        }
}

/** Per-relay delivery state of one mirrored event (§6.2). */
enum class FanoutMirrorDeliveryStatus {
    DONE, PENDING, DEAD;

    companion object {
        fun fromWire(value: String?): FanoutMirrorDeliveryStatus = when (value?.lowercase()) {
            "done" -> DONE
            "dead" -> DEAD
            else -> PENDING
        }
    }
}

data class FanoutEventRelayStatus(
    val url: String,
    val status: FanoutMirrorDeliveryStatus,
    val attempts: Int
)

data class FanoutEventStatus(
    val eventId: String,
    val relays: List<FanoutEventRelayStatus>
)

/**
 * `POST /api/fanout/events-status` batch result (§6.2). Absent ids = no
 * data (30-day retention sweep or pre-entitlement mail) — NEVER a failure.
 */
class FanoutEventsStatus(val events: List<FanoutEventStatus>) {
    private val byId: Map<String, FanoutEventStatus> = events.associateBy { it.eventId }

    /**
     * True when every reported relay delivered, false when any is still
     * pending/dead, null when the server has no data for the id.
     */
    fun isMirrored(eventId: String): Boolean? = byId[eventId]?.let { status ->
        status.relays.isNotEmpty() && status.relays.all { it.status == FanoutMirrorDeliveryStatus.DONE }
    }
}

/** One mirror's reconcile report (§6.3). */
data class FanoutReconcileMirror(
    val url: String,
    val error: String? = null,
    val seen: Int = 0,
    /** Wraps living on the mirror but missing locally (≤ 200 per mirror). */
    val missingLocallyIds: List<String> = emptyList(),
    val missingLocallyTruncated: Boolean = false,
    /** Tombstoned ids — the user deleted them; NEVER importable. */
    val locallyDeletedCount: Int = 0
)

/** `POST /api/fanout/reconcile` report (§6.3) — explicit user action only. */
data class FanoutReconcile(
    val mirrors: List<FanoutReconcileMirror> = emptyList()
) {
    /** Distinct importable ids across mirrors (capped per import call). */
    val importableIds: List<String>
        get() = mirrors.flatMap { it.missingLocallyIds }.distinct()
}

/** `POST /api/fanout/import` outcome (§6.3). */
data class FanoutImportResult(
    val imported: Int = 0,
    val skipped: Int = 0
)

/** Outcome of one relay in a backup fetch (`fetchFromBackupRelays`). */
enum class BackupFetchStatus { DONE, TIMED_OUT, AUTH_REQUIRED, FAILED }

data class BackupFetchResult(
    val url: String,
    val status: BackupFetchStatus,
    val detail: String? = null
)

/**
 * Advisory NIP-support assessment for a candidate mirroring relay. A missing
 * NIP NEVER blocks selection (ANDROID_DM_FANOUT.md: marker/validation rules
 * are server-authoritative) — it only highlights the row as possibly not
 * supported.
 */
enum class FanoutNipSupportStatus {
    /** NIP-11 doc says the relay speaks the needed NIPs. */
    SUPPORTED,

    /** Speaks NIP-01 but doesn't advertise DM storage — selectable, highlighted. */
    POSSIBLY_UNSUPPORTED,

    /** No NIP-11 data available (doc unreachable / not in the directory). */
    UNKNOWN
}

/**
 * The NIPs a relay-mirroring target should support, assessed against a
 * relay's NIP-11 `supported_nips` (from the relay's own information
 * document, the directory's harvested copy, or the §6.1 server
 * relay-check). Rendered as one chip per NIP under each relay row.
 * Mirrored server-side by refs/RELAY_MIRRORING_CHECK.md — keep the two in
 * sync.
 */
object FanoutNipSupport {
    /**
     * Basic protocol — a kind-1059 gift wrap is an ordinary event and the
     * backup fetch REQs `#p` single-letter tag filters; both are NIP-01.
     */
    val REQUIRED: Set<Int> = setOf(1)

    /**
     * The DM-relay check list: NIP-09 (deletion — removed mail should also
     * disappear from the backup relay, not linger forever; §6.4 propagates
     * kind-5 deletions), NIP-17 (private messaging semantics) and NIP-59
     * (gift wraps). Many fine relays store 1059s without advertising these
     * — hence advisory, not required.
     */
    val EXPECTED: Set<Int> = setOf(9, 17, 59)

    /** Display order of the per-NIP chips. */
    val CHECK_LIST: List<Int> = listOf(1) + EXPECTED.sorted()

    fun assess(supportedNips: Set<Int>?): FanoutNipSupportStatus = when {
        supportedNips == null -> FanoutNipSupportStatus.UNKNOWN
        REQUIRED.all { it in supportedNips } && EXPECTED.all { it in supportedNips } ->
            FanoutNipSupportStatus.SUPPORTED
        else -> FanoutNipSupportStatus.POSSIBLY_UNSUPPORTED
    }

    /**
     * Which of the needed NIPs the relay does not advertise (display only).
     * Empty when there is no NIP-11 data — absent data claims nothing.
     */
    fun missing(supportedNips: Set<Int>?): Set<Int> =
        supportedNips?.let { (REQUIRED + EXPECTED) - it } ?: emptySet()
}

/**
 * Everything the mirroring surfaces know about one relay: the relay's NIP-11
 * profile (name, icon, `supported_nips`) enriched with the directory's own
 * probes (ping, uptime). Powers both the relay-list rows and the directory
 * picker. Mirrored server-side by refs/RELAY_MIRRORING_CHECK.md.
 */
data class FanoutRelayInfo(
    val url: String,
    val name: String? = null,
    /** Relay's set profile picture (NIP-11 `icon`, http(s) only). */
    val iconUrl: String? = null,
    /** Directory probe status: online | offline | unknown | unprobeable. */
    val status: String? = null,
    val network: String? = null,
    val isPremium: Boolean = false,
    /** 0.0–1.0 own-probe uptime; null = no checks in the window. */
    val uptime7d: Double? = null,
    /** Directory probe: WebSocket handshake time, ms. */
    val rttOpenMs: Long? = null,
    /** Directory probe: REQ → first-frame time, ms. */
    val rttReadMs: Long? = null,
    val supportedNips: Set<Int>? = null
) {
    val nipSupport: FanoutNipSupportStatus get() = FanoutNipSupport.assess(supportedNips)

    val missingNips: Set<Int> get() = FanoutNipSupport.missing(supportedNips)
}

/**
 * The relay rejected a premium-gated publish with `OK false
 * "premium required: …"` (ANDROID_DM_FANOUT.md §2). Never auto-retry —
 * surface the purchase/upgrade UI instead and keep other settings savable.
 */
class PremiumRequiredException(message: String) : Exception(message) {
    companion object {
        private const val MARKER = "premium required"

        fun fromVerdictMessage(message: String?): PremiumRequiredException? =
            if (message != null && message.contains(MARKER, ignoreCase = true)) {
                PremiumRequiredException(message)
            } else {
                null
            }
    }
}
