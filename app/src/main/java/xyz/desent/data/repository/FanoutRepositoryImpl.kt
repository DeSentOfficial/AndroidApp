package xyz.desent.data.repository

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import xyz.desent.data.RelayConfig
import xyz.desent.data.directory.RelayDirectoryClient
import xyz.desent.data.fanout.FanoutClient
import xyz.desent.data.local.preferences.SecurityConfigStore
import xyz.desent.data.nip11.Nip11MetadataService
import xyz.desent.data.nip46.Nip46PairingUriParser
import xyz.desent.domain.model.BackupFetchResult
import xyz.desent.domain.model.FanoutEventsStatus
import xyz.desent.domain.model.FanoutEventRelayStatus
import xyz.desent.domain.model.FanoutEventStatus
import xyz.desent.domain.model.FanoutHealth
import xyz.desent.domain.model.FanoutImportResult
import xyz.desent.domain.model.FanoutMirrorDeliveryStatus
import xyz.desent.domain.model.FanoutNipGap
import xyz.desent.domain.model.FanoutReconcile
import xyz.desent.domain.model.FanoutReconcileMirror
import xyz.desent.domain.model.FanoutRelayCheck
import xyz.desent.domain.model.FanoutRelayEntry
import xyz.desent.domain.model.FanoutRelayHealth
import xyz.desent.domain.model.FanoutRelayInfo
import xyz.desent.domain.repository.FanoutRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * Implements [FanoutRepository] over the Nostr WS surface
 * ([NostrRepository] owns the relay plumbing — kind-10002 fetch/publish and
 * the backup fetch), the mirroring health API ([FanoutClient]), the public
 * relay directory ([RelayDirectoryClient], directory.yadha.net) and NIP-11
 * ([Nip11MetadataService]).
 */
