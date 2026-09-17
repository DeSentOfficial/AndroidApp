package xyz.desent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.domain.model.DkimStatus
import xyz.desent.domain.model.DmarcStatus
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailAttachment
import xyz.desent.domain.model.EmailBodyFormat
import xyz.desent.domain.model.EmailDirection
import xyz.desent.domain.model.EmailRecipient
import xyz.desent.domain.model.EmailType
import xyz.desent.domain.model.SpfStatus

/**
 * Validates the unified kind-1010 outbound rumor tags against the contract in
 * refs/FromServer/NIP-EMAIL.md § Tag schema + § Outbound flow, and the
 * migration deltas in EMAIL_NIP_ANDROID_MIGRATION.md §3 (thread_token /
 * send_to / bridge / type are gone; from / to / direction / format are in).
 */
class EmailBridgeTagsTest {

    @Test
    fun reply_carriesFromToSubjectDirectionFormatMessageIdAndBracketedThreading() {
        val tags = EmailBridgeTags.outbound(
            fromAlias = "alice@desent.xyz",
            to = listOf(EmailRecipient("bob@example.com")),
            subject = "Re: Hello",
            messageId = "m1@desent.xyz",
            inReplyTo = "orig@example.com",
            references = "root@example.com orig@example.com",
            format = EmailBodyFormat.HTML
        )

        assertEquals(
            listOf(
                listOf("from", "alice@desent.xyz"),
                listOf("to", "bob@example.com"),
                listOf("subject", "Re: Hello"),
                listOf("direction", "outbound"),
                listOf("message_id", "m1@desent.xyz"),
                listOf("format", "html"),
                // Threading ids are stored bare but emitted angle-bracketed:
                // the relay copies them verbatim into SMTP In-Reply-To /
                // References headers where the bracketed form is required.
                listOf("in_reply_to", "<orig@example.com>"),
                listOf("references", "<root@example.com> <orig@example.com>")
            ),
            tags
        )
    }

    @Test
    fun coldSend_omitsInReplyToAndReferences_defaultsToPlainFormat() {
        val tags = EmailBridgeTags.outbound(
            fromAlias = "alice@desent.xyz",
            to = listOf(EmailRecipient("carol@foo.io")),
            subject = "Hi",
            messageId = "m2@desent.xyz"
        )

        assertEquals(
            listOf(
                listOf("from", "alice@desent.xyz"),
                listOf("to", "carol@foo.io"),
                listOf("subject", "Hi"),
                listOf("direction", "outbound"),
                listOf("message_id", "m2@desent.xyz"),
                listOf("format", "plain")
            ),
            tags
        )
        // The legacy vocabulary is fully retired.
        val names = tags.map { it[0] }
        assertFalse(names.contains("thread_token"))
        assertFalse(names.contains("send_to"))
        assertFalse(names.contains("bridge"))
        assertFalse(names.contains("type"))
        // The `p` (recipient) tag is added by the gift-wrap service, not here.
        assertFalse(names.contains("p"))
    }

    // ---------------------------------------------------------------
    // Recipient lists (END-01 §3.4, deployed 2026-09): one tag per mailbox,
    // display name in the optional third slot, Bcc bare, ≤20 cap constant.
    // ---------------------------------------------------------------

    @Test
    fun multiRecipient_emitsOneTagPerMailbox_bccBare() {
        val tags = EmailBridgeTags.outbound(
            fromAlias = "alice@desent.xyz",
            to = listOf(
                EmailRecipient("Bob@X.io", "Bob Example"),
                EmailRecipient("carol@foo.io")
            ),
            cc = listOf(EmailRecipient("dave@baz.net", "Dave")),
            bcc = listOf(EmailRecipient("secret@hidden.io", "ignored")),
            subject = "Plans",
            messageId = "m4@desent.xyz"
        )

        assertEquals(
            listOf(
                listOf("from", "alice@desent.xyz"),
                // Addr-specs lowercased (from-tag vocabulary), display names
                // in slot 3 — present only when one was parsed.
                listOf("to", "bob@x.io", "Bob Example"),
                listOf("to", "carol@foo.io"),
                listOf("cc", "dave@baz.net", "Dave"),
                // Bcc gets no display-name slot — no one else ever sees it.
                listOf("bcc", "secret@hidden.io"),
                listOf("subject", "Plans"),
                listOf("direction", "outbound"),
                listOf("message_id", "m4@desent.xyz"),
                listOf("format", "plain")
            ),
            tags
        )
    }

    @Test
    fun displayNameLineBreaks_strippedAsHeaderInjectionHygiene() {
        val tags = EmailBridgeTags.outbound(
            fromAlias = "a@desent.xyz",
            to = listOf(EmailRecipient("b@x.io", "Bob\r\nBcc: evil@c.io")),
            subject = "S",
            messageId = "m5@desent.xyz"
        )
        assertEquals(listOf("to", "b@x.io", "BobBcc: evil@c.io"), tags.first { it[0] == "to" })
    }

