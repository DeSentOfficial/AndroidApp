package xyz.desent.data.vanity.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateVanityRequestRequest(
    @SerialName("local_part") val localPart: String,
    val domain: String? = null,
    val kind: String = "alias"
)

@Serializable
data class VanityRequestDto(
    val id: Long = 0,
    @SerialName("local_part") val localPart: String = "",
    val domain: String = "",
    val kind: String = "alias",
    @SerialName("quoted_satoshi") val quotedSatoshi: Long = 0,
    val status: String = "pending",
    @SerialName("requested_at") val requestedAt: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
    val note: String? = null
)

@Serializable
data class VanityRequestsResponse(
    val requests: List<VanityRequestDto> = emptyList()
)

@Serializable
data class VanityErrorDetail(
    val error: String? = null,
    val detail: String? = null,
    val message: String? = null
)

@Serializable
data class VanityErrorResponse(
    val detail: VanityErrorDetail? = null
)

/** Typed failures raised by [xyz.desent.data.vanity.VanityClient]. */
sealed class VanityError(message: String) : Exception(message) {
    /** 409 — a live (pending/approved) request from any requester holds the name. */
    object RequestExists : VanityError("This name is already being requested")
    object Taken : VanityError("This address is already taken")
    /** 422 — the name is 8+ chars (or that length is free); claim it directly. */
    object NotPriced : VanityError("Names 8+ characters are free — create it directly")
    object Reserved : VanityError("This local-part is reserved")
    object InvalidLocalPart : VanityError("Invalid local-part format")
    object InvalidKind : VanityError("Invalid kind")
    object RegistrationDisabledDomain : VanityError("That domain is closed for new claims")
    object RateLimited : VanityError("Too many requests — try again later")
    object Unauthorized : VanityError("Authentication failed")
    class Server(message: String, val code: Int) : VanityError(message)
    class Unknown(message: String) : VanityError(message)
}
