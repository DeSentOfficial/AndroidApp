package xyz.desent.data.nostr

/**
 * Client-owned RFC 5322 thread resolution for NIP-EMAIL
 * (refs/FromServer/NIP-EMAIL.md § Threading is client-owned).
 *
 * There is no server thread identity: a reply's `in_reply_to` points at its
 * parent's `message_id`, and `references` carries the ancestry. This resolver
 * matches that chain against locally stored messages and returns the grouping
 * key the new message should join — which may be a legacy kind-14 thread's
 * `thread_token`, making new replies land in old conversations.
 *
 * Each stored row already carries its resolved grouping key
 * (`threadRoot` → `threadToken` → `id`), so a found parent resolves in a
 * single hop; `references` (nearest ancestor first) covers the case where the
 * immediate parent was never delivered to us. Pure logic + a suspend lookup,
 * so it is unit-testable without Room.
 */
class EmailThreadResolver(
    /** Returns the stored parent for a Message-ID, or null when unknown. */
    private val findParent: suspend (messageId: String) -> String?
) {

    /**
     * Resolve the thread key a message belongs to, or null when it starts its
     * own thread (the caller keys it by its own `message_id`).
     */
    suspend fun resolve(inReplyTo: String?, references: String?): String? {
        normalizeMessageId(inReplyTo)?.let { mid ->
            findParent(mid)?.let { return it }
        }
        for (mid in parseReferences(references)) {
            findParent(mid)?.let { return it }
        }
        return null
    }

    companion object {
        /**
         * Normalize an RFC 5322 Message-ID for comparison/storage: trim and
         * drop the angle-bracket form (optional per NIP-EMAIL, but MUAs vary —
         * `<a@x>` and `a@x` must resolve to the same thread).
         */
        fun normalizeMessageId(id: String?): String? =
            id?.trim()?.removePrefix("<")?.removeSuffix(">")?.takeIf { it.isNotEmpty() }

        /**
         * Parse an RFC 5322 `References` header (angle-bracket form optional)
         * into normalized Message-IDs, **nearest ancestor first** (the wire
         * order is oldest → newest, so it is reversed here).
         */
        fun parseReferences(references: String?): List<String> {
            if (references.isNullOrBlank()) return emptyList()
            return references.split(Regex("\\s+"))
                .mapNotNull { normalizeMessageId(it) }
                .reversed()
        }
    }
}
