package xyz.desent.presentation.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import xyz.desent.data.local.preferences.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Void,
    primaryContainer = Amber,
    onPrimaryContainer = Void,
    secondary = NostrTeal,
    onSecondary = Void,
    secondaryContainer = DeepNavy,
    onSecondaryContainer = WarmWhite,
    tertiary = Amber,
    onTertiary = Void,
    tertiaryContainer = Amber,
    onTertiaryContainer = Void,
    error = SignalRed,
    onError = WarmWhite,
    errorContainer = SignalRed,
    onErrorContainer = WarmWhite,
    background = Void,
    onBackground = WarmWhite,
    surface = DeepNavy,
    onSurface = WarmWhite,
    surfaceVariant = Slate,
    onSurfaceVariant = MidGray,
    outline = MidGray,
    inverseOnSurface = WarmWhite,
    inverseSurface = DeepNavy,
    inversePrimary = Amber
)

private val LightColorScheme = lightColorScheme(
    primary = ClassicBlue,
    onPrimary = OnClassicBlue,
    primaryContainer = ClassicBlueContainer,
    onPrimaryContainer = OnClassicBlueContainer,
    secondary = SoftBlue,
    onSecondary = OnSoftBlue,
    secondaryContainer = SoftBlueContainer,
    onSecondaryContainer = OnSoftBlueContainer,
    tertiary = SlateBlue,
    onTertiary = OnSoftBlue,
    tertiaryContainer = SlateBlueContainer,
    onTertiaryContainer = OnSlateBlueContainer,
    error = SoftRed,
    onError = OnSoftBlue,
    errorContainer = SoftRedContainer,
    onErrorContainer = OnSoftRedContainer,
    background = PureWhite,
    onBackground = LightOnSurfaceText,
    surface = MistGrey,
    onSurface = LightOnSurfaceText,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariantText,
    outline = LightOutline,
    inverseOnSurface = LightInverseOnSurface,
    inverseSurface = LightInverseSurface,
    inversePrimary = LightInversePrimary
)

@Composable
fun DeSentTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (useDarkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDarkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !useDarkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
