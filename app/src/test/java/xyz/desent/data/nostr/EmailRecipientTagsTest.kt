package xyz.desent.data.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailDirection
import xyz.desent.domain.model.EmailRecipient

/**
 * RFC 5322 recipient-list parsing (END-01 §3.4; ANDROID_EMAIL_MIGRATION.md §7)
 * — collect ALL to/cc tags (never just the first), the optional slot-3
 * display names, and the envelope `delivered_to` that powers the "BCC'd to
 * you" chip. Inbound `bcc` tags are deliberately not surfaced (END-01 §6).
 */
class EmailRecipientTagsTest {

    @Test
    fun parse_collectsAllToAndCcTags_withDisplayNames_lowercased() {
        val parsed = EmailRecipientTags.parse(
            listOf(
                listOf("p", "aa".repeat(32)),
                listOf("from", "bob@example.com"),
                listOf("subject", "Group plans"),
                listOf("direction", "inbound"),
                listOf("message_id", "m@x"),
                listOf("to", "Alice@DeSent.xyz", "Alice A."),
                listOf("to", "team@desent.xyz"),
                listOf("cc", "Carol@Foo.io", "Carol"),
                listOf("cc", "dave@baz.net"),
                listOf("delivered_to", "alice@desent.xyz")
            )
        )

        assertEquals(
            listOf(
                EmailRecipient("alice@desent.xyz", "Alice A."),
                EmailRecipient("team@desent.xyz")
            ),
            parsed.to
        )
        assertEquals(
            listOf(
                EmailRecipient("carol@foo.io", "Carol"),
                EmailRecipient("dave@baz.net")
            ),
            parsed.cc
        )
        assertEquals("alice@desent.xyz", parsed.deliveredTo)
    }

    @Test
    fun parse_onlyFirstToTag_theOldClientBehavior_droppedTheRest() {
        // Regression shape: the pre-2026-09 client read tags.find { to } —
        // every mailbox after the first vanished. The parser must use filter.
        val parsed = EmailRecipientTags.parse(
            listOf(
                listOf("to", "first@x.io"),
                listOf("to", "second@x.io"),
                listOf("cc", "third@x.io")
            )
        )
        assertEquals(2, parsed.to.size)
        assertEquals(1, parsed.cc.size)
    }

    @Test
    fun parse_stripsLineBreaksFromWireData() {
        val parsed = EmailRecipientTags.parse(
            listOf(
                listOf("to", "a@x.io", "Alice\r\nBcc: evil@c.io"),
                listOf("delivered_to", " b@x.io ")
            )
        )
        assertEquals("AliceBcc: evil@c.io", parsed.to.first().displayName)
        assertEquals("b@x.io", parsed.deliveredTo)
    }

    @Test
    fun parse_ignoresInboundBccTags_blankDeliveredTo() {
        // A conforming sender's MTA strips Bcc before delivery; a leftover
        // header is not the recipient's business (END-01 §6) — the parser has
        // no bcc output at all.
        val parsed = EmailRecipientTags.parse(
            listOf(
                listOf("to", "alice@desent.xyz"),
                listOf("bcc", "leftover@spoof.io"),
                listOf("delivered_to", "")
            )
        )
        assertEquals(listOf(EmailRecipient("alice@desent.xyz")), parsed.to)
        assertNull(parsed.deliveredTo)
    }

    @Test
    fun addresses_allToValues_forReceiptMatching() {
        // Delivery receipts carry one `to` tag per envelope recipient
        // (END-03 §5) — every value, in order.
        assertEquals(
            listOf("bob@x.io", "carol@foo.io", "secret@hidden.io"),
            EmailRecipientTags.addresses(
                listOf(
                    listOf("to", "bob@x.io"),
                    listOf("to", "carol@foo.io"),
                    listOf("to", "secret@hidden.io"),
                    listOf("subject", "S")
                )
            )
        )
        assertTrue(EmailRecipientTags.addresses(listOf(listOf("subject", "S"))).isEmpty())
    }

    // ---------------------------------------------------------------
    // The "BCC'd to you" chip: delivered_to ∉ to ∪ cc.
    // ---------------------------------------------------------------

    private fun email(
        to: List<EmailRecipient>,
        cc: List<EmailRecipient> = emptyList(),
        deliveredTo: String? = null
    ) = Email(
        id = "gw-1",
        recipientNpub = "npub1me",
        senderEmail = "bob@example.com",
        senderDomain = "example.com",
        subject = "S",
        content = "x",
        dkimStatus = xyz.desent.domain.model.DkimStatus.PASS,
        emailType = xyz.desent.domain.model.EmailType.OTHER,
        bridge = "email",
        messageId = "m@x",
        threadToken = null,
        toRecipients = to,
        ccRecipients = cc,
        deliveredTo = deliveredTo,
        direction = EmailDirection.INBOUND,
        createdAt = 1L
    )

    @Test
    fun bccHint_deliveredOnlyCopy_arrivedViaEnvelope() {
        assertTrue(
            email(
                to = listOf(EmailRecipient("team@desent.xyz")),
                deliveredTo = "alice@desent.xyz"
            ).bccHint
        )
    }

    @Test
    fun bccHint_falseWhenDeliveredToAppearsInToOrCc() {
        assertFalse(
            email(
                to = listOf(EmailRecipient("alice@desent.xyz"), EmailRecipient("team@desent.xyz")),
                deliveredTo = "alice@desent.xyz"
            ).bccHint
        )
        assertFalse(
            email(
                to = listOf(EmailRecipient("team@desent.xyz")),
                cc = listOf(EmailRecipient("alice@desent.xyz")),
                deliveredTo = "alice@desent.xyz"
            ).bccHint
        )
        // Absent delivered_to (legacy row) → no chip.
        assertFalse(email(to = listOf(EmailRecipient("team@desent.xyz"))).bccHint)
    }
}
