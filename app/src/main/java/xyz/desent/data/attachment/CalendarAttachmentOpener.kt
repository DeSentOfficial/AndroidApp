package xyz.desent.data.attachment

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import xyz.desent.domain.model.AttachmentMeta
import xyz.desent.domain.usecase.CalendarUseCase
import java.io.File

/**
 * Downloads a calendar-event attachment's ciphertext, AES-GCM decrypts it on
 * device, writes the plaintext to `filesDir/attachments/` and exposes it
 * through the app's [FileProvider] so the system viewer can open it. Mirrors
 * [NoteAttachmentOpener] for the calendar flow.
 */
class CalendarAttachmentOpener(
    private val context: Context,
    private val useCase: CalendarUseCase
) {

    data class OpenTarget(val uri: Uri, val mimeType: String)

    suspend fun open(meta: AttachmentMeta): Result<OpenTarget> =
        useCase.downloadAttachment(meta).map { plaintext ->
            val dir = File(context.filesDir, "attachments").apply { mkdirs() }
            val safeName = meta.filename.ifBlank { meta.sha256.take(12) }
            val file = File(dir, "cal_${meta.sha256.take(12)}_${sanitize(safeName)}")
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
