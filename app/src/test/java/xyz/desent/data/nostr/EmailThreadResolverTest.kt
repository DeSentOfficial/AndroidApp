package xyz.desent.data.nostr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Client-owned RFC 5322 threading per refs/FromServer/NIP-EMAIL.md § Threading
 * is client-owned and EMAIL_NIP_ANDROID_MIGRATION.md §5.
 */
class EmailThreadResolverTest {

    private fun resolverWith(vararg known: Pair<String, String>): EmailThreadResolver {
        val map = known.toMap()
        return EmailThreadResolver { messageId -> map[messageId] }
    }

    @Test
    fun resolvesViaInReplyTo_parentKey_withBracketNormalization() = runBlocking {
        // Parent stored under a legacy thread_token (message ids are stored
        // normalized/bare) — new replies must join the OLD thread, even when
        // the arriving in_reply_to carries the angle-bracket form.
        val resolver = resolverWith("parent@example.com" to "NBRIDGE:v1:legacy-token")
        assertEquals(
            "NBRIDGE:v1:legacy-token",
            resolver.resolve(inReplyTo = "<parent@example.com>", references = null)
        )
    }

    @Test
    fun resolvesViaReferences_nearestAncestorFirst_whenParentUnknown() = runBlocking {
        // The immediate parent was never delivered; references still carries
        // the root, which we do have.
        val resolver = resolverWith(
            "root@example.com" to "root-key",
            "unrelated@other.com" to "other-key"
        )
        assertEquals(
            "root-key",
            resolver.resolve(inReplyTo = "<missing@example.com>", references = "<root@example.com> <missing@example.com>")
        )
    }

    @Test
    fun prefersNearestKnownAncestor_overRoot() = runBlocking {
        val resolver = resolverWith(
            "root@example.com" to "root-key",
            "mid@example.com" to "mid-key"
        )
        assertEquals(
            "mid-key",
            resolver.resolve(inReplyTo = "<missing@example.com>", references = "<root@example.com> <mid@example.com> <missing@example.com>")
        )
    }

    @Test
    fun returnsNull_whenNothingKnown_ownThread() = runBlocking {
        val resolver = resolverWith("other@x.com" to "k")
        assertNull(resolver.resolve(inReplyTo = "<unknown@example.com>", references = "<also-unknown@example.com>"))
    }

    @Test
    fun returnsNull_whenNoThreadingHeaders() = runBlocking {
        val resolver = resolverWith("root@x.com" to "root-key")
        assertNull(resolver.resolve(inReplyTo = null, references = null))
        assertNull(resolver.resolve(inReplyTo = "  ", references = ""))
    }

    @Test
    fun normalizeMessageId_stripsBrackets_andWhitespace() {
        assertEquals("a@x.com", EmailThreadResolver.normalizeMessageId("<a@x.com>"))
        assertEquals("a@x.com", EmailThreadResolver.normalizeMessageId("  a@x.com "))
        assertEquals("a@x.com", EmailThreadResolver.normalizeMessageId("a@x.com"))
        assertNull(EmailThreadResolver.normalizeMessageId("<>"))
        assertNull(EmailThreadResolver.normalizeMessageId(null))
    }

    @Test
    fun parseReferences_stripsAngleBrackets_andReversesToNearestFirst() {
        val ids = EmailThreadResolver.parseReferences("<a@x.com> <b@x.com> <c@x.com>")
        assertEquals(listOf("c@x.com", "b@x.com", "a@x.com"), ids)
    }

    @Test
    fun parseReferences_handlesBareIds_andGarbage() {
        assertEquals(listOf("b@x.com", "a@x.com"), EmailThreadResolver.parseReferences("a@x.com b@x.com"))
        assertEquals(emptyList<String>(), EmailThreadResolver.parseReferences(null))
        assertEquals(emptyList<String>(), EmailThreadResolver.parseReferences("   "))
    }
}
