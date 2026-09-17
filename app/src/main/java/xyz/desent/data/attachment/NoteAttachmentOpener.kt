package xyz.desent.data.attachment

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import xyz.desent.domain.model.AttachmentMeta
import xyz.desent.domain.usecase.PrivateStorageUseCase
import java.io.File

/**
 * Downloads a note attachment's ciphertext, AES-GCM decrypts it on device,
 * writes the plaintext to `filesDir/attachments/` and exposes it through the
 * app's [FileProvider] (`${applicationId}.fileprovider`) so the system viewer
 * (ACTION_VIEW) can open it.
 *
 * The relay only ever stored opaque ciphertext; the AES key + nonce come from
 * the NIP-44-encrypted [AttachmentMeta] held inside the note.
 */
class NoteAttachmentOpener(
    private val context: Context,
    private val useCase: PrivateStorageUseCase
) {

    /** A decrypted attachment ready to hand to an `ACTION_VIEW` intent. */
    data class OpenTarget(val uri: Uri, val mimeType: String)

    suspend fun open(meta: AttachmentMeta): Result<OpenTarget> =
        useCase.downloadAttachment(meta).map { plaintext ->
            val dir = File(context.filesDir, "attachments").apply { mkdirs() }
            val safeName = meta.filename.ifBlank { meta.sha256.take(12) }
            // Prefix with the sha so repeated opens of equally-named files
            // don't clobber each other.
            val file = File(dir, "${meta.sha256.take(12)}_${sanitize(safeName)}")
            file.writeBytes(plaintext)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            OpenTarget(uri, meta.mimeType.ifBlank { "application/octet-stream" })
        }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(MAX_NAME_LEN)

    private companion object {
        const val MAX_NAME_LEN = 64
    }
}
