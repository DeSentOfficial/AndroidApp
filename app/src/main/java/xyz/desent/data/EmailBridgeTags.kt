package xyz.desent.data

import xyz.desent.domain.model.DkimStatus
import xyz.desent.domain.model.DmarcStatus
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailBodyFormat
import xyz.desent.domain.model.EmailRecipient
import xyz.desent.domain.model.SpfStatus
import java.util.UUID

/**
 * Rumor tags for the Email-over-Nostr outbound flow (kind 1010).
 * See refs/FromServer/NIP-EMAIL.md § Tag schema + § Outbound flow.
 *
 * Outbound is unified: a reply and a cold send are the same operation,
 * differing only in whether [inReplyTo] is present. The relay verifies the
 * publisher owns the [fromAlias] address, DKIM-signs, and sends via SMTP.
 *
 * The `p` tag (gift-wrap recipient) is added by the gift-wrap service; the
 * kind `1010` itself is the marker that this is email (no `bridge`/`type` tag).
 *
 * Threading ids are stored locally bare (normalized) but emitted
 * **angle-bracketed** here: the relay copies `in_reply_to` / `references`
 * verbatim into the SMTP `In-Reply-To` / `References` headers, where the
 * bracketed `<id@domain>` form is required. The client-minted `message_id`
 * stays bare — the relay validates it against the RFC 5322 `id@domain` shape
 * and uses it as the outbound SMTP `Message-ID`, so external replies thread
 * back into this conversation (NIP-EMAIL § Outbound flow).
 */
object EmailBridgeTags {

    const val DIRECTION_INBOUND = "inbound"
    const val DIRECTION_OUTBOUND = "outbound"
    const val DIRECTION_DELIVERY_RECEIPT = "delivery-receipt"

    /**
     * Login-security alert (refs/FromServer/ANDROID_SECURITY_ALERTS.md):
     * a relay-sealed kind-1010 rumor reporting a sign-in to the user's
     * account. Carries the security tag block (`surface`, `time`, `ip`,
     * `ua`, `device`, `geo`) and must be routed to the security UI, never
     * rendered as ordinary inbox mail.
     */
    const val DIRECTION_SECURITY = "security"

    /**
     * Badge award notice (refs/FromServer/ANDROID_BADGES.md §7): a
     * relay-sealed kind-1010 rumor announcing a new NIP-58 badge award.
     * Carries `["badge", "<slug>"]` and a `subject` of the form
     * "New badge: <name>". Routed to a badge notification, never the inbox.
     */
    const val DIRECTION_BADGE = "badge"

    /**
     * AI-agent action tag (refs/ANDROID_AI_AGENTS.md §6, Sept 2026): agent
     * proposals arrive as NORMAL inbound mail (`direction: "inbound"`,
     * human-readable body) from the agent's address, additionally carrying
     * `["agent","1"]`, `["action","calendar.propose"]`,
     * `["agent_addr", "<address>"]` and `["cal", <json>]` — the machine
     * payload `{"title","start_unix","end_unix","location","details",
     * "timezone"}` the detail view turns into an "Add to calendar" button.
     */
    const val TAG_AGENT = "agent"
    const val TAG_ACTION = "action"
    const val TAG_AGENT_ADDR = "agent_addr"
    const val TAG_CAL = "cal"
    const val ACTION_CALENDAR_PROPOSE = "calendar.propose"


    /**
     * Bridge guard mirrored client-side (END-03 §6): at most 20 envelope
     * recipients (`to`+`cc`+`bcc`) per send — the relay refuses the whole
     * send otherwise. PGP-encrypted sends are always single-recipient.
     */
    const val MAX_ENVELOPE_RECIPIENTS = 20

    /**
     * Tags for an outbound kind-1010 rumor: `from` (an address the user owns),
     * the full RFC 5322 recipient lists — one `to` tag per To mailbox and one
     * `cc` tag per Cc mailbox (`["to", addr-spec, display-name?]`, END-01
     * §3.4), one bare `bcc` tag per Bcc recipient (envelope-only; the bridge
     * never writes a `Bcc:` header on any copy) — `subject`,
     * `direction: outbound`, a fresh `message_id`, `format` (which MIME part
     * to send `content` as), and — for a reply — `in_reply_to` (+ `references`
     * ancestry), angle-bracketed.
     *
     * A PGP-encrypted send (ANDROID_PGP.md §4.2) instead carries
     * `["pgp","encrypted"]` and NO `format` tag — the content is the armored
     * PGP MESSAGE and the relay ignores body format for PGP sends.
     */
    fun outbound(
        fromAlias: String,
        to: List<EmailRecipient>,
        subject: String,
        cc: List<EmailRecipient> = emptyList(),
        bcc: List<EmailRecipient> = emptyList(),
        messageId: String = makeMessageId(),
        inReplyTo: String? = null,
        references: String? = null,
        format: EmailBodyFormat = EmailBodyFormat.PLAIN,
        pgpEncrypted: Boolean = false
    ): List<List<String>> = buildList {
        add(listOf("from", fromAlias))
        to.forEach { add(recipientTag("to", it)) }
        cc.forEach { add(recipientTag("cc", it)) }
        // Bcc gets no display-name slot — no one else ever sees it (END-01 §3.4).
        bcc.forEach { add(listOf("bcc", it.address.lowercase())) }
        add(listOf("subject", subject))
        add(listOf("direction", DIRECTION_OUTBOUND))
        add(listOf("message_id", messageId))
        if (pgpEncrypted) {
            add(listOf(TAG_PGP, PGP_VALUE_ENCRYPTED))
        } else {
            add(listOf("format", format.wireValue))
        }
        if (!inReplyTo.isNullOrBlank()) add(listOf("in_reply_to", bracketMessageId(inReplyTo)))
        if (!references.isNullOrBlank()) add(listOf("references", bracketReferences(references)))
    }

