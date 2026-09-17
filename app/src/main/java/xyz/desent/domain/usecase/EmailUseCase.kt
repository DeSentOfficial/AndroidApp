package xyz.desent.domain.usecase

import kotlinx.coroutines.flow.Flow
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailBodyFormat
import xyz.desent.domain.model.EmailForwardSummary
import xyz.desent.domain.model.EmailOutboxEntry
import xyz.desent.domain.repository.EmailRepository

class EmailUseCase(
    private val emailRepository: EmailRepository
) {
    suspend fun markAsRead(emailId: String) {
        emailRepository.markEmailAsRead(emailId)
    }

    suspend fun markAsUnread(emailId: String) {
        emailRepository.markEmailAsUnread(emailId)
    }

    suspend fun requestDeletion(emailId: String): Result<String> {
        return emailRepository.requestDeletion(emailId)
    }

    fun observeEmails(recipientNpub: String): Flow<List<Email>> {
        return emailRepository.observeEmails(recipientNpub)
    }

    fun observeThreads(recipientNpub: String): Flow<List<Email>> {
        return emailRepository.observeThreads(recipientNpub)
    }

    fun observeThread(recipientNpub: String, threadKey: String): Flow<List<Email>> {
        return emailRepository.observeThread(recipientNpub, threadKey)
    }

    /** Addresses the account most recently emailed, newest first (compose autocomplete ranking). */
    fun observeRecentOutboundRecipients(
        recipientNpub: String,
        limit: Int
    ): Flow<List<xyz.desent.domain.repository.RecentCorrespondent>> {
        return emailRepository.observeRecentOutboundRecipients(recipientNpub, limit)
    }

    suspend fun markThreadRead(recipientNpub: String, threadKey: String) {
        emailRepository.markThreadRead(recipientNpub, threadKey)
    }

    fun observeUnreadCount(recipientNpub: String): Flow<Int> {
        return emailRepository.observeUnreadCount(recipientNpub)
    }

    suspend fun getUnreadCount(recipientNpub: String): Int {
        return emailRepository.getUnreadCount(recipientNpub)
    }

    /** Reactive count of quarantined spam threads for [recipientNpub]. */
    fun observeSpamCount(recipientNpub: String): Flow<Int> {
        return emailRepository.observeSpamCount(recipientNpub)
    }

    suspend fun findAttachmentKey(sha256: String): String? {
        return emailRepository.findAttachmentKey(sha256)
    }

    suspend fun getEmailById(id: String): Email? {
        return emailRepository.getEmailById(id)
    }

    /**
     * Prefilled recipients for a reply (plain vs reply-all, RFC 5322 §3.6.2)
     * — see [EmailRepository.computeReplyRecipients]. Null when the anchor
     * has no external recipient to answer.
     */
    suspend fun computeReplyRecipients(
        emailId: String,
        replyAll: Boolean = false
    ): xyz.desent.domain.model.ReplyRecipients? {
        return emailRepository.computeReplyRecipients(emailId, replyAll)
    }

    suspend fun sendReply(
        emailId: String,
        replyText: String,
        format: EmailBodyFormat = EmailBodyFormat.PLAIN,
        pgp: Boolean = false,
        to: List<xyz.desent.domain.model.EmailRecipient> = emptyList(),
        cc: List<xyz.desent.domain.model.EmailRecipient> = emptyList(),
        bcc: List<xyz.desent.domain.model.EmailRecipient> = emptyList()
    ): Result<Unit> {
        return emailRepository.sendReply(emailId, replyText, format, pgp, to, cc, bcc)
    }

    suspend fun sendColdEmail(
        to: List<xyz.desent.domain.model.EmailRecipient>,
        subject: String,
        body: String,
        format: EmailBodyFormat = EmailBodyFormat.PLAIN,
        pgp: Boolean = false,
        cc: List<xyz.desent.domain.model.EmailRecipient> = emptyList(),
        bcc: List<xyz.desent.domain.model.EmailRecipient> = emptyList()
    ): Result<Unit> {
        return emailRepository.sendColdEmail(to, subject, body, format, pgp, cc, bcc)
    }

    // ==================== Outbox (delivery ledger) ====================

    /** Every outbox entry for an account, newest send first. */
    fun observeOutbox(npub: String): Flow<List<EmailOutboxEntry>> {
        return emailRepository.observeOutbox(npub)
    }

    /** Outbox entries for one thread — drives the sent-bubble status footers. */
    fun observeOutboxByThread(npub: String, threadKey: String): Flow<List<EmailOutboxEntry>> {
        return emailRepository.observeOutboxByThread(npub, threadKey)
    }

    /** Flip stale PENDING entries to TIMED_OUT; call when email UI opens. */
    suspend fun reconcileOutbox(npub: String) {
        emailRepository.reconcileOutbox(npub)
    }

    /** One-tap retry of a FAILED/TIMED_OUT send from its stored entry. */
    suspend fun retrySend(messageId: String): Result<Unit> {
        return emailRepository.retrySend(messageId)
    }

    /** Remove a resolved entry from the Outbox ledger. */
    suspend fun deleteOutboxEntry(messageId: String) {
        emailRepository.deleteOutboxEntry(messageId)
    }

    // ==================== Forwarding / migration (NIP-EMAIL) ====================

    /** Row ids of forward-eligible mail ([threadKeys] null = whole mailbox, spam excluded). */
    suspend fun getForwardEligibleEmailIds(
        recipientNpub: String,
        threadKeys: List<String>? = null
    ): List<String> {
        return emailRepository.getForwardEligibleEmailIds(recipientNpub, threadKeys)
    }

    /**
     * Re-deliver stored mail to another Nostr key (see
     * [EmailRepository.forwardEmails]). [onProgress] fires after each message.
     */
    suspend fun forwardEmails(
        emailIds: List<String>,
        targetNpub: String,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<EmailForwardSummary> {
        return emailRepository.forwardEmails(emailIds, targetNpub, onProgress)
    }
}
