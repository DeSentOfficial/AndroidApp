package xyz.desent.presentation.ui.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import xyz.desent.domain.model.BadgeDefinition
import xyz.desent.presentation.theme.MaterialSymbols
import xyz.desent.presentation.theme.MaterialSymbolsFontFamily

/**
 * Renders a badge's art in the mode its definition declares
 * (ANDROID_BADGES.md §4):
 *
 * 1. `icon` mode — the named Material Symbols Rounded glyph (the full
 *    ~4,271-name vocabulary bundled in this app), rendered locally with no
 *    network fetch, filled with the `color` tag (app accent default).
 *    Unknown names fall back to a filled circle + the badge's first letter
 *    — never crash (acceptance checklist §9).
 * 2. `image` mode — `thumb` in dense contexts, `image` in detail views.
 *    Panel-uploaded art is content-addressed and immutable, so plain Coil
 *    caching keeps it forever.
 *
 * @param dense true for dense contexts (profile rows/chips) — prefers the
 *        thumb variant.
 */
@Composable
fun BadgeArt(
    definition: BadgeDefinition,
    size: Dp,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    contentDescription: String? = definition.name
) {
    when {
        definition.iconName != null -> BadgeGlyph(
            glyphName = definition.iconName,
            colorHex = definition.color,
            fallbackLetter = definition.name.firstOrNull()?.uppercaseChar(),
            size = size,
            modifier = modifier,
            contentDescription = contentDescription
        )
        else -> {
            val url = if (dense) definition.thumbUrl ?: definition.imageUrl else definition.imageUrl
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                modifier = modifier.size(size),
                contentScale = ContentScale.Fit
            )
        }
    }
}

private fun parseColor(hex: String?): Color? {
    if (hex == null) return null
    val clean = hex.removePrefix("#")
    if (clean.length != 6) return null
    return try {
        Color(android.graphics.Color.parseColor("#$clean"))
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun BadgeGlyph(
    glyphName: String,
    colorHex: String?,
    fallbackLetter: Char?,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String?
) {
    val context = LocalContext.current
    // Resolve via the bundled codepoints index; remembered on the name so a
    // recomposition doesn't re-look-up (index itself is cached process-wide).
    val codepoint = remember(glyphName) {
        MaterialSymbols.codepointFor(context, glyphName)
    }
    val tint = parseColor(colorHex) ?: MaterialTheme.colorScheme.primary

    if (codepoint != null) {
        Text(
            text = String(Character.toChars(codepoint)),
            color = tint,
            fontSize = size.value.sp,
            lineHeight = size.value.sp,
            fontFamily = MaterialSymbolsFontFamily,
            modifier = modifier
                .size(size)
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else Modifier
                )
        )
    } else {
        // Acceptance fallback: filled circle + first letter — an unknown
        // glyph name must never crash or render empty.
        Box(
            modifier = modifier
                .size(size)
                .background(tint.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = fallbackLetter?.toString() ?: "★",
                color = tint,
                fontSize = (size.value * 0.5f).coerceAtLeast(8f).sp,
                maxLines = 1
            )
        }
    }
}
