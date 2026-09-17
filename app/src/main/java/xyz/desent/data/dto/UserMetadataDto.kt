package xyz.desent.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class UserMetadataDto(
    val name: String? = null,
    val display_name: String? = null,
    val about: String? = null,
    val picture: String? = null,
    val banner: String? = null,
    val website: String? = null,
    val lud06: String? = null,
    val lud16: String? = null,
    val nip05: String? = null
)
