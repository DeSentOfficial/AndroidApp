package xyz.desent.presentation.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class NoteListFormattersTest {

    // ------------------------------------------------------------------
    // notePreview
    // ------------------------------------------------------------------

    @Test
    fun preview_blankBody() {
        assertEquals("", notePreview(""))
        assertEquals("", notePreview("   \n  \n"))
    }

    @Test
    fun preview_stripsHeadingListAndEmphasis() {
        val body = "# Title\n\n- **bold** item\n- *italic* item\n\nplain `code` text"
        assertEquals("Title bold item italic item plain code text", notePreview(body))
    }

    @Test
    fun preview_keepsLinkTextDropsTarget() {
        val body = "See [the docs](https://example.com/a?b=c) for details"
        assertEquals("See the docs for details", notePreview(body))
    }

    @Test
    fun preview_imageFallsBackToAltText() {
        val body = "Before\n\n![screenshot](attachment:0123456789abcdef)\n\nAfter"
        assertEquals("Before screenshot After", notePreview(body))
    }

    @Test
    fun preview_dropsFencedCodeBlocks() {
        val body = "intro\n\n```kotlin\nval x = 1\n```\n\noutro"
        assertEquals("intro outro", notePreview(body))
    }

    @Test
    fun preview_flattensTablesToCells() {
        val body = "Comparison:\n\n| Name | Qty |\n|------|-----|\n| Apples | 3 |\n| Pears | 5 |"
        assertEquals("Comparison: Name · Qty Apples · 3 Pears · 5", notePreview(body))
    }

    @Test
    fun preview_stripsBlockquotesAndOrderedLists() {
        val body = "> quoted wisdom\n1. first step\n2. second step"
        assertEquals("quoted wisdom first step second step", notePreview(body))
    }

    @Test
    fun preview_strikesStrikethrough() {
        // Struck text stays visible in rendered markdown; only the markers go.
        assertEquals("draft final", notePreview("~~draft~~ final"))
    }

    @Test
    fun preview_truncatesWithEllipsis() {
        val body = (1..50).joinToString(" ") { "word$it" }
        val result = notePreview(body, maxLength = 40)
        assertEquals(41, result.length)
        assertEquals("…", result.takeLast(1))
        assertTruePrefix(result, "word1")
    }

    @Test
    fun preview_dropsHorizontalRules() {
        assertEquals("above below", notePreview("above\n\n---\n\nbelow"))
    }

    private fun assertTruePrefix(actual: String, prefix: String) {
        assert(actual.startsWith(prefix)) { "expected prefix '$prefix' in '$actual'" }
    }

    // ------------------------------------------------------------------
    // noteDateGroup / formatNoteListDate
    // ------------------------------------------------------------------

    private val now: Long = Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, 29, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val startOfToday: Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun secondsAt(millis: Long): Long = millis / 1000

    @Test
    fun dateGroup_boundaries() {
        assertEquals(NoteDateGroup.TODAY, noteDateGroup(secondsAt(now), now))
        assertEquals(NoteDateGroup.TODAY, noteDateGroup(secondsAt(startOfToday), now))
        assertEquals(
            NoteDateGroup.YESTERDAY,
            noteDateGroup(secondsAt(startOfToday - 12 * 3_600_000), now)
        )
        assertEquals(
            NoteDateGroup.THIS_WEEK,
            noteDateGroup(secondsAt(startOfToday - 3 * 86_400_000L), now)
        )
        assertEquals(
            NoteDateGroup.EARLIER,
            noteDateGroup(secondsAt(startOfToday - 30 * 86_400_000L), now)
        )
    }

    @Test
    fun listDate_recentLabels() {
        assertEquals("Today", formatNoteListDate(secondsAt(now), now))
        assertEquals("Yesterday", formatNoteListDate(secondsAt(startOfToday - 1_000), now))
    }

    @Test
    fun listDate_sameYearUsesShortPattern() {
        val ts = secondsAt(startOfToday - 3 * 86_400_000L)
        val expected = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts * 1000))
        assertEquals(expected, formatNoteListDate(ts, now))
    }

    @Test
    fun listDate_otherYearIncludesYear() {
        val ts = secondsAt(startOfToday - 400 * 86_400_000L)
        val expected = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ts * 1000))
        assertEquals(expected, formatNoteListDate(ts, now))
    }
}
