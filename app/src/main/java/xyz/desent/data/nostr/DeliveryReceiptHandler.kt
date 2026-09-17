package xyz.desent.data.nostr

import xyz.desent.crypto.Bech32Utils
import xyz.desent.data.local.database.dao.EmailDao
import xyz.desent.data.local.database.dao.EmailOutboxDao
import xyz.desent.data.local.database.entity.EmailEntity
import xyz.desent.data.local.database.entity.EmailOutboxEntity
import xyz.desent.data.mapper.EmailMapper
import xyz.desent.domain.model.EmailDirection
import xyz.desent.domain.model.EmailRecipient
import xyz.desent.domain.model.EmailType
import xyz.desent.domain.model.UnwrappedContent

/**
 * Processes NIP-EMAIL `direction: delivery-receipt` gift wraps (kind 1010,
 * sealed by the relay's npub — the caller has already verified the seal).
 *
 * The receipt carries one `to` tag per envelope recipient (mirroring the
 * outbound rumor's to+cc+bcc list, END-03 §5), the same `subject`, and a
 * **relay-minted** `message_id` (never an echo of ours), so correlation is
 * subject + ANY-address overlap + time — there is no correlation id on the
 * wire (refs/FromServer/NIP-EMAIL.md § Direction; ANDROID_EMAIL_MIGRATION.md
 * §8).
 *
 * Matching cascade:
 *  1. a PENDING outbox entry (subject + any-address overlap, window) →
 *     resolved CONFIRMED/FAILED;
 *  2. a TIMED_OUT entry (a late receipt upgrades it to its real state);
 *  3. a recent OUTBOUND email row (sends made before the outbox table
 *     existed) → the receipt renders inline in that thread;
 *  4. nothing matches (a send made from another device) → a synthetic
 *     resolved outbox entry is reconstructed from the receipt, so the Outbox
 *     screen still shows the send. Never an inbox thread.
 *
 * Matched receipts (1, 2) update outbox state only — the sent bubble's status
 * footer in the thread renders the result, so no SYSTEM email row is inserted
 * and receipts can no longer pile up in the inbox.
 */
