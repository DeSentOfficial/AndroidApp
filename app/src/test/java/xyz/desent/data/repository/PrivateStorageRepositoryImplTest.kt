package xyz.desent.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nostr.event.impl.GenericEvent
import nostr.event.tag.GenericTag
import nostr.id.Identity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.Nip44Encryption
import xyz.desent.crypto.PrivateStorageCrypto
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.database.dao.PrivateContactsDao
import xyz.desent.data.local.database.dao.PrivateNoteDao
import xyz.desent.data.local.database.entity.BayesianTokenEntity
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.mapper.PrivateStorageMapper
import xyz.desent.data.nostr.NostrKinds
import xyz.desent.domain.model.PrivateContact
import xyz.desent.domain.model.PrivateNote
import xyz.desent.domain.model.SpamFilterConfig
import xyz.desent.domain.model.SpamSettingsPayload
import xyz.desent.domain.model.SpamTokenEntry
import xyz.desent.domain.model.SpamTokenShard

class PrivateStorageRepositoryImplTest {

    private val privHex = "d5ca9d8c6a7e8f2b1c3d4e5f60718293a4b5c6d7e8f9012233445566778899aa"
    private val priv = Nip44Encryption.hexToBytes(privHex)
    private val identity = Identity.create(privHex)
    private val ownerNpub = Bech32Utils.hexToNpub(identity.publicKey.toHexString())

    private lateinit var noteDao: PrivateNoteDao
    private lateinit var contactsDao: PrivateContactsDao
    private lateinit var userFileDao: xyz.desent.data.local.database.dao.UserFileDao
    private lateinit var bayesianTokenDao: xyz.desent.data.local.database.dao.BayesianTokenDao
    private lateinit var personalSpamRuleDao: xyz.desent.data.local.database.dao.PersonalSpamRuleDao
    private lateinit var preferencesManager: xyz.desent.data.local.preferences.PreferencesManager
    private lateinit var nostrRepo: NostrRepository
    private lateinit var secureKeyManager: SecureKeyManager
    private lateinit var repo: PrivateStorageRepositoryImpl

    @Before
    fun setUp() {
        noteDao = mockk(relaxed = true)
        contactsDao = mockk(relaxed = true)
        userFileDao = mockk(relaxed = true)
        bayesianTokenDao = mockk(relaxed = true)
        personalSpamRuleDao = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        nostrRepo = mockk(relaxed = true)
        secureKeyManager = mockk()
        coEvery { secureKeyManager.getIdentityFromStoredNSEC() } returns Result.success(identity)
        // Default the watermark flows the impl reads on the inbound/publish paths.
        every { preferencesManager.spamSettingsLastSeenAt } returns kotlinx.coroutines.flow.flowOf(0L)
        every { preferencesManager.spamTokensLastSum } returns kotlinx.coroutines.flow.flowOf(0L)
        every { preferencesManager.spamTokensShardCount } returns kotlinx.coroutines.flow.flowOf(0)
        repo = PrivateStorageRepositoryImpl(noteDao, contactsDao, userFileDao, PrivateStorageMapper(), secureKeyManager, nostrRepo, bayesianTokenDao, personalSpamRuleDao, preferencesManager)
    }

    @Test
    fun saveNote_publishesEncryptedKind30078_andCachesLocally() = runBlocking {
        val meta = xyz.desent.domain.model.AttachmentMeta(
            sha256 = "sha1", keyHex = "k", nonceHex = "n",
            mimeType = "image/png", filename = "f.png", size = 9L
        )
        val note = PrivateNote(
            id = "abc", ownerNpub = ownerNpub, title = "Codes", body = "847291",
            updatedAt = 1000L, folder = "Work", attachments = listOf(meta), dTag = "desent:note:abc"
        )
        val cipherSlot = slot<String>()
        coEvery { nostrRepo.publishPrivateStorage(any(), capture(cipherSlot)) } returns Result.success(Unit)

        val result = repo.saveNote(note)
        assertTrue(result.isSuccess)

        coVerify { nostrRepo.publishPrivateStorage("desent:note:abc", cipherSlot.captured) }
        coVerify { noteDao.upsertNote(any()) }

        // The published ciphertext must decrypt back to the note payload JSON.
        val decrypted = PrivateStorageCrypto.decryptFromSelf(cipherSlot.captured, priv)
        val payload = Json { ignoreUnknownKeys = true }.decodeFromString(
            xyz.desent.domain.model.NotePayload.serializer(), decrypted
        )
        assertEquals("Codes", payload.title)
        assertEquals("847291", payload.body)
        assertEquals("Work", payload.folder)
        assertEquals(listOf(meta), payload.attachments)
    }

