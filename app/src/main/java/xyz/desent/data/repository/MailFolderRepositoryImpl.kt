package xyz.desent.data.repository

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.PrivateStorageCrypto
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.database.dao.EmailDao
import xyz.desent.data.local.database.dao.MailFolderDao
import xyz.desent.data.local.database.dao.MailStateDao
import xyz.desent.data.local.database.entity.EmailEntity
import xyz.desent.data.local.database.entity.MailFolderManifestEntity
import xyz.desent.data.local.database.entity.MailShardStateEntity
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.model.MailFolder
import xyz.desent.domain.model.MailFoldersCodec
import xyz.desent.domain.model.MailStateEntry
import xyz.desent.domain.model.MailStateKeys
import xyz.desent.domain.model.MailStateShard
import xyz.desent.domain.repository.MailFolderRepository

/**
 * Implements [MailFolderRepository] over the kind-30078 mail namespaces
 * (refs/FROM_email.desent.xyz/ANDROID_MAIL_FOLDERS.md):
 *
 *  - Folder CRUD republishes the whole `desent:mail-folders` manifest
 *    (LWW by event `created_at`, echo-suppressed via the lastSeen watermark).
 *    Entries ride as verbatim JSON objects so unknown keys round-trip.
 *  - Move/unfile/read-mark update one `mail_state` row and mark its shard
 *    dirty; shards flush debounced (~3 s), publishing every dirty or
 *    previously-published shard, with entries older than 400 days pruned
 *    and emptied shards tombstoned off the wire (relay hard-deletes).
 *  - Inbound merges apply per-entry only on strictly-greater `ts`; a merge
 *    that changed state re-marks the shard dirty without bumping `ts`
 *    (anti-entropy), so echoes are no-ops and devices converge with no
 *    ping-pong.
 */
