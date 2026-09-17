package xyz.desent.presentation.ui.splash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.desent.R
import xyz.desent.presentation.theme.SilkscreenFontFamily
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.DesentWordmark
import xyz.desent.presentation.ui.splash.viewmodel.SplashViewModel
import kotlin.random.Random

/**
 * Clean brand splash: static logo, flat wordmark, one random fortune-cookie
 * tagline. The CRT halo, flicker scrim, and blinking cursor moved off this
 * screen; the welcome/login screen now runs the typewriter tagline.
 */
@Composable
fun SplashScreen(
    onNavigateToLogin: () -> Unit,
    onNavigateToInbox: () -> Unit,
    viewModel: SplashViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.navigationDestination) {
        when (uiState.navigationDestination) {
            SplashDestination.LOGIN -> onNavigateToLogin()
            SplashDestination.INBOX -> onNavigateToInbox()
            null -> { }
        }
    }

    val colorScheme = MaterialTheme.colorScheme

    // Fortune-cookie tagline: one random phrase per launch.
    val taglines = stringArrayResource(R.array.splash_taglines)
    val tagline = remember { taglines[Random.nextInt(taglines.size)] }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_desent_logo),
                contentDescription = "DeSent logo",
                tint = Color.Unspecified,
                modifier = Modifier.size(96.dp)
            )

            Spacer(modifier = Modifier.height(Spacing.lg))

            DesentWordmark(
                text = "DeSent",
                fontSize = 30.sp,
                color = colorScheme.primary,
                // Flat face: no neon glow layers.
                glowLayers = listOf(0.dp to 0f)
            )

            Spacer(modifier = Modifier.height(Spacing.lg))

            Text(
                text = tagline,
                color = colorScheme.onSurfaceVariant,
                fontFamily = SilkscreenFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp
            )
        }
    }
}

enum class SplashDestination {
    LOGIN,
    INBOX
}

data class SplashUiState(
    val navigationDestination: SplashDestination? = null,
    val error: String? = null
)