    @Test
    fun deleteNote_publishesEmptyTombstone_andDeletesLocally() = runBlocking {
        coEvery { nostrRepo.publishPrivateStorage(any(), any()) } returns Result.success(Unit)

        val result = repo.deleteNote(ownerNpub, "abc")
        assertTrue(result.isSuccess)

        coVerify { nostrRepo.publishPrivateStorage("desent:note:abc", "") }
        coVerify { noteDao.deleteNote(ownerNpub, "abc") }
    }

    // ------------------------------------------------------------------
    // User files namespace (`desent:file:<sha256>` — END-23)
    // ------------------------------------------------------------------

    private fun testUserFile() = xyz.desent.domain.model.UserFile(
        sha256 = "cafe1234", filename = "doc.pdf", mimeType = "application/pdf",
        size = 1234L, keyHex = "aa", nonceHex = "bb",
        uploadedAt = 100L, blurhash = "R0AAAAfQfQ", width = 640, height = 480,
        ownerNpub = ownerNpub, dTag = "desent:file:cafe1234"
    )

    @Test
    fun saveUserFile_publishesEncryptedV1Payload_andCachesLocally() = runBlocking {
        val cipherSlot = slot<String>()
        coEvery { nostrRepo.publishPrivateStorage(any(), capture(cipherSlot)) } returns Result.success(Unit)

        val result = repo.saveUserFile(testUserFile())
        assertTrue(result.isSuccess)

        coVerify { nostrRepo.publishPrivateStorage("desent:file:cafe1234", cipherSlot.captured) }
        coVerify { userFileDao.upsertFile(any()) }

        // The published ciphertext must decrypt back to the END-23 v1 payload —
        // no sha on the wire (the d tag carries it), preview fields included.
        val decrypted = PrivateStorageCrypto.decryptFromSelf(cipherSlot.captured, priv)
        val payload = Json { ignoreUnknownKeys = true }.decodeFromString(
            xyz.desent.domain.model.UserFilePayload.serializer(), decrypted
        )
        assertEquals(1, payload.v)
        assertEquals("doc.pdf", payload.filename)
        assertEquals("application/pdf", payload.mimeType)
        assertEquals(1234L, payload.size)
        assertEquals("aa", payload.keyHex)
        assertEquals("bb", payload.nonceHex)
        assertEquals(100L, payload.uploadedAt)
        assertEquals("R0AAAAfQfQ", payload.blurhash)
        assertEquals(640, payload.width)
        assertEquals(480, payload.height)
        assertFalse(decrypted.contains("sha256"))
    }

    @Test
    fun deleteUserFile_publishesEmptyTombstone_andDeletesLocally() = runBlocking {
        coEvery { nostrRepo.publishPrivateStorage(any(), any()) } returns Result.success(Unit)

        val result = repo.deleteUserFile(ownerNpub, "cafe1234")
        assertTrue(result.isSuccess)

        coVerify { nostrRepo.publishPrivateStorage("desent:file:cafe1234", "") }
        coVerify { userFileDao.deleteFile(ownerNpub, "cafe1234") }
    }

    @Test
    fun onInbound_userFileEcho_keysRowByDTagSha() = runBlocking {
        // END-23 v1 payload: no sha field — the row key is the d-tag suffix.
        val payload = xyz.desent.domain.model.UserFilePayload(
            v = 1, filename = "doc.pdf", mimeType = "application/pdf",
            size = 1234L, keyHex = "aa", nonceHex = "bb", uploadedAt = 100L,
            blurhash = "R0AAAAfQfQ", width = 640, height = 480
        )
        val ct = PrivateStorageCrypto.encryptToSelf(
            Json.encodeToString(xyz.desent.domain.model.UserFilePayload.serializer(), payload), priv
        )
        val entitySlot = slot<xyz.desent.data.local.database.entity.UserFileEntity>()

        repo.onInboundPrivateStorageEvent(buildSpamEvent("desent:file:cafe1234", ct, 100L))

        coVerify { userFileDao.upsertFile(capture(entitySlot)) }
        assertEquals("cafe1234", entitySlot.captured.sha256)
        assertEquals(ownerNpub, entitySlot.captured.ownerNpub)
        assertEquals("doc.pdf", entitySlot.captured.filename)
        assertEquals(640, entitySlot.captured.width)
        assertEquals("desent:file:cafe1234", entitySlot.captured.dTag)
    }

