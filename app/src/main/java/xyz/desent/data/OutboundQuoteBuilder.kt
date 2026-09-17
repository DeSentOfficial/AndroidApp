package xyz.desent.data

import xyz.desent.data.local.database.entity.EmailEntity
import xyz.desent.domain.model.EmailBodyFormat
import xyz.desent.domain.model.EmailType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Appends the quoted anchor message below a reply's text before it is sent —
 * standard email behavior (Gmail semantics): the recipient sees the message
 * they wrote under the new reply, and because that anchor may itself carry
 * older quoted history, the chain accumulates naturally over turns.
 *
 * The emitted quote uses the markers mail clients already understand:
 *  - HTML send → `<div class="gmail_quote">…<blockquote>…</blockquote></div>`
 *  - PLAIN send → `On … wrote:` header over `>`-prefixed lines
 * Both are recognized by [xyz.desent.presentation.ui.email.components.EmailQuoteSplitter],
 * so DeSent recipients auto-collapse the history ("Show quoted content").
 *
 * Pure string processing — no I/O, fully deterministic given the anchor row.
 */
object OutboundQuoteBuilder {

    /** Keep the attribution inside the plain splitter's `^On .{1,200} wrote:$` envelope. */
    private const val MAX_SENDER_CHARS = 80

    private const val TRUNCATION_NOTE = "[quoted history truncated]"

    /**
     * Build the outbound body for a reply: [replyText] followed by the quoted
     * [anchor]. Returns [replyText] unchanged when there is nothing quotable
     * (bridge receipts, PGP-armored anchors, blank bodies) or no room left
     * under [maxTotalBytes]. The combined body never exceeds [maxTotalBytes]
     * UTF-8 bytes — oversize history is trimmed from its end (the oldest
     * nested history) and marked truncated.
     */
    fun build(
        replyText: String,
        anchor: EmailEntity,
        format: EmailBodyFormat,
        maxTotalBytes: Int
    ): String {
        if (anchor.emailType == EmailType.SYSTEM.name) return replyText
        if (anchor.isPgpEncrypted) return replyText
        if (anchor.content.isBlank()) return replyText

        val quote = quoteFor(anchor, format)
        val budget = maxTotalBytes - replyText.toByteArray(Charsets.UTF_8).size
        if (budget <= TRUNCATION_NOTE.toByteArray(Charsets.UTF_8).size + 8) {
            // The reply alone fills the cap — nothing meaningful to quote.
            return replyText
        }

        val quotable = if (quote.toByteArray(Charsets.UTF_8).size <= budget) {
            quote
        } else {
            cutToByteBudget(quote, budget - TRUNCATION_NOTE.toByteArray(Charsets.UTF_8).size)
                .let { if (format == EmailBodyFormat.HTML) dropPartialTag(it) else trimToLine(it) } +
                truncationNote(format)
        }
        return replyText + quotable
    }

    /** The quoted anchor in the send [format], attribution header included. */
    private fun quoteFor(anchor: EmailEntity, format: EmailBodyFormat): String {
        val date = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
            .format(Date(anchor.senderDate ?: anchor.createdAt))
        val sender = attributionSender(anchor)
        val anchorFormat = EmailBodyFormat.fromWire(anchor.bodyFormat)
        return when (format) {
            EmailBodyFormat.HTML -> {
                val anchorHtml = if (anchorFormat == EmailBodyFormat.HTML) {
                    anchor.content
                } else {
                    EmailBridgeTags.plainToHtml(anchor.content)
                }
                "<br><br><div class=\"gmail_quote\">On ${escapeHtml(date)}, " +
                    "${escapeHtml(sender)} wrote:<blockquote>$anchorHtml</blockquote></div>"
            }
            EmailBodyFormat.PLAIN -> {
                val anchorPlain = if (anchorFormat == EmailBodyFormat.HTML) {
                    // Tags leave stray spaces behind; trim each stripped line
                    // (plain anchors are prefixed verbatim, spaces included).
                    HtmlTextUtils.stripHtmlKeepLines(anchor.content)
                        .lines().joinToString("\n") { it.trim() }
                } else {
                    anchor.content
                }
                "\n\nOn $date, $sender wrote:\n" +
                    anchorPlain.lines().joinToString("\n") { if (it.isBlank()) ">" else "> $it" }
            }
        }
    }

    private fun truncationNote(format: EmailBodyFormat): String = when (format) {
        EmailBodyFormat.HTML -> "<br>… $TRUNCATION_NOTE"
        EmailBodyFormat.PLAIN -> "\n> … $TRUNCATION_NOTE"
    }

    private fun attributionSender(anchor: EmailEntity): String {
        val raw = anchor.senderName?.takeIf { it.isNotBlank() } ?: anchor.senderEmail
        return if (raw.length > MAX_SENDER_CHARS) raw.take(MAX_SENDER_CHARS - 1) + "…" else raw
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** Longest prefix of [s] fitting in [maxBytes] UTF-8 bytes, never splitting a char. */
    private fun cutToByteBudget(s: String, maxBytes: Int): String {
        var bytes = 0
        var end = 0
        for ((i, ch) in s.withIndex()) {
            val charBytes = when {
                ch.code <= 0x7F -> 1
                ch.code <= 0x7FF -> 2
                ch.code <= 0xFFFF -> 3
                else -> 4
            }
            if (bytes + charBytes > maxBytes) break
            bytes += charBytes
            end = i + 1
        }
        return s.substring(0, end)
    }

    /** Plain quotes cut at a line boundary so no half-line dangles. */
    private fun trimToLine(s: String): String =
        s.substringBeforeLast('\n').trimEnd(' ', '\r')

    /** HTML quotes drop a trailing partial tag (budget cut mid-`<tag`). */
    private fun dropPartialTag(s: String): String {
        val lastOpen = s.lastIndexOf('<')
        val lastClose = s.lastIndexOf('>')
        return if (lastOpen > lastClose) s.substring(0, lastOpen) else s
    }
}
