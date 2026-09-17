package xyz.desent.domain.repository

import xyz.desent.domain.model.BackupFetchResult
import xyz.desent.domain.model.FanoutEventsStatus
import xyz.desent.domain.model.FanoutHealth
import xyz.desent.domain.model.FanoutImportResult
import xyz.desent.domain.model.FanoutReconcile
import xyz.desent.domain.model.FanoutRelayCheck
import xyz.desent.domain.model.FanoutRelayEntry
import xyz.desent.domain.model.FanoutRelayInfo

/**
 * DM-relay fan-out orchestration (refs/FROM_email.desent.xyz/ANDROID_DM_FANOUT.md):
 * the NIP-65 relay list (kind 10002), the delivery health surface, the relay
 * directory picker, and the backup-inbox fetch from the user's own relays.
 *
 * The opt-in itself (`dm_fanout`, kind 30079) lives with
 * [SecurityConfigRepository] — the mirroring slice of the user settings.
 */
interface FanoutRepository {

    /** Latest own kind-10002 relay list (empty when none published). */
    suspend fun fetchRelayList(): Result<List<FanoutRelayEntry>>

    /**
     * Canonicalize ([xyz.desent.data.nip46.Nip46PairingUriParser.normalizeRelay])
     * and publish the kind-10002 list to the DeSent relay, then refresh the
     * backup fetch (no-op unless the opt-in is on). Returns null for input
     * that yields no usable host.
     */
    suspend fun publishRelayList(entries: List<FanoutRelayEntry>): Result<Unit>

    /** `GET /api/fanout/status` — per-relay queue health. */
    suspend fun getHealth(): Result<FanoutHealth>

    /** Directory relays usable as mirroring targets (clearnet, online, free). */
    suspend fun searchDirectory(query: String?): Result<List<FanoutRelayInfo>>

    /**
     * Everything the mirroring surfaces know about one relay (icon, name,
     * ping, uptime, `supported_nips`). Merge precedence: directory detail
     * (`GET /api/relays/{base64url-id}` — first-party probes + harvested
     * NIP-11) → the relay's own NIP-11 document (no ping) → null (UNKNOWN).
     */
    suspend fun fetchRelayInfo(url: String): Result<FanoutRelayInfo?>

    /**
     * Backup-inbox fetch across the user's relays (skips the DeSent relay
     * and non-wss entries). Events flow through the standard gift-wrap
     * pipeline; non-email rumors are dumped by the processor.
     */
    suspend fun fetchFromBackupRelays(entries: List<FanoutRelayEntry>): Result<List<BackupFetchResult>>

    /**
     * Fire-and-forget: pull mail from the user's backup relays when (and
     * only when) the 30079 `dm_fanout` opt-in is on. Non-blocking — launches
     * on the repository's own scope; concurrent calls coalesce.
     */
    fun syncBackupIfEnabled()

    /**
     * `GET /api/fanout/relay-check?url=…` (§6.1) — advisory add-time check;
     * NEVER blocks a save.
     */
    suspend fun relayCheck(url: String): Result<FanoutRelayCheck>

    /**
     * `POST /api/fanout/events-status` (§6.2) — per-event mirror delivery
     * for a visible-list batch (≤ 200 ids). Absent ids carry no verdict.
     */
    suspend fun eventsStatus(eventIds: List<String>): Result<FanoutEventsStatus>

    /**
     * `POST /api/fanout/reconcile` (§6.3) — gap detection. Explicit user
     * action only (~5/hour server rate limit); never on a timer.
     */
    suspend fun reconcile(): Result<FanoutReconcile>

    /**
     * `POST /api/fanout/import` (§6.3) — re-fetch + re-store the missing
     * wraps (≤ 100 ids per call). `locally_deleted` ids are never passable.
     */
    suspend fun importWraps(eventIds: List<String>): Result<FanoutImportResult>
}
