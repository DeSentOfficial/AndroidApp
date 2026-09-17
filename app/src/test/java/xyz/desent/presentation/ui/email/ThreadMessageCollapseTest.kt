package xyz.desent.presentation.ui.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.domain.model.DkimStatus
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailDirection
import xyz.desent.domain.model.EmailType

/**
 * Gmail-style thread collapse rule: everything strictly before the thread's
 * last real (non-SYSTEM) message starts collapsed; the newest message and any
 * receipts after it (delivery state of the latest send) stay expanded.
 */
class ThreadMessageCollapseTest {

    private fun msg(
        id: String,
        type: EmailType = EmailType.OTHER,
        direction: EmailDirection = EmailDirection.INBOUND
    ) = Email(
        id = id,
        recipientNpub = "npub1",
        senderEmail = "a@b.c",
        senderDomain = "b.c",
        subject = "s",
        content = "c",
        dkimStatus = DkimStatus.NONE,
        emailType = type,
        bridge = "email",
        threadToken = null,
        messageId = null,
        createdAt = 1L,
        direction = direction
    )

    @Test
    fun olderMessagesCollapse_lastRealMessageStaysExpanded() {
        val ids = defaultExpandedIds(listOf(msg("m1"), msg("m2"), msg("m3")))
        assertEquals(setOf("m3"), ids)
    }

    @Test
    fun receiptsAfterTheLastRealMessage_stayExpanded() {
        val ids = defaultExpandedIds(
            listOf(msg("m1"), msg("m2"), msg("r1", EmailType.SYSTEM), msg("r2", EmailType.SYSTEM))
        )
        assertEquals(setOf("m2", "r1", "r2"), ids)
    }

    @Test
    fun receiptsBeforeTheLastRealMessage_collapse() {
        val ids = defaultExpandedIds(
            listOf(msg("m1"), msg("r1", EmailType.SYSTEM), msg("m2"))
        )
        assertEquals(setOf("m2"), ids)
    }

    @Test
    fun singleMessage_expandsItself() {
        assertEquals(setOf("m1"), defaultExpandedIds(listOf(msg("m1"))))
    }

    @Test
    fun receiptsOnlyThread_expandsAll() {
        assertEquals(
            setOf("r1", "r2"),
            defaultExpandedIds(listOf(msg("r1", EmailType.SYSTEM), msg("r2", EmailType.SYSTEM)))
        )
    }

    @Test
    fun emptyThread_expandsNothing() {
        assertEquals(emptySet<String>(), defaultExpandedIds(emptyList()))
    }

    // ---------------------------------------------------------------
    // Reading mode vs conversation mode (avatar column)
    // ---------------------------------------------------------------

    @Test
    fun inboundOnlyThread_isReadingMode_noUserResponse() {
        assertFalse(hasUserResponse(listOf(msg("m1"), msg("m2"))))
    }

    @Test
    fun receiptsDoNotCountAsAUserResponse() {
        assertFalse(
            hasUserResponse(
                listOf(msg("m1"), msg("r1", EmailType.SYSTEM), msg("r2", EmailType.SYSTEM))
            )
        )
    }

    @Test
    fun anyOutboundMessage_flipsToConversationMode() {
        assertTrue(hasUserResponse(listOf(msg("m1"), msg("mine", direction = EmailDirection.OUTBOUND))))
    }

    @Test
    fun outboundOnlyThread_isConversation() {
        assertTrue(
            hasUserResponse(listOf(msg("mine1", direction = EmailDirection.OUTBOUND)))
        )
    }

    @Test
    fun emptyThread_isReadingMode() {
        assertFalse(hasUserResponse(emptyList()))
    }
}