class MailFolderRepositoryImpl(
    private val mailFolderDao: MailFolderDao,
    private val mailStateDao: MailStateDao,
    private val emailDao: EmailDao,
    private val secureKeyManager: SecureKeyManager,
    private val nostrRepository: NostrRepository,
    private val preferencesManager: PreferencesManager,
    private val coroutineScope: CoroutineScope
) : MailFolderRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private suspend fun selfPriv(): ByteArray? =
        secureKeyManager.getIdentityFromStoredNSEC().getOrNull()?.privateKey?.rawData

    private suspend fun activeOwnerNpub(): String? {
        val hex = secureKeyManager.getIdentityFromStoredNSEC().getOrNull()?.publicKey?.toHexString()
        return hex?.let { Bech32Utils.hexToNpub(it) }
    }

    private val flushMutex = Mutex()
    private var flushJob: Job? = null

    // ------------------------------------------------------------------
    // Observation
    // ------------------------------------------------------------------

    override fun observeFolders(ownerNpub: String): Flow<List<MailFolder>> =
        mailFolderDao.observeManifest(ownerNpub).map { row ->
            row?.let { MailFoldersCodec.decode(it.manifestJson) } ?: emptyList()
        }

    override fun observeState(ownerNpub: String): Flow<Map<String, MailStateEntry>> =
        mailStateDao.observeAll(ownerNpub).map { rows ->
            rows.associate {
                it.folderKey to MailStateEntry(
                    k = it.folderKey,
                    f = it.folderId,
                    r = if (it.isRead) 1 else 0,
                    ts = it.ts
                )
            }
        }

    // ------------------------------------------------------------------
    // Folder CRUD (whole-manifest republish, LWW by created_at)
    // ------------------------------------------------------------------

    override suspend fun createFolder(ownerNpub: String, name: String, parent: String?): Result<MailFolder> {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.contains('/')) {
            return Result.failure(IllegalArgumentException("Folder name must be non-empty and slash-free"))
        }
        val current = currentFolders(ownerNpub)
        if (parent != null && current.none { it.id == parent }) {
            return Result.failure(IllegalArgumentException("Parent folder not found"))
        }
        val id = MailStateKeys.newFolderId()
        val folder = MailFolder(
            id = id, name = trimmed, parent = parent, color = null, sort = 0,
            raw = MailFoldersCodec.newEntry(id, trimmed, parent)
        )
        return republishManifest(ownerNpub, current + folder).map { folder }
    }

    override suspend fun renameFolder(ownerNpub: String, id: String, newName: String): Result<Unit> {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed.contains('/')) {
            return Result.failure(IllegalArgumentException("Folder name must be non-empty and slash-free"))
        }
        val current = currentFolders(ownerNpub)
        val target = current.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalArgumentException("Folder not found"))
        // Rebuild the wire entry from the verbatim original so unknown keys survive.
        val renamed = target.copy(
            name = trimmed,
            raw = buildJsonObject {
                target.raw.forEach { (key, value) -> put(key, value) }
                put("name", trimmed)
            }
        )
        return republishManifest(ownerNpub, current.map { if (it.id == id) renamed else it })
    }

    override suspend fun deleteFolder(ownerNpub: String, id: String): Result<Unit> {
        val current = currentFolders(ownerNpub)
        if (current.none { it.id == id }) {
            return Result.failure(IllegalArgumentException("Folder not found"))
        }
        val result = republishManifest(ownerNpub, current.filterNot { it.id == id })
        if (result.isSuccess) {
            // Clear assignments via unfile tombstones: newer ts → every device
            // (including us) drops the folder reference; the mail stays put.
            val now = nowSec()
            for (key in mailStateDao.getKeysInFolder(ownerNpub, id)) {
                val prev = mailStateDao.getByKey(ownerNpub, key)
                mailStateDao.upsertLww(
                    ownerNpub = ownerNpub,
                    folderKey = key,
                    folderId = null,
                    isRead = prev?.isRead ?: false,
                    ts = maxOf(now, (prev?.ts ?: 0L) + 1L)
                )
                markDirty(ownerNpub, MailStateKeys.shardOf(key))
            }
            scheduleFlush()
        }
        return result
    }

    /** Republish the whole manifest, refresh the local cache, bump the LWW watermark. */
    private suspend fun republishManifest(ownerNpub: String, folders: List<MailFolder>): Result<Unit> {
        val priv = selfPriv() ?: return Result.failure(Exception("No active identity"))
        return try {
            val now = nowSec()
            val plaintext = MailFoldersCodec.encode(folders, now)
            val ciphertext = PrivateStorageCrypto.encryptToSelf(plaintext, priv)
            nostrRepository.publishPrivateStorage(MailStateKeys.FOLDERS_D_TAG, ciphertext).getOrThrow()
            // Treat our own publish as seen so the relay echo is a no-op.
            preferencesManager.setMailFoldersLastSeenAt(now)
            mailFolderDao.upsertManifest(
                MailFolderManifestEntity(
                    ownerNpub = ownerNpub,
                    manifestJson = plaintext,
                    updatedAt = now,
                    dTag = MailStateKeys.FOLDERS_D_TAG,
                    createdAt = now
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "manifest republish failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun currentFolders(ownerNpub: String): List<MailFolder> =
        mailFolderDao.getManifest(ownerNpub)?.let { MailFoldersCodec.decode(it.manifestJson) } ?: emptyList()

    // ------------------------------------------------------------------
    // Per-message state (LWW-element-set)
    // ------------------------------------------------------------------

    override suspend fun moveToFolder(ownerNpub: String, messageKey: String, folderId: String?): Result<Unit> {
        return try {
            val prev = mailStateDao.getByKey(ownerNpub, messageKey)
            if (prev?.folderId == folderId) return Result.success(Unit) // no-op
            mailStateDao.upsertLww(
                ownerNpub = ownerNpub,
                folderKey = messageKey,
                folderId = folderId,
                isRead = prev?.isRead ?: false,
                ts = nextTs(prev?.ts)
            )
            markDirty(ownerNpub, MailStateKeys.shardOf(messageKey))
            scheduleFlush()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "moveToFolder failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun setRead(ownerNpub: String, messageKey: String, read: Boolean) {
        try {
            val prev = mailStateDao.getByKey(ownerNpub, messageKey)
            // Absent == unread, so nothing to record the first time unread is set.
            if (prev == null && !read) return
            if (prev?.isRead == read) return
            mailStateDao.upsertLww(
                ownerNpub = ownerNpub,
                folderKey = messageKey,
                folderId = prev?.folderId,
                isRead = read,
                ts = nextTs(prev?.ts)
            )
            mailStateDao.applyReadToEmails(ownerNpub, messageKey, read)
            markDirty(ownerNpub, MailStateKeys.shardOf(messageKey))
            scheduleFlush()
        } catch (e: Exception) {
            Log.w(TAG, "setRead overlay stamp failed: ${e.message}")
        }
    }

    override suspend fun markThreadRead(ownerNpub: String, threadKey: String) {
        try {
            val now = nowSec()
            for (row in emailDao.getThreadRows(ownerNpub, threadKey)) {
                val key = MailStateKeys.pinnedKey(row.messageId, row.id)
                val prev = mailStateDao.getByKey(ownerNpub, key)
                if (prev?.isRead == true) continue
                mailStateDao.upsertLww(
                    ownerNpub = ownerNpub,
                    folderKey = key,
                    folderId = prev?.folderId,
                    isRead = true,
                    ts = maxOf(now, (prev?.ts ?: 0L) + 1L)
                )
                markDirty(ownerNpub, MailStateKeys.shardOf(key))
            }
            scheduleFlush()
        } catch (e: Exception) {
            Log.w(TAG, "markThreadRead overlay stamp failed: ${e.message}")
        }
    }

    /** Local user action stamp: always strictly greater than anything seen. */
    private fun nextTs(prevTs: Long?): Long = maxOf(nowSec(), (prevTs ?: 0L) + 1L)

    private suspend fun markDirty(ownerNpub: String, shard: Int) {
        mailStateDao.upsertShardState(MailShardStateEntity(ownerNpub, shard, dirty = true, published = true))
    }

    // ------------------------------------------------------------------
    // Publish (debounced flush)
    // ------------------------------------------------------------------

    /** Debounce the shard flush: user actions batch, the relay round-trips once. */
    fun scheduleFlush() {
        flushJob?.cancel()
        flushJob = coroutineScope.launch {
            delay(FLUSH_DEBOUNCE_MS)
            runCatching { flush() }.onFailure {
                Log.w(TAG, "debounced flush failed: ${it.message}")
            }
        }
    }

    override suspend fun flush(force: Boolean): Result<Unit> = flushMutex.withLock {
        val ownerNpub = activeOwnerNpub()
        val priv = selfPriv()
        if (ownerNpub == null || priv == null) {
            return@withLock Result.failure<Unit>(Exception("No active identity"))
        }
        try {
            val shardStates = mailStateDao.getShardStates(ownerNpub)
            val dirty = shardStates.filter { it.dirty }.map { it.shardIndex }.toSet()
            if (dirty.isEmpty() && !force) return@withLock Result.success(Unit)
            // Publish every dirty shard, plus every previously-published one so
            // the 400-day prune propagates (web anti-entropy parity).
            val targets = (dirty + shardStates.filter { it.published }.map { it.shardIndex }).toSortedSet()

            val horizon = nowSec() - PRUNE_SECONDS
            val byShard = mailStateDao.getAll(ownerNpub)
                .filter { it.ts >= horizon }
                .groupBy { MailStateKeys.shardOf(it.folderKey) }

            for (shardIndex in targets) {
                val entries = byShard[shardIndex].orEmpty()
                    .sortedByDescending { it.ts }
                    .take(MailStateKeys.MAX_ENTRIES_PER_SHARD)
                    .map {
                        MailStateEntry(k = it.folderKey, f = it.folderId, r = if (it.isRead) 1 else 0, ts = it.ts)
                    }
                if (entries.isEmpty()) {
                    // Zero live entries: empty-content tombstone (relay hard-deletes).
                    nostrRepository.publishPrivateStorage(MailStateKeys.shardDTag(shardIndex), "").getOrThrow()
                    mailStateDao.deleteShardState(ownerNpub, shardIndex)
                } else {
                    val shard = MailStateShard(index = shardIndex, entries = entries)
                    val ciphertext = PrivateStorageCrypto.encryptToSelf(
                        json.encodeToString(MailStateShard.serializer(), shard), priv
                    )
                    nostrRepository.publishPrivateStorage(MailStateKeys.shardDTag(shardIndex), ciphertext).getOrThrow()
                    mailStateDao.upsertShardState(
                        MailShardStateEntity(ownerNpub, shardIndex, dirty = false, published = true)
                    )
                }
            }
            Log.d(TAG, "flush: published ${targets.size} mail-state shard(s)")
            Result.success(Unit)
        } catch (e: Exception) {
            // Dirty flags stay set — the next trigger retries.
            Log.e(TAG, "flush failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------
    // Inbound (called from PrivateStorageRepositoryImpl for kind 30078)
    // ------------------------------------------------------------------

    /**
     * `desent:mail-folders` seen on the own-pubkey subscription. LWW by event
     * `created_at` with echo suppression. The raw plaintext is cached verbatim
     * so unknown keys on folder entries round-trip.
     */
    suspend fun onInboundManifest(plaintext: String, createdAt: Long, ownerNpub: String) {
        val lastSeen = preferencesManager.mailFoldersLastSeenAt.first()
        if (createdAt <= lastSeen) {
            Log.d(TAG, "onInboundManifest: stale ($createdAt <= $lastSeen), ignored")
            return
        }
        preferencesManager.setMailFoldersLastSeenAt(createdAt)
        mailFolderDao.upsertManifest(
            MailFolderManifestEntity(
                ownerNpub = ownerNpub,
                manifestJson = plaintext,
                updatedAt = createdAt,
                dTag = MailStateKeys.FOLDERS_D_TAG,
                createdAt = createdAt
            )
        )
        Log.d(TAG, "onInboundManifest: applied manifest (createdAt=$createdAt)")
    }

    /**
     * `desent:mail-state:<i>` seen on the own-pubkey subscription. Per-entry
     * LWW: apply only on strictly-greater `ts`. A merge that changed state
     * re-marks the shard dirty WITHOUT bumping `ts` (anti-entropy: a device
     * that was offline heals the others; echoes are no-ops — no ping-pong).
     * Entries referencing not-yet-synced mail simply wait in the overlay and
     * apply at ingest.
     */
    suspend fun onInboundShard(plaintext: String, ownerNpub: String) {
        val shard = try {
            json.decodeFromString(MailStateShard.serializer(), plaintext)
        } catch (e: Exception) {
            Log.w(TAG, "onInboundShard: decode failed: ${e.message}")
            return
        }
        var changed = false
        for (entry in shard.entries) {
            val applied = mailStateDao.upsertLww(
                ownerNpub = ownerNpub,
                folderKey = entry.k,
                folderId = entry.f,
                isRead = entry.r == 1,
                ts = entry.ts
            )
            if (applied > 0) {
                changed = true
                mailStateDao.applyReadToEmails(ownerNpub, entry.k, entry.r == 1)
            }
        }
        if (changed) {
            markDirty(ownerNpub, shard.index)
            scheduleFlush()
        }
        Log.d(TAG, "onInboundShard: shard ${shard.index}, ${shard.entries.size} entries, changed=$changed")
    }

    /**
     * Apply a pre-existing overlay entry to freshly ingested mail. The read
     * flag writes through to the row; the folder assignment needs no write —
     * folder views join the overlay live by pinned key.
     */
    suspend fun applyOnArrival(ownerNpub: String, messageKey: String, isReadDefault: Boolean): Boolean {
        val entry = mailStateDao.getByKey(ownerNpub, messageKey) ?: return isReadDefault
        if (entry.isRead != isReadDefault) {
            mailStateDao.applyReadToEmails(ownerNpub, messageKey, entry.isRead)
        }
        return entry.isRead
    }

    companion object {
        private const val TAG = "MailFolderRepo"
        private const val FLUSH_DEBOUNCE_MS = 3_000L

        /** Deterministic prune horizon (web parity): ts < now − 400 d drops at publish. */
        private const val PRUNE_SECONDS = 400L * 24L * 60L * 60L
    }
}
