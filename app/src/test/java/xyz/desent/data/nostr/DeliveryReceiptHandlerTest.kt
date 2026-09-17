package xyz.desent.data.nostr

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.data.local.database.dao.EmailDao
import xyz.desent.data.local.database.dao.EmailOutboxDao
import xyz.desent.data.local.database.entity.EmailEntity
import xyz.desent.data.local.database.entity.EmailOutboxEntity
import xyz.desent.domain.model.EmailDirection
import xyz.desent.domain.model.EmailType
import xyz.desent.domain.model.UnwrappedContent

/**
 * Delivery-receipt processing per refs/FromServer/NIP-EMAIL.md § Direction +
 * ANDROID_EMAIL_MIGRATION.md §7. The raw payloads below mirror real receipts:
 * relay-minted angle-bracketed message_id, from noreply@desent.xyz, to+subject
 * echoing the outbound send, ✉/❌ status-line content.
 */
class DeliveryReceiptHandlerTest {

    private lateinit var emailDao: EmailDao
    private lateinit var outboxDao: EmailOutboxDao
    private lateinit var handler: DeliveryReceiptHandler

    private val wrapId = "6f78296e01f8dca3545fcfe9aba6557f57fda4c488153c306358fcb284d96455"
    private val userHex = "97b3d0248006f7162513effe0cf0169c1fe0cd0306058c383a258a38246bc336"

    @Before
    fun setUp() {
        emailDao = mockk(relaxed = true)
        outboxDao = mockk(relaxed = true)
        handler = DeliveryReceiptHandler(emailDao, outboxDao)

        // Dedup lookups: nothing seen by default; individual tests override.
        coEvery { emailDao.getEmailById(any()) } returns null
        coEvery { outboxDao.getByReceiptEventId(any()) } returns null
        coEvery { outboxDao.findPendingMatches(any(), any(), any()) } returns emptyList()
        coEvery { outboxDao.findTimedOutMatches(any(), any(), any()) } returns emptyList()
        coEvery { emailDao.findRecentOutbound(any(), any(), any(), any()) } returns emptyList()
    }

    private fun receipt(
        content: String = "✉ Email sent to bob@example.com:\n\nDo you exit the server?",
        to: String = "bob@example.com",
        subject: String = "Re: testuibg",
        messageId: String = "<178671176191.698060.843776349201052670@desent.xyz>"
    ) = UnwrappedContent(
        content = content,
        senderNpub = "npub1relayseal",
        kind = 1010,
        tags = listOf(
            listOf("p", userHex),
            listOf("from", "noreply@desent.xyz"),
            listOf("from_domain", "desent.xyz"),
            listOf("subject", subject),
            listOf("dkim", "disabled"),
            listOf("direction", "delivery-receipt"),
            listOf("message_id", messageId),
            listOf("to", to)
        )
    )

    private fun pendingEntry(
        messageId: String = "11111111-2222-3333-4444-555555555555@desent.xyz",
        status: String = EmailOutboxEntity.STATUS_PENDING
    ) = EmailOutboxEntity(
        messageId = messageId,
        recipientNpub = "npub1me",
        threadKey = "thread-1",
        fromAlias = "alice@desent.xyz",
        toEmail = "bob@example.com",
        subject = "Re: testuibg",
        body = "Do you exit the server?",
        sentAt = System.currentTimeMillis() - 5_000,
        status = status
    )

    @Test
    fun pendingSend_successReceipt_resolvesConfirmedWithoutEmailRow() = runBlocking {
        val entry = pendingEntry()
        coEvery { outboxDao.findPendingMatches("Re: testuibg", any(), any()) } returns listOf(entry)

        assertTrue(handler.process(receipt(), wrapId, null, userHex))

        coVerify(exactly = 1) {
            outboxDao.updateStatus(
                messageId = entry.messageId,
                status = EmailOutboxEntity.STATUS_CONFIRMED,
                receiptEventId = wrapId,
                errorMessage = null,
                resolvedAt = any()
            )
        }
        // The receipt resolves ledger state only — no inbox/thread mail row.
        coVerify(exactly = 0) { emailDao.insertEmail(any()) }
        coVerify(exactly = 0) { outboxDao.insert(any()) }
    }

