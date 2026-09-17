package xyz.desent.presentation.ui.login

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import xyz.desent.R
import xyz.desent.presentation.theme.SilkscreenFontFamily
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.DesentWordmark
import xyz.desent.presentation.ui.login.components.CtaButton
import xyz.desent.presentation.ui.login.viewmodel.LoginViewModel

/**
 * Blink-style welcome screen: brand block centered on clean whitespace with
 * the two flow entry points pinned to the bottom. The old single-page
 * accordion split into [CreateAccountChooserScreen] ("Create new account")
 * and [LoginMethodScreen] ("Log in / Restore").
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onNavigateToCreate: () -> Unit,
    onNavigateToExisting: () -> Unit,
    viewModel: LoginViewModel,
    /**
     * When true, the screen is being used to add an additional account to an
     * already-logged-in device (rather than the initial first-time login).
     * Changes the framing copy; the underlying ViewModel logic is unchanged —
     * [xyz.desent.data.repository.AuthRepositoryImpl.login] is additive by
     * default in the multi-account world.
     */
    additive: Boolean = false,
    /**
     * Invite code from a `?ref=` deep link (desent://register or the web
     * wizard URL). Consumed once: uppercased, prefilled into the create gate
     * and live-validated. Kept in ViewModel memory only — never persisted —
     * so a re-share can't silently reuse a stale code. Present in the arg
     * means jump straight into the create flow.
     */
    referralCodeArg: String? = null
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isLoginSuccess) {
        if (uiState.isLoginSuccess) {
            onLoginSuccess()
        }
    }

    LaunchedEffect(referralCodeArg) {
        if (!referralCodeArg.isNullOrBlank()) {
            viewModel.onReferralCodePrefilled(referralCodeArg)
            onNavigateToCreate()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = Spacing.lg)
    ) {
        Box(modifier = Modifier.weight(1.15f))

        BrandBlock()

        Box(modifier = Modifier.weight(1f))

        Column(modifier = Modifier.padding(bottom = Spacing.lg)) {
            CtaButton(
                text = if (additive) "Create another account" else "Create new account",
                onClick = onNavigateToCreate
            )
            TextButton(
                onClick = onNavigateToExisting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Log in / Restore")
            }
        }
    }
}

/** Logo glyph, flat wordmark, and the typewriter fortune-cookie tagline. */
@Composable
private fun BrandBlock() {
    val accent = MaterialTheme.colorScheme.primary

    // Fortune-cookie taglines, typed out one character at a time and cycling
    // forever (the same set the splash picks from at random). The effect is
    // keyed on the contents, not the array — stringArrayResource allocates a
    // fresh array on every recomposition, and the blinking cursor recomposes
    // this block many times a second, which would restart the typewriter on
    // every frame.
    val taglines = stringArrayResource(R.array.splash_taglines)
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(taglines.joinToString(separator = "\u0000")) {
        var index = 0
        while (true) {
            val message = taglines[index % taglines.size]
            for (n in 1..message.length) {
                typed = message.take(n)
                delay(75)
            }
            delay(1700)
            for (n in message.length - 1 downTo 0) {
                typed = message.take(n)
                delay(30)
            }
            delay(400)
            index++
        }
    }

    // Blinking terminal cursor (hard blink ~1.05s, like CSS steps(1)).
    val cursorTransition = rememberInfiniteTransition(label = "cursor")
    val cursorVisible by cursorTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1050
                1f at 0
                1f at 500
                0f at 510
                0f at 1050
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "cursorVisible"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_desent_logo),
            contentDescription = "DeSent logo",
            tint = Color.Unspecified,
            modifier = Modifier.size(96.dp)
        )

        Spacer(modifier = Modifier.height(Spacing.lg))

        // Flat wordmark: no glow layers, just the crisp face.
        DesentWordmark(
            text = "DeSent",
            fontSize = 21.sp,
            color = accent,
            glowLayers = listOf(0.dp to 0f)
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = typed,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = SilkscreenFontFamily,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.width(3.dp))
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(14.dp)
                    .background(accent.copy(alpha = cursorVisible))
            )
        }
    }
}
