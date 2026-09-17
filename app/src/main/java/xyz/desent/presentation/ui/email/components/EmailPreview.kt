package xyz.desent.presentation.ui.email.components

import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailBodyFormat
import xyz.desent.domain.model.EmailDirection

/**
 * Snippet for inbox rows: the message's own words only — the quoted tail is
 * split off ([EmailQuoteSplitter]), HTML is stripped, whitespace collapsed.
 * Inbound PGP mail shows a locked placeholder instead of armor (the body is
 * only decrypted on-device when opened).
 */
fun emailPreview(email: Email): String {
    if (email.isPgpEncrypted && email.direction != EmailDirection.OUTBOUND) {
        return "🔒 Encrypted message — open to decrypt"
    }
    if (email.content.isBlank()) return ""
    val split = EmailQuoteSplitter.split(email.content, email.bodyFormat)
    val body = if (split.quotedTail != null) split.newContent else email.content
    return if (email.bodyFormat == EmailBodyFormat.PLAIN) {
        body.replace(Regex("\\s+"), " ")
    } else {
        xyz.desent.data.HtmlTextUtils.stripHtml(body)
    }.trim()
}