class DeliveryReceiptHandler(
    private val emailDao: EmailDao,
    private val emailOutboxDao: EmailOutboxDao
) {

    private val mapper = EmailMapper()

    /** Window for correlating a receipt to a send (receipts land in seconds;
     *  backlog after reconnect can be much later — see RECEIPT_MATCH_WINDOW_MS
     *  discussion in ANDROID_EMAIL_MIGRATION.md §7). */
    private val matchWindowMs: Long = DEFAULT_MATCH_WINDOW_MS

    suspend fun process(
        unwrapped: UnwrappedContent,
        giftWrapEventId: String,
        receiverNpub: String?,
        currentUserPubKeyHex: String?
    ): Boolean {
        fun tag(name: String): String? =
            unwrapped.tags.firstOrNull { it.isNotEmpty() && it[0] == name }?.getOrNull(1)

        // One address per envelope recipient; a receipt without any to tag
        // cannot be correlated to anything.
        val receiptAddresses = EmailRecipientTags.addresses(unwrapped.tags)
        if (receiptAddresses.isEmpty()) return false
        val toEmail = receiptAddresses.first()
        val subject = tag("subject") ?: ""
        val fromEmail = tag("from") ?: "noreply@desent.xyz"
        val fromDomain = tag("from_domain") ?: fromEmail.substringAfterLast('@', "")

        // Dedup: an old build may have persisted this wrap as an email row.
        if (emailDao.getEmailById(giftWrapEventId) != null) return false
        // Dedup: we already applied this exact receipt to an outbox entry.
        if (emailOutboxDao.getByReceiptEventId(giftWrapEventId) != null) return false

        val recipientPubkey = tag("p") ?: currentUserPubKeyHex ?: ""
        val recipientNpub = Bech32Utils.hexToNpub(recipientPubkey)

        // Wall-clock now: the gift wrap's created_at is NIP-59-randomized, so
        // neither it nor the rumor's own timestamp can order the receipt.
        val nowMs = System.currentTimeMillis()
        val content = unwrapped.content
        val success = content.trimStart().startsWith(SUCCESS_PREFIX)

        // 1. Pending entry for this send.
        val pending = emailOutboxDao
            .findPendingMatches(subject, nowMs, matchWindowMs)
            .firstOrNull { overlaps(it, receiptAddresses) }
        if (pending != null) {
            emailOutboxDao.updateStatus(
                messageId = pending.messageId,
                status = if (success) EmailOutboxEntity.STATUS_CONFIRMED else EmailOutboxEntity.STATUS_FAILED,
                receiptEventId = giftWrapEventId,
                errorMessage = if (success) null else content,
                resolvedAt = nowMs
            )
            return true
        }

        // 2. A late receipt upgrading a timed-out send.
        val timedOut = emailOutboxDao
            .findTimedOutMatches(subject, nowMs, matchWindowMs)
            .firstOrNull { overlaps(it, receiptAddresses) }
        if (timedOut != null) {
            emailOutboxDao.updateStatus(
                messageId = timedOut.messageId,
                status = if (success) EmailOutboxEntity.STATUS_CONFIRMED else EmailOutboxEntity.STATUS_FAILED,
                receiptEventId = giftWrapEventId,
                errorMessage = if (success) null else content,
                resolvedAt = nowMs
            )
            return true
        }

        // 3. Legacy fallback: an OUTBOUND email row predating the outbox
        //    table. The receipt renders inline in that thread.
        val legacyAnchor = emailDao.findRecentOutbound(
            npub = recipientNpub,
            subject = subject,
            fromMs = nowMs - matchWindowMs,
            toMs = nowMs + matchWindowMs
        ).firstOrNull { rowOverlap(it, receiptAddresses) }
        if (legacyAnchor != null) {
            val threadKey = legacyAnchor.threadRoot ?: legacyAnchor.threadToken ?: legacyAnchor.id
            emailDao.insertEmail(
                EmailEntity(
                    id = giftWrapEventId,
                    recipientNpub = recipientNpub,
                    senderEmail = fromEmail,
                    senderDomain = fromDomain,
                    senderName = "DeSent Email Bridge",
                    replyTo = null,
                    subject = subject,
                    content = content,
                    dkimStatus = xyz.desent.domain.model.DkimStatus.NONE.name,
                    spfStatus = xyz.desent.domain.model.SpfStatus.UNKNOWN.name,
                    dmarcStatus = null,
                    emailType = EmailType.SYSTEM.name,
                    bridge = "email",
                    messageId = null,
                    inReplyTo = null,
                    referencesHeader = null,
                    threadToken = null,
                    threadRoot = threadKey,
                    toEmail = toEmail,
                    direction = EmailDirection.DELIVERY_RECEIPT.name,
                    alias = null,
                    attachmentsJson = null,
                    senderDate = nowMs,
                    createdAt = nowMs,
                    isRead = true,
                    deletionRequested = false,
                    deletionEventId = null,
                    threadSenderPubkey = unwrapped.senderNpub
                )
            )
            return true
        }

        // 4. Unknown send (made on another device): reconstruct a resolved
        //    outbox entry so the Outbox ledger stays complete. Successes also
        //    get an OUTBOUND thread row (a real sent message we can render);
        //    failures stay ledger-only — there is no bubble to attach to.
        val receiptMessageId = EmailThreadResolver.normalizeMessageId(tag("message_id"))
            ?: "receipt-${giftWrapEventId.take(16)}"
        val synthesizedBody = if (success) extractBodyPreview(content) else ""
        if (success) {
            emailDao.insertEmail(
                EmailEntity(
                    id = giftWrapEventId,
                    recipientNpub = recipientNpub,
                    senderEmail = fromEmail,
                    senderDomain = fromDomain,
                    senderName = null,
                    replyTo = null,
                    subject = subject,
                    content = synthesizedBody,
                    bodyFormat = xyz.desent.domain.model.EmailBodyFormat.PLAIN.name,
                    dkimStatus = xyz.desent.domain.model.DkimStatus.NONE.name,
                    spfStatus = xyz.desent.domain.model.SpfStatus.UNKNOWN.name,
                    dmarcStatus = null,
                    emailType = EmailType.OTHER.name,
                    bridge = "email",
                    messageId = receiptMessageId,
                    inReplyTo = null,
                    referencesHeader = null,
                    threadToken = null,
                    threadRoot = receiptMessageId,
                    toEmail = toEmail,
                    direction = EmailDirection.OUTBOUND.name,
                    alias = null,
                    attachmentsJson = null,
                    senderDate = nowMs,
                    createdAt = nowMs,
                    isRead = true,
                    deletionRequested = false,
                    deletionEventId = null,
                    threadSenderPubkey = unwrapped.senderNpub
                )
            )
        }
        emailOutboxDao.insert(
            EmailOutboxEntity(
                messageId = receiptMessageId,
                recipientNpub = recipientNpub,
                threadKey = receiptMessageId,
                fromAlias = fromEmail,
                toEmail = toEmail,
                toRecipientsJson = mapper.encodeRecipients(receiptAddresses.map { EmailRecipient(it) }),
                subject = subject,
                body = synthesizedBody,
                sentAt = nowMs,
                status = if (success) EmailOutboxEntity.STATUS_CONFIRMED else EmailOutboxEntity.STATUS_FAILED,
                receiptEventId = giftWrapEventId,
                resolvedAt = nowMs,
                errorMessage = if (success) null else content,
                isSynthetic = true
            )
        )
        return true
    }

    /**
     * Any-address overlap (ANDROID_EMAIL_MIGRATION.md §8): the pending entry
     * stores the full to/cc/bcc list it sent and a receipt address may match
     * ANY of them. Case-insensitive; pre-v56 rows fall back to `toEmail`.
     */
    private fun overlaps(entry: EmailOutboxEntity, receiptAddresses: List<String>): Boolean {
        val sent = buildSet {
            add(entry.toEmail.lowercase())
            mapper.decodeRecipients(entry.toRecipientsJson).forEach { add(it.address) }
            mapper.decodeRecipients(entry.ccRecipientsJson).forEach { add(it.address) }
            mapper.decodeRecipients(entry.bccRecipientsJson).forEach { add(it.address) }
        }
        return receiptAddresses.any { it.lowercase() in sent }
    }

    /** Same overlap test against a stored OUTBOUND row (legacy fallback). */
    private fun rowOverlap(row: EmailEntity, receiptAddresses: List<String>): Boolean {
        val sent = buildSet {
            row.toEmail?.let { add(it.lowercase()) }
            mapper.decodeRecipients(row.toRecipientsJson).forEach { add(it.address) }
            mapper.decodeRecipients(row.ccRecipientsJson).forEach { add(it.address) }
            mapper.decodeRecipients(row.bccRecipientsJson).forEach { add(it.address) }
        }
        return receiptAddresses.any { it.lowercase() in sent }
    }

    /**
     * "✉ Email sent to x@y.z:\n\n<body>" → "<body>". Failures and receipts
     * without a blank-line separator have no body preview.
     */
    private fun extractBodyPreview(content: String): String {
        val idx = content.indexOf("\n\n")
        return if (idx >= 0) content.substring(idx + 2).trim() else ""
    }

    companion object {
        private const val SUCCESS_PREFIX = "✉"
        const val DEFAULT_MATCH_WINDOW_MS = 10 * 60 * 1000L
    }
}
