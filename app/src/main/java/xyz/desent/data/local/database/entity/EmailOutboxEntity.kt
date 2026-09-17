package xyz.desent.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Outbound email ledger (NIP-EMAIL unified send path). Created when the
 * kind-1010 gift wrap is published; the relay's `direction: delivery-receipt`
 * is matched back to the pending entry by `to` + `subject` + time (there is
 * no correlation id — see refs/FromServer/EMAIL_NIP_ANDROID_MIGRATION.md §7).
 *
 * Lifecycle: PENDING → CONFIRMED (✉ receipt) / FAILED (❌ receipt) /
 * TIMED_OUT (no receipt within [xyz.desent.data.repository.EmailRepositoryImpl]
 * 's sweep window). A retry replaces the row (fresh message id) back to
 * PENDING. Entries flagged [isSynthetic] were reconstructed from a receipt
 * for a send this device never made (e.g. another device) — they have no
 * gift-wrap publish behind them and cannot be retried.
 */
@Entity(tableName = "email_outbox")
data class EmailOutboxEntity(
    /** The fresh RFC 5322 Message-ID we minted for the outbound rumor. */
    @PrimaryKey
    val messageId: String,
    val recipientNpub: String,
    val threadKey: String,
    val fromAlias: String,
    /** First To addr-spec (display/legacy field). */
    val toEmail: String,
    /**
     * Full RFC 5322 recipient lists as sent (JSON `[EmailRecipient]`):
     * delivery-receipt matching is subject + ANY-address overlap, and a retry
     * rebuilds the rumor verbatim from these (ANDROID_EMAIL_MIGRATION.md §8).
     */
    val toRecipientsJson: String? = null,
    val ccRecipientsJson: String? = null,
    val bccRecipientsJson: String? = null,
    val subject: String,
    val body: String,
    /** Wall-clock millis when the gift wrap was published. */
    val sentAt: Long,
    /** PENDING → CONFIRMED (✉) / FAILED (❌) / TIMED_OUT (sweep). */
    val status: String = STATUS_PENDING,
    /** Gift-wrap event id of the matched delivery-receipt, if any. */
    val receiptEventId: String? = null,
    /** Wall-clock millis when the receipt arrived (null while pending). */
    val resolvedAt: Long? = null,
    /** RFC 5322 In-Reply-To of the outbound rumor (null = cold send). */
    val inReplyTo: String? = null,
    /** RFC 5322 References ancestry (space-separated), when replying. */
    val referencesHeader: String? = null,
    /** [xyz.desent.domain.model.EmailBodyFormat] name — how to re-send the body. */
    @ColumnInfo(defaultValue = "PLAIN")
    val bodyFormat: String = "PLAIN",
    /** Full receipt content on failure ("❌ Send failed: …"); null otherwise. */
    val errorMessage: String? = null,
    /** Gift-wrap event id of OUR publish (echoed by OK from the relay). */
    val giftWrapEventId: String? = null,
    /** True when reconstructed from a receipt for a send made elsewhere. */
    @ColumnInfo(defaultValue = "0")
    val isSynthetic: Boolean = false,
    /**
     * PGP send (ANDROID_PGP.md §4.2): [body] is the plaintext draft — a
     * retry re-runs WKD discovery + encryption, the same as the first
     * attempt (never store recipient-bound ciphertext as the retry source).
     */
    @ColumnInfo(defaultValue = "0")
    val isPgpEncrypted: Boolean = false
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_CONFIRMED = "CONFIRMED"
        const val STATUS_FAILED = "FAILED"

        /** Sweep flipped: no receipt arrived within the reconcile window. */
        const val STATUS_TIMED_OUT = "TIMED_OUT"
    }
}
