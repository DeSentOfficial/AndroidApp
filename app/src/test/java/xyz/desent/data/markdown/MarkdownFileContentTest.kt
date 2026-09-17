package xyz.desent.data.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MarkdownFileContent] byte-level guards: the size cap and binary sniff
 * that keep a mislabeled or oversized file out of the viewer.
 */
class MarkdownFileContentTest {

    @Test
    fun `decodes utf-8 text`() {
        val text = MarkdownFileContent.toText("# Héllo wörld ✓".toByteArray()).getOrThrow()

        assertEquals("# Héllo wörld ✓", text)
    }

    @Test
    fun `rejects files above the cap`() {
        val tooBig = ByteArray(MarkdownFileContent.MAX_BYTES + 1) { 'a'.code.toByte() }

        assertTrue(MarkdownFileContent.toText(tooBig).isFailure)
    }

    @Test
    fun `rejects binary content with nul bytes`() {
        val binary = byteArrayOf(0x50, 0x4b, 0x00, 0x03)

        assertTrue(MarkdownFileContent.toText(binary).isFailure)
    }

    @Test
    fun `rejects empty files`() {
        val result = MarkdownFileContent.toText(ByteArray(0))

        assertTrue(result.isFailure)
        assertNull(result.getOrNull())
    }

    @Test
    fun `accepts a file exactly at the cap`() {
        val atCap = ByteArray(MarkdownFileContent.MAX_BYTES) { 'a'.code.toByte() }

        assertFalse(MarkdownFileContent.toText(atCap).isFailure)
    }
}
