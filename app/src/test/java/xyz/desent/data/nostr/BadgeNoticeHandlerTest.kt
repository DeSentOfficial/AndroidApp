package xyz.desent.data.nostr

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.data.local.database.dao.BadgeNoticeDao
import xyz.desent.data.local.database.entity.BadgeNoticeEntity
import xyz.desent.domain.model.BadgeAwardNotice

class BadgeNoticeHandlerTest {

    private lateinit var dao: BadgeNoticeDao
    private lateinit var handler: BadgeNoticeHandler

    private val notice = BadgeAwardNotice(
        eventId = "wrap1",
        ownerNpub = "npub1me",
        slug = "early-adopter",
        subject = "New badge: Early Adopter",
        body = "You earned Early Adopter"
    )

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        handler = BadgeNoticeHandler(dao)
    }

    @Test
    fun store_firstInsertReturnsTrue() = runBlocking {
        coEvery { dao.insert(any()) } returns 1L

        assertTrue(handler.store(notice))

        val stored = slot<BadgeNoticeEntity>()
        coVerify { dao.insert(capture(stored)) }
        assertEquals("wrap1", stored.captured.eventId)
        assertEquals("npub1me", stored.captured.ownerNpub)
        assertEquals("early-adopter", stored.captured.slug)
        assertEquals("New badge: Early Adopter", stored.captured.subject)
        assertEquals("You earned Early Adopter", stored.captured.body)
        assertEquals(false, stored.captured.isSeen)
        assertTrue(stored.captured.receivedAt > 0)
    }

    @Test
    fun store_duplicateInsertReturnsFalse() = runBlocking {
        // Room's OnConflictStrategy.IGNORE returns -1 when the row exists.
        coEvery { dao.insert(any()) } returns -1L

        assertFalse(handler.store(notice))
    }

    @Test
    fun store_daoFailureReturnsFalseNeverThrows() = runBlocking {
        coEvery { dao.insert(any()) } throws RuntimeException("db locked")

        assertFalse(handler.store(notice))
    }
}
