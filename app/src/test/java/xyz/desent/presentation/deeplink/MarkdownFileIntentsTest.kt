package xyz.desent.presentation.deeplink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MarkdownFileIntents] matching rules: mirrors the manifest's two markdown
 * intent-filters (MIME-based and extension-based) and must never claim
 * non-markdown documents (e.g. .desentbackup restore files).
 */
class MarkdownFileIntentsTest {

    @Test
    fun `markdown mime type matches for content uri`() {
        assertTrue(
            MarkdownFileIntents.isMarkdownDocument(
                "content://com.android.providers.media.documents/document/document%3A28",
                "text/markdown"
            )
        )
    }

    @Test
    fun `x-markdown mime type matches regardless of case and whitespace`() {
        assertTrue(
            MarkdownFileIntents.isMarkdownDocument(
                "content://media/external/file/12",
                "  TEXT/X-MARKDOWN "
            )
        )
    }

    @Test
    fun `md extension matches when provider reports octet-stream`() {
        assertTrue(
            MarkdownFileIntents.isMarkdownDocument(
                "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fnotes.md",
                "application/octet-stream"
            )
        )
    }

    @Test
    fun `md extension matches with null mime and legacy file scheme`() {
        assertTrue(matches("file:///sdcard/Download/notes.md"))
    }

    @Test
    fun `markdown extension and mixed case match`() {
        assertTrue(matches("content://x/y/README.MARKDOWN"))
        assertTrue(matches("content://x/y/report.Md"))
    }

    @Test
    fun `query string and fragment are ignored`() {
        assertTrue(matches("content://x/y/notes.md?source=files"))
        assertTrue(matches("content://x/y/notes.md#section"))
    }

    @Test
    fun `desentbackup files never match`() {
        assertFalse(
            MarkdownFileIntents.isMarkdownDocument(
                "content://x/y/desent-backup-20260101.desentbackup",
                "application/octet-stream"
            )
        )
    }

    @Test
    fun `other extensions and mimes do not match`() {
        assertFalse(matches("content://x/y/report.pdf"))
        assertFalse(matches("content://x/y/photo.png", "image/png"))
        assertFalse(matches("content://x/y/readme.txt", "text/plain"))
    }

    @Test
    fun `non file schemes never match`() {
        assertFalse(matches("https://desent.xyz/docs/readme.md", "text/markdown"))
        assertFalse(matches("desent://settings"))
        assertFalse(matches("nostr:npub1abc"))
    }

    @Test
    fun `blank input never matches`() {
        assertFalse(MarkdownFileIntents.isMarkdownDocument(null, "text/markdown"))
        assertFalse(MarkdownFileIntents.isMarkdownDocument("", null))
        assertFalse(MarkdownFileIntents.isMarkdownDocument("   ", "text/markdown"))
    }

    private fun matches(uri: String, mime: String? = null): Boolean =
        MarkdownFileIntents.isMarkdownDocument(uri, mime)
}
