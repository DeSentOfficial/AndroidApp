package xyz.desent.domain.usecase

import kotlinx.coroutines.flow.Flow
import xyz.desent.domain.model.SecurityAlertMode
import xyz.desent.domain.model.SecurityConfig
import xyz.desent.domain.repository.SecurityConfigRepository

/**
 * Read / publish the security slice of the kind-30079 user settings:
 * the login-security alert mode (off / new_device / always).
 */
class SecurityConfigUseCase(
    private val repository: SecurityConfigRepository
) {
    fun observe(ownerNpub: String): Flow<SecurityConfig?> = repository.observe(ownerNpub)

    suspend fun get(ownerNpub: String): SecurityConfig? = repository.get(ownerNpub)

    suspend fun setAlertMode(ownerNpub: String, mode: SecurityAlertMode): Result<Unit> =
        repository.setAlertMode(ownerNpub, mode)

    /** Pre-check the compose lock when the recipient has a discoverable key. */
    suspend fun setPgpAutoEncrypt(ownerNpub: String, enabled: Boolean): Result<Unit> =
        repository.setPgpAutoEncrypt(ownerNpub, enabled)

    suspend fun refresh(): Result<Unit> = repository.refresh()
}
