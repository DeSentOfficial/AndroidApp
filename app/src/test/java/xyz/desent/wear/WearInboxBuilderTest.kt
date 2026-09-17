package xyz.desent.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.data.local.database.entity.EmailEntity

class WearInboxBuilderTest {

    private fun entity(
        id: String,
        subject: String = "Subject $id",
        content: String = "Hello from the test suite.",
        bodyFormat: String? = "PLAIN",
        senderName: String? = null,
        senderEmail: String = "sender@example.com",
        createdAt: Long = 1000L,
        isRead: Boolean = false,
        isPgp: Boolean = false,
        direction: String? = "INBOUND",
        attachmentsJson: String? = null,
        threadRoot: String? = null,
        isSpam: Boolean = false
    ) = EmailEntity(
        id = id,
        recipientNpub = "npub1test",
        senderEmail = senderEmail,
        senderDomain = "example.com",
        senderName = senderName,
        subject = subject,
        content = content,
        bodyFormat = bodyFormat,
        dkimStatus = "PASS",
        emailType = "OTHER",
        bridge = "none",
        messageId = "<$id@example.com>",
        threadToken = null,
        threadRoot = threadRoot,
        createdAt = createdAt,
        isRead = isRead,
        isPgpEncrypted = isPgp,
        direction = direction,
        attachmentsJson = attachmentsJson,
        isSpam = isSpam
    )

    @Test
    fun mapsThreadRowsToWearEmails() {
        val inbox = WearInboxBuilder.build(
            threads = listOf(
                entity(
                    "a",
                    senderName = "Alice",
                    subject = "Hi",
                    content = "Plain body",
                    createdAt = 20L,
                    attachmentsJson = """[{"sha256":"aa","mimeType":"image/png","size":5,"keyHex":"k","filename":"a.png"},{"sha256":"bb","mimeType":"image/png","size":6,"keyHex":"k","filename":"b.png"}]"""
                )
            ),
            spamThreads = emptyList(),
            spamEnabled = true,
            unreadCount = 1,
            syncedAt = 5L
        )
        val email = inbox.emails.single()
        assertEquals("Alice", email.senderName)
        assertEquals("Plain body", email.body)
        assertEquals("Hi", email.subject)
        assertEquals(20L, email.createdAt)
        assertEquals(false, email.isRead)
        assertEquals(2, email.attachmentCount)
        assertEquals("a", email.threadKey) // threadRoot ?: token ?: id
        assertEquals(5L, inbox.syncedAt)
    }

    @Test
    fun htmlBodyIsStrippedWithParagraphBreaks() {
        val html = """
            <html><head><style>body{color:red}</style></head>
            <body><p>First paragraph.</p><p>Second &amp; paragraph.</p>
            <div>Line three<br>after break</div></body></html>
        """.trimIndent()
        val inbox = WearInboxBuilder.build(
            threads = listOf(entity("a", content = html, bodyFormat = "HTML")),
            spamThreads = emptyList(),
            spamEnabled = true,
            unreadCount = 0
        )
        val body = inbox.emails.single().body
        assertTrue(body.startsWith("First paragraph."))
        assertTrue(body.contains("Second & paragraph."))
        assertTrue(body.contains("Line three\nafter break"))
        assertTrue("style block must be dropped: '$body'", !body.contains("color:red"))
    }

    @Test
    fun inboundPgpGetsPlaceholderOutboundPgpKeepsBody() {
        val inbox = WearInboxBuilder.build(
            threads = listOf(
                entity("in", content = "-----BEGIN PGP MESSAGE-----\nabc", isPgp = true, direction = "INBOUND"),
                entity("out", content = "My typed draft", isPgp = true, direction = "OUTBOUND")
            ),
            spamThreads = emptyList(),
            spamEnabled = true,
            unreadCount = 0
        )
        val inbound = inbox.emails.first { it.id == "in" }
        val outbound = inbox.emails.first { it.id == "out" }
        assertTrue(inbound.isPgp)
        assertEquals(WearInboxBuilder.PGP_PLACEHOLDER, inbound.body)
        assertTrue(!inbound.body.contains("BEGIN PGP"))
        assertEquals(false, outbound.isPgp)
        assertEquals("My typed draft", outbound.body)
    }

    @Test
    fun capsThreadCountsAndBodyLength() {
        // Builder contract: input lists are newest-first (DAO ORDER BY createdAt DESC).
        val threads = (1..40).map { entity("e$it", content = "x".repeat(6000), createdAt = (41 - it).toLong()) }
        val inbox = WearInboxBuilder.build(
            threads = threads,
            spamThreads = threads,
            spamEnabled = true,
            unreadCount = 0
        )
        assertEquals(WearInboxBuilder.MAX_EMAILS, inbox.emails.size)
        assertEquals(WearInboxBuilder.MAX_SPAM, inbox.spam.size)
        // take() keeps the 25 newest; the oldest entries are dropped
        assertTrue(inbox.emails.none { it.id == "e40" })
        assertTrue(inbox.emails.any { it.id == "e1" })
        assertTrue(inbox.emails.all { it.body.length <= WearInboxBuilder.MAX_BODY_CHARS })
    }

    @Test
    fun spamFlaggedRowsAreExcludedFromInboxList() {
        val inbox = WearInboxBuilder.build(
            threads = listOf(
                entity("clean", createdAt = 20L),
                entity("flagged", createdAt = 10L, isSpam = true)
            ),
            spamThreads = listOf(entity("flagged", createdAt = 10L, isSpam = true)),
            spamEnabled = true,
            unreadCount = 1
        )
        assertEquals(listOf("clean"), inbox.emails.map { it.id })
        assertEquals(listOf("flagged"), inbox.spam.map { it.id })
    }

    @Test
    fun spamDisabledYieldsEmptySpamList() {
        val inbox = WearInboxBuilder.build(
            threads = emptyList(),
            spamThreads = listOf(entity("s1")),
            spamEnabled = false,
            unreadCount = 0
        )
        assertEquals(0, inbox.spam.size)
        assertEquals(false, inbox.spamEnabled)
    }

    @Test
    fun blankSubjectFallsBackAndLongFieldsAreCapped() {
        val inbox = WearInboxBuilder.build(
            threads = listOf(
                entity(
                    "a",
                    subject = "",
                    senderEmail = (1..12).joinToString(".") { "segment$it.example.subdomain" } + "@mail.example.com"
                )
            ),
            spamThreads = emptyList(),
            spamEnabled = true,
            unreadCount = 0
        )
        val email = inbox.emails.single()
        assertEquals("(no subject)", email.subject)
        assertTrue(email.senderEmail.length <= WearInboxBuilder.MAX_SENDER_CHARS)
        assertTrue(email.senderEmail.endsWith("…"))
    }

    @Test
    fun malformedAttachmentsJsonCountsAsZero() {
        val inbox = WearInboxBuilder.build(
            threads = listOf(entity("a", attachmentsJson = "not json at all")),
            spamThreads = emptyList(),
            spamEnabled = true,
            unreadCount = 0
        )
        assertEquals(0, inbox.emails.single().attachmentCount)
    }
}
