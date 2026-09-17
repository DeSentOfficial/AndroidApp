package xyz.desent.wear.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.wear.compose.material.Typography
import xyz.desent.wear.R

/**
 * Brand typography for the watch — mirrors the phone's Type.kt choices:
 * Silkscreen (pixel) drives screen titles, Press Start 2P the wordmark, and
 * body/caption copy stays the system sans for legibility at watch sizes.
 */
val SilkscreenFontFamily = FontFamily(
    androidx.compose.ui.text.font.Font(R.font.silkscreen_regular),
    androidx.compose.ui.text.font.Font(R.font.silkscreen_bold, FontWeight.Bold)
)

val PressStartFontFamily = FontFamily(
    androidx.compose.ui.text.font.Font(R.font.press_start_2p)
)

/** Titles in Silkscreen Bold; everything else keeps the wear defaults. */
val DeSentWearTypography: Typography = Typography().run {
    copy(
        title1 = title1.withBrandTitle(),
        title2 = title2.withBrandTitle(),
        title3 = title3.withBrandTitle()
    )
}

private fun TextStyle.withBrandTitle(): TextStyle =
    copy(fontFamily = SilkscreenFontFamily, fontWeight = FontWeight.Bold)
