package xyz.desent.domain.repository

import kotlinx.coroutines.flow.Flow
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailBodyFormat
import xyz.desent.domain.model.EmailForwardSummary
import xyz.desent.domain.model.EmailOutboxEntry
import xyz.desent.domain.model.EmailRecipient
import xyz.desent.domain.model.ReplyRecipients

/**
 * An address the account has emailed, with the epoch-ms timestamp of the most
 * recent outbound mail to it — the recency ranking for compose-screen contact
 * suggestions.
 */
data class RecentCorrespondent(val address: String, val lastAt: Long)

interface EmailRepository {

    /**
     * Mail-state overlay hook (mail folders + synced read state). Wired
     * post-construction by AppContainer; when set, read/unread changes are
     * mirrored into the synced `desent:mail-state` overlay — absent (tests,
     * wear) the local column stays device-local.
     */
    var mailFolderRepository: MailFolderRepository?

    suspend fun saveEmail(email: Email)
    suspend fun getEmailById(id: String): Email?
    suspend fun markEmailAsRead(id: String)
    suspend fun markEmailAsUnread(id: String)
    suspend fun requestDeletion(emailId: String): Result<String>
    fun observeEmails(recipientNpub: String): Flow<List<Email>>
    fun observeUnreadCount(recipientNpub: String): Flow<Int>
    suspend fun getUnreadCount(recipientNpub: String): Int

    /** Count of quarantined spam threads for [recipientNpub]. */
    fun observeSpamCount(recipientNpub: String): Flow<Int>

    /**
     * Latest message per thread. Threads are keyed by the client-computed
     * RFC 5322 thread root (NIP-EMAIL), falling back to the legacy server
     * `threadToken`, then the row id.
     */
    fun observeThreads(recipientNpub: String): Flow<List<Email>>

    /** Every message in a thread (inbound + sent + receipts), oldest first. */
    fun observeThread(recipientNpub: String, threadKey: String): Flow<List<Email>>

    /**
     * Distinct addresses the account has most recently emailed (outbound
     * `to`), newest first. Backs the recipient-autocomplete ranking on the
     * compose screen.
     */
    fun observeRecentOutboundRecipients(recipientNpub: String, limit: Int): Flow<List<RecentCorrespondent>>

    suspend fun markThreadRead(recipientNpub: String, threadKey: String)

    /**
     * Recover the AES-256-GCM key for an attachment by matching [sha256] against
     * the attachments stored on the user's emails. Returns null when no stored
     * email references this blob (e.g. the message was deleted → orphaned).
     */
    suspend fun findAttachmentKey(sha256: String): String?

    /**
     * Prefilled recipients for a reply (ANDROID_EMAIL_MIGRATION.md §7).
     * Plain reply ([replyAll] = false): `to` = `reply_to` ∥ `from` of an
     * inbound anchor (or the anchor's own To list for an outbound anchor).
     * Reply-all: `to` = `reply_to` ∥ `from`; `cc` = the anchor's to ∪ cc
     * MINUS every address the user owns (primary + active aliases) — own
     * addresses never land in the outgoing lists. Null when the anchor has
     * no external recipient to answer.
     */
    suspend fun computeReplyRecipients(
        emailId: String,
        replyAll: Boolean = false
    ): ReplyRecipients?

    /**
     * Reply to any stored email (NIP-EMAIL unified outbound path): the
     * recipient lists ride as one `to`/`cc`/`bcc` rumor tag per mailbox,
     * `in_reply_to` = the anchor's `message_id`. Empty [to] keeps the plain
     * -reply derivation (`reply_to` ∥ `from` of an inbound anchor / the
     * anchor's To for an outbound anchor), so quick-reply flows need no
     * precompute. Legacy kind-14 anchors map naturally, so old threads stay
     * replyable. [format] declares which MIME part [replyText] is ("reply in
     * kind"). [pgp] wraps [replyText] in an armored PGP MESSAGE for the
     * single recipient's WKD-discovered key (ANDROID_PGP.md §4).
     */
    suspend fun sendReply(
        emailId: String,
        replyText: String,
        format: EmailBodyFormat = EmailBodyFormat.PLAIN,
        pgp: Boolean = false,
        to: List<EmailRecipient> = emptyList(),
        cc: List<EmailRecipient> = emptyList(),
        bcc: List<EmailRecipient> = emptyList()
    ): Result<Unit>

    /**
     * Cold send a brand-new email (no prior thread context) — the same
     * operation as a reply, minus `in_reply_to`. Full RFC 5322 recipient
     * lists: one `to`/`cc`/`bcc` tag per mailbox, ≤ 20 envelope recipients
     * total, PGP sends single-recipient (END-01 §3.4, END-03 §6).
     */
    suspend fun sendColdEmail(
        to: List<EmailRecipient>,
        subject: String,
        body: String,
        format: EmailBodyFormat = EmailBodyFormat.PLAIN,
        pgp: Boolean = false,
        cc: List<EmailRecipient> = emptyList(),
        bcc: List<EmailRecipient> = emptyList()
    ): Result<Unit>

    // ==================== Outbox (delivery ledger) ====================

    /** Every outbox entry for an account, newest send first. */
    fun observeOutbox(npub: String): Flow<List<EmailOutboxEntry>>

    /** Outbox entries for one thread — drives the sent-bubble status footers. */
    fun observeOutboxByThread(npub: String, threadKey: String): Flow<List<EmailOutboxEntry>>

    /**
     * Flip PENDING entries older than the reconcile window to TIMED_OUT.
     * Receipts normally land within seconds; anything silent past the window
     * is treated as failed pending a retry (the receipt pipeline still
     * upgrades a TIMED_OUT entry if one arrives later).
     */
    suspend fun reconcileOutbox(npub: String)

    /**
     * One-tap retry of a FAILED/TIMED_OUT send: republishes the gift wrap
     * from the stored entry (fresh Message-ID) and resets the entry to
     * PENDING. The sent bubble is re-pointed at the new id so its footer
     * keeps tracking delivery state.
     */
    suspend fun retrySend(messageId: String): Result<Unit>

    /** Remove a resolved entry from the Outbox ledger. */
    suspend fun deleteOutboxEntry(messageId: String)

    // ==================== Forwarding / migration (NIP-EMAIL) ====================

    /**
     * Row ids of forward-eligible mail (inbound only — our own sends, receipts
     * and system rows never re-deliver). [threadKeys] scopes to specific
     * threads; null selects the whole mailbox (spam excluded).
     */
    suspend fun getForwardEligibleEmailIds(
        recipientNpub: String,
        threadKeys: List<String>? = null
    ): List<String>

    /**
     * Re-deliver stored mail to another Nostr key: each message is rebuilt as
     * a fresh kind-1010 rumor (all RFC 5322 tags + DKIM verdicts + attachment
     * keys verbatim, plus a `forwarded_by` provenance tag), sealed with the
     * user's key, gift-wrapped to [targetNpub] and published. Idempotent:
     * (email, target) pairs already SENT in the forward ledger are skipped,
     * so interrupted runs resume cleanly. [onProgress] fires after each
     * processed message with (processed, total).
     */
    suspend fun forwardEmails(
        emailIds: Collection<String>,
        targetNpub: String,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<EmailForwardSummary>
}
