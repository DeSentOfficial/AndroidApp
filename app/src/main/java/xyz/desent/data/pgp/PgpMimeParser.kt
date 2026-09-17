package xyz.desent.data.pgp

import org.apache.james.mime4j.dom.BinaryBody
import org.apache.james.mime4j.dom.Entity
import org.apache.james.mime4j.dom.Multipart
import org.apache.james.mime4j.dom.TextBody
import org.apache.james.mime4j.message.DefaultMessageBuilder
import xyz.desent.domain.model.PgpDecryptedMessage
import xyz.desent.domain.model.PgpInnerAttachment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Parses the decrypted contents of a PGP/MIME message (RFC 3156): the
 * plaintext inside the encryption envelope is itself a MIME entity, usually
 * `multipart/mixed` with `text/plain` or `text/html` body parts plus inline
 * attachments. These parts have NO `attachment`/`cid` rumor tags — the inner
 * MIME is entirely client-handled (ANDROID_PGP.md §3.3).
 *
 * Pure JVM — no Android dependencies — so it is directly unit-testable.
 */
class PgpMimeParser {

    fun parse(plaintext: ByteArray): PgpDecryptedMessage {
        val asText = String(plaintext, Charsets.UTF_8)
        if (!looksLikeMime(asText)) {
            // Simple senders encrypt bare text; no MIME structure at all.
            return PgpDecryptedMessage(
                body = asText,
                isHtml = false,
                attachments = emptyList()
            )
        }
        return try {
            val message = DefaultMessageBuilder().parseMessage(ByteArrayInputStream(plaintext))
            val collected = Collector()
            collected.walk(message)
            val body = collected.plainBody ?: collected.htmlBody ?: ""
            PgpDecryptedMessage(
                body = body,
                isHtml = collected.plainBody == null && collected.htmlBody != null,
                attachments = collected.attachments
            )
        } catch (e: Exception) {
            // Header-ish but unparseable — degrade to raw text, never crash
            // the reader on a malformed sender.
            PgpDecryptedMessage(body = asText, isHtml = false, attachments = emptyList())
        }
    }

    /**
     * Heuristic: does the decrypted plaintext carry MIME headers? Bare
     * message text almost never starts with a header name at column 0, while
     * every RFC 3156 payload does.
     */
    private fun looksLikeMime(text: String): Boolean {
        val head = text.take(2000)
        return HEADER_REGEX.containsMatchIn(head)
    }

    private class Collector {
        var plainBody: String? = null
        var htmlBody: String? = null
        val attachments = mutableListOf<PgpInnerAttachment>()

        fun walk(entity: Entity) {
            val body = entity.body
            if (body is Multipart) {
                for (part in body.bodyParts) walk(part)
                return
            }

            val mime = entity.mimeType?.lowercase() ?: "text/plain"
            val filename = entity.filename
            val isAttachment = filename != null ||
                entity.dispositionType.equals("attachment", ignoreCase = true) ||
                !mime.startsWith("text/")

            if (isAttachment) {
                val bytes = when (body) {
                    is BinaryBody -> body.inputStream.readBytes()
                    is TextBody -> body.reader.readText().toByteArray(Charsets.UTF_8)
                    else -> ByteArray(0)
                }
                if (bytes.isNotEmpty()) {
                    attachments.add(
                        PgpInnerAttachment(
                            filename = filename ?: "attachment-${attachments.size + 1}",
                            mimeType = mime,
                            data = bytes
                        )
                    )
                }
                return
            }

            when {
                mime == "text/plain" && plainBody == null -> {
                    plainBody = (body as? TextBody)?.reader?.readText()
                        ?: String(bodyBytes(body), Charsets.UTF_8)
                }
                mime == "text/html" && htmlBody == null -> {
                    htmlBody = (body as? TextBody)?.reader?.readText()
                        ?: String(bodyBytes(body), Charsets.UTF_8)
                }
            }
        }

        private fun bodyBytes(body: org.apache.james.mime4j.dom.Body): ByteArray =
            ByteArrayOutputStream().use { out ->
                when (body) {
                    is BinaryBody -> body.inputStream.copyTo(out)
                    is TextBody -> body.reader.forEachLine { out.write(it.toByteArray()); out.write('\n'.code) }
                    else -> {
                        // unknown body type: nothing sensible to extract
                    }
                }
                out.toByteArray()
            }
    }

    companion object {
        private val HEADER_REGEX = Regex(
            "^(Content-Type|MIME-Version|From|To|Subject|Date|Message-ID|User-Agent):",
            RegexOption.IGNORE_CASE
        )
    }
}