    @Test
    fun pendingSend_failureReceipt_resolvesFailedWithReason() = runBlocking {
        val entry = pendingEntry()
        coEvery { outboxDao.findPendingMatches(any(), any(), any()) } returns listOf(entry)
        val failure = receipt(
            content = "❌ Send failed: [SSL: CERTIFICATE_VERIFY_FAILED] certificate verify failed"
        )

        assertTrue(handler.process(failure, wrapId, null, userHex))

        coVerify(exactly = 1) {
            outboxDao.updateStatus(
                messageId = entry.messageId,
                status = EmailOutboxEntity.STATUS_FAILED,
                receiptEventId = wrapId,
                errorMessage = "❌ Send failed: [SSL: CERTIFICATE_VERIFY_FAILED] certificate verify failed",
                resolvedAt = any()
            )
        }
        coVerify(exactly = 0) { emailDao.insertEmail(any()) }
    }

    @Test
    fun timedOutSend_lateReceipt_upgradesToRealState() = runBlocking {
        val entry = pendingEntry(status = EmailOutboxEntity.STATUS_TIMED_OUT)
        coEvery { outboxDao.findTimedOutMatches(any(), any(), any()) } returns listOf(entry)

        assertTrue(handler.process(receipt(), wrapId, null, userHex))

        coVerify(exactly = 1) {
            outboxDao.updateStatus(
                messageId = entry.messageId,
                status = EmailOutboxEntity.STATUS_CONFIRMED,
                receiptEventId = wrapId,
                errorMessage = null,
                resolvedAt = any()
            )
        }
    }

    @Test
    fun legacyOutboundRow_receiptRendersInlineInThatThread() = runBlocking {
        val legacy = EmailEntity(
            id = "legacy-row", recipientNpub = "npub1me",
            senderEmail = "alice@desent.xyz", senderDomain = "desent.xyz",
            subject = "Re: testuibg", content = "old send",
            dkimStatus = "NONE", emailType = "OTHER", bridge = "email",
            messageId = "old@desent.xyz", threadToken = null, threadRoot = "thread-legacy",
            toEmail = "bob@example.com", direction = EmailDirection.OUTBOUND.name,
            createdAt = System.currentTimeMillis() - 60_000
        )
        coEvery { emailDao.findRecentOutbound(any(), any(), any(), any()) } returns listOf(legacy)

        assertTrue(handler.process(receipt(), wrapId, null, userHex))

        val stored = slot<EmailEntity>()
        coVerify { emailDao.insertEmail(capture(stored)) }
        assertEquals(EmailType.SYSTEM.name, stored.captured.emailType)
        assertEquals(EmailDirection.DELIVERY_RECEIPT.name, stored.captured.direction)
        // Real tags, not the old hardcoded "Delivery status" / "bridge@desent.xyz".
        assertEquals("Re: testuibg", stored.captured.subject)
        assertEquals("noreply@desent.xyz", stored.captured.senderEmail)
        assertEquals("thread-legacy", stored.captured.threadRoot)
        assertTrue(stored.captured.isRead)
        coVerify(exactly = 0) { outboxDao.insert(any()) }
    }

    @Test
    fun unknownSend_success_synthesizesLedgerEntryAndSentRow() = runBlocking {
        assertTrue(handler.process(receipt(), wrapId, null, userHex))

        val entry = slot<EmailOutboxEntity>()
        coVerify { outboxDao.insert(capture(entry)) }
        assertTrue(entry.captured.isSynthetic)
        assertEquals(EmailOutboxEntity.STATUS_CONFIRMED, entry.captured.status)
        assertEquals(wrapId, entry.captured.receiptEventId)
        // Angle brackets stripped from the relay-minted id (thread lookups normalize).
        assertEquals("178671176191.698060.843776349201052670@desent.xyz", entry.captured.messageId)
        // Body preview extracted after the status line.
        assertEquals("Do you exit the server?", entry.captured.body)

        val row = slot<EmailEntity>()
        coVerify { emailDao.insertEmail(capture(row)) }
        assertEquals(EmailDirection.OUTBOUND.name, row.captured.direction)
        assertEquals("Do you exit the server?", row.captured.content)
        assertEquals("178671176191.698060.843776349201052670@desent.xyz", row.captured.threadRoot)
    }

    @Test
    fun unknownSend_failure_ledgerOnlyNoSentRow() = runBlocking {
        assertTrue(handler.process(receipt(content = "❌ Send failed: rejected by MX"), wrapId, null, userHex))

        val entry = slot<EmailOutboxEntity>()
        coVerify { outboxDao.insert(capture(entry)) }
        assertEquals(EmailOutboxEntity.STATUS_FAILED, entry.captured.status)
        assertEquals("❌ Send failed: rejected by MX", entry.captured.errorMessage)
        coVerify(exactly = 0) { emailDao.insertEmail(any()) }
    }

