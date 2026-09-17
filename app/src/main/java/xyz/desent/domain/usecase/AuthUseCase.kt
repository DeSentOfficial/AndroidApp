package xyz.desent.domain.usecase

import android.content.Context
import xyz.desent.domain.model.AccountCreationRequest
import xyz.desent.domain.model.AccountCreationResult
import xyz.desent.domain.model.CustodialAccountCreationRequest
import xyz.desent.domain.model.CustodialLoginResult
import xyz.desent.domain.repository.AuthRepository
import xyz.desent.domain.repository.LogoutResult

class AuthUseCase(
    private val authRepository: AuthRepository
) {

    suspend fun login(nsec: String, rememberMe: Boolean, enableBiometrics: Boolean): Result<String> {
        return authRepository.login(nsec, rememberMe, enableBiometrics)
    }

    suspend fun loginWithNcryptsec(
        ncryptsec: String,
        password: String,
        rememberMe: Boolean,
        enableBiometrics: Boolean
    ): Result<String> {
        return authRepository.loginWithNcryptsec(ncryptsec, password, rememberMe, enableBiometrics)
    }

    suspend fun custodialLogin(
        username: String,
        password: String,
        rememberMe: Boolean,
        enableBiometrics: Boolean
    ): Result<CustodialLoginResult> {
        return authRepository.custodialLogin(username, password, rememberMe, enableBiometrics)
    }

    suspend fun createAccount(
        request: AccountCreationRequest,
        context: Context,
        existingNsec: String? = null
    ): Result<AccountCreationResult> {
        return authRepository.createAccount(request, context, existingNsec)
    }

    suspend fun createCustodialAccount(
        request: CustodialAccountCreationRequest,
        context: Context,
        existingNsec: String? = null
    ): Result<AccountCreationResult> {
        return authRepository.createCustodialAccount(request, context, existingNsec)
    }

    /** Memory-only keypair for the pre-account vanity checkout (§4.1). */
    suspend fun prepareSignupKey(): Result<xyz.desent.domain.repository.PreparedSignupKey> {
        return authRepository.prepareSignupKey()
    }

    suspend fun changeCustodialPassword(oldPassword: String, newPassword: String): Result<Unit> {
        return authRepository.changeCustodialPassword(oldPassword, newPassword)
    }

    suspend fun probeLinkedNostrAccount(
        nsec: String
    ): Result<xyz.desent.domain.repository.LinkedNostrProbe> {
        return authRepository.probeLinkedNostrAccount(nsec)
    }

    suspend fun completeLinkedNostrLogin(
        probe: xyz.desent.domain.repository.LinkedNostrProbe.Linked,
        password: String,
        rememberMe: Boolean,
        enableBiometrics: Boolean
    ): Result<CustodialLoginResult> {
        return authRepository.completeLinkedNostrLogin(probe, password, rememberMe, enableBiometrics)
    }

    suspend fun logout(): LogoutResult {
        return authRepository.logout()
    }

    suspend fun validateNsec(nsec: String): Result<String> {
        return authRepository.validateNsec(nsec)
    }

    suspend fun getNpubFromNsec(nsec: String): String? {
        return authRepository.getNpubFromNsec(nsec)
    }

    suspend fun isNsecStored(): Boolean {
        return authRepository.isNsecStored()
    }

    suspend fun getStoredNpub(): String? {
        return authRepository.getStoredNpub()
    }

    suspend fun reconnectRelays() {
        return authRepository.reconnectRelays()
    }
}