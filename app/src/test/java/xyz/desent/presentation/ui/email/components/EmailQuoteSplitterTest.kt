package xyz.desent.presentation.ui.email.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.domain.model.EmailBodyFormat

/**
 * Quote-splitting vectors derived from real MUA quoting: Gmail ("On … wrote:"
 * + `>` chains), Outlook ("From:/Sent:" header blocks), and the common HTML
 * quote containers (gmail_quote div, blockquote).
 */
class EmailQuoteSplitterTest {

    private fun splitPlain(content: String) = EmailQuoteSplitter.split(content, EmailBodyFormat.PLAIN)
    private fun splitHtml(content: String) = EmailQuoteSplitter.split(content, EmailBodyFormat.HTML)

    @Test
    fun plain_gmailOnWroteHeader() {
        val body = "Sure, sounds good.\n\n" +
            "On Mon, Aug 4, 2026 at 10:12 AM Bob <bob@example.com> wrote:\n" +
            "> what do you think?\n> > earlier"
        val split = splitPlain(body)
        assertEquals("Sure, sounds good.", split.newContent)
        assertEquals(
            "On Mon, Aug 4, 2026 at 10:12 AM Bob <bob@example.com> wrote:\n> what do you think?\n> > earlier",
            split.quotedTail
        )
    }

    @Test
    fun plain_outlookFromSentHeader() {
        val body = "Answer below.\n\nFrom: Bob\nSent: Monday, August 4, 2026 10:12 AM\nTo: Alice\nSubject: test\n\nthe question"
        val split = splitPlain(body)
        assertEquals("Answer below.", split.newContent)
        assertTrue(split.quotedTail!!.startsWith("From: Bob"))
        assertTrue(split.quotedTail!!.endsWith("the question"))
    }

    @Test
    fun plain_quotedLineChain() {
        val split = splitPlain("new content\n> quoted reply\n> more history")
        assertEquals("new content", split.newContent)
        assertEquals("> quoted reply\n> more history", split.quotedTail)
    }

    @Test
    fun plain_originalMessageSeparator() {
        val body = "my reply\n\n---- Original Message ----\nFrom: Bob\n\nprior"
        val split = splitPlain(body)
        assertEquals("my reply", split.newContent)
        assertTrue(split.quotedTail!!.startsWith("---- Original Message ----"))
    }

    @Test
    fun plain_noQuote_rendersWhole() {
        val body = "just a normal message\nwith lines\nand no quotes"
        val split = splitPlain(body)
        assertEquals(body, split.newContent)
        assertNull(split.quotedTail)
    }

    @Test
    fun plain_allQuote_rendersWhole() {
        val body = "> only quoted content\n> nothing new"
        val split = splitPlain(body)
        assertEquals(body, split.newContent)
        assertNull(split.quotedTail)
    }

    @Test
    fun html_gmailQuoteDiv() {
        val body = "<p>new reply</p><br><br><div class=\"gmail_quote\">quoted history</div>"
        val split = splitHtml(body)
        assertEquals("<p>new reply</p>", split.newContent)
        assertEquals("<div class=\"gmail_quote\">quoted history</div>", split.quotedTail)
    }

    @Test
    fun html_blockquote() {
        val body = "<p>new</p><blockquote type=\"cite\">old</blockquote>"
        val split = splitHtml(body)
        assertEquals("<p>new</p>", split.newContent)
        assertEquals("<blockquote type=\"cite\">old</blockquote>", split.quotedTail)
    }

    @Test
    fun html_outlookAppendOnSend() {
        val body = "<div>new</div><div id=\"appendonsend\">quote</div>"
        val split = splitHtml(body)
        assertEquals("<div>new</div>", split.newContent)
        assertEquals("<div id=\"appendonsend\">quote</div>", split.quotedTail)
    }

    @Test
    fun html_leadingQuoteOnly_rendersWhole() {
        val body = "<div class=\"gmail_quote\">everything is a quote</div>"
        val split = splitHtml(body)
        assertEquals(body, split.newContent)
        assertNull(split.quotedTail)
    }

    @Test
    fun html_noMarker_rendersWhole() {
        val body = "<p>plain body</p><p>more</p>"
        val split = splitHtml(body)
        assertEquals(body, split.newContent)
        assertNull(split.quotedTail)
    }

    @Test
    fun html_earliestMarkerWins() {
        val body = "<p>new</p><blockquote>first marker</blockquote><div class=\"gmail_quote\">second</div>"
        val split = splitHtml(body)
        assertEquals("<p>new</p>", split.newContent)
        assertTrue(split.quotedTail!!.startsWith("<blockquote>"))
    }

    @Test
    fun blankBody_noTail() {
        val split = splitPlain("")
        assertEquals("", split.newContent)
        assertNull(split.quotedTail)
    }
}
