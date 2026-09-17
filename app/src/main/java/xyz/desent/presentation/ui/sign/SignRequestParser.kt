package xyz.desent.presentation.ui.sign

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.desent.domain.model.UnsignedNostrEvent

object SignRequestParser {
    
    fun parseFromDeepLink(eventBase64: String): UnsignedNostrEvent? {
        return try {
            val jsonBytes = Base64.decode(eventBase64, Base64.DEFAULT)
            val jsonString = String(jsonBytes, Charsets.UTF_8)
            Json.decodeFromString(jsonString)
        } catch (e: Exception) {
            null
        }
    }
    
    @Serializable
    data class UnsignedEventJson(
        val kind: Long,
        val content: String,
        val tags: List<List<String>>,
        val created_at: Long?
    ) {
        fun toDomain(): UnsignedNostrEvent {
            return UnsignedNostrEvent(
                kind = kind,
                content = content,
                tags = tags,
                createdAt = created_at
            )
        }
    }
}
