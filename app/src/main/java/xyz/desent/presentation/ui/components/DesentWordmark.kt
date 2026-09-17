package xyz.desent.presentation.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import xyz.desent.presentation.theme.PressStartFontFamily

/**
 * The DeSent neon wordmark. Replicates the landing-page .wordmark look
 * (main.css): Press Start 2P set in the active accent color with a layered
 * text-shadow glow (tight → wide).
 *
 * Compose's [Shadow] only carries a single blur layer, so we stack N [Text]s
 * from widest/softest to tightest/crisp to build the multi-stop glow that CSS
 * expresses as one multi-shadow rule.
 */
@Composable
fun DesentWordmark(
    text: String = "DeSent",
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    // (blurDp, alpha) pairs, drawn bottom → top; last entry is the crisp face.
    glowLayers: List<Pair<Dp, Float>> = listOf(
        28.dp to 0.55f,
        14.dp to 0.65f,
        6.dp to 0.95f
    )
) {
    val density = LocalDensity.current

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Each layer draws the solid accent glyph plus one shadow halo. Stacked
        // from widest/softest (bottom) to tightest (top), the union of halos
        // builds the multi-stop neon glow that CSS expresses as one rule.
        glowLayers.forEach { (blurDp, alpha) ->
            val blurPx = with(density) { blurDp.toPx() }
            Text(
                text = text,
                color = color,
                fontFamily = PressStartFontFamily,
                style = TextStyle(
                    fontSize = fontSize,
                    shadow = Shadow(
                        color = color.copy(alpha = alpha),
                        blurRadius = blurPx,
                        offset = Offset.Zero
                    )
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}
