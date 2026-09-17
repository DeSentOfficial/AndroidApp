package xyz.desent.presentation.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import xyz.desent.presentation.ui.components.avatarTintFor
import xyz.desent.presentation.ui.components.darken

/**
 * Contact avatar with the v2 spec's fallback chain: nostr profile `picture`
 * when one resolves (image errors fall back to initials), else initials on
 * a deterministic per-contact tint.
 */
@Composable
fun ContactAvatar(
    contact: xyz.desent.domain.model.PrivateContact,
    pictureUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val initials = remember(contact) { initialsOf(contact) }
    val seed = remember(contact) {
        contact.primaryEmail.ifBlank { contact.name }.ifBlank { contact.pubkey.orEmpty() }
    }
    val tint = remember(seed) { avatarTintFor(seed) }
    val gradient = remember(tint) {
        Brush.linearGradient(listOf(tint, tint.darken(0.25f)))
    }

    Box(
        modifier = modifier
            .size(size)
            .background(gradient, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (pictureUrl != null) {
            AsyncImage(
                model = pictureUrl,
                contentDescription = "Contact picture",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
                error = null // failed loads reveal the initials underneath
            )
        } else {
            Text(
                text = initials,
                color = Color.White,
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** "Ann Lee" → "AL"; unnamed contacts fall back to the email/domain initial. */
private fun initialsOf(contact: xyz.desent.domain.model.PrivateContact): String {
    val fromName = contact.name.trim().split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
    if (fromName.isNotEmpty()) return fromName
    val email = contact.primaryEmail
    if (email.isNotEmpty()) return email.first().uppercaseChar().toString()
    return contact.domain.takeIf { it.isNotEmpty() }?.first()?.uppercaseChar()?.toString() ?: "?"
}
