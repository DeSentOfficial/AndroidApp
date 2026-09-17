package xyz.desent.wear

import kotlinx.serialization.json.Json
import xyz.desent.data.local.database.entity.EmailEntity
import xyz.desent.data.wearsync.WearEmail
import xyz.desent.data.wearsync.WearInbox
import xyz.desent.domain.model.EmailAttachment
import xyz.desent.domain.model.EmailDirection

/**
 * Pure mapping of decrypted Room rows ([EmailEntity]) to the watch payload
 * ([WearInbox]). The watch never decrypts anything — this is where the
 * plaintext reading copy is produced, HTML is stripped, PGP mail gets its
 * placeholder, and payload budget caps are enforced.
 *
 * Kept free of Android dependencies so the caps/stripping logic is
 * unit-testable (see WearInboxBuilderTest).
 */
object WearInboxBuilder {

    /** Newest-first thread caps for each folder. */
    const val MAX_EMAILS = 25
    const val MAX_SPAM = 25

    /** Reading-copy caps keeping the gzipped payload far under the 100KB DataItem limit. */
    const val MAX_BODY_CHARS = 4000
    const val MAX_SUBJECT_CHARS = 200
    const val MAX_SENDER_CHARS = 120

    const val PGP_PLACEHOLDER = "🔒 Encrypted message — open DeSent on your phone to decrypt"

    private val attachmentsJson = Json { ignoreUnknownKeys = true }

    /** Outbound PGP rows keep a typed draft in `content`; only inbound armor is unreadable. */
    private val EmailEntity.isLockedPgp: Boolean
        get() = isPgpEncrypted && direction != EmailDirection.OUTBOUND.name

    fun build(
        threads: List<EmailEntity>,
        spamThreads: List<EmailEntity>,
        spamEnabled: Boolean,
        unreadCount: Int,
        syncedAt: Long = System.currentTimeMillis()
    ): WearInbox = WearInbox(
        // The threads query does not exclude spam (repository layer normally
        // filters it) — drop spam rows here so quarantined mail never lands
        // in the watch inbox list.
        emails = threads.filterNot { it.isSpam }.take(MAX_EMAILS).map(::toWearEmail),
        spam = if (spamEnabled) spamThreads.take(MAX_SPAM).map(::toWearEmail) else emptyList(),
        spamEnabled = spamEnabled,
        unreadCount = unreadCount,
        syncedAt = syncedAt
    )

    private fun toWearEmail(entity: EmailEntity): WearEmail = WearEmail(
        id = entity.id,
        threadKey = entity.threadKey,
        senderName = cap(entity.senderName ?: entity.senderEmail, MAX_SENDER_CHARS),
        senderEmail = cap(entity.senderEmail, MAX_SENDER_CHARS),
        subject = cap(entity.subject.ifBlank { "(no subject)" }, MAX_SUBJECT_CHARS),
        body = cap(bodyText(entity), MAX_BODY_CHARS),
        createdAt = entity.createdAt,
        isRead = entity.isRead,
        isPgp = entity.isLockedPgp,
        attachmentCount = attachmentCount(entity.attachmentsJson)
    )

    private fun bodyText(entity: EmailEntity): String = when {
        entity.isLockedPgp -> PGP_PLACEHOLDER
        entity.bodyFormat == "PLAIN" -> entity.content
        else -> htmlToText(entity.content)
    }

    /**
     * Full-body HTML strip (reading copy, keeps paragraph breaks): script/
     * style/head blocks dropped, tags removed with block tags becoming
     * newlines, entities decoded, blank-line runs collapsed. Mirrors the
     * snippet stripper in presentation/ui/email/components/EmailPreview.kt
     * but preserves vertical layout for on-watch reading.
     */
    internal fun htmlToText(html: String): String {
        val noBlocks = html.replace(Regex("(?is)<(style|script|head)[^>]*>.*?</\\1>"), " ")
        val breaks = noBlocks.replace(
            Regex("(?i)<(br|/p|/div|/tr|/li|/h[1-6]|/blockquote)[^>]*>"),
            "\n"
        )
        val noTags = breaks.replace(Regex("<[^>]+>"), " ")
        return decodeEntities(noTags)
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** `&amp;` decodes last so escaped entities (`&amp;lt;`) survive round-trips. */
    private fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun cap(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    private fun attachmentCount(stored: String?): Int {
        if (stored.isNullOrBlank()) return 0
        return runCatching {
            attachmentsJson.decodeFromString<List<EmailAttachment>>(stored).size
        }.getOrDefault(0)
    }
}