    @Test
    fun recipientCap_isTwenty_endo03Guard() {
        assertEquals(20, EmailBridgeTags.MAX_ENVELOPE_RECIPIENTS)
    }

    @Test
    fun blankThreadingTags_areOmitted() {        val tags = EmailBridgeTags.outbound(
            fromAlias = "a@desent.xyz",
            to = listOf(EmailRecipient("b@example.com")),
            subject = "S",
            messageId = "m3@desent.xyz",
            inReplyTo = "  ",
            references = ""
        )
        assertTrue(tags.none { it[0] == "in_reply_to" })
        assertTrue(tags.none { it[0] == "references" })
    }

    @Test
    fun makeMessageId_isUnique_andDesentShaped() {
        val a = EmailBridgeTags.makeMessageId()
        val b = EmailBridgeTags.makeMessageId()
        // Bare RFC 5322 form (angle brackets optional per NIP-EMAIL) matching
        // the relay's validated id@domain shape — it becomes the SMTP
        // Message-ID so external replies thread back to this conversation.
        assertNotNull(Regex("^[0-9a-f-]{36}@desent\\.xyz$").find(a))
        assertFalse(a == b)
    }

    @Test
    fun bracketing_isIdempotent() {
        assertEquals("<a@x.com>", EmailBridgeTags.bracketMessageId("a@x.com"))
        assertEquals("<a@x.com>", EmailBridgeTags.bracketMessageId("<a@x.com>"))
        assertEquals("<a@x.com> <b@x.com>", EmailBridgeTags.bracketReferences("a@x.com <b@x.com>"))
    }

    @Test
    fun plainToHtml_escapesMarkup_andPreservesLineBreaks() {
        assertEquals(
            "line1<br>line2",
            EmailBridgeTags.plainToHtml("line1\nline2")
        )
        assertEquals(
            "a &lt;b&gt; c &amp; d &quot;e&quot; &#39;f&#39;",
            EmailBridgeTags.plainToHtml("a <b> c & d \"e\" 'f'")
        )
        // CRLF normalizes through \n.
        assertEquals("x<br>y", EmailBridgeTags.plainToHtml("x\r\ny"))
        assertEquals("", EmailBridgeTags.plainToHtml(""))
    }

    @Test
    fun bodyFormat_parsesWireValues_withHtmlFallback() {
        assertEquals(EmailBodyFormat.HTML, EmailBodyFormat.fromWire("html"))
        assertEquals(EmailBodyFormat.PLAIN, EmailBodyFormat.fromWire("plain"))
        assertEquals(EmailBodyFormat.PLAIN, EmailBodyFormat.fromWire("  PLAIN "))
        // Absent / unknown / legacy → HTML (how kind-14 bodies always rendered).
        assertEquals(EmailBodyFormat.HTML, EmailBodyFormat.fromWire(null))
        assertEquals(EmailBodyFormat.HTML, EmailBodyFormat.fromWire(""))
        assertEquals(EmailBodyFormat.HTML, EmailBodyFormat.fromWire("rich"))
        assertNull(EmailBodyFormat.entries.firstOrNull { it.wireValue == "rich" })
    }

    // ---------------------------------------------------------------
    // Forwarding (NIP-EMAIL): rebuilt rumor must be tag-faithful so
    // threading + authenticity survive on the recipient.
    // ---------------------------------------------------------------

    private fun fullEmail() = Email(
        id = "gw-1",
        recipientNpub = "npub_alice",
        senderEmail = "bob@example.com",
        senderDomain = "example.com",
        senderName = "Bob Example",
        replyTo = "human@example.com",
        subject = "Quarterly report",
        content = "the body",
        bodyFormat = EmailBodyFormat.PLAIN,
        dkimStatus = DkimStatus.PASS,
        spfStatus = SpfStatus.PASS,
        dmarcStatus = DmarcStatus.PASS,
        emailType = EmailType.OTHER,
        bridge = "email",
        messageId = "orig-1@example.com",
        inReplyTo = "root-0@example.com",
        referencesHeader = "root-0@example.com",
        threadToken = null,
        threadRoot = "root-0@example.com",
        toEmail = "alice@desent.xyz",
        toRecipients = listOf(
            EmailRecipient("alice@desent.xyz", "Alice"),
            EmailRecipient("team@desent.xyz")
        ),
        ccRecipients = listOf(EmailRecipient("carol@foo.io", "Carol")),
        deliveredTo = "alice@desent.xyz",
        direction = EmailDirection.INBOUND,
        alias = "alice@desent.xyz",
        attachments = listOf(
            EmailAttachment("abc", "application/pdf", 123, "deadbeef", "report.pdf")
        ),
        senderDate = 1_700_000_123_000L,
        createdAt = 1_700_000_999_000L
    )

