package xyz.desent.data.markdown

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads and writes markdown documents on the device through the Storage
 * Access Framework. Abstracted behind an interface so the viewer ViewModel
 * is testable without a ContentResolver.
 *
 * Access semantics: URIs arrive either from an ACTION_VIEW intent (temporary
 * read grant, tied to the activity instance — write may be denied) or the
 * in-app OpenDocument picker (read + write for the session). Write failures
 * surface as failures the caller can react to (e.g. offer a copy elsewhere).
 */
interface MarkdownFileIO {
    suspend fun read(uriString: String): Result<String>
    suspend fun write(uriString: String, content: String): Result<Unit>
}

/** Pure content validation so the cap/binary rules are unit-testable on the JVM. */
object MarkdownFileContent {
    /** Markdown documents above this size are rejected rather than loaded into memory. */
    const val MAX_BYTES: Int = 2 * 1024 * 1024

    /**
     * Decode raw bytes as a UTF-8 markdown document. Fails when the file
     * exceeds [MAX_BYTES] or contains NUL bytes (binary sniff — a text
     * document mislabeled .md), which would otherwise render as garbage.
     */
    fun toText(bytes: ByteArray): Result<String> = runCatching {
        require(bytes.size <= MAX_BYTES) {
            "File is too large to open (${bytes.size / 1024} KB; limit ${MAX_BYTES / 1024} KB)"
        }
        require(bytes.isNotEmpty()) { "File is empty" }
        require(!bytes.contains(0)) { "Not a text document" }
        String(bytes, Charsets.UTF_8)
    }
}

/** [MarkdownFileIO] backed by the app's [ContentResolver]. */
class ContentResolverMarkdownFileIO(
    private val contentResolver: ContentResolver
) : MarkdownFileIO {

    override suspend fun read(uriString: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("File not found or unreadable")
            MarkdownFileContent.toText(bytes).getOrThrow()
        }
    }

    override suspend fun write(uriString: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            val bytes = content.toByteArray(Charsets.UTF_8)
            // "wt" truncates; some providers reject the mode and need plain "w".
            val stream = try {
                contentResolver.openOutputStream(uri, "wt")
            } catch (_: Exception) {
                null
            } ?: contentResolver.openOutputStream(uri, "w")
                ?: error("File is not writable")
            stream.use { it.write(bytes) }
        }
    }
}