    @Test
    fun onInbound_userFileTombstone_deletesRoomRow() = runBlocking {
        repo.onInboundPrivateStorageEvent(buildSpamEvent("desent:file:cafe1234", "", 100L))

        coVerify { userFileDao.deleteFile(ownerNpub, "cafe1234") }
    }

    @Test
    fun onInbound_userFileDecryptFailure_tombstonesRoomRow() = runBlocking {
        // END-23 §3 (ANDROID_USER_FILES.md): a decrypt failure is treated as
        // a tombstone — content that can't be read with this key is dropped.
        repo.onInboundPrivateStorageEvent(buildSpamEvent("desent:file:cafe1234", "not-nip44", 100L))

        coVerify { userFileDao.deleteFile(ownerNpub, "cafe1234") }
    }

    @Test
    fun saveContacts_publishesEncryptedContactsList_andCachesLocally() = runBlocking {
        val contacts = listOf(
            PrivateContact(
                name = "Amazon",
                emails = listOf(xyz.desent.domain.model.ContactEmailAddress("", "support@amazon.com")),
                domain = "amazon.com"
            )
        )
        val cipherSlot = slot<String>()
        coEvery { nostrRepo.publishPrivateStorage(any(), capture(cipherSlot)) } returns Result.success(Unit)

        val result = repo.saveContacts(ownerNpub, contacts)
        assertTrue(result.isSuccess)

        coVerify { nostrRepo.publishPrivateStorage("desent:contacts", cipherSlot.captured) }
        coVerify { contactsDao.upsertContacts(any()) }

        val decrypted = PrivateStorageCrypto.decryptFromSelf(cipherSlot.captured, priv)
        assertTrue(decrypted.contains("support@amazon.com"))
        // v2 wire shape: labeled emails array, no flat email scalar.
        assertTrue(decrypted.contains("\"emails\":["))
        assertEquals(false, decrypted.contains("NBRIDGE"))
    }

    // ------------------------------------------------------------------
    // Spam settings namespace (`desent:spam-settings`)
    // ------------------------------------------------------------------

    @Test
    fun saveSpamSettings_publishesEncryptedConfig() = runBlocking {
        val config = SpamFilterConfig(
            enabled = false, threshold = 7.5,
            heuristicWeight = 0.5, bayesianWeight = 0.5,
            layerHeuristicsEnabled = false, layerBlocklistEnabled = true, layerBayesianEnabled = false
        )
        val cipherSlot = slot<String>()
        coEvery { nostrRepo.publishPrivateStorage(any(), capture(cipherSlot)) } returns Result.success(Unit)

        val result = repo.saveSpamSettings(config)
        assertTrue(result.isSuccess)

        coVerify { nostrRepo.publishPrivateStorage("desent:spam-settings", cipherSlot.captured) }
        coVerify { preferencesManager.setSpamSettingsLastSeenAt(any()) }

        val payload = Json { ignoreUnknownKeys = true }.decodeFromString(
            SpamSettingsPayload.serializer(),
            PrivateStorageCrypto.decryptFromSelf(cipherSlot.captured, priv)
        )
        assertEquals(7.5, payload.threshold, 0.0001)
        assertFalse(payload.enabled)
        assertFalse(payload.layerBayesianEnabled)
    }

    @Test
    fun onInbound_appliesNewerSpamSettings() = runBlocking {
        val payload = SpamSettingsPayload(
            enabled = true, threshold = 9.0,
            heuristicWeight = 0.6, bayesianWeight = 0.4,
            layerHeuristicsEnabled = true, layerBlocklistEnabled = true, layerBayesianEnabled = true,
            updatedAt = 500L
        )
        val ct = PrivateStorageCrypto.encryptToSelf(
            Json.encodeToString(SpamSettingsPayload.serializer(), payload), priv
        )
        val cfgSlot = slot<SpamFilterConfig>()
        coEvery { preferencesManager.setSpamFilterConfig(capture(cfgSlot)) } returns Unit

        repo.onInboundPrivateStorageEvent(buildSpamEvent("desent:spam-settings", ct, 500L))

        coVerify { preferencesManager.setSpamSettingsLastSeenAt(500L) }
        assertEquals(9.0, cfgSlot.captured.threshold, 0.0001)
    }

