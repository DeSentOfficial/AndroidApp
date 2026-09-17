package xyz.desent.data.repository

import android.util.Log
import xyz.desent.data.registration.RegistrationClient
import xyz.desent.data.registration.model.RegisterRequest
import xyz.desent.data.registration.model.RegistrationError
import xyz.desent.data.registration.model.UpdateProfileRequest
import xyz.desent.domain.model.AvailabilityInfo
import xyz.desent.domain.model.InviteCode
import xyz.desent.domain.model.InviteCodes
import xyz.desent.domain.model.RegisteredAccount
import xyz.desent.domain.model.RegistrationMode
import xyz.desent.domain.model.parseIsoEpochMillis
import xyz.desent.domain.repository.RegistrationRepository

class RegistrationRepositoryImpl(
    private val registrationClient: RegistrationClient
) : RegistrationRepository {

    override suspend fun getAccount(identity: nostr.id.Identity?): Result<RegisteredAccount?> {
        return try {
            registrationClient.getMe(identity).map { it.toDomain() }
                .recoverCatching { e ->
                    if (e is RegistrationError.NotRegistered) null else throw e
                }
        } catch (e: Exception) {
            Log.w(TAG, "getAccount failed: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun checkAvailable(local: String): Result<AvailabilityInfo> {
        return registrationClient.checkAvailable(local).map {
            AvailabilityInfo(
                available = it.available,
                local = it.local,
                priceSats = it.priceSats,
                vanity = it.vanity
            )
        }
    }

    override suspend fun getRegistrationMode(): Result<RegistrationMode> {
        return registrationClient.getRegisterMode().map { RegistrationMode.fromWire(it.mode) }
    }

    override suspend fun validateReferralCode(code: String): Result<Boolean> {
        return registrationClient.checkReferralCode(code).map { it.valid }
    }

    override suspend fun getInviteCodes(): Result<InviteCodes> {
        return registrationClient.getReferrals().map { response ->
            InviteCodes(
                codes = response.codes.map { dto ->
                    InviteCode(
                        code = dto.code,
                        used = dto.used,
                        usedBy = dto.usedBy,
                        createdAt = parseIsoEpochMillis(dto.createdAt),
                        usedAt = parseIsoEpochMillis(dto.usedAt)
                    )
                },
                cap = response.cap
            )
        }
    }

    override suspend fun register(
        local: String,
        displayName: String?,
        picture: String?,
        about: String?,
        referralCode: String?,
        domain: String?
    ): Result<RegisteredAccount> {
        val request = RegisterRequest(
            local = local.trim(),
            domain = domain,
            displayName = displayName,
            picture = picture,
            about = about,
            // The server compares verbatim after trimming — always send uppercase.
            referralCode = referralCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        )
        return registrationClient.register(request).map { it.toDomain() }
    }

    override suspend fun updateProfile(
        displayName: String?,
        about: String?,
        picture: String?,
        website: String?
    ): Result<RegisteredAccount> {
        val request = UpdateProfileRequest(
            displayName = displayName,
            about = about,
            picture = picture,
            website = website
        )
        return registrationClient.updateProfile(request).map { it.toDomain() }
    }

    override suspend fun uploadProfilePicture(
        imageData: ByteArray,
        mimeType: String,
        fileName: String
    ): Result<String> =
        registrationClient.uploadProfilePicture(imageData, mimeType, fileName)

    private fun xyz.desent.data.registration.model.MeResponse.toDomain() = RegisteredAccount(
        local = resolvedLocal,
        displayName = displayName,
        picture = resolvedPicture,
        about = about,
        tier = resolvedTier,
        nip05 = nip05,
        emailAddress = emailAddress
    )

    companion object {
        private const val TAG = "RegistrationRepository"
    }
}