class FanoutRepositoryImpl(
    private val nostrRepository: NostrRepository,
    private val fanoutClient: FanoutClient,
    private val relayDirectoryClient: RelayDirectoryClient,
    private val nip11MetadataService: Nip11MetadataService,
    private val securityConfigStore: SecurityConfigStore
) : FanoutRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val backupSyncMutex = Mutex()

    override suspend fun fetchRelayList(): Result<List<FanoutRelayEntry>> =
        nostrRepository.fetchOwnRelayList()

    override suspend fun publishRelayList(entries: List<FanoutRelayEntry>): Result<Unit> {
        val canonical = entries.mapNotNull { entry ->
            canonicalRelayUrl(entry.url)?.let { entry.copy(url = it) }
        }.distinctBy { it.url }
        if (canonical.isEmpty()) {
            return Result.failure(IllegalArgumentException("No valid relay URLs in the list"))
        }
        return nostrRepository.publishRelayList(canonical).onSuccess {
            // Fresh list + opt-in on → pull anything the new relays hold.
            syncBackupIfEnabled()
        }
    }

    override suspend fun getHealth(): Result<FanoutHealth> =
        fanoutClient.getStatus().map { resp ->
            FanoutHealth(
                relays = resp.relays.map {
                    FanoutRelayHealth(
                        url = it.url,
                        pending = it.pending,
                        done = it.done,
                        dead = it.dead,
                        lastActivity = it.lastActivity
                    )
                },
                totalPending = resp.totalPending,
                totalDead = resp.totalDead
            )
        }

    override suspend fun searchDirectory(query: String?): Result<List<FanoutRelayInfo>> =
        relayDirectoryClient.searchFanoutRelays(query).map { relays ->
            relays.map { it.toRelayInfo() }
        }

    override suspend fun fetchRelayInfo(url: String): Result<FanoutRelayInfo?> {
        return try {
            // Directory detail first: first-party probes (ping/uptime) plus
            // the harvested NIP-11 (icon/name/nips) in one lookup.
            val fromDirectory = relayDirectoryClient.getRelayDetail(url).getOrNull()
            if (fromDirectory != null) {
                return Result.success(fromDirectory.toRelayInfo())
            }
            // Fallback: the relay's own NIP-11 document (icon/name/nips, no ping).
            val metadata = nip11MetadataService.fetchMetadata(url)
            Result.success(
                metadata?.let {
                    FanoutRelayInfo(
                        url = url,
                        name = it.name,
                        iconUrl = it.icon,
                        supportedNips = it.supportedNips?.toSet()
                    )
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "relay info fetch failed for $url: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun fetchFromBackupRelays(entries: List<FanoutRelayEntry>): Result<List<BackupFetchResult>> =
        runCatching {
            val urls = entries.asSequence()
                .mapNotNull { canonicalRelayUrl(it.url) }
                .filter { it.startsWith("wss://") }
                .filter { it != RelayConfig.EMAIL_RELAY_URL }
                .distinct()
                .toList()
            nostrRepository.fetchGiftWrapsFromBackupRelays(urls)
        }

    override fun syncBackupIfEnabled() {
        if (!backupSyncMutex.tryLock()) {
            Log.d(TAG, "backup sync already running - skipping")
            return
        }
        scope.launch {
            try {
                val ownerNpub = nostrRepository.getCurrentUserNpub() ?: return@launch
                val optedIn = securityConfigStore.get(ownerNpub)?.dmFanout == true
                if (!optedIn) return@launch

                val entries = nostrRepository.fetchOwnRelayList().getOrDefault(emptyList())
                if (entries.isEmpty()) return@launch

                val results = fetchFromBackupRelays(entries).getOrDefault(emptyList())
                Log.i(TAG, "backup sync: ${results.count { it.status == xyz.desent.domain.model.BackupFetchStatus.DONE }}/${results.size} relays done")
            } catch (e: Exception) {
                Log.w(TAG, "backup sync failed: ${e.message}")
            } finally {
                backupSyncMutex.unlock()
            }
        }
    }

    override suspend fun relayCheck(url: String): Result<FanoutRelayCheck> =
        fanoutClient.relayCheck(url).map { resp ->
            FanoutRelayCheck(
                url = resp.url.ifBlank { url },
                usable = resp.usable,
                unusableReason = resp.unusableReason,
                name = resp.nip11?.name ?: resp.directory?.name,
                supportedNips = resp.nip11?.supportedNips ?: resp.directory?.supportedNips,
                uptime7d = resp.directory?.uptime7d,
                rttOpenMs = resp.directory?.rttOpenMs,
                missingNips = resp.capabilities.missing.map { FanoutNipGap(it.nip, it.why) },
                capabilitiesUnknown = resp.capabilities.unknown,
                notes = resp.capabilities.notes.map { FanoutNipGap(it.nip, it.why) },
                checkedAt = resp.checkedAt
            )
        }

    override suspend fun eventsStatus(eventIds: List<String>): Result<FanoutEventsStatus> =
        fanoutClient.eventsStatus(eventIds).map { resp ->
            FanoutEventsStatus(
                resp.events.map { event ->
                    FanoutEventStatus(
                        eventId = event.eventId,
                        relays = event.relays.map {
                            FanoutEventRelayStatus(
                                url = it.url,
                                status = FanoutMirrorDeliveryStatus.fromWire(it.status),
                                attempts = it.attempts
                            )
                        }
                    )
                }
            )
        }

    override suspend fun reconcile(): Result<FanoutReconcile> =
        fanoutClient.reconcile().map { resp ->
            FanoutReconcile(
                mirrors = resp.mirrors.map { mirror ->
                    FanoutReconcileMirror(
                        url = mirror.url,
                        error = mirror.error,
                        seen = mirror.seen,
                        missingLocallyIds = mirror.missingLocally,
                        missingLocallyTruncated = mirror.missingLocallyTruncated,
                        locallyDeletedCount = (mirror.locallyDeleted as? kotlinx.serialization.json.JsonPrimitive)
                            ?.content?.toIntOrNull()
                            ?: (mirror.locallyDeleted as? kotlinx.serialization.json.JsonArray)?.size
                            ?: 0
                    )
                }
            )
        }

    override suspend fun importWraps(eventIds: List<String>): Result<FanoutImportResult> =
        fanoutClient.importWraps(eventIds).map { resp ->
            val result = FanoutImportResult(imported = resp.imported, skipped = resp.skipped)
            // §6.3: re-fetch the inbox after `imported > 0` — the re-stored
            // wraps are usually older than the live subscription's watermark.
            if (result.imported > 0) {
                nostrRepository.fetchWrapsByIds(eventIds)
            }
            result
        }

    private fun xyz.desent.data.directory.model.DirectoryRelayDto.toRelayInfo() = FanoutRelayInfo(
        url = url,
        name = name,
        iconUrl = icon,
        status = status,
        network = network,
        isPremium = isPremium,
        uptime7d = uptime7d,
        rttOpenMs = rttOpenMs,
        rttReadMs = rttReadMs,
        supportedNips = supportedNips?.toSet()
    )

    companion object {
        private const val TAG = "FanoutRepository"

        /** DNS-style host, optionally with a port — the shape NIP-65 carries. */
        private val HOST_PORT_RE = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?(:[0-9]{1,5})?$")

        /**
         * Canonical form for a relay-list URL, built on the app's nestled
         * WSS normalizer ([Nip46PairingUriParser.normalizeRelay] — scheme
         * coercion ws/http/bare-host → `wss://`, trailing slash). The
         * trailing slash is the desent-internal convention; NIP-65 wire
         * form is bare, so it is stripped for the published tags. Null when
         * the input yields no usable host (the normalizer coerces even
         * garbage into a `wss://`-prefixed string, so the host:port shape
         * is the real gate).
         */
        fun canonicalRelayUrl(input: String): String? {
            val normalized = Nip46PairingUriParser.normalizeRelay(input).removeSuffix("/")
            val hostPort = normalized.removePrefix("wss://").substringBefore('/')
            return if (HOST_PORT_RE.matches(hostPort)) normalized else null
        }
    }
}
