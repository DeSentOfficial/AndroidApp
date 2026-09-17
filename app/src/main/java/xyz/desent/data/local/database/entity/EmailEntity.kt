package xyz.desent.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import xyz.desent.domain.model.MailStateKeys

@Entity(tableName = "emails")
data class EmailEntity(
    @PrimaryKey
    val id: String,
    val recipientNpub: String,
    val senderEmail: String,
    val senderDomain: String?,
    val senderName: String? = null,
    val replyTo: String? = null,
    val subject: String,
    val content: String,
    /** `EmailBodyFormat` name; null on legacy rows (rendered as HTML). */
    val bodyFormat: String? = null,
    val dkimStatus: String,
    val spfStatus: String = "UNKNOWN",
    val dmarcStatus: String? = null,
    val emailType: String,
    val bridge: String,
    val messageId: String?,
    val inReplyTo: String? = null,
    val referencesHeader: String? = null,
    val threadToken: String?,
    val threadRoot: String? = null,
    /** First To addr-spec (legacy single-recipient field; full list in [toRecipientsJson]). */
    val toEmail: String? = null,
    /** JSON `[EmailRecipient]` — RFC 5322 To address-list (END-01 §3.4). */
    val toRecipientsJson: String? = null,
    /** JSON `[EmailRecipient]` — RFC 5322 Cc address-list (informational inbound). */
    val ccRecipientsJson: String? = null,
    /** JSON `[EmailRecipient]` — Bcc list, outbound rows only (envelope-only on the wire). */
    val bccRecipientsJson: String? = null,
    /** Envelope (RCPT TO) address this copy was delivered to (inbound, always). */
    val deliveredTo: String? = null,
    val direction: String? = null,
    val alias: String? = null,
    /** Provenance for migrated/forwarded mail: the forwarder's npub (`forwarded_by` tag). */
    val forwardedByNpub: String? = null,
    val attachmentsJson: String? = null,
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
     * PGP E2E mail (ANDROID_PGP.md §3): content is the armored PGP MESSAGE;
     * decrypted only on-device for rendering/scoring, never stored as
     * plaintext. Our own outbound rows keep the typed draft here instead.
     */
    @ColumnInfo(defaultValue = "0")
    val isPgpEncrypted: Boolean = false,
    /**
     * `action` rumor tag (ANDROID_AI_AGENTS.md §6): e.g.
     * "calendar.propose" on an AI-agent proposal — normal inbound mail
     * with an "Add to calendar" affordance in the detail view. Null on
     * ordinary mail.
     */
    @ColumnInfo(defaultValue = "NULL")
    val actionTag: String? = null,
    /**
     * `cal` rumor tag payload (ANDROID_AI_AGENTS.md §6): the machine JSON
     * `{"title","start_unix","end_unix","location","details","timezone"}`;
     * unknown keys round-trip. Null on ordinary mail.
     */
    @ColumnInfo(defaultValue = "NULL")
    val calJson: String? = null
) {
    /** Grouping key: NIP-EMAIL thread root → legacy thread_token → row id. */
    val threadKey: String get() = threadRoot ?: threadToken ?: id

    /**
     * Pinned overlay key (ANDROID_MAIL_FOLDERS.md §1): the RFC 5322
     * Message-ID when present — stable across SMTP-retry duplicate wraps and
     * NIP-40 expiry — else `"ev:" + wrap event id`. This is the `k` of the
     * synced `desent:mail-state` entries and the join key for folder views.
     */
    val folderKey: String get() = MailStateKeys.pinnedKey(messageId, id)
}
