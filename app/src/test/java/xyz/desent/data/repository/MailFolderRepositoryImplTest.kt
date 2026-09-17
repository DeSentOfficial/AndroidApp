package xyz.desent.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.Nip44Encryption
import xyz.desent.crypto.PrivateStorageCrypto
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.database.dao.EmailDao
import xyz.desent.data.local.database.dao.MailFolderDao
import xyz.desent.data.local.database.dao.MailStateDao
import xyz.desent.data.local.database.entity.MailFolderManifestEntity
import xyz.desent.data.local.database.entity.MailShardStateEntity
import xyz.desent.data.local.database.entity.MailStateEntity
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.model.MailFolder
import xyz.desent.domain.model.MailFoldersCodec
import xyz.desent.domain.model.MailStateEntry
import xyz.desent.domain.model.MailStateKeys
import xyz.desent.domain.model.MailStateShard
import nostr.id.Identity

/**
 * Mail-folder overlay sync engine (refs/FROM_email.desent.xyz/
 * ANDROID_MAIL_FOLDERS.md): whole-manifest LWW publish, per-entry LWW shard
 * merges with echo no-ops, debounced-flush publish/prune/tombstone rules.
 */
class MailFolderRepositoryImplTest {

    private val privHex = "d5ca9d8c6a7e8f2b1c3d4e5f60718293a4b5c6d7e8f9012233445566778899aa"
    private val priv = Nip44Encryption.hexToBytes(privHex)
    private val identity = Identity.create(privHex)
    private val ownerNpub = Bech32Utils.hexToNpub(identity.publicKey.toHexString())

    private lateinit var mailFolderDao: MailFolderDao
    private lateinit var mailStateDao: MailStateDao
    private lateinit var emailDao: EmailDao
    private lateinit var secureKeyManager: SecureKeyManager
    private lateinit var nostrRepo: NostrRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var repo: MailFolderRepositoryImpl

