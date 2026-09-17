package xyz.desent.data

/**
 * Lossy HTML → plain-text conversion shared by the inbox snippet renderer and
 * the outbound quote builder. Deliberately heuristic: drops style/script/head
 * blocks, strips tags, decodes the common entities. Whitespace collapsing is
 * left to the caller so quoting (which must preserve line structure) can keep
 * its own newlines.
 */
object HtmlTextUtils {

    /** Strip tags and decode entities; collapses runs of whitespace to single spaces. */
    fun stripHtml(html: String): String =
        collapseWhitespace(stripHtmlKeepLines(html))

    /**
     * Strip tags and decode entities while preserving line breaks — `&nbsp;`,
     * `<br>` and block-level closings become newlines, so quoted paragraphs
     * survive as separate lines.
     */
    fun stripHtmlKeepLines(html: String): String {
        val noBlocks = html.replace(Regex("(?is)<(style|script|head)[^>]*>.*?</\\1>"), " ")
        val asLines = noBlocks
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|tr|li|h[1-6]|blockquote)>"), "\n")
        val noTags = asLines.replace(Regex("<[^>]+>"), " ")
        return decodeEntities(noTags)
    }

    fun collapseWhitespace(s: String): String = s.replace(Regex("\\s+"), " ")

    /** `&amp;` decodes last so escaped entities (`&amp;lt;`) survive round-trips. */
    fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
