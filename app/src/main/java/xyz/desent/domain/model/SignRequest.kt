package xyz.desent.domain.model

data class SignRequest(
    val requestingApp: String,
    val callbackScheme: String,
    val unsignedEvent: UnsignedNostrEvent,
    val timestamp: Long = System.currentTimeMillis()
)

data class UnsignedNostrEvent(
    val kind: Long,
    val content: String,
    val tags: List<List<String>>,
    val createdAt: Long? = null
)

data class SignedEventResponse(
    val eventId: String,
    val pubkey: String,
    val signature: String,
    val serializedEvent: String
)
