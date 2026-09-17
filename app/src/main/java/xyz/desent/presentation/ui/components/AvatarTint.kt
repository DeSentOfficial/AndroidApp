package xyz.desent.presentation.ui.components

import androidx.compose.ui.graphics.Color
import kotlin.math.absoluteValue

/**
 * Deterministic avatar tint shared by every initials-fallback tile
 * (contacts, email senders): the same identity text always maps to the
 * same hue, so tiles are stable across recompositions and screens.
 */
fun avatarTintFor(seed: String): Color {
    val hash = if (seed.isEmpty()) 0 else seed.hashCode().absoluteValue
    return hsvColor(hue = (hash % 360) * 1f, saturation = 0.45f, value = 0.62f)
}

/** Classic HSV → RGB (Compose has no built-in hsv constructor). */
internal fun hsvColor(hue: Float, saturation: Float, value: Float): Color {
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

internal fun Color.darken(factor: Float): Color =
    Color(red = red * (1 - factor), green = green * (1 - factor), blue = blue * (1 - factor))
