package xyz.desent.data.mapper

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.desent.data.local.database.entity.EmailEntity
import xyz.desent.domain.model.DkimStatus
import xyz.desent.domain.model.DmarcStatus
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailAttachment
import xyz.desent.domain.model.EmailBodyFormat
import xyz.desent.domain.model.EmailDirection
import xyz.desent.domain.model.EmailRecipient
import xyz.desent.domain.model.EmailType
import xyz.desent.domain.model.SpfStatus

class EmailMapper {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Recipient-list codec: unlike [json], defaults are omitted so an unnamed
     * mailbox stores `{"address":"a@x"}` — displayName appears only when the
     * rumor tag carried one (END-01 §3.4 slot 3).
     */
    private val recipientJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Null for an empty list — absent tags store no JSON (matches attachmentsJson). */
    fun encodeRecipients(recipients: List<EmailRecipient>): String? =
        if (recipients.isEmpty()) null else recipientJson.encodeToString(recipients)

    /** Malformed stored JSON decodes to an empty list (legacy rows have none). */
    fun decodeRecipients(stored: String?): List<EmailRecipient> {
        if (stored.isNullOrBlank()) return emptyList()
        return runCatching { recipientJson.decodeFromString<List<EmailRecipient>>(stored) }
            .getOrDefault(emptyList())
    }

    fun mapToEntity(domain: Email): EmailEntity = EmailEntity(
        id = domain.id,
        recipientNpub = domain.recipientNpub,
        senderEmail = domain.senderEmail,
        senderDomain = domain.senderDomain,
        senderName = domain.senderName,
        replyTo = domain.replyTo,
        subject = domain.subject,
        content = domain.content,
        bodyFormat = domain.bodyFormat.name,
        dkimStatus = domain.dkimStatus.name,
        spfStatus = domain.spfStatus.name,
        dmarcStatus = domain.dmarcStatus.takeIf { it != DmarcStatus.UNKNOWN }?.name,
        emailType = domain.emailType.name,
        bridge = domain.bridge,
        messageId = domain.messageId,
        inReplyTo = domain.inReplyTo,
        referencesHeader = domain.referencesHeader,
        threadToken = domain.threadToken,
        threadRoot = domain.threadRoot,
        toEmail = domain.toEmail,
        toRecipientsJson = encodeRecipients(domain.toRecipients),
        ccRecipientsJson = encodeRecipients(domain.ccRecipients),
        bccRecipientsJson = encodeRecipients(domain.bccRecipients),
        deliveredTo = domain.deliveredTo,
        direction = domain.direction.name,
        alias = domain.alias,
        forwardedByNpub = domain.forwardedByNpub,
        attachmentsJson = if (domain.attachments.isEmpty()) null else json.encodeToString(domain.attachments),
        senderDate = domain.senderDate,
        createdAt = domain.createdAt,
        isRead = domain.isRead,
        deletionRequested = domain.deletionRequested,
        deletionEventId = domain.deletionEventId,
        threadSenderPubkey = domain.threadSenderPubkey,
        spamScore = domain.spamScore,
        isSpam = domain.isSpam,
        spamReasons = domain.spamReasons,
        isPgpEncrypted = domain.isPgpEncrypted,
        actionTag = domain.actionTag,
        calJson = domain.calJson
    )

    fun mapToDomain(entity: EmailEntity): Email = Email(
        id = entity.id,
        recipientNpub = entity.recipientNpub,
        senderEmail = entity.senderEmail,
        senderDomain = entity.senderDomain,
        senderName = entity.senderName,
        replyTo = entity.replyTo,
        subject = entity.subject,
        content = entity.content,
        bodyFormat = entity.bodyFormat
            ?.let { runCatching { EmailBodyFormat.valueOf(it) }.getOrNull() }
            ?: EmailBodyFormat.HTML,
        dkimStatus = runCatching { DkimStatus.valueOf(entity.dkimStatus) }.getOrDefault(DkimStatus.NONE),
        spfStatus = runCatching { SpfStatus.valueOf(entity.spfStatus) }.getOrDefault(SpfStatus.UNKNOWN),
        dmarcStatus = entity.dmarcStatus?.let { runCatching { DmarcStatus.valueOf(it) }.getOrDefault(DmarcStatus.UNKNOWN) }
            ?: DmarcStatus.UNKNOWN,
        emailType = runCatching { EmailType.valueOf(entity.emailType) }.getOrDefault(EmailType.OTHER),
        bridge = entity.bridge,
        messageId = entity.messageId,
        inReplyTo = entity.inReplyTo,
        referencesHeader = entity.referencesHeader,
        threadToken = entity.threadToken,
        threadRoot = entity.threadRoot,
        toEmail = entity.toEmail,
        toRecipients = decodeRecipients(entity.toRecipientsJson),
        ccRecipients = decodeRecipients(entity.ccRecipientsJson),
        bccRecipients = decodeRecipients(entity.bccRecipientsJson),
        deliveredTo = entity.deliveredTo,
        direction = entity.direction?.let { runCatching { EmailDirection.valueOf(it) }.getOrDefault(EmailDirection.INBOUND) }
            ?: EmailDirection.INBOUND,
        alias = entity.alias,
        forwardedByNpub = entity.forwardedByNpub,
        attachments = parseAttachments(entity.attachmentsJson),
        senderDate = entity.senderDate,
        createdAt = entity.createdAt,
        isRead = entity.isRead,
        deletionRequested = entity.deletionRequested,
        deletionEventId = entity.deletionEventId,
        threadSenderPubkey = entity.threadSenderPubkey,
        spamScore = entity.spamScore,
        isSpam = entity.isSpam,
        spamReasons = entity.spamReasons,
        isPgpEncrypted = entity.isPgpEncrypted,
        actionTag = entity.actionTag,
        calJson = entity.calJson
    )

    private fun parseAttachments(stored: String?): List<EmailAttachment> {
        if (stored.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<EmailAttachment>>(stored)
        }.getOrDefault(emptyList())
    }
}
