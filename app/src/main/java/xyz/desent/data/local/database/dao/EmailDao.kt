package xyz.desent.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import xyz.desent.data.local.database.entity.EmailEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EmailDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEmail(email: EmailEntity)

    @Update
    suspend fun updateEmail(email: EmailEntity)

    @Query("SELECT * FROM emails WHERE id = :id")
    suspend fun getEmailById(id: String): EmailEntity?

    @Query("SELECT * FROM emails WHERE recipientNpub = :npub ORDER BY createdAt DESC")
    fun observeEmailsByRecipient(npub: String): Flow<List<EmailEntity>>

    @Query("SELECT * FROM emails WHERE recipientNpub = :npub ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getEmailsByRecipient(npub: String, limit: Int): List<EmailEntity>

    @Query("UPDATE emails SET isRead = 1 WHERE id = :id")
    suspend fun markEmailAsRead(id: String)

    @Query("UPDATE emails SET isRead = 0 WHERE id = :id")
    suspend fun markEmailAsUnread(id: String)

    @Query("UPDATE emails SET deletionRequested = 1, deletionEventId = :eventId WHERE id = :id")
    suspend fun markDeletionRequested(id: String, eventId: String)

    @Query("DELETE FROM emails WHERE id = :id")
    suspend fun deleteEmail(id: String)

    @Query("SELECT * FROM emails WHERE attachmentsJson IS NOT NULL")
    suspend fun getEmailsWithAttachments(): List<EmailEntity>

    // Bridge confirmations/receipts are status, not mail — excluded from unread.
    @Query("SELECT COUNT(*) FROM emails WHERE recipientNpub = :npub AND isRead = 0 AND emailType != 'SYSTEM'")
    fun observeUnreadCount(npub: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM emails WHERE recipientNpub = :npub AND isRead = 0 AND emailType != 'SYSTEM'")
    suspend fun getUnreadCount(npub: String): Int

    /**
     * Unread inbox count excluding spam — the phone inbox-badge semantics,
     * used for the wear sync payload's unread badge.
     */
    @Query(
        """
        SELECT COUNT(*) FROM emails
        WHERE recipientNpub = :npub AND isRead = 0 AND isSpam = 0 AND emailType != 'SYSTEM'
        """
    )
    suspend fun getUnreadInboxCount(npub: String): Int

    /**
     * One representative (latest) row per email thread, ordered by most recent
     * activity. Threads are keyed by the client-computed RFC 5322 thread root
     * (NIP-EMAIL), falling back to the legacy server `threadToken`, then to the
     * row id for messages that start their own thread.
     *
     * Bridge receipts (SYSTEM / delivery-receipt rows) never represent a
     * thread — they are delivery status, not mail, so the thread keeps showing
     * its latest real message (and orphaned receipts never spawn inbox threads).
     */
    @Query(
        """
        SELECT * FROM emails
        WHERE recipientNpub = :npub
        AND emailType != 'SYSTEM'
        AND (direction IS NULL OR direction != 'DELIVERY_RECEIPT')
        GROUP BY COALESCE(threadRoot, threadToken, id)
        HAVING createdAt = MAX(createdAt)
        ORDER BY createdAt DESC
        """
    )
    fun observeThreads(npub: String): Flow<List<EmailEntity>>

    /**
     * Every message in a thread (original email + replies + inline receipts),
     * oldest first. The sender-claimed date wins when it is no more than a
     * day BEFORE the relay-stamped createdAt (sender clock slack) and no more
     * than three days AFTER it — the relay NIP-59-randomizes wrap timestamps
     * up to two days into the past, so a symmetric ±1-day guard would fall
     * back to a stale stamp and sort fresh replies above older mail. Claims
     * outside the window are treated as skewed sender clocks and createdAt
     * is used instead.
     */
    @Query(
        """
        SELECT * FROM emails
        WHERE recipientNpub = :npub
        AND COALESCE(threadRoot, threadToken, id) = :threadKey
        ORDER BY CASE
            WHEN senderDate IS NOT NULL
                AND senderDate >= createdAt - 86400000
                AND senderDate <= createdAt + 259200000
            THEN senderDate
            ELSE createdAt
        END ASC
        """
    )
    fun observeThread(npub: String, threadKey: String): Flow<List<EmailEntity>>

    /**
     * The largest effective sort key in a thread — the same CASE expression
     * [observeThread] orders by. Outbound sends stamp their row at least one
     * tick past this value so a fresh send always lands below every message
     * already in the thread, whatever the device clock says.
     */
    @Query(
        """
        SELECT MAX(CASE
            WHEN senderDate IS NOT NULL
                AND senderDate >= createdAt - 86400000
                AND senderDate <= createdAt + 259200000
            THEN senderDate
            ELSE createdAt
        END)
        FROM emails
        WHERE recipientNpub = :npub
        AND COALESCE(threadRoot, threadToken, id) = :threadKey
        """
    )
    suspend fun getThreadMaxSortKey(npub: String, threadKey: String): Long?

    @Query(
        """
        UPDATE emails SET isRead = 1
        WHERE recipientNpub = :npub
        AND COALESCE(threadRoot, threadToken, id) = :threadKey
        """
    )
    suspend fun markThreadRead(npub: String, threadKey: String)

    /**
     * Every row of a thread — used to stamp the synced mail-state overlay when
     * a thread is marked read (one overlay entry per message).
     */
    @Query(
        """
        SELECT * FROM emails
        WHERE recipientNpub = :npub
        AND COALESCE(threadRoot, threadToken, id) = :threadKey
        """
    )
    suspend fun getThreadRows(npub: String, threadKey: String): List<EmailEntity>

    /** Bulk-delete every email addressed to [npub]. Used by account removal. */
    @Query("DELETE FROM emails WHERE recipientNpub = :npub")
    suspend fun deleteAllForRecipient(npub: String)

    // ==================== THREADING (client-owned, NIP-EMAIL) ====================

    /** Newest stored message carrying an RFC 5322 Message-ID, if any. */
    @Query("SELECT * FROM emails WHERE recipientNpub = :npub AND messageId = :messageId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getEmailByMessageId(npub: String, messageId: String): EmailEntity?

    /**
     * Recent OUTBOUND rows with this `subject` in the window — the fallback
     * anchor candidates when a delivery-receipt arrives with no matching
     * outbox entry (e.g. a send made before the outbox table existed). The
     * any-address overlap (receipt `to` tags vs the row's recipient lists) is
     * applied by the caller; newest first so the overlap winner is the most
     * recent send.
     */
    @Query(
        """
        SELECT * FROM emails
        WHERE recipientNpub = :npub
        AND direction = 'OUTBOUND'
        AND subject = :subject
        AND createdAt BETWEEN :fromMs AND :toMs
        ORDER BY createdAt DESC
        LIMIT 10
        """
    )
    suspend fun findRecentOutbound(
        npub: String,
        subject: String,
        fromMs: Long,
        toMs: Long
    ): List<EmailEntity>

    /**
     * Re-point a sent bubble at a retried send's fresh Message-ID so the
     * bubble's delivery-status footer keeps tracking the live outbox entry.
     */
    @Query(
        """
        UPDATE emails SET messageId = :newMessageId
        WHERE messageId = :oldMessageId AND direction = 'OUTBOUND'
        """
    )
    suspend fun updateOutboundMessageId(oldMessageId: String, newMessageId: String)

    // ==================== SPAM FILTER ====================
    // See refs/SPAM_FILTER_REFERENCE.md. Verdicts are stamped at ingestion;
    // the inbox queries above are filtered further at the repository layer
    // so this DAO stays a thin data access object.

    /** Threads flagged as spam (latest non-receipt message per spam thread). */
    @Query(
        """
        SELECT * FROM emails
        WHERE recipientNpub = :npub AND isSpam = 1
        AND emailType != 'SYSTEM'
        AND (direction IS NULL OR direction != 'DELIVERY_RECEIPT')
        GROUP BY COALESCE(threadRoot, threadToken, id)
        HAVING createdAt = MAX(createdAt)
        ORDER BY createdAt DESC
        """
    )
    fun observeSpamThreads(npub: String): Flow<List<EmailEntity>>

    /** Overwrite the spam verdict on one row. */
    @Query("UPDATE emails SET isSpam = :isSpam, spamScore = :score, spamReasons = :reasons WHERE id = :id")
    suspend fun markSpam(id: String, isSpam: Boolean, score: Double, reasons: String?)

    /**
     * Non-SYSTEM messages for [npub] without an explicit user verdict — used
     * by the one-shot first-run retroactive reclassification pass
     * (ReclassifyInboxUseCase). Rows carrying a `user_marked_*` reason are the
     * user's own decision and must never be overwritten by the retro pass.
     */
    @Query(
        """
        SELECT * FROM emails
        WHERE recipientNpub = :npub AND emailType != 'SYSTEM'
          AND (spamReasons IS NULL OR spamReasons NOT LIKE '%user_marked%')
        """
    )
    suspend fun getEmailsForReclassification(npub: String): List<EmailEntity>

    // ==================== FORWARDING / MIGRATION (NIP-EMAIL) ====================

    /**
     * Forward-eligible mail: inbound mail only — outbound rows (our own
     * sends), delivery receipts, SYSTEM rows and security/badge notices never
     * re-deliver. Legacy kind-14 rows (direction NULL) are eligible and are
     * upgraded to kind 1010 on the wire by the forward path.
     */
    @Query(
        """
        SELECT * FROM emails
        WHERE recipientNpub = :npub
        AND emailType != 'SYSTEM'
        AND (direction IS NULL OR direction NOT IN ('OUTBOUND', 'DELIVERY_RECEIPT', 'SECURITY'))
        ORDER BY createdAt ASC
        """
    )
    suspend fun getForwardEligibleEmails(npub: String): List<EmailEntity>

    /** Forward-eligible mail within the given threads (see [getForwardEligibleEmails]). */
    @Query(
        """
        SELECT * FROM emails
        WHERE recipientNpub = :npub
        AND emailType != 'SYSTEM'
        AND (direction IS NULL OR direction NOT IN ('OUTBOUND', 'DELIVERY_RECEIPT', 'SECURITY'))
        AND COALESCE(threadRoot, threadToken, id) IN (:threadKeys)
        ORDER BY createdAt ASC
        """
    )
    suspend fun getForwardEligibleEmailsByThreads(npub: String, threadKeys: List<String>): List<EmailEntity>

    /** Count of quarantined messages for the active account (Spam folder badge). */
    @Query("SELECT COUNT(*) FROM emails WHERE recipientNpub = :npub AND isSpam = 1")
    fun observeSpamCount(npub: String): Flow<Int>

    // ==================== COMPOSE AUTOCOMPLETE ====================

    /**
     * Distinct outbound recipient addresses, most recently emailed first — the
     * recency signal for the compose screen's contact suggestions. Both cold
     * sends and replies store `toEmail` on OUTBOUND rows, so the two send
     * paths rank together.
     */
    @Query(
        """
        SELECT LOWER(TRIM(toEmail)) AS address, MAX(COALESCE(senderDate, createdAt)) AS lastAt
        FROM emails
        WHERE recipientNpub = :npub
        AND direction = 'OUTBOUND'
        AND toEmail IS NOT NULL
        AND TRIM(toEmail) != ''
        GROUP BY LOWER(TRIM(toEmail))
        ORDER BY lastAt DESC
        LIMIT :limit
        """
    )
    fun observeRecentOutboundAddresses(npub: String, limit: Int): Flow<List<RecentCorrespondentRow>>
}

/** Projection row for [EmailDao.observeRecentOutboundAddresses]. */
data class RecentCorrespondentRow(val address: String, val lastAt: Long)
