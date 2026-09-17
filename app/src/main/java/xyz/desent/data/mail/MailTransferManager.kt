package xyz.desent.data.mail

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.desent.crypto.BackupEnvelope
import xyz.desent.data.local.database.dao.EmailDao
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.mapper.EmailMapper
import xyz.desent.domain.model.Email

/**
 * Offline mail transfer (NIP-EMAIL forwarding fallback): exports stored mail
 * to a passphrase-encrypted `.dsme` file and imports it back under the
 * currently active account — a relay-free path for device transfers or as a
 * fallback when direct key-to-key forwarding isn't possible.
 *
 * The envelope is the same `DSBK1` scrypt + AES-256-GCM construction as the
 * account backup ([xyz.desent.crypto.BackupEnvelope]); the plaintext is a
 * versioned JSON payload of decrypted [Email] rows. Threading ids, spam
 * verdicts and attachment keys ride along, so an import under the target
 * account renders identical threads with zero relay round-trips.
 */
class MailTransferManager(
    private val emailDao: EmailDao,
    private val emailMapper: EmailMapper,
    private val preferencesManager: PreferencesManager
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    data class MailExportPayload(
        val version: Int = VERSION,
        val exportedAt: Long,
        val emails: List<Email>
    )

    /**
     * Encrypt the forward-eligible mailbox (optionally scoped to [threadKeys])
     * under [passphrase]. Returns the `.dsme` envelope bytes.
     */
    suspend fun export(
        threadKeys: List<String>?,
        passphrase: String
    ): Result<ByteArray> = withContext(Dispatchers.Default) {
        runCatching {
            require(passphrase.length >= MIN_PASSPHRASE_LEN) { "Passphrase must be at least $MIN_PASSPHRASE_LEN characters" }
            val npub = preferencesManager.npubKey.firstOrNull() ?: error("Not logged in")
            val entities = if (threadKeys.isNullOrEmpty()) {
                emailDao.getForwardEligibleEmails(npub).filterNot { it.isSpam }
            } else {
                emailDao.getForwardEligibleEmailsByThreads(npub, threadKeys)
            }
            require(entities.isNotEmpty()) { "No mail to export" }

            val payload = MailExportPayload(
                exportedAt = System.currentTimeMillis(),
                emails = entities.map { emailMapper.mapToDomain(it) }
            )
            BackupEnvelope.pack(
                json.encodeToString(payload).toByteArray(Charsets.UTF_8),
                passphrase
            )
        }.onFailure { Log.e(TAG, "Mail export failed: ${it.message}", it) }
    }

    /**
     * Decrypt a `.dsme` envelope and store its mail under the currently
     * active account. Rows keep their ids — inserts are IGNOREd, so
     * re-importing the same file (or overlapping files) is idempotent.
     * Returns the number of newly inserted messages.
     */
    suspend fun import(blob: ByteArray, passphrase: String): Result<Int> =
        withContext(Dispatchers.Default) {
            runCatching {
                val npub = preferencesManager.npubKey.firstOrNull() ?: error("Not logged in")
                val payload = json.decodeFromString<MailExportPayload>(
                    String(BackupEnvelope.unpack(blob, passphrase), Charsets.UTF_8)
                )
                require(payload.version <= VERSION) { "Newer export format: ${payload.version}" }

                var inserted = 0
                payload.emails.forEach { email ->
                    val entity = emailMapper.mapToEntity(email.copy(recipientNpub = npub))
                    val existed = emailDao.getEmailById(entity.id) != null
                    emailDao.insertEmail(entity)
                    if (!existed) inserted++
                }
                inserted
            }.onFailure { Log.e(TAG, "Mail import failed: ${it.message}", it) }
        }

    companion object {
        private const val TAG = "MailTransfer"
        const val VERSION = 1
        const val FILE_EXTENSION = "dsme"
        private const val MIN_PASSPHRASE_LEN = 4
    }
}
