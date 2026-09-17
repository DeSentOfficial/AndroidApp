package xyz.desent.data.pgp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inner-MIME extraction from decrypted PGP/MIME payloads
 * (ANDROID_PGP.md §3.3): bare text passes through, multipart/mixed yields
 * body + attachments.
 */
class PgpMimeParserTest {

    private val parser = PgpMimeParser()

    @Test
    fun bareText_isNotMime_passesThrough() {
        val result = parser.parse("Just a plain hello\nwith two lines".toByteArray())

        assertEquals("Just a plain hello\nwith two lines", result.body)
        assertFalse(result.isHtml)
        assertTrue(result.attachments.isEmpty())
    }

    @Test
    fun simpleTextPlainMime_extractsBody() {
        val mime = """
            Content-Type: text/plain; charset=utf-8

            Hello from the inside.
        """.trimIndent()

        val result = parser.parse(mime.toByteArray())

        assertEquals("Hello from the inside.", result.body.trim())
        assertFalse(result.isHtml)
    }

    @Test
    fun htmlBody_isFlagged() {
        val mime = """
            Content-Type: text/html; charset=utf-8

            <html><body><p>Hi</p></body></html>
        """.trimIndent()

        val result = parser.parse(mime.toByteArray())

        assertTrue(result.isHtml)
        assertTrue(result.body.contains("<p>Hi</p>"))
    }

    @Test
    fun multipartMixed_extractsBodyAndAttachment() {
        val mime = buildString {
            append("Content-Type: multipart/mixed; boundary=\"=BOUND=\"\r\n")
            append("\r\n")
            append("--=BOUND=\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("\r\n")
            append("See attached.\r\n")
            append("--=BOUND=\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Disposition: attachment; filename=\"data.bin\"\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("\r\n")
            append("aGVsbG8gcGdw\r\n")
            append("--=BOUND=--\r\n")
        }

        val result = parser.parse(mime.toByteArray())

        assertEquals("See attached.", result.body.trim())
        assertEquals(1, result.attachments.size)
        val attachment = result.attachments.first()
        assertEquals("data.bin", attachment.filename)
        assertEquals("hello pgp", String(attachment.data))
    }

    @Test
    fun multipartAlternative_prefersPlainText() {
        val mime = buildString {
            append("Content-Type: multipart/alternative; boundary=\"=B=\"\r\n")
            append("\r\n")
            append("--=B=\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("\r\n")
            append("plain wins\r\n")
            append("--=B=\r\n")
            append("Content-Type: text/html\r\n")
            append("\r\n")
            append("<p>html loses</p>\r\n")
            append("--=B=--\r\n")
        }

        val result = parser.parse(mime.toByteArray())

        assertEquals("plain wins", result.body.trim())
        assertFalse(result.isHtml)
    }

    @Test
    fun malformedMime_degradesToRawText() {
        val result = parser.parse("Subject: broken\r\n\r\nnot really mime \u0000 junk".toByteArray())
        // Never crashes the reader; body is whatever the plaintext was.
        assertTrue(result.body.contains("not really mime"))
    }
}
