package xyz.desent.data.local.database.entity

import androidx.room.Entity

/**
 * Forward ledger (NIP-EMAIL forwarding / mailbox migration): one row per
 * (email, target) re-delivery attempt. Makes bulk runs resumable and
 * idempotent — SENT rows are skipped on re-run, PENDING/FAILED rows are
 * retried — and gives the progress UI a durable source of truth across
 * process death.
 */
@Entity(
    tableName = "email_forward_ledger",
    primaryKeys = ["emailId", "targetNpub"]
)
data class EmailForwardLedgerEntity(
    /** Row id of the forwarded email in `emails` (NOT its message_id — legacy rows may lack one). */
    val emailId: String,
    /** The npub the mail was re-wrapped to. */
    val targetNpub: String,
    /** PENDING → SENT | FAILED. */
    val status: String,
    /** The published kind-1059 wrap's event id (on success). */
    val giftWrapEventId: String? = null,
    /** Failure reason (on FAILED). */
    val errorMessage: String? = null,
    val createdAt: Long,
    val resolvedAt: Long? = null
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED"
    }
}
