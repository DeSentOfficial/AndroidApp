package xyz.desent.presentation.deeplink

/**
 * Detection for markdown documents handed to DeSent via ACTION_VIEW (file
 * managers, "open with" choosers, the Notes in-app picker). Mirrors the
 * manifest's two markdown intent-filters: the proper MIME types and the
 * extension-based filter for providers that report `application/octet-stream`.
 *
 * String-based (no `Uri`) so the matching rules are unit-testable in plain
 * JVM tests.
 */
object MarkdownFileIntents {

    val MARKDOWN_MIME_TYPES: Set<String> = setOf("text/markdown", "text/x-markdown")

    private val MARKDOWN_EXTENSIONS = listOf(".md", ".markdown")
    private val HANDLED_SCHEMES = setOf("content", "file")

    /**
     * True when an ACTION_VIEW data URI + MIME type pair describes a markdown
     * document DeSent can open: scheme `content`/`file` with a markdown MIME
     * type, or a path ending in `.md`/`.markdown` (case-insensitive, query
     * string and fragment ignored).
     */
    fun isMarkdownDocument(uriString: String?, mimeType: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val scheme = uriString.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme !in HANDLED_SCHEMES) return false
        if (mimeType != null && mimeType.trim().lowercase() in MARKDOWN_MIME_TYPES) return true
        val path = uriString.substringBefore('?').substringBefore('#')
        return MARKDOWN_EXTENSIONS.any { path.endsWith(it, ignoreCase = true) }
    }
}
