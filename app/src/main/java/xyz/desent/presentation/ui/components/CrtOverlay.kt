package xyz.desent.presentation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * CRT phosphor overlay: scanlines + optional vignette, replicating the
 * .crt effect on the desent.xyz landing page (main.css).
 *
 * Drawn as a full-bleed [Canvas]; intended to sit above the background and
 * below content. Both passes are pure draws (no per-frame animation here) so
 * the layer is cheap and cacheable.
 *
 * @param scanlineAlpha darkness of each 1dp scanline band (0..1). Landing uses
 *                     0.16 on dark and ~0.08 reads well on light.
 * @param scanlinePeriod vertical period of the scanline mask. Landing = 4px.
 * @param showVignette when true, darkens the edges to fake a curved CRT bezel.
 * @param vignetteAlpha max darkness at the edges. Landing = 0.35 (dark) / 0.12 (light).
 */
@Composable
fun CrtOverlay(
    modifier: Modifier = Modifier,
    scanlineAlpha: Float = 0.16f,
    scanlinePeriod: Dp = 4.dp,
    showVignette: Boolean = true,
    vignetteAlpha: Float = 0.35f,
    vignetteColor: Color = Color.Black
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val periodPx = scanlinePeriod.toPx().coerceAtLeast(1f)
        val strokePx = 1.dp.toPx().coerceAtLeast(0.5f)
        val lineColor = Color.Black.copy(alpha = scanlineAlpha)

        // Scanlines: one 1px line near the bottom of each period band.
        var y = periodPx * 0.75f
        while (y < height) {
            drawLine(
                color = lineColor,
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = strokePx
            )
            y += periodPx
        }

        // Vignette: radial darken from transparent center to edge.
        if (showVignette) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        vignetteColor.copy(alpha = vignetteAlpha)
                    ),
                    center = Offset(width / 2f, height / 2f),
                    radius = maxOf(width, height) * 0.75f
                )
            )
        }
    }
}

/** Convenience wrapper that fills its parent and clips to bounds. */
@Composable
fun CrtOverlayBox(
    modifier: Modifier = Modifier,
    scanlineAlpha: Float = 0.16f,
    scanlinePeriod: Dp = 4.dp,
    showVignette: Boolean = true,
    vignetteAlpha: Float = 0.35f,
    vignetteColor: Color = Color.Black
) {
    Box(modifier = modifier) {
        CrtOverlay(
            modifier = Modifier.fillMaxSize(),
            scanlineAlpha = scanlineAlpha,
            scanlinePeriod = scanlinePeriod,
            showVignette = showVignette,
            vignetteAlpha = vignetteAlpha,
            vignetteColor = vignetteColor
        )
    }
}
