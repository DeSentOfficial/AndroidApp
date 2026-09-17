package xyz.desent.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.desent.crypto.BackupEnvelope
import xyz.desent.crypto.BackupException
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.database.dao.AccountDao
import xyz.desent.data.local.database.dao.RelayDao
import xyz.desent.domain.model.BackupAccount
import xyz.desent.domain.model.BackupContents
import xyz.desent.domain.model.BackupRelay
import xyz.desent.domain.repository.KeyBackupRepository

/**
 * Builds/reads `DSBK1` backup blobs from local key + relay storage.
 *
 * Export gathers each selected account's nsec (via [SecureKeyManager]) and its
 * cached profile (via [AccountDao]), plus the persistent relay list
 * ([RelayDao]); the relay list is always included so a restore re-seeds the
 * connection pool. Crypto runs on [Dispatchers.Default] (scrypt is CPU-bound).
 */
class KeyBackupRepositoryImpl(
    private val secureKeyManager: SecureKeyManager,
    private val accountDao: AccountDao,
    private val relayDao: RelayDao,
) : KeyBackupRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun export(
        selectedNpubs: Set<String>,
        passphrase: String,
    ): Result<ByteArray> = withContext(Dispatchers.Default) {
        runCatching {
            require(selectedNpubs.isNotEmpty()) { "No accounts selected for backup" }
            require(passphrase.isNotEmpty()) { "Passphrase must not be empty" }

            val accounts = selectedNpubs.map { npub ->
                val nsec = secureKeyManager.getNSECForAccount(npub).getOrElse {
                    throw IllegalStateException("No stored key for account $npub", it)
                }
                val row = accountDao.getAccount(npub)
                BackupAccount(
                    npub = npub,
                    nsec = nsec,
                    displayName = row?.displayName,
                    picture = row?.picture,
                    nip05 = row?.nip05,
                    requiresBiometrics = row?.requiresBiometrics ?: false,
                )
            }

            val relays = relayDao.getPersistentRelays().map { entity ->
                BackupRelay(url = entity.url, isWrite = entity.isWrite)
            }

            val contents = BackupContents(
                version = 1,
                createdAt = System.currentTimeMillis(),
                accounts = accounts,
                relays = relays,
            )

            val plaintext = json.encodeToString(contents).toByteArray(Charsets.UTF_8)
            BackupEnvelope.pack(plaintext, passphrase)
        }.onFailure { Log.e(TAG, "Backup export failed", it) }
    }

    override suspend fun decrypt(
        blob: ByteArray,
        passphrase: String,
    ): Result<BackupContents> = withContext(Dispatchers.Default) {
        runCatching {
            val plaintext = BackupEnvelope.unpack(blob, passphrase)
            json.decodeFromString<BackupContents>(String(plaintext, Charsets.UTF_8))
        }.onFailure { Log.e(TAG, "Backup decrypt failed", it) }
    }

    companion object {
        private const val TAG = "KeyBackupRepo"
    }
}
