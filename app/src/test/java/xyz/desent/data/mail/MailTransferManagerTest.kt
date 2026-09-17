package xyz.desent.data.mail

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.BackupEnvelope
import xyz.desent.crypto.WrongPassphraseException
import xyz.desent.data.local.database.dao.EmailDao
import xyz.desent.data.local.database.entity.EmailEntity
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.mapper.EmailMapper
import xyz.desent.domain.model.EmailDirection

/**
 * Offline mail transfer (.dsme) round-trip: export → BackupEnvelope →
 * import re-keys rows to the active account, preserves threading ids and
 * attachment keys, and is idempotent on overlapping imports.
 */
class MailTransferManagerTest {

    private lateinit var emailDao: EmailDao
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var manager: MailTransferManager

    private val userNpub = "npub_alice"
    private val storedRow = EmailEntity(
        id = "gw-1",
        recipientNpub = userNpub,
        senderEmail = "bob@example.com",
        senderDomain = "example.com",
        subject = "Report",
        content = "body text",
        bodyFormat = "PLAIN",
        dkimStatus = "PASS",
        emailType = "OTHER",
        bridge = "email",
        messageId = "m1@example.com",
        inReplyTo = "root@example.com",
        threadToken = null,
        threadRoot = "root@example.com",
        direction = EmailDirection.INBOUND.name,
        attachmentsJson = """[{"sha256":"abc","mimeType":"application/pdf","size":10,"keyHex":"key","filename":"f.pdf"}]""",
        createdAt = 1_000L
    )

    @Before
    fun setUp() {
        emailDao = mockk(relaxed = true)
        preferencesManager = mockk()
        every { preferencesManager.npubKey } returns flowOf(userNpub)
        manager = MailTransferManager(emailDao, EmailMapper(), preferencesManager)
    }

    @Test
    fun exportImport_roundTrip_preservesMailAndIsIdempotent() = runBlocking {
        coEvery { emailDao.getForwardEligibleEmails(userNpub) } returns listOf(storedRow)
        val inserted = mutableListOf<EmailEntity>()
        coEvery { emailDao.insertEmail(capture(inserted)) } returns Unit
        coEvery { emailDao.getEmailById(any()) } returns null

        val blob = manager.export(threadKeys = null, passphrase = "correct horse")
        assertTrue(blob.isSuccess)

        val result = manager.import(blob.getOrThrow(), "correct horse")
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow())

        // Re-keyed to the (same) active account; ids + threading preserved.
        val row = inserted.single()
        assertEquals("gw-1", row.id)
        assertEquals(userNpub, row.recipientNpub)
        assertEquals("m1@example.com", row.messageId)
        assertEquals("root@example.com", row.threadRoot)
        assertTrue(row.attachmentsJson!!.contains("\"keyHex\":\"key\""))

        // Re-import with the row already present → nothing new.
        coEvery { emailDao.getEmailById("gw-1") } returns storedRow
        assertEquals(0, manager.import(blob.getOrThrow(), "correct horse").getOrThrow())
    }

    @Test
    fun import_wrongPassphraseFailsCleanly() = runBlocking {
        coEvery { emailDao.getForwardEligibleEmails(userNpub) } returns listOf(storedRow)
        val blob = manager.export(threadKeys = null, passphrase = "right").getOrThrow()

        val result = manager.import(blob, "wrong")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is WrongPassphraseException)
    }

    @Test
    fun export_validatesPassphraseAndSelection() = runBlocking {
        coEvery { emailDao.getForwardEligibleEmails(userNpub) } returns listOf(storedRow)
        assertTrue(manager.export(null, "ab").isFailure) // too short

        coEvery { emailDao.getForwardEligibleEmails(userNpub) } returns emptyList()
        assertTrue(manager.export(null, "long enough").isFailure) // nothing to export
    }

    @Test
    fun export_envelopeIsBackupEnvelopeFormat() = runBlocking {
        coEvery { emailDao.getForwardEligibleEmails(userNpub) } returns listOf(storedRow)
        val blob = manager.export(null, "passphrase").getOrThrow()

        // DSBK1 magic header — same envelope construction as account backups.
        assertEquals("DSBK1", String(blob.copyOfRange(0, 5), Charsets.US_ASCII))
        // And it decrypts with the raw envelope for forward compatibility.
        assertTrue(BackupEnvelope.unpack(blob, "passphrase").isNotEmpty())
    }
}
