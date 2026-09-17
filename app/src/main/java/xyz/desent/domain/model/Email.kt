package xyz.desent.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Email(
    val id: String,
    val recipientNpub: String,
    val senderEmail: String,
    val senderDomain: String?,
    val senderName: String? = null,
    val replyTo: String? = null,
    val subject: String,
    val content: String,
    /** Which MIME part the body is (`["format", …]` tag, NIP-EMAIL). Null-tagged legacy rows render as HTML. */
    val bodyFormat: EmailBodyFormat = EmailBodyFormat.HTML,
    val dkimStatus: DkimStatus,
    val spfStatus: SpfStatus = SpfStatus.UNKNOWN,
    val dmarcStatus: DmarcStatus = DmarcStatus.UNKNOWN,
    val emailType: EmailType,
    val bridge: String,
    val messageId: String?,
    val inReplyTo: String? = null,
    val referencesHeader: String? = null,
    val threadToken: String?,
    /** Client-computed RFC 5322 thread root (NIP-EMAIL). Takes precedence over [threadToken]. */
    val threadRoot: String? = null,
    /**
     * RFC 5322 To — the external recipient of an outbound message, or the
     * FIRST To mailbox of an inbound one (legacy single-recipient field;
     * the full lists live in [toRecipients]/[ccRecipients]/[bccRecipients]).
     */
    val toEmail: String? = null,
    /** RFC 5322 To address-list, one entry per mailbox (END-01 §3.4). */
    val toRecipients: List<EmailRecipient> = emptyList(),
    /** RFC 5322 Cc address-list, one entry per mailbox (informational inbound). */
    val ccRecipients: List<EmailRecipient> = emptyList(),
    /** RFC 5322 Bcc — outbound rows only (envelope-only on the wire, never inbound). */
    val bccRecipients: List<EmailRecipient> = emptyList(),
    /**
     * The envelope (RCPT TO) address THIS copy was delivered to (inbound,
     * always per END-01 §3.4). When absent from to ∪ cc → [bccHint].
     */
    val deliveredTo: String? = null,
    /** Routing direction per NIP-EMAIL (inbound / outbound / delivery-receipt). */
    val direction: EmailDirection = EmailDirection.INBOUND,
    val alias: String? = null,
    /** Provenance for migrated/forwarded mail: the forwarder's npub (`forwarded_by` tag). */
    val forwardedByNpub: String? = null,
    val attachments: List<EmailAttachment> = emptyList(),
    val senderDate: Long? = null,
    val createdAt: Long,
    val isRead: Boolean = false,
    val deletionRequested: Boolean = false,
    val deletionEventId: String? = null,
    val threadSenderPubkey: String? = null,
    // Spam filter verdict stamped at ingestion (see refs/SPAM_FILTER_REFERENCE.md).
    val spamScore: Double = 0.0,
    val isSpam: Boolean = false,
    val spamReasons: String? = null,
    /**
     * PGP E2E mail (ANDROID_PGP.md §3): content is the armored PGP MESSAGE
     * (decrypted on-device only). Outbound rows carry the plaintext draft.
     */
    val isPgpEncrypted: Boolean = false,
    /**
     * `action` rumor tag (ANDROID_AI_AGENTS.md §6): "calendar.propose" on
     * an AI-agent proposal — ordinary inbound mail with an "Add to
     * calendar" affordance in the detail view. Null on ordinary mail.
     */
    val actionTag: String? = null,
    /**
     * `cal` rumor tag payload (ANDROID_AI_AGENTS.md §6): machine JSON
     * `{"title","start_unix","end_unix","location","details","timezone"}`;
     * unknown keys round-trip. Null on ordinary mail.
     */
    val calJson: String? = null
) {
    /** True when this mail is an agent calendar proposal (§6 affordance). */
    val isCalendarProposal: Boolean
        get() = actionTag == xyz.desent.data.EmailBridgeTags.ACTION_CALENDAR_PROPOSE && !calJson.isNullOrBlank()

    /** Display name if present, else the bare sender email. */
    val displaySender: String get() = senderName?.takeIf { it.isNotBlank() } ?: senderEmail

    /**
     * This copy arrived via the SMTP envelope only — the `delivered_to`
     * address is in neither the To nor the Cc list, which is how BCC works
     * (RFC 5322 §3.6.3). Render a muted "BCC — delivered to …" chip
     * (ANDROID_EMAIL_MIGRATION.md §7).
     */
    val bccHint: Boolean
        get() = !deliveredTo.isNullOrBlank() &&
            deliveredTo !in toRecipients.map { it.address } &&
            deliveredTo !in ccRecipients.map { it.address }

    /**
     * The key this message groups under in the inbox: the client-computed
     * RFC 5322 thread root when known, else the legacy server `thread_token`
     * (kind-14 era), else the row id (message starts its own thread).
     */
    val threadKey: String get() = threadRoot ?: threadToken ?: id

    /**
     * Pinned overlay key (mail folders + synced read state): RFC 5322
     * Message-ID when present, else `"ev:" + row id` (the wrap event id).
     */
    val folderKey: String get() = MailStateKeys.pinnedKey(messageId, id)
}