    @Test
    fun onInbound_skipsStaleSpamSettings() = runBlocking {
        // We've already seen created_at = 1000; an event at 500 is a stale echo.
        every { preferencesManager.spamSettingsLastSeenAt } returns flowOf(1000L)
        val payload = SpamSettingsPayload(
            enabled = true, threshold = 9.0,
            heuristicWeight = 0.6, bayesianWeight = 0.4,
            layerHeuristicsEnabled = true, layerBlocklistEnabled = true, layerBayesianEnabled = true,
            updatedAt = 500L
        )
        val ct = PrivateStorageCrypto.encryptToSelf(
            Json.encodeToString(SpamSettingsPayload.serializer(), payload), priv
        )

        repo.onInboundPrivateStorageEvent(buildSpamEvent("desent:spam-settings", ct, 500L))

        coVerify(exactly = 0) { preferencesManager.setSpamFilterConfig(any()) }
        coVerify(exactly = 0) { preferencesManager.setSpamSettingsLastSeenAt(any()) }
    }

    // ------------------------------------------------------------------
    // Spam tokens namespace (`desent:spam-tokens:*`)
    // ------------------------------------------------------------------

    @Test
    fun snapshotAndPublishTokens_publishesShardAndManifest_whenDirty() = runBlocking {
        val tokens = listOf(
            BayesianTokenEntity(tokenHash = "h1", ownerNpub = ownerNpub, spamCount = 3, hamCount = 1),
            BayesianTokenEntity(tokenHash = "h2", ownerNpub = ownerNpub, spamCount = 2, hamCount = 0)
        )
        coEvery { bayesianTokenDao.getAllForOwner(ownerNpub) } returns tokens
        // lastSum defaults to 0 (setUp) → sum(6) > 0 → dirty.
        val cts = mutableListOf<String>()
        coEvery { nostrRepo.publishPrivateStorage(any(), capture(cts)) } returns Result.success(Unit)

        val result = repo.snapshotAndPublishTokens(force = false)
        assertTrue(result.isSuccess)

        // Shards publish before the manifest → cts[0] is the shard, cts[1] the manifest.
        coVerify { nostrRepo.publishPrivateStorage("desent:spam-tokens:0", cts[0]) }
        coVerify { nostrRepo.publishPrivateStorage("desent:spam-tokens:manifest", cts[1]) }
        coVerify { preferencesManager.setSpamTokensLastSum(6L) }
        coVerify { preferencesManager.setSpamTokensShardCount(1) }

        val shard = Json { ignoreUnknownKeys = true }.decodeFromString(
            SpamTokenShard.serializer(),
            PrivateStorageCrypto.decryptFromSelf(cts[0], priv)
        )
        assertEquals(0, shard.index)
        assertEquals(2, shard.entries.size)
    }

    @Test
    fun snapshotAndPublishTokens_skipsWhenNotDirty() = runBlocking {
        val tokens = listOf(
            BayesianTokenEntity(tokenHash = "h1", ownerNpub = ownerNpub, spamCount = 2, hamCount = 0)
        )
        coEvery { bayesianTokenDao.getAllForOwner(ownerNpub) } returns tokens
        // sum(2) <= lastSum(10) → not dirty → no relay round-trip.
        every { preferencesManager.spamTokensLastSum } returns flowOf(10L)

        val result = repo.snapshotAndPublishTokens(force = false)
        assertTrue(result.isSuccess)

        coVerify(exactly = 0) { nostrRepo.publishPrivateStorage(any(), any()) }
    }

