package xyz.desent.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nostr.event.impl.GenericEvent
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.PrivateStorageCrypto
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.database.dao.BayesianTokenDao
import xyz.desent.data.local.database.dao.PrivateContactsDao
import xyz.desent.data.local.database.dao.PrivateNoteDao
import xyz.desent.data.local.database.dao.UserFileDao
import xyz.desent.data.local.database.entity.PrivateContactsEntity
import xyz.desent.data.local.database.entity.UserFileEntity
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.mapper.PrivateStorageMapper
import xyz.desent.data.repository.NostrRepository.Companion.KIND_PRIVATE_STORAGE
import xyz.desent.domain.model.MailStateKeys
import xyz.desent.domain.model.NotePayload
import xyz.desent.domain.model.PgpKeyPayload
import xyz.desent.domain.model.PrivateContact
import xyz.desent.domain.model.PrivateNote
import xyz.desent.domain.model.SpamFilterConfig
import xyz.desent.domain.model.SpamSettingsPayload
import xyz.desent.domain.model.SpamTokenEntry
import xyz.desent.domain.model.SpamTokenManifest
import xyz.desent.domain.model.SpamTokenShard
import xyz.desent.domain.model.UserFile
import xyz.desent.domain.model.UserFilePayload
import xyz.desent.domain.repository.PrivateStorageRepository

/**
 * Implements [PrivateStorageRepository] over NIP-78 kind 30078 events.
 *
 * Publish path: serialise → NIP-44 self-encrypt → kind 30078 → DeSent relays.
 * Inbound path (kind 30078 seen on the own-pubkey subscription): decrypt →
 * upsert the local cache, or hard-delete on an empty-content tombstone.
 *
 * The `d` tag is the namespace:
 *  - `desent:note:<uuid>`       — one note
 *  - `desent:file:<sha256>`     — one user-uploaded encrypted file
 *  - `desent:contacts`           — the global email address book
 *  - `desent:pgp`                — the account's OpenPGP identity (LWW)
 *  - `desent:spam-settings`      — spam-filter config (LWW by `created_at`)
 *  - `desent:spam-tokens:<i>`    — Bayesian token snapshot shard (max-merged)
 *  - `desent:spam-tokens:manifest` — shard index for the token snapshot
 *  - `desent:mail-folders`       — mail folder manifest (LWW; empty = no-op)
 *  - `desent:mail-state:<i>`     — per-message folder/read-state shard
 *  - `desent:mail-state:manifest` — informational (ignored)
 */