    /**
     * One recipient-list tag: addr-spec lowercased (matching the `from`
     * vocabulary, END-01 §3.4), optional display name in slot 3. CR/LF are
     * stripped from both — the bridge sanitizes display names against header
     * injection, this keeps what we publish clean at the source.
     */
    private fun recipientTag(name: String, recipient: EmailRecipient): List<String> {
        val address = recipient.address.trim().lowercase()
            .replace("\r", "").replace("\n", "")
        val displayName = recipient.displayName
            ?.takeIf { it.isNotBlank() }
            ?.let { it.replace("\r", "").replace("\n", "") }
        return if (displayName == null) {
            listOf(name, address)
        } else {
            listOf(name, address, displayName)
        }
    }

    /**
     * Tag name marking a kind-1010 rumor as re-delivered by another Nostr key
     * (mailbox migration / forwarding, refs/FromServer/NIP-EMAIL.md). Value is
     * the forwarder's hex pubkey — the same key that signed the seal, so the
     * recipient can attribute the re-delivery without extra lookups.
     */
    const val TAG_FORWARDED_BY = "forwarded_by"

    /**
     * PGP E2E tag pair (PGP_ENCRYPTION.md § Inbound/Outbound passthrough):
     * `["pgp","encrypted"]` marks every PGP-carried rumor; inbound rumors
     * additionally carry `["format","pgp"]`.
     */
    const val TAG_PGP = "pgp"
    const val TAG_FORMAT = "format"
    const val PGP_VALUE_ENCRYPTED = "encrypted"
    const val FORMAT_VALUE_PGP = "pgp"

    /**
     * Tags for a forwarded kind-1010 rumor (NIP-EMAIL § Forwarding): a stored
     * email is rebuilt verbatim — every RFC 5322 header tag, the DKIM/SPF/DMARC
     * verdicts (still attesting the ORIGINAL external sender's domain), and the
     * attachment descriptors with their AES keys — so threading and
     * authenticity survive on the recipient untouched. Only two things change:
     * `direction` stays `inbound` (the recipient renders it as ordinary mail)
     * and a fresh `forwarded_by` tag records provenance.
     *
     * The `p` tag (gift-wrap recipient) is added by the gift-wrap service.
     */
    fun forwarded(
        email: Email,
        forwarderPubkeyHex: String
    ): List<List<String>> = buildList {
        add(listOf("from", email.senderEmail))
        email.senderName?.takeIf { it.isNotBlank() }?.let { add(listOf("from_name", it)) }
        email.senderDomain?.takeIf { it.isNotBlank() }?.let { add(listOf("from_domain", it)) }
        // Original RFC 5322 recipient lists ride along verbatim (END-01 §3.4)
        // so reply-all works from the forwarded copy. `delivered_to` is
        // deliberately NOT re-emitted: it is envelope metadata of the ORIGINAL
        // delivery — on the target device it would render a misleading
        // "BCC'd to you" chip for an address that is not theirs.
        email.toRecipients.forEach { add(recipientTag("to", it)) }
        email.ccRecipients.forEach { add(recipientTag("cc", it)) }
        add(listOf("subject", email.subject))
        add(listOf("direction", DIRECTION_INBOUND))
        email.messageId?.takeIf { it.isNotBlank() }?.let { add(listOf("message_id", it)) }
        add(listOf("format", email.bodyFormat.wireValue))
        email.inReplyTo?.takeIf { it.isNotBlank() }?.let { add(listOf("in_reply_to", bracketMessageId(it))) }
        email.referencesHeader?.takeIf { it.isNotBlank() }?.let {
            add(listOf("references", bracketReferences(it)))
        }
        email.senderDate?.let { add(listOf("date", (it / 1000L).toString())) }
        email.replyTo?.takeIf { it.isNotBlank() }?.let { add(listOf("reply_to", it)) }
        if (email.dkimStatus != DkimStatus.NONE) add(listOf("dkim", email.dkimStatus.wireValue))
        if (email.spfStatus != SpfStatus.UNKNOWN) add(listOf("spf", email.spfStatus.wireValue))
        email.dmarcStatus.takeIf { it != DmarcStatus.UNKNOWN }?.let {
            add(listOf("dmarc", it.wireValue))
        }
        email.alias?.takeIf { it.isNotBlank() }?.let { add(listOf("alias", it)) }
        email.attachments.forEach { a ->
            add(listOf("attachment", a.sha256, a.mimeType, a.size.toString(), a.keyHex, a.filename))
        }
        add(listOf(TAG_FORWARDED_BY, forwarderPubkeyHex))
    }

    /**
     * RFC 5322 Message-ID for a user-authored message. Bare (no angle
     * brackets — optional per NIP-EMAIL) so it round-trips identically through
     * every normalization, on the wire and in local thread lookups.
     */
    fun makeMessageId(): String = "${UUID.randomUUID()}@desent.xyz"

    /** `<id@domain>` — idempotent for already-bracketed ids. */
    internal fun bracketMessageId(id: String): String =
        if (id.startsWith("<") && id.endsWith(">")) id else "<$id>"

    /** Bracket every id in a space-separated `references` list. */
    internal fun bracketReferences(references: String): String =
        references.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { bracketMessageId(it) }

    /**
     * Convert quick-reply plain text to the equivalent minimal HTML so a plain
     * composer can still send into an HTML-format thread (reply in kind):
     * HTML-escape everything, then map line breaks to `<br>`.
     */
    fun plainToHtml(plain: String): String = plain
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
        .replace("\r\n", "\n")
        .replace("\n", "<br>")
}
