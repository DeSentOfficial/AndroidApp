package xyz.desent.domain.model

/**
 * One entry of the email Outbox ledger: an outbound kind-1010 send and its
 * delivery state (NIP-EMAIL). Mirrors `EmailOutboxEntity`; the repository
 * maps between them.
 */
data class EmailOutboxEntry(
    /** RFC 5322 Message-ID the client minted for the outbound rumor. */
    val messageId: String,
    val recipientNpub: String,
    val threadKey: String,
    val fromAlias: String,
    /** First To addr-spec (display/legacy field; full lists below). */
    val toEmail: String,
    /** Full RFC 5322 recipient lists as sent — receipt matching by any-address
     *  overlap + verbatim retry rebuild (ANDROID_EMAIL_MIGRATION.md §8). */
    val toRecipients: List<EmailRecipient> = emptyList(),
    val ccRecipients: List<EmailRecipient> = emptyList(),
    val bccRecipients: List<EmailRecipient> = emptyList(),
    val subject: String,
    val body: String,
    val sentAt: Long,
    val status: OutboxStatus = OutboxStatus.PENDING,
    /** Gift-wrap event id of the matched delivery-receipt, if any. */
    val receiptEventId: String? = null,
    val resolvedAt: Long? = null,
    val inReplyTo: String? = null,
    val referencesHeader: String? = null,
    val bodyFormat: EmailBodyFormat = EmailBodyFormat.PLAIN,
    /** The ❌ receipt's failure reason ("Send failed: …"). */
    val errorMessage: String? = null,
    /** True when reconstructed from a receipt for a send made on another device. */
    val isSynthetic: Boolean = false,
    /** PGP send: retry re-encrypts [body] to a freshly discovered key. */
    val isPgpEncrypted: Boolean = false
) {
    /** Retryable once the relay (or the timeout sweep) has rejected the send. */
    val canRetry: Boolean get() = !isSynthetic && (status == OutboxStatus.FAILED || status == OutboxStatus.TIMED_OUT)

    /** A thread exists to open (synthetic failures never had a local bubble). */
    val hasThread: Boolean get() = !(isSynthetic && status == OutboxStatus.FAILED)
}

enum class OutboxStatus {
    /** Published, waiting for the relay's delivery-receipt. */
    PENDING,

    /** "✉ Email sent to …" — the relay DKIM-signed and SMTP-delivered it. */
    CONFIRMED,

    /** "❌ Send failed: …" — the relay rejected the send; see errorMessage. */
    FAILED,

    /** No receipt arrived within the reconcile window; retryable. */
    TIMED_OUT;

    companion object {
        fun fromEntity(value: String): OutboxStatus =
            runCatching { valueOf(value) }.getOrDefault(PENDING)
    }
}