enum class DkimStatus {
    PASS, FAIL, DISABLED, NONE;

    /** `["dkim", …]` wire value (NIP-EMAIL tag schema). */
    val wireValue: String get() = name.lowercase()
}

enum class SpfStatus {
    PASS, FAIL, DISABLED, UNKNOWN;

    /** `["spf", …]` wire value (NIP-EMAIL tag schema). */
    val wireValue: String get() = name.lowercase()
}

enum class DmarcStatus {
    PASS, FAIL, NONE, UNKNOWN;

    /** `["dmarc", …]` wire value (NIP-EMAIL tag schema). */
    val wireValue: String get() = name.lowercase()
}

/** `["direction", …]` routing values per refs/FromServer/NIP-EMAIL.md. */
enum class EmailDirection {
    INBOUND, OUTBOUND, DELIVERY_RECEIPT,

    /**
     * Login-security alert (direction "security", refs/FromServer/
     * ANDROID_SECURITY_ALERTS.md). Relay-sealed kind-1010 rumor that never
     * renders as ordinary mail; stored in the dedicated security_alerts
     * table rather than the emails table (kept here for tag-dictionary
     * completeness and future cross-routing).
     */
    SECURITY
}

/**
 * Body MIME format carried by the `["format", …]` rumor tag (NIP-EMAIL):
 * which MIME part the bridge selected inbound / which format to send as
 * outbound. Clients reply in kind.
 */
enum class EmailBodyFormat(val wireValue: String) {
    HTML("html"), PLAIN("plain");

    companion object {
        /** Parse the wire value; absent/unknown falls back to HTML (legacy kind-14 rows). */
        fun fromWire(value: String?): EmailBodyFormat =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: HTML
    }
}

enum class EmailType {
    TRANSACTIONAL, PROMOTIONAL, SOCIAL, SECURITY, OTHER, SYSTEM
}

@Serializable
data class EmailAttachment(
    val sha256: String,
    val mimeType: String,
    val size: Long,
    val keyHex: String,
    val filename: String
)

/**
 * One RFC 5322 recipient mailbox (END-01 §3.4): bare addr-spec (lowercased,
 * matching the `from` vocabulary) plus the optional display name carried in
 * the rumor tag's third slot. Bcc entries never carry a display name — no
 * other recipient would ever see it.
 */
@Serializable
data class EmailRecipient(
    val address: String,
    val displayName: String? = null
) {
    /** Chip/summary label: `Name <addr>` when named, else the bare address. */
    val displayLabel: String
        get() = displayName?.takeIf { it.isNotBlank() }?.let { "$it <$address>" } ?: address
}

/**
 * Prefilled recipients for a reply (ANDROID_EMAIL_MIGRATION.md §7): plain
 * reply → single [to]; reply-all → [to] = `reply_to` ∥ `from` and
 * [cc] = the original to ∪ cc minus every address the user owns.
 */
data class ReplyRecipients(
    val to: List<EmailRecipient>,
    val cc: List<EmailRecipient> = emptyList()
)

/**
 * Outcome of a forward/migration run (NIP-EMAIL forwarding): how many messages
 * were offered, re-published to the target key, skipped as already delivered
 * (idempotent resume), and how many publishes failed.
 */
data class EmailForwardSummary(
    val total: Int,
    val sent: Int,
    val alreadyDelivered: Int,
    val failed: Int
)