    @Test
    fun snapshotAndPublishTokens_tombstonesShrunkShards() = runBlocking {
        val tokens = listOf(
            BayesianTokenEntity(tokenHash = "h1", ownerNpub = ownerNpub, spamCount = 1, hamCount = 0)
        )
        coEvery { bayesianTokenDao.getAllForOwner(ownerNpub) } returns tokens
        // Previously published 3 shards; the new corpus fits in 1 → tombstone 1 and 2.
        every { preferencesManager.spamTokensShardCount } returns flowOf(3)
        coEvery { nostrRepo.publishPrivateStorage(any(), any()) } returns Result.success(Unit)

        repo.snapshotAndPublishTokens(force = true)

        coVerify { nostrRepo.publishPrivateStorage("desent:spam-tokens:1", "") }
        coVerify { nostrRepo.publishPrivateStorage("desent:spam-tokens:2", "") }
        coVerify { preferencesManager.setSpamTokensShardCount(1) }
    }

    @Test
    fun onInbound_mergesTokenShardViaMax() = runBlocking {
        val shard = SpamTokenShard(
            index = 0,
            entries = listOf(SpamTokenEntry("hashA", 5, 2), SpamTokenEntry("hashB", 0, 3))
        )
        val ct = PrivateStorageCrypto.encryptToSelf(
            Json.encodeToString(SpamTokenShard.serializer(), shard), priv
        )

        repo.onInboundPrivateStorageEvent(buildSpamEvent("desent:spam-tokens:0", ct, 100L))

        coVerify { bayesianTokenDao.upsertMax("hashA", ownerNpub, 5, 2) }
        coVerify { bayesianTokenDao.upsertMax("hashB", ownerNpub, 0, 3) }
    }

    // ---------------------------------------------------------------
    // Mail namespaces (`desent:mail-folders` / `desent:mail-state:*`)
    // ---------------------------------------------------------------

    @Test
    fun onInbound_mailNamespaceTombstone_isReadNoOp() = runBlocking {
        // Empty content in the mail namespaces must NOT delete anything —
        // unlike notes/contacts/pgp. An emptied shard is just the relay's
        // hard-delete of that row; local state converges via entries.
        repo.onInboundPrivateStorageEvent(buildSpamEvent("desent:mail-state:3", "", 100L))
        repo.onInboundPrivateStorageEvent(buildSpamEvent("desent:mail-folders", "", 100L))

        coVerify(exactly = 0) { noteDao.deleteNote(any(), any()) }
        coVerify(exactly = 0) { contactsDao.deleteContacts(any()) }
    }

    @Test
    fun onInbound_routesManifestAndShardsToMailRepo() = runBlocking {
        val mailRepo = mockk<MailFolderRepositoryImpl>(relaxed = true)
        repo.mailFolderRepository = mailRepo

        val manifestCt = PrivateStorageCrypto.encryptToSelf("""{"folders":[],"updated_at":1}""", priv)
        repo.onInboundPrivateStorageEvent(buildSpamEvent("desent:mail-folders", manifestCt, 100L))
        coVerify { mailRepo.onInboundManifest(any(), 100L, ownerNpub) }

        val shardCt = PrivateStorageCrypto.encryptToSelf(
            """{"index":3,"entries":[]}""", priv
        )
        repo.onInboundPrivateStorageEvent(buildSpamEvent("desent:mail-state:3", shardCt, 100L))
        coVerify { mailRepo.onInboundShard(any(), ownerNpub) }

        // The informational manifest decodes but routes nowhere — still just
        // the one shard call from above, no manifest-info call.
        val infoCt = PrivateStorageCrypto.encryptToSelf(
            """{"shard_count":16,"total_entries":0,"updated_at":1}""", priv
        )
        repo.onInboundPrivateStorageEvent(buildSpamEvent("desent:mail-state:manifest", infoCt, 100L))
        coVerify(exactly = 1) { mailRepo.onInboundShard(any(), any()) }
        coVerify(exactly = 1) { mailRepo.onInboundManifest(any(), any(), any()) }
    }

    /** Builds a kind-30078 event authored by the test identity with one `d` tag. */
    private fun buildSpamEvent(dTag: String, content: String, createdAt: Long): GenericEvent {
        val tags = listOf<nostr.event.BaseTag>(GenericTag("d", listOf(dTag)))
        return GenericEvent.builder()
            .pubKey(identity.publicKey)
            .kind(NostrKinds.APPLICATION_SPECIFIC_DATA)
            .createdAt(createdAt)
            .content(content)
            .tags(tags)
            .build()
    }