    @Before
    fun setUp() {
        mailFolderDao = mockk(relaxed = true)
        mailStateDao = mockk(relaxed = true)
        emailDao = mockk(relaxed = true)
        nostrRepo = mockk(relaxed = true)
        secureKeyManager = mockk()
        preferencesManager = mockk(relaxed = true)
        coEvery { secureKeyManager.getIdentityFromStoredNSEC() } returns Result.success(identity)
        every { preferencesManager.mailFoldersLastSeenAt } returns flowOf(0L)

        repo = MailFolderRepositoryImpl(
            mailFolderDao = mailFolderDao,
            mailStateDao = mailStateDao,
            emailDao = emailDao,
            secureKeyManager = secureKeyManager,
            nostrRepository = nostrRepo,
            preferencesManager = preferencesManager,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
    }

    private fun manifestWith(vararg folders: MailFolder) =
        MailFoldersCodec.encode(folders.toList(), 100L)

    private fun storedFolder(id: String, name: String, parent: String? = null): MailFolder {
        val entry = MailFoldersCodec.newEntry(id, name, parent)
        return MailFolder(id, name, parent, null, 0, entry)
    }

    // ------------------------------------------------------------------
    // Folder CRUD — whole-manifest republish
    // ------------------------------------------------------------------

    @Test
    fun createFolder_publishesManifest_andCachesIt() = runBlocking {
        coEvery { mailFolderDao.getManifest(ownerNpub) } returns null
        val ctSlot = slot<String>()
        coEvery { nostrRepo.publishPrivateStorage(any(), capture(ctSlot)) } returns Result.success(Unit)

        val result = repo.createFolder(ownerNpub, "Invoices")

        assertTrue(result.isSuccess)
        coVerify { nostrRepo.publishPrivateStorage(MailStateKeys.FOLDERS_D_TAG, ctSlot.captured) }
        coVerify { preferencesManager.setMailFoldersLastSeenAt(any()) }

        val decoded = MailFoldersCodec.decode(
            PrivateStorageCrypto.decryptFromSelf(ctSlot.captured, priv)
        )
        assertEquals(listOf("Invoices"), decoded.map { it.name })
        assertTrue(decoded.single().id.matches(Regex("f_[0-9a-f]{10}")))
        coVerify { mailFolderDao.upsertManifest(any()) }
    }

    @Test
    fun createFolder_rejectsBlankOrSlashyNames() = runBlocking {
        assertTrue(repo.createFolder(ownerNpub, "  ").isFailure)
        assertTrue(repo.createFolder(ownerNpub, "Work/Projects").isFailure)
        coVerify(exactly = 0) { nostrRepo.publishPrivateStorage(any(), any()) }
    }

    @Test
    fun renameFolder_preservesUnknownKeysVerbatim() = runBlocking {
        // Simulate a manifest authored by the web client with an unknown key.
        val inbound = """
            {"folders":[{"id":"f_1a2b3c4d5e","name":"Old","parent":null,
              "color":null,"sort":2,"sync_rev":7}],"updated_at":50}
        """.trimIndent()
        coEvery { mailFolderDao.getManifest(ownerNpub) } returns MailFolderManifestEntity(
            ownerNpub = ownerNpub, manifestJson = inbound, updatedAt = 50L,
            dTag = MailStateKeys.FOLDERS_D_TAG, createdAt = 50L
        )
        val ctSlot = slot<String>()
        coEvery { nostrRepo.publishPrivateStorage(any(), capture(ctSlot)) } returns Result.success(Unit)

        val result = repo.renameFolder(ownerNpub, "f_1a2b3c4d5e", "New")

        assertTrue(result.isSuccess)
        val plaintext = PrivateStorageCrypto.decryptFromSelf(ctSlot.captured, priv)
        assertTrue(plaintext.contains("\"name\":\"New\""))
        assertTrue(plaintext.contains("\"sync_rev\":7"))
        assertTrue(plaintext.contains("\"sort\":2"))
    }

    @Test
    fun deleteFolder_clearsAssignmentsViaUnfileTombstones() = runBlocking {
        val folder = storedFolder("f_deadbeef01", "Old")
        coEvery { mailFolderDao.getManifest(ownerNpub) } returns MailFolderManifestEntity(
            ownerNpub = ownerNpub, manifestJson = manifestWith(folder), updatedAt = 100L,
            dTag = MailStateKeys.FOLDERS_D_TAG, createdAt = 100L
        )
        coEvery { nostrRepo.publishPrivateStorage(any(), any()) } returns Result.success(Unit)
        val key = "mid-1@example.com"
        coEvery { mailStateDao.getKeysInFolder(ownerNpub, "f_deadbeef01") } returns listOf(key)
        coEvery { mailStateDao.getByKey(ownerNpub, key) } returns MailStateEntity(
            ownerNpub = ownerNpub, folderKey = key, folderId = "f_deadbeef01", isRead = true, ts = 500L
        )
        val tsSlot = slot<Long>()
        coEvery {
            mailStateDao.upsertLww(ownerNpub, key, null, any(), capture(tsSlot))
        } returns 1

        val result = repo.deleteFolder(ownerNpub, "f_deadbeef01")

        assertTrue(result.isSuccess)
        // Local user stamp must beat the previous ts (spec: max(now, prev+1)).
        assertTrue(tsSlot.captured >= 501L)
        coVerify { mailStateDao.upsertShardState(MailShardStateEntity(ownerNpub, MailStateKeys.shardOf(key), true, true)) }
        // Read flag survives the unfile.
        coVerify { mailStateDao.upsertLww(ownerNpub, key, null, true, tsSlot.captured) }
    }

    // ------------------------------------------------------------------
    // Per-message state
    // ------------------------------------------------------------------

    @Test
    fun moveToFolder_stampsWinningTs_andMarksShardDirty() = runBlocking {
        val key = "mid-2@example.com"
        coEvery { mailStateDao.getByKey(ownerNpub, key) } returns MailStateEntity(
            ownerNpub, key, null, isRead = false, ts = 1_000_000_000L
        )
        val tsSlot = slot<Long>()
        coEvery { mailStateDao.upsertLww(ownerNpub, key, "f_target", any(), capture(tsSlot)) } returns 1

        val result = repo.moveToFolder(ownerNpub, key, "f_target")

        assertTrue(result.isSuccess)
        assertTrue(tsSlot.captured > 1_000_000_000L)
        val shard = MailStateKeys.shardOf(key)
        coVerify { mailStateDao.upsertShardState(MailShardStateEntity(ownerNpub, shard, dirty = true, published = true)) }
    }

    @Test
    fun moveToFolder_isNoOp_whenAlreadyFiledThere() = runBlocking {
        val key = "mid-3@example.com"
        coEvery { mailStateDao.getByKey(ownerNpub, key) } returns MailStateEntity(
            ownerNpub, key, "f_same", isRead = false, ts = 10L
        )

        val result = repo.moveToFolder(ownerNpub, key, "f_same")

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { mailStateDao.upsertLww(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { mailStateDao.upsertShardState(any()) }
    }

    @Test
    fun setRead_recordsRead_andWritesThroughToEmails() = runBlocking {
        val key = "mid-4@example.com"
        coEvery { mailStateDao.getByKey(ownerNpub, key) } returns null
        val tsSlot = slot<Long>()
        coEvery { mailStateDao.upsertLww(ownerNpub, key, null, true, capture(tsSlot)) } returns 1

        repo.setRead(ownerNpub, key, read = true)

        assertTrue(tsSlot.captured > 0L)
        coVerify { mailStateDao.applyReadToEmails(ownerNpub, key, true) }
        coVerify { mailStateDao.upsertShardState(any()) }
    }

    @Test
    fun setRead_skipsWhenNothingToRecord() = runBlocking {
        val key = "mid-5@example.com"
        coEvery { mailStateDao.getByKey(ownerNpub, key) } returns null

        repo.setRead(ownerNpub, key, read = false)

        // Absent == unread: no entry, no dirty shard, no write-through.
        coVerify(exactly = 0) { mailStateDao.upsertLww(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { mailStateDao.applyReadToEmails(any(), any(), any()) }
        coVerify(exactly = 0) { mailStateDao.upsertShardState(any()) }
    }

    @Test
    fun markThreadRead_stampsEveryThreadRow() = runBlocking {
        val row1 = xyz.desent.data.local.database.entity.EmailEntity(
            id = "ev1", recipientNpub = ownerNpub, senderEmail = "a@b.c", senderDomain = "b.c",
            subject = "s", content = "c", dkimStatus = "NONE", emailType = "OTHER", bridge = "email",
            messageId = "mid-a@b.c", threadToken = null, createdAt = 0L
        )
        val row2 = row1.copy(id = "ev2", messageId = null)
        coEvery { emailDao.getThreadRows(ownerNpub, "th") } returns listOf(row1, row2)
        coEvery { mailStateDao.getByKey(any(), any()) } returns null
        coEvery { mailStateDao.upsertLww(any(), any(), any(), any(), any()) } returns 1

        repo.markThreadRead(ownerNpub, "th")

        coVerify { mailStateDao.upsertLww(ownerNpub, "mid-a@b.c", null, true, any()) }
        coVerify { mailStateDao.upsertLww(ownerNpub, "ev:ev2", null, true, any()) }
    }

    // ------------------------------------------------------------------
    // Flush — publish / prune / tombstone
    // ------------------------------------------------------------------

    @Test
    fun flush_publishesDirtyShardEncrypted_andClearsDirty() = runBlocking {
        val key = "mid-6@example.com"
        val shardIndex = MailStateKeys.shardOf(key)
        coEvery { mailStateDao.getShardStates(ownerNpub) } returns listOf(
            MailShardStateEntity(ownerNpub, shardIndex, dirty = true, published = true)
        )
        coEvery { mailStateDao.getAll(ownerNpub) } returns listOf(
            MailStateEntity(ownerNpub, key, "f_x", isRead = true, ts = System.currentTimeMillis() / 1000)
        )
        val ctSlot = slot<String>()
        coEvery { nostrRepo.publishPrivateStorage(any(), capture(ctSlot)) } returns Result.success(Unit)

        val result = repo.flush(force = false)

        assertTrue(result.isSuccess)
        coVerify { nostrRepo.publishPrivateStorage(MailStateKeys.shardDTag(shardIndex), ctSlot.captured) }
        coVerify {
            mailStateDao.upsertShardState(MailShardStateEntity(ownerNpub, shardIndex, dirty = false, published = true))
        }

        val shard = Json { ignoreUnknownKeys = true }.decodeFromString(
            MailStateShard.serializer(),
            PrivateStorageCrypto.decryptFromSelf(ctSlot.captured, priv)
        )
        assertEquals(shardIndex, shard.index)
        assertEquals(listOf(MailStateEntry(k = key, f = "f_x", r = 1, ts = shard.entries.single().ts)), shard.entries)
    }

    @Test
    fun flush_prunesEntriesOlderThan400Days() = runBlocking {
        val key = "mid-old@example.com"
        val shardIndex = MailStateKeys.shardOf(key)
        val ancient = System.currentTimeMillis() / 1000 - (401L * 24 * 60 * 60)
        coEvery { mailStateDao.getShardStates(ownerNpub) } returns listOf(
            MailShardStateEntity(ownerNpub, shardIndex, dirty = true, published = true)
        )
        coEvery { mailStateDao.getAll(ownerNpub) } returns listOf(
            MailStateEntity(ownerNpub, key, "f_x", true, ts = ancient)
        )
        coEvery { nostrRepo.publishPrivateStorage(any(), any()) } returns Result.success(Unit)

        repo.flush(force = false)

        // No live entries remain → empty-content tombstone + bookkeeping drop.
        coVerify { nostrRepo.publishPrivateStorage(MailStateKeys.shardDTag(shardIndex), "") }
        coVerify { mailStateDao.deleteShardState(ownerNpub, shardIndex) }
    }

    @Test
    fun flush_isNoOpWhenNothingDirty() = runBlocking {
        coEvery { mailStateDao.getShardStates(ownerNpub) } returns emptyList()

        val result = repo.flush(force = false)

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { nostrRepo.publishPrivateStorage(any(), any()) }
    }

    // ------------------------------------------------------------------
    // Inbound — LWW merges + echo suppression
    // ------------------------------------------------------------------

    @Test
    fun onInboundShard_appliesEntries_andMarksDirtyWhenChanged() = runBlocking {
        val shard = MailStateShard(
            index = 3,
            entries = listOf(MailStateEntry(k = "k1", f = "f_1", r = 1, ts = 900L))
        )
        coEvery { mailStateDao.upsertLww(ownerNpub, "k1", "f_1", true, 900L) } returns 1

        repo.onInboundShard(Json.encodeToString(MailStateShard.serializer(), shard), ownerNpub)

        coVerify { mailStateDao.applyReadToEmails(ownerNpub, "k1", true) }
        coVerify { mailStateDao.upsertShardState(MailShardStateEntity(ownerNpub, 3, dirty = true, published = true)) }
    }

    @Test
    fun onInboundShard_echoIsANoOp() = runBlocking {
        // The DAO's ts-guard rejects our own echo (0 rows affected).
        coEvery { mailStateDao.upsertLww(any(), any(), any(), any(), any()) } returns 0

        val shard = MailStateShard(
            index = 3,
            entries = listOf(MailStateEntry(k = "k1", f = "f_1", r = 1, ts = 900L))
        )
        repo.onInboundShard(Json.encodeToString(MailStateShard.serializer(), shard), ownerNpub)

        coVerify(exactly = 0) { mailStateDao.applyReadToEmails(any(), any(), any()) }
        coVerify(exactly = 0) { mailStateDao.upsertShardState(any()) }
    }

    @Test
    fun onInboundManifest_appliesNewer_andSuppressesStaleEcho() = runBlocking {
        val manifest = MailFoldersCodec.encode(
            listOf(storedFolder("f_1a2b3c4d5e", "Invoices")), 555L
        )

        repo.onInboundManifest(manifest, createdAt = 555L, ownerNpub = ownerNpub)
        coVerify { mailFolderDao.upsertManifest(any()) }
        coVerify { preferencesManager.setMailFoldersLastSeenAt(555L) }

        // A stale re-delivery is ignored.
        every { preferencesManager.mailFoldersLastSeenAt } returns flowOf(1000L)
        repo.onInboundManifest(manifest, createdAt = 555L, ownerNpub = ownerNpub)
        coVerify(exactly = 1) { mailFolderDao.upsertManifest(any()) }
    }

    @Test
    fun applyOnArrival_appliesPreExistingReadState() = runBlocking {
        val key = "mid-7@example.com"
        coEvery { mailStateDao.getByKey(ownerNpub, key) } returns MailStateEntity(
            ownerNpub, key, "f_1", isRead = true, ts = 10L
        )

        val effectiveRead = repo.applyOnArrival(ownerNpub, key, isReadDefault = false)

        assertEquals(true, effectiveRead)
        coVerify { mailStateDao.applyReadToEmails(ownerNpub, key, true) }
    }

    @Test
    fun applyOnArrival_noEntry_keepsDefault() = runBlocking {
        coEvery { mailStateDao.getByKey(ownerNpub, "k") } returns null
        assertEquals(false, repo.applyOnArrival(ownerNpub, "k", isReadDefault = false))
        coVerify(exactly = 0) { mailStateDao.applyReadToEmails(any(), any(), any()) }
    }
}