    @Test
    fun forwarded_copiesEveryHeaderTag_andAddsProvenance() {
        val tags = EmailBridgeTags.forwarded(fullEmail(), forwarderPubkeyHex = "aa".repeat(32))

        assertEquals(
            listOf(
                listOf("from", "bob@example.com"),
                listOf("from_name", "Bob Example"),
                listOf("from_domain", "example.com"),
                // Original RFC 5322 recipient lists ride along verbatim
                // (END-01 §3.4) so reply-all works from the forwarded copy.
                listOf("to", "alice@desent.xyz", "Alice"),
                listOf("to", "team@desent.xyz"),
                listOf("cc", "carol@foo.io", "Carol"),
                listOf("subject", "Quarterly report"),
                listOf("direction", "inbound"),
                listOf("message_id", "orig-1@example.com"),
                listOf("format", "plain"),
                // Threading ids angle-bracketed, ancestry preserved.
                listOf("in_reply_to", "<root-0@example.com>"),
                listOf("references", "<root-0@example.com>"),
                // Sender-claimed Date: ms → unix seconds.
                listOf("date", "1700000123"),
                listOf("reply_to", "human@example.com"),
                // Auth results still attest the ORIGINAL sender's domain.
                listOf("dkim", "pass"),
                listOf("spf", "pass"),
                listOf("dmarc", "pass"),
                listOf("alias", "alice@desent.xyz"),
                // Attachment descriptor with its AES key rides along.
                listOf("attachment", "abc", "application/pdf", "123", "deadbeef", "report.pdf"),
                // Provenance: who re-delivered this mail.
                listOf("forwarded_by", "aa".repeat(32))
            ),
            tags
        )
    }

    @Test
    fun forwarded_minimalLegacyRow_omitsAbsentTags() {
        val legacy = Email(
            id = "legacy-1",
            recipientNpub = "npub_alice",
            senderEmail = "someone@old.example",
            senderDomain = null,
            subject = "Old thread",
            content = "legacy body",
            dkimStatus = DkimStatus.NONE,
            emailType = EmailType.OTHER,
            bridge = "email",
            messageId = null, // legacy kind-14 rows carry no Message-ID
            threadToken = "NBRIDGE:v1:old",
            createdAt = 1L
        )

        val tags = EmailBridgeTags.forwarded(legacy, forwarderPubkeyHex = "bb")

        assertEquals(
            listOf(
                listOf("from", "someone@old.example"),
                listOf("subject", "Old thread"),
                listOf("direction", "inbound"),
                // No message_id on the wire → recipient threads it as a root.
                listOf("format", "html"),
                listOf("forwarded_by", "bb")
            ),
            tags
        )
    }

    @Test
    fun forwarded_neverCarriesOutboundDirection_orLegacyVocabulary() {
        val tags = EmailBridgeTags.forwarded(fullEmail(), "cc")
        val names = tags.map { it[0] }
        assertEquals("inbound", tags.first { it[0] == "direction" }[1])
        // to/cc ARE re-emitted (informational), but never delivered_to: it is
        // envelope metadata of the ORIGINAL delivery and would render a
        // misleading "BCC'd to you" chip on the target device.
        assertTrue(names.contains("to"))
        assertTrue(names.contains("cc"))
        assertFalse(names.contains("delivered_to"))
        assertFalse(names.contains("bcc"))
        assertFalse(names.contains("thread_token"))
        assertFalse(names.contains("bridge"))
        assertFalse(names.contains("type"))
        assertFalse(names.contains("p")) // added by the gift-wrap service
    }

    // ---------------------------------------------------------------
    // PGP-encrypted sends (ANDROID_PGP.md §4.2 / PGP_ENCRYPTION.md § Outbound)
    // ---------------------------------------------------------------

    @Test
    fun pgpSend_carriesPgpTag_andOmitsFormatTag() {
        val tags = EmailBridgeTags.outbound(
            fromAlias = "alice@desent.xyz",
            to = listOf(EmailRecipient("bob@example.com")),
            subject = "Secret",
            messageId = "m2@desent.xyz",
            format = EmailBodyFormat.PLAIN,
            pgpEncrypted = true
        )

        assertEquals(
            listOf(
                listOf("from", "alice@desent.xyz"),
                listOf("to", "bob@example.com"),
                listOf("subject", "Secret"),
                listOf("direction", "outbound"),
                listOf("message_id", "m2@desent.xyz"),
                listOf("pgp", "encrypted")
            ),
            tags
        )
    }

    @Test
    fun pgpSend_withThreading_keepsBracketedAncestry() {
        val tags = EmailBridgeTags.outbound(
            fromAlias = "alice@desent.xyz",
            to = listOf(EmailRecipient("bob@example.com")),
            subject = "Re: Secret",
            messageId = "m3@desent.xyz",
            inReplyTo = "orig@example.com",
            references = "root@example.com",
            format = EmailBodyFormat.PLAIN,
            pgpEncrypted = true
        )

        assertTrue(tags.contains(listOf("in_reply_to", "<orig@example.com>")))
        assertTrue(tags.contains(listOf("references", "<root@example.com>")))
        assertFalse(tags.any { it[0] == "format" }) // relay ignores format for PGP
        assertTrue(tags.contains(listOf("pgp", "encrypted")))
    }
}
