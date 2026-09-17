package xyz.desent.domain.usecase

import xyz.desent.domain.repository.KeyBackupRepository

/**
 * Produces an encrypted `DSBK1` backup blob for the given accounts. The caller
 * (ViewModel) is responsible for writing the returned bytes to the
 * user-chosen Storage-Access-Framework destination.
 */
class ExportBackupUseCase(
    private val keyBackupRepository: KeyBackupRepository,
) {
    suspend fun execute(selectedNpubs: Set<String>, passphrase: String): Result<ByteArray> =
        keyBackupRepository.export(selectedNpubs, passphrase)
}