class PrivateStorageRepositoryImpl(
    private val noteDao: PrivateNoteDao,
    private val contactsDao: PrivateContactsDao,
    private val userFileDao: UserFileDao,
    private val mapper: PrivateStorageMapper,
    private val secureKeyManager: SecureKeyManager,
    private val nostrRepository: NostrRepository,
    private val bayesianTokenDao: BayesianTokenDao,
    private val personalSpamRuleDao: xyz.desent.data.local.database.dao.PersonalSpamRuleDao,
    private val preferencesManager: PreferencesManager
) : PrivateStorageRepository {

    /**
     * Mail folders + synced read state (`desent:mail-folders` /
     * `desent:mail-state:*`). Set post-construction from AppContainer to
     * avoid a DI cycle — same pattern as NostrEventProcessor's bunker hook.
     */
    var mailFolderRepository: MailFolderRepositoryImpl? = null

    private val json = Json { ignoreUnknownKeys = true }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private fun noteDTag(id: String): String = "desent:note:$id"

    private fun fileDTag(sha256: String): String = "desent:file:$sha256"

    private suspend fun selfPriv(): ByteArray? =
        secureKeyManager.getIdentityFromStoredNSEC().getOrNull()?.privateKey?.rawData

    // ------------------------------------------------------------------
    // Notes
    // ------------------------------------------------------------------

    override fun observeNotes(ownerNpub: String): Flow<List<PrivateNote>> =
        noteDao.observeNotes(ownerNpub).map { rows -> rows.map { mapper.mapNoteToDomain(it) } }

    override fun observeNote(ownerNpub: String, id: String): Flow<PrivateNote?> =
        noteDao.observeNote(ownerNpub, id).map { it?.let { mapper.mapNoteToDomain(it) } }

    override suspend fun saveNote(note: PrivateNote): Result<Unit> {
        val priv = selfPriv()
            ?: return Result.failure(Exception("No active identity"))
        return try {
            val payload = mapper.notePayload(note)
            val ciphertext = PrivateStorageCrypto.encryptToSelf(
                json.encodeToString(NotePayload.serializer(), payload),
                priv
            )
            nostrRepository.publishPrivateStorage(note.dTag, ciphertext).getOrThrow()

            // Mirror into the local cache immediately for a snappy UI; the
            // inbound echo from the relay will REPLACE this row (same PK).
            noteDao.upsertNote(mapper.mapNoteToEntity(note, nowSec()))
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "saveNote failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteNote(ownerNpub: String, id: String): Result<Unit> {
        return try {
            // Empty-content tombstone: relay hard-deletes the prior version and
            // does not store the tombstone itself.
            nostrRepository.publishPrivateStorage(noteDTag(id), "").getOrThrow()
            noteDao.deleteNote(ownerNpub, id)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteNote failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------
    // Contacts
    // ------------------------------------------------------------------

    override fun observeContacts(ownerNpub: String): Flow<List<PrivateContact>> =
        contactsDao.observeContacts(ownerNpub).map { entity ->
            entity?.let { mapper.contactsToDomain(it) } ?: emptyList()
        }

    override suspend fun saveContacts(ownerNpub: String, contacts: List<PrivateContact>): Result<Unit> {
        val priv = selfPriv()
            ?: return Result.failure(Exception("No active identity"))
        return try {
            val plaintext = mapper.contactsToJson(contacts)
            val ciphertext = PrivateStorageCrypto.encryptToSelf(plaintext, priv)
            nostrRepository.publishPrivateStorage(CONTACTS_D_TAG, ciphertext).getOrThrow()

            contactsDao.upsertContacts(
                PrivateContactsEntity(
                    ownerNpub = ownerNpub,
                    contactsJson = plaintext,
                    updatedAt = nowSec(),
                    dTag = CONTACTS_D_TAG,
                    createdAt = nowSec()
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "saveContacts failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun activeOwnerNpub(): String? {
        val hex = secureKeyManager.getIdentityFromStoredNSEC().getOrNull()?.publicKey?.toHexString()
        return hex?.let { Bech32Utils.hexToNpub(it) }
    }

    // ------------------------------------------------------------------
    // User files (`desent:file:<sha256>`)
    // ------------------------------------------------------------------

    override fun observeUserFiles(ownerNpub: String): Flow<List<UserFile>> =
        userFileDao.observeFiles(ownerNpub).map { rows -> rows.map { it.toDomain() } }

    override suspend fun saveUserFile(file: UserFile): Result<Unit> {
        val priv = selfPriv()
            ?: return Result.failure(Exception("No active identity"))
        return try {
            val payload = UserFilePayload(
                v = 1,
                filename = file.filename,
                mimeType = file.mimeType,
                size = file.size,
                keyHex = file.keyHex,
                nonceHex = file.nonceHex,
                uploadedAt = file.uploadedAt,
                blurhash = file.blurhash,
                width = file.width,
                height = file.height
            )
            val ciphertext = PrivateStorageCrypto.encryptToSelf(
                json.encodeToString(UserFilePayload.serializer(), payload),
                priv
            )
            nostrRepository.publishPrivateStorage(file.dTag, ciphertext).getOrThrow()

            // Mirror into the local cache immediately for a snappy UI; the
            // inbound echo from the relay will REPLACE this row (same PK).
            userFileDao.upsertFile(file.toEntity())
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "saveUserFile failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteUserFile(ownerNpub: String, sha256: String): Result<Unit> {
        return try {
            // Empty-content tombstone: relay hard-deletes the prior version and
            // does not store the tombstone itself.
            nostrRepository.publishPrivateStorage(fileDTag(sha256), "").getOrThrow()
            userFileDao.deleteFile(ownerNpub, sha256)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteUserFile failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun UserFile.toEntity() = UserFileEntity(
        sha256 = sha256,
        ownerNpub = ownerNpub,
        filename = filename,
        mimeType = mimeType,
        size = size,
        keyHex = keyHex,
        nonceHex = nonceHex,
        uploadedAt = uploadedAt,
        blurhash = blurhash,
        width = width,
        height = height,
        dTag = dTag
    )

    private fun UserFileEntity.toDomain() = UserFile(
        sha256 = sha256,
        filename = filename,
        mimeType = mimeType,
        size = size,
        keyHex = keyHex,
        nonceHex = nonceHex,
        uploadedAt = uploadedAt,
        blurhash = blurhash,
        width = width,
        height = height,
        ownerNpub = ownerNpub,
        dTag = dTag
    )

    // ------------------------------------------------------------------
    // Spam settings (`desent:spam-settings`)
    // ------------------------------------------------------------------

    override suspend fun saveSpamSettings(
        config: SpamFilterConfig,
        personal: xyz.desent.domain.model.PersonalSpamRules
    ): Result<Unit> {
        val priv = selfPriv() ?: return Result.failure(Exception("No active identity"))
        return try {
            val now = nowSec()
            val payload = SpamSettingsPayload(
                enabled = config.enabled,
                threshold = config.threshold,
                heuristicWeight = config.heuristicWeight,
                bayesianWeight = config.bayesianWeight,
                layerHeuristicsEnabled = config.layerHeuristicsEnabled,
                layerBlocklistEnabled = config.layerBlocklistEnabled,
                layerBayesianEnabled = config.layerBayesianEnabled,
                blockRemoteImages = config.blockRemoteImages,
                imageAllowedSenders = config.imageAllowedSenders,
                imageAllowedDomains = config.imageAllowedDomains,
                imagesAllowedForContacts = config.imagesAllowedForContacts,
                blockedDomains = personal.blockedDomains.toList(),
                allowedDomains = personal.allowedDomains.toList(),
                blockedSenders = personal.blockedSenders.toList(),
                allowedSenders = personal.allowedSenders.toList(),
                updatedAt = now
            )
            val ciphertext = PrivateStorageCrypto.encryptToSelf(
                json.encodeToString(SpamSettingsPayload.serializer(), payload), priv
            )
            nostrRepository.publishPrivateStorage(SPAM_SETTINGS_D_TAG, ciphertext).getOrThrow()
            // Record the created_at we just published so our own relay echo is
            // treated as "already seen" and not re-applied (echo suppression).
            preferencesManager.setSpamSettingsLastSeenAt(now)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "saveSpamSettings failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Apply the personal block/allow lists from an inbound (newer, LWW-passed)
     * spam-settings snapshot. Payloads written by older builds carry empty
     * lists — those are a no-op so a stale device can't wipe local rules.
     */
    private suspend fun applyInboundPersonalSpamRules(
        ownerNpub: String,
        payload: SpamSettingsPayload
    ) {
        val hasAny = payload.blockedDomains.isNotEmpty() || payload.allowedDomains.isNotEmpty() ||
            payload.blockedSenders.isNotEmpty() || payload.allowedSenders.isNotEmpty()
        if (!hasAny) return
        personalSpamRuleDao.clearForOwner(ownerNpub)
        val now = nowSec()
        val rows = buildList {
            payload.blockedDomains.forEach { add(rule(ownerNpub, BLOCK_DOMAIN, it, now)) }
            payload.allowedDomains.forEach { add(rule(ownerNpub, ALLOW_DOMAIN, it, now)) }
            payload.blockedSenders.forEach { add(rule(ownerNpub, BLOCK_SENDER, it, now)) }
            payload.allowedSenders.forEach { add(rule(ownerNpub, ALLOW_SENDER, it, now)) }
        }
        personalSpamRuleDao.upsertAll(rows)
    }

    private fun rule(ownerNpub: String, type: String, value: String, now: Long) =
        xyz.desent.data.local.database.entity.PersonalSpamRuleEntity(
            ownerNpub = ownerNpub,
            type = type,
            value = value.lowercase().trim(),
            updatedAt = now
        )

    // ------------------------------------------------------------------
    // Spam tokens (`desent:spam-tokens:<i>` + `desent:spam-tokens:manifest`)
    // ------------------------------------------------------------------

    override suspend fun snapshotAndPublishTokens(force: Boolean): Result<Unit> {
        val priv = selfPriv() ?: return Result.failure(Exception("No active identity"))
        val ownerNpub = activeOwnerNpub()
            ?: return Result.failure(Exception("No active identity"))

        return try {
            val tokens = bayesianTokenDao.getAllForOwner(ownerNpub)
            val currentSum = tokens.sumOf { (it.spamCount + it.hamCount).toLong() }
            val lastSum = preferencesManager.spamTokensLastSum.first()

            // Dirty check: counts are monotonic (bumpCounts only increments,
            // upsertMax only increases), so a higher sum means new local state
            // worth publishing. Skip the relay round-trip otherwise.
            if (!force && currentSum <= lastSum) {
                Log.d(TAG, "snapshotAndPublishTokens: not dirty ($currentSum <= $lastSum), skipping")
                return Result.success(Unit)
            }

            val entries = tokens.map { SpamTokenEntry(h = it.tokenHash, s = it.spamCount, m = it.hamCount) }
            val publishShards = entries.chunked(MAX_SHARD_TOKENS).take(MAX_SHARDS)
            val publishedCount = publishShards.size
            if (entries.size > publishedCount * MAX_SHARD_TOKENS) {
                Log.w(TAG, "Token corpus (${entries.size}) exceeds ${MAX_SHARDS} shard cap; truncated")
            }

            for ((index, shardEntries) in publishShards.withIndex()) {
                val shard = SpamTokenShard(index = index, entries = shardEntries)
                val ct = PrivateStorageCrypto.encryptToSelf(
                    json.encodeToString(SpamTokenShard.serializer(), shard), priv
                )
                nostrRepository.publishPrivateStorage(tokenShardDTag(index), ct)
            }

            // Manifest so readers know how many shards to reassemble.
            val manifest = SpamTokenManifest(
                shardCount = publishedCount,
                totalTokens = entries.size,
                updatedAt = nowSec()
            )
            val manifestCt = PrivateStorageCrypto.encryptToSelf(
                json.encodeToString(SpamTokenManifest.serializer(), manifest), priv
            )
            nostrRepository.publishPrivateStorage(SPAM_TOKENS_MANIFEST_D_TAG, manifestCt)

            // Tombstone shards beyond the new count (corpus shrank) so a
            // restoring device isn't left waiting for stale shards.
            val prevShardCount = preferencesManager.spamTokensShardCount.first()
            for (i in publishedCount until prevShardCount) {
                runCatching { nostrRepository.publishPrivateStorage(tokenShardDTag(i), "") }
            }

            preferencesManager.setSpamTokensLastSum(currentSum)
            preferencesManager.setSpamTokensShardCount(publishedCount)
            Log.d(TAG, "snapshotAndPublishTokens: published $publishedCount shards, ${entries.size} tokens")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "snapshotAndPublishTokens failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun subscribeToOwnPrivateStorage(): Result<Unit> = try {
        nostrRepository.subscribeToOwnPrivateStorage()
        Result.success(Unit)
    } catch (e: Exception) {
        Log.e(TAG, "subscribeToOwnPrivateStorage failed: ${e.message}", e)
        Result.failure(e)
    }

    // ------------------------------------------------------------------
    // PGP key (`desent:pgp`)
    // ------------------------------------------------------------------

    /** LWW watermark for the pgp 30078 namespace (0 = nothing seen yet). */
    private val pgpLastSeenCreatedAt = MutableStateFlow(0L)

    private val _pgpKeyFlow = MutableStateFlow<PgpKeyPayload?>(null)
    override val pgpKeyFlow: StateFlow<PgpKeyPayload?> = _pgpKeyFlow.asStateFlow()

    override suspend fun savePgpKey(payload: PgpKeyPayload): Result<Unit> {
        val priv = selfPriv() ?: return Result.failure(Exception("No active identity"))
        return try {
            val ciphertext = PrivateStorageCrypto.encryptToSelf(
                json.encodeToString(PgpKeyPayload.serializer(), payload),
                priv
            )
            nostrRepository.publishPrivateStorage(PGP_D_TAG, ciphertext).getOrThrow()
            // Treat our own publish as seen so the relay echo doesn't race the
            // UI state (the echo would apply the identical payload anyway).
            pgpLastSeenCreatedAt.value = nowSec()
            _pgpKeyFlow.value = payload
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "savePgpKey failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun removePgpKey(): Result<Unit> {
        return try {
            nostrRepository.publishPrivateStorage(PGP_D_TAG, "").getOrThrow()
            pgpLastSeenCreatedAt.value = nowSec()
            _pgpKeyFlow.value = null
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "removePgpKey failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------
    // Inbound (called by NostrEventProcessor for kind 30078)
    // ------------------------------------------------------------------

    /**
     * Decrypt and cache a kind-30078 event authored by the active user.
     * Empty content is a tombstone → local row deleted. Defence-in-depth: only
     * processes events whose author is the active identity (the relay already
     * owner-scopes reads, so we should only ever see our own events here).
     */
    suspend fun onInboundPrivateStorageEvent(event: GenericEvent) {
        val authorHex = event.pubKey.toHexString()
        val activeHex = secureKeyManager.getIdentityFromStoredNSEC().getOrNull()?.publicKey?.toHexString()
        if (activeHex == null || authorHex != activeHex) {
            Log.d(TAG, "onInbound: skipping 30078 not authored by active user (${authorHex.take(8)})")
            return
        }

        val ownerNpub = Bech32Utils.hexToNpub(authorHex)
        val dTag = event.tags.firstOrNull { it.getCode() == "d" }
            ?.let { (it as? nostr.event.tag.GenericTag)?.getParams()?.firstOrNull() }
            ?: return

        // Mail namespaces: empty content is a READ no-op (unlike notes/
        // contacts/pgp). An emptied shard exists only as the relay's
        // hard-delete of that row — local state converges via entries, never
        // via tombstones. Exact-match the manifest before prefix-matching
        // shards (`desent:mail-state:manifest` is informational).
        if (dTag == MailStateKeys.FOLDERS_D_TAG || MailStateKeys.isStateShardDTag(dTag)) {
            if (event.content.isNullOrEmpty()) return
            val priv = selfPriv() ?: return
            val plaintextMail = runCatching {
                PrivateStorageCrypto.decryptFromSelf(event.content, priv)
            }.getOrNull() ?: return
            runCatching {
                when {
                    dTag == MailStateKeys.FOLDERS_D_TAG ->
                        mailFolderRepository?.onInboundManifest(plaintextMail, event.createdAt, ownerNpub)
                    dTag == MailStateKeys.STATE_MANIFEST_D_TAG ->
                        Log.d(TAG, "onInbound: mail-state manifest received (informational)")
                    else ->
                        mailFolderRepository?.onInboundShard(plaintextMail, ownerNpub)
                }
            }.onFailure { Log.w(TAG, "onInbound: mail namespace $dTag failed: ${it.message}") }
            return
        }

        // Tombstone: relay hard-deleted the prior version; mirror locally.
        if (event.content.isNullOrEmpty()) {
            when {
                dTag.startsWith(NOTE_PREFIX) -> {
                    val id = dTag.removePrefix(NOTE_PREFIX)
                    noteDao.deleteNote(ownerNpub, id)
                    Log.d(TAG, "onInbound: tombstone deleted note $id")
                }
                dTag.startsWith(FILE_PREFIX) -> {
                    val sha = dTag.removePrefix(FILE_PREFIX)
                    userFileDao.deleteFile(ownerNpub, sha)
                    Log.d(TAG, "onInbound: tombstone deleted user file $sha")
                }
                dTag == CONTACTS_D_TAG -> {
                    contactsDao.deleteContacts(ownerNpub)
                    Log.d(TAG, "onInbound: tombstone deleted contacts")
                }
                dTag == PGP_D_TAG -> {
                    if (event.createdAt > pgpLastSeenCreatedAt.value) {
                        pgpLastSeenCreatedAt.value = event.createdAt
                        _pgpKeyFlow.value = null
                    }
                    Log.d(TAG, "onInbound: tombstone deleted pgp key")
                }
            }
            return
        }

        val priv = selfPriv() ?: return
        val plaintext = try {
            PrivateStorageCrypto.decryptFromSelf(event.content, priv)
        } catch (e: Exception) {
            Log.w(TAG, "onInbound: decrypt failed for d=$dTag: ${e.message}")
            // END-23 §3 (ANDROID_USER_FILES.md): for user files a decrypt
            // failure is treated as a tombstone — the entry can no longer be
            // read with this account's key, so drop the local row.
            if (dTag.startsWith(FILE_PREFIX)) {
                val sha = dTag.removePrefix(FILE_PREFIX)
                userFileDao.deleteFile(ownerNpub, sha)
                Log.d(TAG, "onInbound: decrypt failure tombstoned user file $sha")
            }
            return
        }

        when {
            dTag == PGP_D_TAG -> {
                val payload = json.decodeFromString(PgpKeyPayload.serializer(), plaintext)
                // LWW: replaceable event — ignore anything not strictly newer
                // than the newest created_at we've seen (self-echo of our own
                // save is already reflected in the watermark).
                if (event.createdAt > pgpLastSeenCreatedAt.value) {
                    pgpLastSeenCreatedAt.value = event.createdAt
                    _pgpKeyFlow.value = payload
                    Log.d(TAG, "onInbound: applied pgp key (createdAt=${event.createdAt})")
                }
            }
            dTag == SPAM_SETTINGS_D_TAG -> {
                val payload = json.decodeFromString(SpamSettingsPayload.serializer(), plaintext)
                val lastSeen = preferencesManager.spamSettingsLastSeenAt.first()
                // Last-write-wins + self-echo suppression: ignore anything not
                // strictly newer than the newest created_at we've seen (whether
                // published by us or received from another device).
                if (event.createdAt > lastSeen) {
                    preferencesManager.setSpamFilterConfig(
                        SpamFilterConfig(
                            enabled = payload.enabled,
                            threshold = payload.threshold,
                            heuristicWeight = payload.heuristicWeight,
                            bayesianWeight = payload.bayesianWeight,
                            layerHeuristicsEnabled = payload.layerHeuristicsEnabled,
                            layerBlocklistEnabled = payload.layerBlocklistEnabled,
                            layerBayesianEnabled = payload.layerBayesianEnabled,
                            blockRemoteImages = payload.blockRemoteImages,
                            imageAllowedSenders = payload.imageAllowedSenders,
                            imageAllowedDomains = payload.imageAllowedDomains,
                            imagesAllowedForContacts = payload.imagesAllowedForContacts
                        )
                    )
                    applyInboundPersonalSpamRules(ownerNpub, payload)
                    preferencesManager.setSpamSettingsLastSeenAt(event.createdAt)
                    Log.d(TAG, "onInbound: applied spam settings (createdAt=${event.createdAt})")
                } else {
                    Log.d(TAG, "onInbound: stale spam settings ($event.createdAt <= $lastSeen), ignored")
                }
            }
            dTag == SPAM_TOKENS_MANIFEST_D_TAG -> {
                // Informational: token shards merge per-shard under element-wise
                // max; there is no last-write-wins for the manifest itself.
                Log.d(TAG, "onInbound: token manifest received")
            }
            dTag.startsWith(SPAM_TOKENS_SHARD_PREFIX) -> {
                val shard = json.decodeFromString(SpamTokenShard.serializer(), plaintext)
                for (entry in shard.entries) {
                    bayesianTokenDao.upsertMax(entry.h, ownerNpub, entry.s, entry.m)
                }
                Log.d(TAG, "onInbound: merged token shard ${shard.index} (${shard.entries.size} entries)")
            }
            dTag.startsWith(NOTE_PREFIX) -> {
                val id = dTag.removePrefix(NOTE_PREFIX)
                val payload = json.decodeFromString(NotePayload.serializer(), plaintext)
                noteDao.upsertNote(
                    xyz.desent.data.local.database.entity.PrivateNoteEntity(
                        id = id,
                        ownerNpub = ownerNpub,
                        title = payload.title,
                        body = payload.body,
                        updatedAt = payload.updated_at,
                        folder = payload.folder,
                        attachmentsJson = json.encodeToString(payload.attachments),
                        dTag = dTag,
                        createdAt = event.createdAt
                    )
                )
                Log.d(TAG, "onInbound: upserted note $id (updatedAt=${payload.updated_at})")
            }
            dTag.startsWith(FILE_PREFIX) -> {
                // Keyed by the sha256 suffix of the d tag — the payload
                // carries no sha field (END-23 v1).
                val sha = dTag.removePrefix(FILE_PREFIX)
                val payload = json.decodeFromString(UserFilePayload.serializer(), plaintext)
                userFileDao.upsertFile(
                    UserFileEntity(
                        sha256 = sha,
                        ownerNpub = ownerNpub,
                        filename = payload.filename,
                        mimeType = payload.mimeType,
                        size = payload.size,
                        keyHex = payload.keyHex,
                        nonceHex = payload.nonceHex,
                        uploadedAt = payload.uploadedAt,
                        blurhash = payload.blurhash,
                        width = payload.width,
                        height = payload.height,
                        dTag = dTag
                    )
                )
                Log.d(TAG, "onInbound: upserted user file ${payload.filename} (${sha.take(12)})")
            }
            dTag == CONTACTS_D_TAG -> {
                val contacts = mapper.contactsToDomain(
                    PrivateContactsEntity(ownerNpub, plaintext, nowSec(), dTag, event.createdAt)
                )
                // Re-serialise through the mapper to canonicalise, then cache.
                contactsDao.upsertContacts(
                    PrivateContactsEntity(
                        ownerNpub = ownerNpub,
                        contactsJson = mapper.contactsToJson(contacts),
                        updatedAt = event.createdAt,
                        dTag = dTag,
                        createdAt = event.createdAt
                    )
                )
                Log.d(TAG, "onInbound: upserted contacts (${contacts.size} entries)")
            }
        }
    }

    companion object {
        private const val TAG = "PrivateStorageRepo"
        private const val NOTE_PREFIX = "desent:note:"
        internal const val FILE_PREFIX = "desent:file:"
        internal const val CONTACTS_D_TAG = "desent:contacts"

        // Spam namespaces (kind 30078, self-encrypted).
        internal const val SPAM_SETTINGS_D_TAG = "desent:spam-settings"
        internal const val SPAM_TOKENS_MANIFEST_D_TAG = "desent:spam-tokens:manifest"
        internal const val SPAM_TOKENS_SHARD_PREFIX = "desent:spam-tokens:"
        private const val BLOCK_DOMAIN = xyz.desent.data.local.database.entity.PersonalSpamRuleEntity.TYPE_BLOCK_DOMAIN
        private const val ALLOW_DOMAIN = xyz.desent.data.local.database.entity.PersonalSpamRuleEntity.TYPE_ALLOW_DOMAIN
        private const val BLOCK_SENDER = xyz.desent.data.local.database.entity.PersonalSpamRuleEntity.TYPE_BLOCK_SENDER
        private const val ALLOW_SENDER = xyz.desent.data.local.database.entity.PersonalSpamRuleEntity.TYPE_ALLOW_SENDER

        /** PGP identity namespace (ANDROID_PGP.md §2.1 custody contract). */
        internal const val PGP_D_TAG = "desent:pgp"
        private const val MAX_SHARD_TOKENS = 600
        private const val MAX_SHARDS = 16

        internal fun tokenShardDTag(index: Int): String = "$SPAM_TOKENS_SHARD_PREFIX$index"
        internal fun isTokenShardDTag(dTag: String): Boolean =
            dTag.startsWith(SPAM_TOKENS_SHARD_PREFIX) && dTag != SPAM_TOKENS_MANIFEST_D_TAG

    }
}
