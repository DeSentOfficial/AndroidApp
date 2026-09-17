package xyz.desent.data.pgp

import android.content.Context
import android.util.Log
import xyz.desent.domain.model.PgpInnerAttachment
import java.io.File

/**
 * App-private on-disk cache for attachments extracted from inside the PGP
 * envelope (ANDROID_PGP.md §3.3 — inner MIME is client-handled; there is no
 * Blossom/`attachment`-tag path for PGP mail). One directory per email id,
 * cleared when the message is deleted.
 */
class PgpAttachmentCache(context: Context) {

    private val root: File = File(context.filesDir, ROOT_DIR)

    /** Persist [attachment] under the email's directory; returns the file. */
    fun save(emailId: String, attachment: PgpInnerAttachment): File? = try {
        val dir = File(root, emailId)
        if (!dir.exists()) dir.mkdirs()
        val safeName = attachment.filename.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .take(MAX_NAME_LEN)
            .ifBlank { "attachment" }
        val file = File(dir, safeName)
        file.writeBytes(attachment.data)
        file
    } catch (e: Exception) {
        Log.w(TAG, "Failed to cache PGP attachment ${attachment.filename}: ${e.message}")
        null
    }

    /** Previously-saved files for a message (display cache hit path). */
    fun filesFor(emailId: String): List<File> {
        val dir = File(root, emailId)
        return if (dir.isDirectory) dir.listFiles()?.sortedBy { it.name }.orEmpty() else emptyList()
    }

    /** Drop a message's decrypted attachments (called on message delete). */
    fun clear(emailId: String) {
        val dir = File(root, emailId)
        if (dir.isDirectory) dir.deleteRecursively()
    }

    companion object {
        private const val TAG = "PgpAttachmentCache"
        private const val ROOT_DIR = "pgp"
        private const val MAX_NAME_LEN = 120
    }
}
