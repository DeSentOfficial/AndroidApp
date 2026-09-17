package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.desent.data.local.database.entity.EmailOutboxEntity

@Dao
interface EmailOutboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: EmailOutboxEntity)

    @Query("SELECT * FROM email_outbox WHERE messageId = :messageId")
    suspend fun getByMessageId(messageId: String): EmailOutboxEntity?

    /** Dedup lookup: has this receipt's gift-wrap event already been applied? */
    @Query("SELECT * FROM email_outbox WHERE receiptEventId = :receiptEventId LIMIT 1")
    suspend fun getByReceiptEventId(receiptEventId: String): EmailOutboxEntity?

    /**
     * Pending entries matching a delivery-receipt's `subject` within the time
     * window — the any-address overlap (receipt carries one `to` tag per
     * envelope recipient vs the entry's full to/cc/bcc list) is applied by the
     * caller in Kotlin (ANDROID_EMAIL_MIGRATION.md §8). No correlation id
     * exists on the wire (NIP-EMAIL); when several sends share subject+address,
     * the oldest entry wins.
     */
    @Query(
        """
        SELECT * FROM email_outbox
        WHERE status = 'PENDING'
        AND subject = :subject
        AND :receiptAt - sentAt BETWEEN 0 AND :windowMillis
        ORDER BY sentAt ASC
        """
    )
    suspend fun findPendingMatches(
        subject: String,
        receiptAt: Long,
        windowMillis: Long
    ): List<EmailOutboxEntity>

    /**
     * TIMED_OUT entries matching a late receipt — a receipt that arrives after
     * the sweep gave up upgrades the entry to its real CONFIRMED/FAILED state.
     * Subject + window here; any-address overlap in the caller.
     */
    @Query(
        """
        SELECT * FROM email_outbox
        WHERE status = 'TIMED_OUT'
        AND subject = :subject
        AND :receiptAt - sentAt BETWEEN 0 AND :windowMillis
        ORDER BY sentAt ASC
        """
    )
    suspend fun findTimedOutMatches(
        subject: String,
        receiptAt: Long,
        windowMillis: Long
    ): List<EmailOutboxEntity>

    /** PENDING entries older than the cutoff — flipped to TIMED_OUT by the sweep. */
    @Query(
        """
        SELECT * FROM email_outbox
        WHERE recipientNpub = :npub
        AND status = 'PENDING'
        AND sentAt < :cutoffMs
        """
    )
    suspend fun findStalePending(npub: String, cutoffMs: Long): List<EmailOutboxEntity>

    @Query(
        """
        UPDATE email_outbox
        SET status = :status,
            receiptEventId = :receiptEventId,
            errorMessage = :errorMessage,
            resolvedAt = :resolvedAt
        WHERE messageId = :messageId
        """
    )
    suspend fun updateStatus(
        messageId: String,
        status: String,
        receiptEventId: String?,
        errorMessage: String?,
        resolvedAt: Long?
    )

    @Query(
        """
        UPDATE email_outbox
        SET status = 'TIMED_OUT', errorMessage = :reason, resolvedAt = :atMs
        WHERE messageId = :messageId
        """
    )
    suspend fun markTimedOut(messageId: String, reason: String, atMs: Long)

    /** Every entry for an account, newest send first (the Outbox screen list). */
    @Query(
        """
        SELECT * FROM email_outbox
        WHERE recipientNpub = :npub
        ORDER BY sentAt DESC
        """
    )
    fun observeAll(npub: String): Flow<List<EmailOutboxEntity>>

    /** Entries belonging to one thread — drives the sent-bubble status footers. */
    @Query(
        """
        SELECT * FROM email_outbox
        WHERE recipientNpub = :npub
        AND threadKey = :threadKey
        ORDER BY sentAt ASC
        """
    )
    fun observeByThread(npub: String, threadKey: String): Flow<List<EmailOutboxEntity>>

    @Query("SELECT * FROM email_outbox WHERE recipientNpub = :npub AND status = 'PENDING'")
    fun observePending(npub: String): Flow<List<EmailOutboxEntity>>

    @Query("SELECT COUNT(*) FROM email_outbox WHERE recipientNpub = :npub AND status = 'PENDING'")
    fun observePendingCount(npub: String): Flow<Int>

    @Query("DELETE FROM email_outbox WHERE messageId = :messageId")
    suspend fun deleteByMessageId(messageId: String)

    /** Bulk-delete outbox entries for an account. Used by account removal. */
    @Query("DELETE FROM email_outbox WHERE recipientNpub = :npub")
    suspend fun deleteAllForRecipient(npub: String)
}
