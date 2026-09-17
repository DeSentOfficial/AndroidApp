package xyz.desent.domain.model

/** The user's registered DeSent account (primary `<local>@desent.xyz` address). */
data class RegisteredAccount(
    val local: String?,
    val displayName: String?,
    val picture: String?,
    val about: String?,
    val tier: String?,
    val nip05: String?,
    /** Full primary address exactly as the API reports it (`email_address`). */
    val emailAddress: String? = null
)
