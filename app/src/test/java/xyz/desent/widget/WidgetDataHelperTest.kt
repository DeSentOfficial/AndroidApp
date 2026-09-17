package xyz.desent.widget

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.data.local.database.dao.CalendarEventDao
import xyz.desent.data.local.database.dao.FavoriteNoteDao
import xyz.desent.data.local.database.dao.PrivateNoteDao
import xyz.desent.data.local.database.dao.UserDao
import xyz.desent.data.local.database.entity.PrivateNoteEntity
import xyz.desent.data.local.preferences.PreferencesManager

class WidgetDataHelperTest {

    private val helper = WidgetDataHelper(
        userDao = mockk<UserDao>(relaxed = true),
        preferencesManager = mockk<PreferencesManager>(relaxed = true),
        privateNoteDao = mockk<PrivateNoteDao>(relaxed = true),
        favoriteNoteDao = mockk<FavoriteNoteDao>(relaxed = true),
        calendarEventDao = mockk<CalendarEventDao>(relaxed = true)
    )

    @Test
    fun `RECENT returns notes sorted by updatedAt desc and limited`() {
        val rows = listOf(
            note("a", "Alpha", "body-a", updatedAt = 100),
            note("c", "Charlie", "body-c", updatedAt = 300),
            note("b", "Bravo", "body-b", updatedAt = 200)
        )

        val result = helper.buildNoteItems(rows, favIds = emptySet(), NotesWidgetMode.RECENT, limit = 2)

        assertEquals(listOf("c", "b"), result.map { it.id })
    }

    @Test
    fun `FAVORITES only includes notes whose id is in favIds`() {
        val rows = listOf(
            note("a", "Alpha", "body-a", updatedAt = 100),
            note("b", "Bravo", "body-b", updatedAt = 200),
            note("c", "Charlie", "body-c", updatedAt = 300)
        )

        val result = helper.buildNoteItems(rows, favIds = setOf("a", "c"), NotesWidgetMode.FAVORITES, limit = 10)

        assertEquals(listOf("c", "a"), result.map { it.id })
    }

    @Test
    fun `FAVORITES with no pins returns empty`() {
        val rows = listOf(note("a", "Alpha", "body-a", 100))

        val result = helper.buildNoteItems(rows, favIds = emptySet(), NotesWidgetMode.FAVORITES, limit = 10)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `blank title renders as untitled`() {
        val rows = listOf(note("a", title = "", body = "body", updatedAt = 1))

        val result = helper.buildNoteItems(rows, emptySet(), NotesWidgetMode.RECENT, 10)

        assertEquals("(untitled)", result.single().title)
    }

    @Test
    fun `body preview uses first non-blank line and truncates to 80 chars`() {
        val longLine = "x".repeat(120)
        val rows = listOf(note("a", "Title", "\n  \n$longLine", updatedAt = 1))

        val result = helper.buildNoteItems(rows, emptySet(), NotesWidgetMode.RECENT, 10)

        assertEquals(80, result.single().bodyPreview.length)
        assertTrue(result.single().bodyPreview.all { it == 'x' })
    }

    @Test
    fun `blank body produces empty preview`() {
        val rows = listOf(note("a", "Title", body = "   \n  ", updatedAt = 1))

        val result = helper.buildNoteItems(rows, emptySet(), NotesWidgetMode.RECENT, 10)

        assertEquals("", result.single().bodyPreview)
    }

    private fun note(
        id: String,
        title: String,
        body: String,
        updatedAt: Long,
        folder: String = ""
    ) = PrivateNoteEntity(
        id = id,
        ownerNpub = "npub_owner",
        title = title,
        body = body,
        updatedAt = updatedAt,
        folder = folder,
        attachmentsJson = "[]",
        dTag = "desent:note:$id",
        createdAt = updatedAt
    )
}
