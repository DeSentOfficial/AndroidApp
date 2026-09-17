package xyz.desent.data.attachment

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import xyz.desent.domain.model.UserFile
import xyz.desent.domain.usecase.PrivateStorageUseCase
import java.io.File

/**
 * Downloads a user-uploaded file's ciphertext, AES-GCM decrypts it on device,
 * writes the plaintext to `filesDir/attachments/` and exposes it through the
 * app's [FileProvider] (`${applicationId}.fileprovider`) so the system viewer
 * (ACTION_VIEW) can open it.
 *
 * The server only ever stored opaque ciphertext; the AES key + nonce come from
 * the NIP-44-encrypted 30078 payload mirrored into [UserFile].
 */
class UserFileOpener(
    private val context: Context,
    private val useCase: PrivateStorageUseCase
) {

    /** A decrypted file ready to hand to an `ACTION_VIEW` intent. */
    data class OpenTarget(val uri: Uri, val mimeType: String)

    suspend fun open(file: UserFile): Result<OpenTarget> =
        useCase.downloadUserFile(file).map { plaintext ->
            val dir = File(context.filesDir, "attachments").apply { mkdirs() }
            val safeName = file.filename.ifBlank { file.sha256.take(12) }
            // Prefix with the sha so repeated opens of equally-named files
            // don't clobber each other.
            val out = File(dir, "${file.sha256.take(12)}_${sanitize(safeName)}")
            out.writeBytes(plaintext)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
            OpenTarget(uri, file.mimeType.ifBlank { "application/octet-stream" })
        }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(MAX_NAME_LEN)

    private companion object {
        const val MAX_NAME_LEN = 64
    }
}
