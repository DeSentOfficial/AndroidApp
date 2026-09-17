package xyz.desent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.data.local.database.entity.EmailEntity
import xyz.desent.domain.model.EmailBodyFormat
import xyz.desent.presentation.ui.email.components.EmailQuoteSplitter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Quoted-history appending on replies (the "blank threads" fix): the anchor
 * rides below the reply in the markers every mail client understands — and
 * that [EmailQuoteSplitter] recognizes, so DeSent recipients auto-collapse it.
 */
class OutboundQuoteBuilderTest {

    private val maxBytes = 10_240

    private fun anchor(
        content: String,
        bodyFormat: String? = "PLAIN",
        senderName: String? = "Bob",
        senderEmail: String = "bob@example.com",
        emailType: String = "OTHER",
        isPgpEncrypted: Boolean = false,
        senderDate: Long? = 1_700_000_000_000L
    ) = EmailEntity(
        id = "a1",
        recipientNpub = "npub1",
        senderEmail = senderEmail,
        senderDomain = "example.com",
        senderName = senderName,
        subject = "Re: hi",
        content = content,
        bodyFormat = bodyFormat,
        dkimStatus = "NONE",
        threadToken = null,
        emailType = emailType,
        bridge = "email",
        messageId = "m@x",
        threadRoot = "root-key",
        senderDate = senderDate,
        createdAt = 1_700_000_100_000L,
        isPgpEncrypted = isPgpEncrypted
    )

    private fun expectedDate() = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
        .format(Date(1_700_000_000_000L))

    @Test
    fun htmlReplyToHtmlAnchor_appendsGmailStyleQuoteBlock() {
        val body = OutboundQuoteBuilder.build(
            "thanks!", anchor("<p>hi</p>", bodyFormat = "HTML"),
            EmailBodyFormat.HTML, maxBytes
        )
        assertEquals(
            "thanks!<br><br><div class=\"gmail_quote\">On ${expectedDate()}, Bob wrote:" +
                "<blockquote><p>hi</p></blockquote></div>",
            body
        )
    }

    @Test
    fun htmlReplyToPlainAnchor_convertsAnchorToHtml() {
        val body = OutboundQuoteBuilder.build(
            "thanks!", anchor("line1\nline2", bodyFormat = "PLAIN"),
            EmailBodyFormat.HTML, maxBytes
        )
        assertTrue(body.contains("<blockquote>line1<br>line2</blockquote>"))
    }

    @Test
    fun htmlReply_escapesAttributionText() {
        val body = OutboundQuoteBuilder.build(
            "ok", anchor("<p>x</p>", bodyFormat = "HTML", senderName = "A&B <bob>"),
            EmailBodyFormat.HTML, maxBytes
        )
        assertTrue(body.contains("A&amp;B &lt;bob&gt; wrote:"))
    }

    @Test
    fun plainReplyToPlainAnchor_prefixesEveryLine() {
        val body = OutboundQuoteBuilder.build(
            "thanks!", anchor("line1\n\nline2", bodyFormat = "PLAIN"),
            EmailBodyFormat.PLAIN, maxBytes
        )
        assertEquals(
            "thanks!\n\nOn ${expectedDate()}, Bob wrote:\n> line1\n>\n> line2",
            body
        )
    }

    @Test
    fun plainReplyToHtmlAnchor_stripsTagsKeepingLines() {
        val body = OutboundQuoteBuilder.build(
            "thanks!", anchor("<p>para one</p><p>para two</p>", bodyFormat = "HTML"),
            EmailBodyFormat.PLAIN, maxBytes
        )
        assertTrue(body.contains("\n> para one\n> para two"))
    }

    @Test
    fun skipsQuoting_forSystemPgpAndBlankAnchors() {
        assertEquals(
            "thanks!",
            OutboundQuoteBuilder.build(
                "thanks!", anchor("status line", emailType = "SYSTEM"),
                EmailBodyFormat.PLAIN, maxBytes
            )
        )
        assertEquals(
            "thanks!",
            OutboundQuoteBuilder.build(
                "thanks!", anchor("-----BEGIN PGP MESSAGE-----", isPgpEncrypted = true),
                EmailBodyFormat.PLAIN, maxBytes
            )
        )
        assertEquals(
            "thanks!",
            OutboundQuoteBuilder.build(
                "thanks!", anchor("  ", bodyFormat = "PLAIN"),
                EmailBodyFormat.PLAIN, maxBytes
            )
        )
    }

    @Test
    fun replyTextAloneFillsTheCap_sendsUnquoted() {
        // A reply saturating the cap leaves no room for a quote.
        val big = "a".repeat(maxBytes)
        assertEquals(
            big,
            OutboundQuoteBuilder.build(big, anchor("hello", bodyFormat = "PLAIN"), EmailBodyFormat.PLAIN, maxBytes)
        )
    }

    @Test
    fun oversizedHistory_truncatesFromItsEnd_andStaysUnderCap() {
        val huge = "x".repeat(20_000)
        val body = OutboundQuoteBuilder.build(
            "thanks!", anchor(huge, bodyFormat = "PLAIN"), EmailBodyFormat.PLAIN, maxBytes
        )
        assertTrue(body.toByteArray(Charsets.UTF_8).size <= maxBytes)
        assertTrue(body.startsWith("thanks!\n\nOn "))
        assertTrue(body.contains("[quoted history truncated]"))
    }

    @Test
    fun truncationNeverSplitsMultibyteCharacters() {
        val multibyte = "é".repeat(9_000)
        val body = OutboundQuoteBuilder.build(
            "ok", anchor(multibyte, bodyFormat = "PLAIN"), EmailBodyFormat.PLAIN, maxBytes
        )
        assertTrue(body.toByteArray(Charsets.UTF_8).size <= maxBytes)
        // No replacement char from a chopped UTF-8 sequence.
        assertTrue(!body.contains("\uFFFD"))
    }

    // ---------------------------------------------------------------
    // Round-trip: what we emit must collapse on DeSent recipients
    // ---------------------------------------------------------------

    @Test
    fun emittedHtmlBody_splitsIntoNewContentAndQuotedTail() {
        val body = OutboundQuoteBuilder.build(
            "thanks!", anchor("<p>hi</p>", bodyFormat = "HTML"),
            EmailBodyFormat.HTML, maxBytes
        )
        val split = EmailQuoteSplitter.split(body, EmailBodyFormat.HTML)
        assertEquals("thanks!", split.newContent)
        assertTrue(split.quotedTail != null)
    }

    @Test
    fun emittedPlainBody_splitsIntoNewContentAndQuotedTail() {
        val body = OutboundQuoteBuilder.build(
            "thanks!", anchor("line1", bodyFormat = "PLAIN"), EmailBodyFormat.PLAIN, maxBytes
        )
        val split = EmailQuoteSplitter.split(body, EmailBodyFormat.PLAIN)
        assertEquals("thanks!", split.newContent)
        assertTrue(split.quotedTail!!.startsWith("On "))
        assertTrue(split.quotedTail.contains("line1"))
    }

    @Test
    fun htmlTailCarriesTheAnchorText_forCollapsingOnDeSent() {
        val body = OutboundQuoteBuilder.build(
            "thanks!", anchor("<p>hi</p>", bodyFormat = "HTML"),
            EmailBodyFormat.HTML, maxBytes
        )
        val split = EmailQuoteSplitter.split(body, EmailBodyFormat.HTML)
        assertTrue(split.quotedTail!!.contains("<blockquote><p>hi</p></blockquote>"))
    }
}
