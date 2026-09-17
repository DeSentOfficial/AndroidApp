package xyz.desent.presentation.ui.notes

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Pure display helpers for the notes list. Kept free of Android/compose
 * dependencies so they are trivially unit-testable.
 */

/** Coarse recency buckets used to section the notes list. */
enum class NoteDateGroup(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This week"),
    EARLIER("Earlier")
}

/**
 * Buckets a note's `updatedAt` (epoch seconds) into a [NoteDateGroup]
 * relative to [nowMillis] (epoch milliseconds; injectable for tests).
 */
fun noteDateGroup(updatedAtSeconds: Long, nowMillis: Long = System.currentTimeMillis()): NoteDateGroup {
    val updatedMillis = updatedAtSeconds * 1000
    val startOfToday = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when {
        updatedMillis >= startOfToday -> NoteDateGroup.TODAY
        updatedMillis >= startOfToday - DAY_MILLIS -> NoteDateGroup.YESTERDAY
        updatedMillis >= startOfToday - 6 * DAY_MILLIS -> NoteDateGroup.THIS_WEEK
        else -> NoteDateGroup.EARLIER
    }
}

/**
 * Compact timestamp for the trailing slot of a note row: "Today" /
 * "Yesterday" for recent notes, "MMM d" within the current year and
 * "MMM d, yyyy" beyond it.
 */
fun formatNoteListDate(updatedAtSeconds: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val group = noteDateGroup(updatedAtSeconds, nowMillis)
    if (group == NoteDateGroup.TODAY) return "Today"
    if (group == NoteDateGroup.YESTERDAY) return "Yesterday"
    val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val currentYear = cal.get(Calendar.YEAR)
    cal.timeInMillis = updatedAtSeconds * 1000
    val pattern = if (cal.get(Calendar.YEAR) == currentYear) "MMM d" else "MMM d, yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(cal.timeInMillis))
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/**
 * Flattens a note body's markdown into a single-line plain-text preview:
 * strips code fences, images, links (keeping their text), heading/list/
 * blockquote markers, emphasis, inline code and table pipes so list rows
 * never leak raw markdown noise. Truncated to [maxLength] characters.
 */
fun notePreview(body: String, maxLength: Int = 200): String {
    if (body.isBlank()) return ""

    val out = StringBuilder()
    var inFence = false
    for (rawLine in body.lines()) {
        val line = rawLine.trim()
        if (line.startsWith("```")) {
            inFence = !inFence
            continue
        }
        if (inFence) continue
        if (line.isEmpty()) continue
        // Table alignment rows (| --- | :---: |) and horizontal rules.
        if (line.isBlankOrRule()) continue
        // Link reference definitions: [label]: https://…
        if (LINK_REF_DEFINITION.matches(line)) continue

        var text = line
        text = text.stripImages()
        text = text.stripLinks()
        text = text.stripHeadingMarker()
        text = text.stripBlockquoteMarker()
        text = text.stripListMarker()
        text = text.stripEmphasis()
        text = text.stripInlineCode()
        if (text.contains('|')) {
            text = text.trim('|').split('|').joinToString(" · ") { it.trim() }
        }
        text = text.trim()
        if (text.isEmpty()) continue
        if (out.isNotEmpty()) out.append(' ')
        out.append(text)
        if (out.length >= maxLength) break
    }

    var result = out.toString().collapseSpaces()
    if (result.length > maxLength) {
        result = result.take(maxLength).trimEnd() + "…"
    }
    return result
}

private fun String.isBlankOrRule(): Boolean {
    val stripped = replace("|", "").trim()
    if (stripped.isEmpty()) return false // a bare "|" line is meaningless; keep handled by pipe cleanup
    return HORIZONTAL_RULE.matches(stripped) || TABLE_RULE.matches(this)
}

private val HORIZONTAL_RULE = Regex("^([-*_])\\1{2,}$")
private val TABLE_RULE = Regex("^\\|?[\\s:|-]*-{3,}[\\s:|-]*\\|?$")
private val LINK_REF_DEFINITION = Regex("^\\[[^\\]]*]:\\s*\\S+.*$")

private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
private val HEADING_MARKER = Regex("^#{1,6}\\s+")
private val BLOCKQUOTE_MARKER = Regex("^(>\\s?)+")
private val LIST_MARKER = Regex("^(?:[-*+]|\\d{1,3}[.)])\\s+")
private val BOLD_ITALIC = Regex("(\\*{1,3}|_{1,3})(.+?)\\1")
private val STRIKETHROUGH = Regex("~~(.+?)~~")
private val INLINE_CODE = Regex("`([^`]*)`")

private fun String.stripImages(): String = IMAGE.replace(this) { it.groupValues[1] }
private fun String.stripLinks(): String = LINK.replace(this) { it.groupValues[1] }
private fun String.stripHeadingMarker(): String = replace(HEADING_MARKER, "")
private fun String.stripBlockquoteMarker(): String = replace(BLOCKQUOTE_MARKER, "")
private fun String.stripListMarker(): String = replace(LIST_MARKER, "")
private fun String.stripEmphasis(): String =
    BOLD_ITALIC.replace(STRIKETHROUGH.replace(this) { it.groupValues[1] }) { it.groupValues[2] }
private fun String.stripInlineCode(): String = INLINE_CODE.replace(this) { it.groupValues[1] }

private fun String.collapseSpaces(): String = replace(Regex("\\s{2,}"), " ").trim()
