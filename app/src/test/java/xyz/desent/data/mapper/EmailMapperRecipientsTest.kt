package xyz.desent.data.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.desent.data.local.database.entity.EmailEntity
import xyz.desent.domain.model.DkimStatus
import xyz.desent.domain.model.EmailDirection
import xyz.desent.domain.model.EmailRecipient
import xyz.desent.domain.model.EmailType

/**
 * RFC 5322 recipient-list round-trip (END-01 §3.4): the JSON columns the
 * 2026-09 migration added store the full to/cc/bcc lists and the envelope
 * `delivered_to`, the same pattern as `attachmentsJson`.
 */
class EmailMapperRecipientsTest {

    private val mapper = EmailMapper()

    private fun entity(
        toRecipientsJson: String? = null,
        ccRecipientsJson: String? = null,
        bccRecipientsJson: String? = null,
        deliveredTo: String? = null
    ) = EmailEntity(
        id = "gw-1",
        recipientNpub = "npub1me",
        senderEmail = "bob@example.com",
        senderDomain = "example.com",
        subject = "S",
        content = "x",
        dkimStatus = DkimStatus.PASS.name,
        emailType = EmailType.OTHER.name,
        bridge = "email",
        messageId = "m@x",
        threadToken = null,
        toEmail = "alice@desent.xyz",
        toRecipientsJson = toRecipientsJson,
        ccRecipientsJson = ccRecipientsJson,
        bccRecipientsJson = bccRecipientsJson,
        deliveredTo = deliveredTo,
        direction = EmailDirection.INBOUND.name,
        createdAt = 1L
    )

    @Test
    fun mapToDomain_decodesRecipientLists_andDeliveredTo() {
        val domain = mapper.mapToDomain(
            entity(
                toRecipientsJson = """[{"address":"alice@desent.xyz","displayName":"Alice"},{"address":"team@desent.xyz"}]""",
                ccRecipientsJson = """[{"address":"carol@foo.io","displayName":"Carol"}]""",
                bccRecipientsJson = """[{"address":"secret@hidden.io"}]""",
                deliveredTo = "alice@desent.xyz"
            )
        )
        assertEquals(
            listOf(
                EmailRecipient("alice@desent.xyz", "Alice"),
                EmailRecipient("team@desent.xyz")
            ),
            domain.toRecipients
        )
        assertEquals(listOf(EmailRecipient("carol@foo.io", "Carol")), domain.ccRecipients)
        assertEquals(listOf(EmailRecipient("secret@hidden.io")), domain.bccRecipients)
        assertEquals("alice@desent.xyz", domain.deliveredTo)
    }

    @Test
    fun mapToEntity_encodesLists_nullWhenEmpty() {
        val stored = mapper.mapToEntity(
            mapper.mapToDomain(
                entity(
                    toRecipientsJson = """[{"address":"alice@desent.xyz"}]""",
                    ccRecipientsJson = """[]"""
                )
            )
        )
        assertEquals("""[{"address":"alice@desent.xyz"}]""", stored.toRecipientsJson)
        // Empty lists store no JSON at all — absent tags stay absent.
        assertNull(stored.ccRecipientsJson)
        assertNull(stored.bccRecipientsJson)
    }

    @Test
    fun legacyRows_nullColumns_decodeToEmptyLists() {
        val domain = mapper.mapToDomain(entity())
        assertEquals(emptyList<EmailRecipient>(), domain.toRecipients)
        assertEquals(emptyList<EmailRecipient>(), domain.ccRecipients)
        assertEquals(emptyList<EmailRecipient>(), domain.bccRecipients)
        assertNull(domain.deliveredTo)
        // The legacy single field still carries the first To addr-spec.
        assertEquals("alice@desent.xyz", domain.toEmail)
    }

    @Test
    fun malformedStoredJson_decodesToEmpty() {
        val domain = mapper.mapToDomain(entity(toRecipientsJson = """not json"""))
        assertEquals(emptyList<EmailRecipient>(), domain.toRecipients)
    }
}
