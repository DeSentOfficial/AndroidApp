package xyz.desent.data.nostr

import xyz.desent.domain.model.EmailRecipient

/**
 * Pure parsing of the RFC 5322 recipient-list rumor tags (END-01 §3.4,
 * deployed 2026-09) — no Room, no Android, fully unit-testable:
 *
 *  - `["to", addr-spec, display-name?]` — one tag per To mailbox, collected
 *    with `filter` (never `find`: multi-recipient mail predates this client's
 *    support and every tag after the first was silently dropped);
 *  - `["cc", addr-spec, display-name?]` — same shape from the Cc header;
 *  - `["delivered_to", address]` — the envelope (RCPT TO) address THIS copy
 *    was delivered to; when absent from to ∪ cc the client renders the
 *    "BCC'd to you" chip.
 *
 * Inbound `bcc` tags are deliberately NOT collected: a conforming sender's
 * MTA strips them before delivery and a nonconforming leftover header is not
 * the recipient's business (END-01 §6).
 */
object EmailRecipientTags {

    data class Parsed(
        val to: List<EmailRecipient> = emptyList(),
        val cc: List<EmailRecipient> = emptyList(),
        val deliveredTo: String? = null
    )

    fun parse(tags: List<List<String>>): Parsed = Parsed(
        to = recipients(tags, "to"),
        cc = recipients(tags, "cc"),
        deliveredTo = tags.firstOrNull { it.isNotEmpty() && it[0] == "delivered_to" }
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    )

    /**
     * Every `to` addr-spec on a rumor — used for delivery-receipts, which
     * carry one `to` tag per envelope recipient (END-03 §5), mirroring the
     * outbound rumor's full to+cc+bcc list.
     */
    fun addresses(tags: List<List<String>>): List<String> = tags
        .filter { it.size >= 2 && it[0] == "to" }
        .mapNotNull { it[1].takeIf { addr -> addr.isNotBlank() } }

    private fun recipients(tags: List<List<String>>, name: String): List<EmailRecipient> =
        tags.filter { it.size >= 2 && it[0] == name }
            .mapNotNull { tag ->
                val address = tag[1].trim().lowercase()
                    .replace("\r", "").replace("\n", "")
                    .takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // Slot 3 carries the mailbox's display name, present only when
                // the original header had one. Never trust the wire for header
                // shaping: strip line breaks defensively.
                val displayName = tag.getOrNull(2)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { it.replace("\r", "").replace("\n", "") }
                EmailRecipient(address = address, displayName = displayName)
            }
}
