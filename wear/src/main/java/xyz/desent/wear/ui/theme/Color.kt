package xyz.desent.wear.ui.theme

import androidx.compose.ui.graphics.Color

// DeSent brand tokens — mirrors app/src/main/java/xyz/desent/presentation/theme/Color.kt
val Amber = Color(0xFFFFB300)
val Void = Color(0xFF0D0D1A)
val DeepNavy = Color(0xFF1A1A2E)
val Slate = Color(0xFF2A2A3E)
val NostrTeal = Color(0xFF00C8B4)
val WarmWhite = Color(0xFFF0F0E8)
val MidGray = Color(0xFF888899)
val SignalRed = Color(0xFFFF3B30)

// Light theme — classic blue accent family (matches the phone's light scheme)
val ClassicBlue = Color(0xFF0066CC)
val OnClassicBlue = Color(0xFFFFFFFF)
val PureWhite = Color(0xFFFFFFFF)
val MistGrey = Color(0xFFF6F7F8)
val LightOnSurfaceText = Color(0xFF0F1419)
val LightSurfaceVariant = Color(0xFFE3E8EC)
val LightOnSurfaceVariantText = Color(0xFF5B6770)
val SoftRed = Color(0xFFC45A4E)
val SoftBlue = Color(0xFF4E6A99)
val OnSoftBlue = Color(0xFFFFFFFF)

// Hero-card surfaces + accent border rings (per-theme pairs).
data class WearHeroColors(
    val surfaceTop: Color,
    val surfaceBottom: Color,
    val ownBorder: Color,
    val feedBorder: Color
)

val HeroColorsDark = WearHeroColors(
    surfaceTop = DeepNavy,
    surfaceBottom = Color(0xFF121222),
    ownBorder = Amber.copy(alpha = 0.22f),
    feedBorder = NostrTeal.copy(alpha = 0.22f)
)

val HeroColorsLight = WearHeroColors(
    surfaceTop = Color(0xFFEAF1FA),
    surfaceBottom = PureWhite,
    ownBorder = ClassicBlue.copy(alpha = 0.18f),
    feedBorder = SoftBlue.copy(alpha = 0.18f)
)
