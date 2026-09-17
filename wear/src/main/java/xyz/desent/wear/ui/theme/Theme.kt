package xyz.desent.wear.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

/** Watch-side mirror of the phone's ThemeMode preference (synced string). */
enum class WearThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun fromSynced(value: String?): WearThemeMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DARK
    }
}

private val DeSentDarkColors = Colors(
    primary = Amber,
    primaryVariant = Amber,
    secondary = NostrTeal,
    secondaryVariant = NostrTeal,
    background = Void,
    onBackground = WarmWhite,
    surface = DeepNavy,
    onSurface = WarmWhite,
    // MidGray is the phone's dark onSurfaceVariant.
    onSurfaceVariant = MidGray,
    error = SignalRed,
    onError = Void,
    onPrimary = Void,
    onSecondary = Void
)

private val DeSentLightColors = Colors(
    primary = ClassicBlue,
    primaryVariant = ClassicBlue,
    secondary = SoftBlue,
    secondaryVariant = SoftBlue,
    background = PureWhite,
    onBackground = LightOnSurfaceText,
    surface = MistGrey,
    onSurface = LightOnSurfaceText,
    onSurfaceVariant = LightOnSurfaceVariantText,
    error = SoftRed,
    onError = OnSoftBlue,
    onPrimary = OnClassicBlue,
    onSecondary = OnSoftBlue
)

/** Per-theme hero-card accents; provided by [DeSentWearTheme]. */
val LocalWearHeroColors = staticCompositionLocalOf { HeroColorsDark }

/**
 * The phone's `colorScheme.surfaceVariant` (Slate dark / light variant) —
 * wear's [Colors] has no slot for it, so it rides a CompositionLocal like
 * the hero accents. Unread mailbox rows sit on it, mirroring the phone.
 */
val LocalWearSurfaceVariant = staticCompositionLocalOf { Slate }

/**
 * DeSent wear theme. Follows the phone's synced theme preference
 * ([themeMode]); SYSTEM defers to the watch's ambient setting.
 */
@Composable
fun DeSentWearTheme(
    themeMode: WearThemeMode = WearThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        WearThemeMode.DARK -> true
        WearThemeMode.LIGHT -> false
        WearThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    CompositionLocalProvider(
        LocalWearHeroColors provides if (useDark) HeroColorsDark else HeroColorsLight,
        LocalWearSurfaceVariant provides if (useDark) Slate else LightSurfaceVariant
    ) {
        MaterialTheme(
            colors = if (useDark) DeSentDarkColors else DeSentLightColors,
            typography = DeSentWearTypography,
            content = content
        )
    }
}
