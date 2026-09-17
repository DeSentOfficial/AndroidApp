package xyz.desent.presentation.ui.email.components

import xyz.desent.domain.model.EmailBodyFormat

/**
 * Splits an email body into (new content, quoted tail) so the thread view can
 * collapse the quoted history every reply drags along (the "jumbled replies"
 * problem). Pure string processing — deliberately heuristic; anything without
 * a recognized marker renders whole.
 */
object EmailQuoteSplitter {

    data class SplitBody(
        val newContent: String,
        val quotedTail: String?
    )

    fun split(content: String, format: EmailBodyFormat): SplitBody {
        if (content.isBlank()) return SplitBody(content, null)
        return when (format) {
            EmailBodyFormat.HTML -> splitHtml(content)
            EmailBodyFormat.PLAIN -> splitPlain(content)
        }
    }

    // ==================== HTML ====================

    /**
     * Well-known HTML quote containers, in order of specificity. The FIRST
     * marker found in the body starts the quoted tail — everything a mail
     * client appends below the quoted block (signatures aside) belongs to it.
     */
    private val HTML_MARKERS = listOf(
        // Gmail: <div class="gmail_quote">…</div>
        Regex("""<div[^>]*class="[^"]*gmail_quote[^"]*"[^>]*>"""),
        // Outlook web: <div id="appendonsend">…</div>
        Regex("""<div[^>]*id="appendonsend"[^>]*>"""),
        // Generic blockquote (Apple Mail, Thunderbird, many others)
        Regex("""<blockquote[^>]*>""")
    )

    private fun splitHtml(html: String): SplitBody {
        val lower = html.lowercase()
        val markerMatch = HTML_MARKERS
            .mapNotNull { regex -> regex.find(lower)?.range?.first?.let { it to it } }
            .minByOrNull { it.first }
            ?: return SplitBody(html, null)

        val (start) = markerMatch
        val head = html.substring(0, start)
        val tail = html.substring(start)

        val trimmedHead = stripTrailingHtmlNoise(head)
        if (trimmedHead.isBlank()) {
            // The whole body is quote — render it whole rather than empty.
            return SplitBody(html, null)
        }
        return SplitBody(trimmedHead, tail)
    }

    /** Trim the stray <br>/<div><br></div>/&nbsp; glue lines mailers leave before a quote. */
    private fun stripTrailingHtmlNoise(head: String): String {
        var out = head.trimEnd()
        while (true) {
            val next = out
                .removeSuffix("<br>")
                .removeSuffix("<br/>")
                .removeSuffix("<br />")
                .removeSuffix("&nbsp;")
                .trimEnd()
            if (next == out || next.isEmpty()) return out
            out = next
        }
    }

    // ==================== Plain text ====================

    /**
     * Plain-text quote headers, matched line-wise:
     *  - Gmail/Apple: "On Mon, Aug 4, 2026 at 10:12 AM X <x@y.z> wrote:"
     *  - Outlook:     a "From: …" line directly followed by a "Sent: …" line
     *  - Forwarded:   "---- Original Message ----" / "-----Original Message-----"
     */
    private val ON_WROTE = Regex("""^On .{1,200} wrote:\s*$""")

    /** `>`-prefixed trailing quote block (Gmail plain quoting, Apple Mail). */
    private fun splitPlain(text: String): SplitBody {
        val lines = text.lines()
        val quoteStart = plainQuoteStart(lines) ?: return SplitBody(text, null)

        val head = lines.subList(0, quoteStart).joinToString("\n").trimEnd()
        if (head.isBlank()) return SplitBody(text, null)

        val tail = lines.subList(quoteStart, lines.size).joinToString("\n").trim()
        if (tail.isBlank()) return SplitBody(text, null)
        return SplitBody(head, tail)
    }

    private fun plainQuoteStart(lines: List<String>): Int? {
        for (i in lines.indices) {
            val line = lines[i].trimEnd()

            // "On … wrote:" header
            if (ON_WROTE.matches(line.trim())) return i

            // "---- Original Message ----" separator
            if (line.matches(Regex("""^-{2,}\s*original message\s*-{2,}$""", RegexOption.IGNORE_CASE))) return i

            // Outlook "From:" immediately followed by "Sent:"
            if (i + 1 < lines.size) {
                val next = lines[i + 1].trim()
                if (line.startsWith("From:") && (next.startsWith("Sent:") || next.startsWith("Date:"))) {
                    // Guard: a "From:" mid-sentence (e.g. quoted signature)
                    // needs to sit after a blank line to be a header block.
                    if (i == 0 || lines[i - 1].isBlank()) return i
                }
            }
        }

        // Trailing run of ">"-quoted lines (allow interleaved blanks).
        var start = -1
        for (i in lines.indices) {
            val l = lines[i]
            if (l.startsWith(">")) {
                if (start == -1) start = i
            } else if (l.isNotBlank()) {
                start = -1
            }
        }
        return if (start > 0) start else null
    }
}
