package xyz.desent.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue

/**
 * Compact initials avatar for email rows/headers — a port of the phone's
 * EmailSenderAvatar conventions: square brand shape (28% rounded, never a
 * circle), deterministic per-sender HSV tint, white SemiBold initials. The
 * watch never loads sender pictures; the initials tile is always the render.
 */
private val WearAvatarShape = RoundedCornerShape(percent = 28)

@Composable
fun EmailSenderAvatar(
    displayName: String,
    seed: String,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val initials = remember(displayName, seed) { senderInitials(displayName, seed) }
    val tint = remember(seed) { avatarTintFor(seed) }
    val gradient = remember(tint) { Brush.linearGradient(listOf(tint, tint.darken(0.25f))) }

    Box(
        modifier = modifier
            .size(size)
            .background(gradient, WearAvatarShape),
        contentAlignment = Alignment.Center
    ) {
        androidx.wear.compose.material.Text(
            text = initials,
            color = Color.White,
            fontSize = (size.value * 0.38f).sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Deterministic per-sender tint — identical to the phone's AvatarTint.kt. */
private fun avatarTintFor(seed: String): Color {
    val hash = if (seed.isEmpty()) 0 else seed.hashCode().absoluteValue
    return hsvColor(hue = (hash % 360) * 1f, saturation = 0.45f, value = 0.62f)
}

/** Classic HSV → RGB (Compose has no built-in hsv constructor). */
private fun hsvColor(hue: Float, saturation: Float, value: Float): Color {
    val h = ((hue % 360f) + 360f) % 360f / 60f
    val i = h.toInt()
    val f = h - i
    val p = value * (1 - saturation)
    val q = value * (1 - saturation * f)
    val t = value * (1 - saturation * (1 - f))
    val (r, g, b) = when (i) {
        0 -> Triple(value, t, p)
        1 -> Triple(q, value, p)
        2 -> Triple(p, q, t)
        3 -> Triple(p, value, q)
        4 -> Triple(t, p, q)
        else -> Triple(value, p, q)
    }
    return Color(red = r, green = g, blue = b)
}

private fun Color.darken(factor: Float): Color =
    Color(red = red * (1 - factor), green = green * (1 - factor), blue = blue * (1 - factor))

/** Two name initials, else the email local-part initial — as on the phone. */
private fun senderInitials(displayName: String, seed: String): String {
    val fromName = displayName.trim().split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
    if (fromName.isNotEmpty()) return fromName
    val local = seed.substringBefore('@')
    return local.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}
