package xyz.desent.domain.repository

import xyz.desent.domain.model.AvailabilityInfo
import xyz.desent.domain.model.InviteCodes
import xyz.desent.domain.model.RegisteredAccount
import xyz.desent.domain.model.RegistrationMode

interface RegistrationRepository {
    /**
     * The caller's account, or null if not yet registered (404).
     *
     * @param identity explicit signer for a non-active account (per-account
     * NIP-98); null signs with the active account's key.
     */
    suspend fun getAccount(identity: nostr.id.Identity? = null): Result<RegisteredAccount?>
    suspend fun checkAvailable(local: String): Result<AvailabilityInfo>
    suspend fun getRegistrationMode(): Result<RegistrationMode>
    /** Live invite-code validation (public, 30 req/hour/IP — debounce callers). */
    suspend fun validateReferralCode(code: String): Result<Boolean>
    /** The caller's invite codes. Re-fetch every use; admins can regenerate unused codes. */
    suspend fun getInviteCodes(): Result<InviteCodes>
    suspend fun register(
        local: String,
        displayName: String?,
        picture: String?,
        about: String?,
        referralCode: String? = null,
        domain: String? = null
    ): Result<RegisteredAccount>

    suspend fun updateProfile(
        displayName: String?,
        about: String?,
        picture: String?,
        website: String?
    ): Result<RegisteredAccount>

    suspend fun uploadProfilePicture(imageData: ByteArray, mimeType: String, fileName: String): Result<String>
}