    // ---------------------------------------------------------------
    // PGP key namespace (`desent:pgp` — ANDROID_PGP.md §2.1)
    // ---------------------------------------------------------------

    private val pgpPayload = xyz.desent.domain.model.PgpKeyPayload(
        privateKeyArmored = "-----BEGIN PGP PRIVATE KEY BLOCK-----\nzz\n-----END PGP PRIVATE KEY BLOCK-----",
        publicKeyArmored = "-----BEGIN PGP PUBLIC KEY BLOCK-----\nzz\n-----END PGP PUBLIC KEY BLOCK-----",
        fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
        source = "generated"
    )

    @Test
    fun savePgpKey_publishesEncryptedKind30078_andSurfacesState() = runBlocking {
        val dSlot = slot<String>()
        val ctSlot = slot<String>()
        coEvery {
            nostrRepo.publishPrivateStorage(capture(dSlot), capture(ctSlot))
        } returns Result.success(mockk(relaxed = true))

        val result = repo.savePgpKey(pgpPayload)

        assertTrue(result.isSuccess)
        assertEquals("desent:pgp", dSlot.captured)
        // Round-trips through the NIP-44-to-self layer.
        val decrypted = PrivateStorageCrypto.decryptFromSelf(ctSlot.captured, priv)
        assertEquals(
            pgpPayload,
            Json.decodeFromString(xyz.desent.domain.model.PgpKeyPayload.serializer(), decrypted)
        )
        // The optimistic local state reflects the key immediately.
        assertEquals(pgpPayload, repo.pgpKeyFlow.value)
    }

    @Test
    fun removePgpKey_publishesEmptyTombstone_andClearsState() = runBlocking {
        val dSlot = slot<String>()
        val ctSlot = slot<String>()
        coEvery {
            nostrRepo.publishPrivateStorage(capture(dSlot), capture(ctSlot))
        } returns Result.success(mockk(relaxed = true))
        repo.onInboundPrivateStorageEvent(
            buildSpamEvent(
                "desent:pgp",
                PrivateStorageCrypto.encryptToSelf(
                    Json.encodeToString(xyz.desent.domain.model.PgpKeyPayload.serializer(), pgpPayload),
                    priv
                ),
                200L
            )
        )
        assertEquals(pgpPayload, repo.pgpKeyFlow.value)

        val result = repo.removePgpKey()

        assertTrue(result.isSuccess)
        assertEquals("desent:pgp", dSlot.captured)
        assertEquals("", ctSlot.captured)
        assertEquals(null, repo.pgpKeyFlow.value)
    }

    @Test
    fun inboundPgpKey_appliesUnderLww_andTombstoneClears() = runBlocking {
        val newer = pgpPayload.copy(fingerprint = "FF".padEnd(40, '0'), source = "imported")
        repo.onInboundPrivateStorageEvent(
            buildSpamEvent(
                "desent:pgp",
                PrivateStorageCrypto.encryptToSelf(
                    Json.encodeToString(xyz.desent.domain.model.PgpKeyPayload.serializer(), pgpPayload),
                    priv
                ),
                100L
            )
        )
        assertEquals(pgpPayload, repo.pgpKeyFlow.value)

        // A stale re-delivery of the older event is ignored.
        repo.onInboundPrivateStorageEvent(
            buildSpamEvent(
                "desent:pgp",
                PrivateStorageCrypto.encryptToSelf(
                    Json.encodeToString(xyz.desent.domain.model.PgpKeyPayload.serializer(), pgpPayload),
                    priv
                ),
                90L
            )
        )
        assertEquals(pgpPayload, repo.pgpKeyFlow.value)

        // A newer event from another device replaces it.
        repo.onInboundPrivateStorageEvent(
            buildSpamEvent(
                "desent:pgp",
                PrivateStorageCrypto.encryptToSelf(
                    Json.encodeToString(xyz.desent.domain.model.PgpKeyPayload.serializer(), newer),
                    priv
                ),
                300L
            )
        )
        assertEquals(newer, repo.pgpKeyFlow.value)

        // The tombstone (relay hard-deleted the stored copy) clears it.
        repo.onInboundPrivateStorageEvent(buildSpamEvent("desent:pgp", "", 400L))
        assertEquals(null, repo.pgpKeyFlow.value)
    }
}
