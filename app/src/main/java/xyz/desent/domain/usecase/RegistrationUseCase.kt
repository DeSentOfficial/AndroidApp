package xyz.desent.domain.usecase

import xyz.desent.domain.model.AvailabilityInfo
import xyz.desent.domain.model.InviteCodes
import xyz.desent.domain.model.RegisteredAccount
import xyz.desent.domain.model.RegistrationMode
import xyz.desent.domain.repository.RegistrationRepository

class RegistrationUseCase(
    private val registrationRepository: RegistrationRepository
) {
    suspend fun getAccount(): Result<RegisteredAccount?> =
        registrationRepository.getAccount()

    suspend fun checkAvailable(local: String): Result<AvailabilityInfo> =
        registrationRepository.checkAvailable(local)

    suspend fun getRegistrationMode(): Result<RegistrationMode> =
        registrationRepository.getRegistrationMode()

    suspend fun validateReferralCode(code: String): Result<Boolean> =
        registrationRepository.validateReferralCode(code)

    suspend fun getInviteCodes(): Result<InviteCodes> =
        registrationRepository.getInviteCodes()

    suspend fun register(
        local: String,
        displayName: String?,
        picture: String?,
        about: String?,
        referralCode: String? = null,
        domain: String? = null
    ): Result<RegisteredAccount> =
        registrationRepository.register(local, displayName, picture, about, referralCode, domain)

    suspend fun updateProfile(
        displayName: String?,
        about: String?,
        picture: String?,
        website: String?
    ): Result<RegisteredAccount> =
        registrationRepository.updateProfile(displayName, about, picture, website)

    suspend fun uploadProfilePicture(imageData: ByteArray, mimeType: String, fileName: String): Result<String> =
        registrationRepository.uploadProfilePicture(imageData, mimeType, fileName)
}
