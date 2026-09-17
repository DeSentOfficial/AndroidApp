package xyz.desent.data.wearsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearInboxCodecTest {

    private fun sampleInbox() = WearInbox(
        emails = listOf(
            WearEmail(
                id = "ev1",
                threadKey = "tk1",
                senderName = "Alice",
                senderEmail = "alice@example.com",
                subject = "Hello",
                body = "Full plaintext body\nwith lines",
                createdAt = 42L,
                isRead = false,
                isPgp = false,
                attachmentCount = 2
            ),
            WearEmail(
                id = "ev2",
                threadKey = "tk2",
                subject = "Locked",
                body = "🔒 Encrypted message — open DeSent on your phone to decrypt",
                createdAt = 43L,
                isPgp = true
            )
        ),
        spam = listOf(
            WearEmail(id = "ev3", threadKey = "tk3", senderEmail = "spam@spam.io", subject = "BUY NOW", createdAt = 44L)
        ),
        spamEnabled = false,
        unreadCount = 7,
        syncedAt = 99L
    )

    @Test
    fun roundTripPreservesFields() {
        val inbox = sampleInbox()
        val decoded = WearInboxCodec.decode(WearInboxCodec.encode(inbox))
        assertEquals(inbox, decoded)
    }

    @Test
    fun payloadIsGzipped() {
        val bytes = WearInboxCodec.encode(sampleInbox())
        // GZIP magic number 0x1f 0x8b — must never be raw JSON on the wire.
        assertEquals(0x1f, bytes[0].toInt() and 0xff)
        assertEquals(0x8b, bytes[1].toInt() and 0xff)
    }

    @Test
    fun compressionBeatsRawForProseBodies() {
        val big = WearInbox(
            emails = (1..25).map {
                WearEmail(
                    id = "e$it",
                    subject = "Subject $it",
                    body = buildString { repeat(2000) { append("lorem ipsum dolor sit amet ") } }
                )
            }
        )
        val encoded = WearInboxCodec.encode(big)
        assertTrue("gzipped payload should stay far under the 100KB DataItem cap", encoded.size < 60_000)
    }

    @Test
    fun defaultsApplyWhenFieldsMissing() {
        val decoded = WearInboxCodec.decode("""{"syncedAt":7}""".encodeToByteArray())
        assertNotNull(decoded)
        assertEquals(emptyList<WearEmail>(), decoded?.emails)
        assertEquals(true, decoded?.spamEnabled)
        assertEquals(0, decoded?.unreadCount)
    }

    @Test
    fun unknownFieldsIgnored() {
        val decoded = WearInboxCodec.decode(
            """{"emails":[],"futureField":123,"syncedAt":1}""".encodeToByteArray()
        )
        assertNotNull(decoded)
        assertEquals(1L, decoded?.syncedAt)
    }

    @Test
    fun garbageReturnsNull() {
        assertNull(WearInboxCodec.decode(byteArrayOf(0x00, 0x01, 0x02)))
        assertNull(WearInboxCodec.decode(ByteArray(0)))
    }

    @Test
    fun emptyInboxRoundTrips() {
        val decoded = WearInboxCodec.decode(WearInboxCodec.encode(WearInbox()))
        assertEquals(WearInbox(), decoded)
    }
}

class WearInboxPrefsCodecTest {

    @Test
    fun roundTrip() {
        val prefs = WearInboxPrefs(spamEnabled = false)
        assertEquals(prefs, WearInboxPrefsCodec.decode(WearInboxPrefsCodec.encode(prefs)))
    }

    @Test
    fun defaultIsSpamEnabled() {
        val decoded = WearInboxPrefsCodec.decode("""{}""".encodeToByteArray())
        assertNotNull(decoded)
        assertTrue(decoded!!.spamEnabled)
    }

    @Test
    fun garbageReturnsNull() {
        assertNull(WearInboxPrefsCodec.decode(byteArrayOf(0x00)))
    }
}
