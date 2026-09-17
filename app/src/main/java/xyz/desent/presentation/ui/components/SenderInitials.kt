package xyz.desent.presentation.ui.components

/**
 * Initials derivation for sender/contact avatars, shared by the email
 * avatar composable and the notification avatar factory so both render the
 * identical fallback. "Ann Lee" → "AL"; address-only seeds fall back to the
 * local-part initial.
 */
fun senderInitials(displayName: String, seed: String): String {
    val fromName = displayName.trim().split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
    if (fromName.isNotEmpty()) return fromName
    val local = seed.substringBefore('@')
    return local.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}