    @Test
    fun receiptWithoutToTag_isDropped() = runBlocking {
        val noTo = receipt().let { it.copy(tags = it.tags.filterNot { t -> t.firstOrNull() == "to" }) }
        assertFalse(handler.process(noTo, wrapId, null, userHex))
        coVerify(exactly = 0) { outboxDao.updateStatus(any(), any(), any(), any(), any()) }
    }

    @Test
    fun alreadyAppliedReceipt_isDeduped() = runBlocking {
        coEvery { outboxDao.getByReceiptEventId(wrapId) } returns pendingEntry(status = EmailOutboxEntity.STATUS_CONFIRMED)

        assertFalse(handler.process(receipt(), wrapId, null, userHex))
        coVerify(exactly = 0) { outboxDao.updateStatus(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { outboxDao.insert(any()) }
    }

    // ---------------------------------------------------------------
    // Any-address overlap (ANDROID_EMAIL_MIGRATION.md §8): the receipt
    // carries one `to` tag per envelope recipient and a receipt address may
    // match ANY of the pending entry's to/cc/bcc addresses.
    // ---------------------------------------------------------------

    private fun multiToReceipt(vararg toAddresses: String): UnwrappedContent {
        val base = receipt()
        return base.copy(
            tags = base.tags.filterNot { it.firstOrNull() == "to" } +
                toAddresses.map { listOf("to", it) }
        )
    }

    @Test
    fun multiToReceipt_matchesPendingEntryViaCcOrBccOverlap() = runBlocking {
        // Pending multi-recipient send: to=bob, cc=carol, bcc=secret. The
        // relay's receipt mirrors the full envelope recipient list.
        val entry = pendingEntry().copy(
            toRecipientsJson = """[{"address":"bob@example.com"}]""",
            ccRecipientsJson = """[{"address":"carol@foo.io"}]""",
            bccRecipientsJson = """[{"address":"secret@hidden.io"}]"""
        )
        coEvery { outboxDao.findPendingMatches("Re: testuibg", any(), any()) } returns listOf(entry)

        // The receipt's FIRST to tag is not the entry's legacy toEmail — the
        // overlap, not first-address equality, decides the match.
        assertTrue(handler.process(multiToReceipt("carol@foo.io", "secret@hidden.io"), wrapId, null, userHex))
        coVerify(exactly = 1) {
            outboxDao.updateStatus(
                messageId = entry.messageId,
                status = EmailOutboxEntity.STATUS_CONFIRMED,
                receiptEventId = wrapId,
                errorMessage = null,
                resolvedAt = any()
            )
        }
    }

    @Test
    fun subjectMatchWithoutAddressOverlap_fallsThroughToSynthetic() = runBlocking {
        // Same subject, disjoint addresses: not our send.
        val entry = pendingEntry()
        coEvery { outboxDao.findPendingMatches("Re: testuibg", any(), any()) } returns listOf(entry)
        coEvery { outboxDao.findTimedOutMatches("Re: testuibg", any(), any()) } returns emptyList()
        coEvery { emailDao.findRecentOutbound(any(), any(), any(), any()) } returns emptyList()

        assertTrue(handler.process(multiToReceipt("someone.else@x.io"), wrapId, null, userHex))

        // Never resolved the foreign entry; reconstructed a synthetic one instead.
        coVerify(exactly = 0) { outboxDao.updateStatus(any(), any(), any(), any(), any()) }
        val synthetic = slot<EmailOutboxEntity>()
        coVerify { outboxDao.insert(capture(synthetic)) }
        assertTrue(synthetic.captured.isSynthetic)
    }

    @Test
    fun syntheticEntryFromMultiToReceipt_storesTheFullAddressList() = runBlocking {
        coEvery { outboxDao.findPendingMatches(any(), any(), any()) } returns emptyList()
        coEvery { outboxDao.findTimedOutMatches(any(), any(), any()) } returns emptyList()
        coEvery { emailDao.findRecentOutbound(any(), any(), any(), any()) } returns emptyList()

        assertTrue(handler.process(multiToReceipt("bob@example.com", "carol@foo.io"), wrapId, null, userHex))

        val synthetic = slot<EmailOutboxEntity>()
        coVerify { outboxDao.insert(capture(synthetic)) }
        assertEquals("bob@example.com", synthetic.captured.toEmail)
        assertEquals(
            """[{"address":"bob@example.com"},{"address":"carol@foo.io"}]""",
            synthetic.captured.toRecipientsJson
        )
    }
}
